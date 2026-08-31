package net.ghoula.eru.http.server.hostile

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** PROXY v2 over TLS: the LB sends the preamble before the TLS handshake, so the server must parse
  * it on the raw socket and then complete the handshake on the same connection.
  *
  * Scenarios:
  *   1. Required mode + valid preamble + TLS: handshake and request succeed.
  *   2. Required mode + no preamble: a plain TLS client is rejected (handshake EOF).
  *   3. Optional mode + no preamble: the peeked ClientHello bytes are replayed into the TLS stream
  *      as seed bytes and the plain TLS client is served — this pins the seed-replay path.
  */
class ProxyOverTlsSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private def trustAllSocketFactory: SSLSocketFactory = {
    val trustAll = new javax.net.ssl.X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      override def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    }
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    // scalafix:off DisableSyntax.null
    ctx.init(null, Array(trustAll), SecureRandom.getInstanceStrong)
    // scalafix:on DisableSyntax.null
    ctx.getSocketFactory
  }

  private def buildPreamble(dstPort: Int): Array[Byte] = {
    val signature = Array(0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a).map(_.toByte)
    val payload = new Array[Byte](12)
    val src = Array[Byte](127, 0, 0, 1)
    System.arraycopy(src, 0, payload, 0, 4)
    System.arraycopy(src, 0, payload, 4, 4)
    payload(8) = 0
    payload(9) = 1
    payload(10) = ((dstPort >>> 8) & 0xff).toByte
    payload(11) = (dstPort & 0xff).toByte

    val out = new Array[Byte](16 + payload.length)
    System.arraycopy(signature, 0, out, 0, 12)
    out(12) = 0x21.toByte // version=2, command=PROXY
    out(13) = 0x11.toByte // AF_INET | TCP
    out(14) = ((payload.length >>> 8) & 0xff).toByte
    out(15) = (payload.length & 0xff).toByte
    System.arraycopy(payload, 0, out, 16, payload.length)
    out
  }

  /** Sends the optional preamble on the raw socket, then completes the TLS handshake and one GET.
    * Returns the response status line prefix, or the failure text if the handshake died.
    */
  private def preambleThenTlsGet(
    host: String,
    port: Int,
    preamble: Option[Array[Byte]]
  ): String = Try {
    val raw = new Socket(host, port)
    try {
      preamble.foreach(raw.getOutputStream.write)
      raw.getOutputStream.flush()

      trustAllSocketFactory.createSocket(raw, host, port, true) match {
        case tls: SSLSocket =>
          tls.setSoTimeout(4000)
          tls.startHandshake()
          val out = tls.getOutputStream
          out.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
          out.flush()
          val in = new BufferedReader(new InputStreamReader(tls.getInputStream))
          Option(in.readLine()).getOrElse("<no response>")
        case other => fail(s"expected an SSLSocket, got: ${other.getClass.getName}")
      }
    } finally Try(raw.close()): Unit
  }.toOption.getOrElse("handshake/connection failed")

  test("Required: PROXY preamble over TLS is accepted and the TLS request is served") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val cfg = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)
        .withProxyProtocolMode(ProxyProtocolMode.Required)
        .copy(acceptorThreads = 1)

      HttpServer
        .scoped(cfg)(handler) { server =>
          server.start.flatMap { address =>
            Eru.effect {
              val resp = preambleThenTlsGet(address.host, address.port, Some(buildPreamble(address.port)))
              assert(resp.startsWith("HTTP/1.1 200"), s"expected 200 over TLS+PROXY, got: $resp")
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }

  test("Required: plain TLS client without preamble is rejected") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val cfg = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)
        .withProxyProtocolMode(ProxyProtocolMode.Required)
        .copy(acceptorThreads = 1)

      HttpServer
        .scoped(cfg)(handler) { server =>
          server.start.flatMap { address =>
            Eru.effect {
              val resp = preambleThenTlsGet(address.host, address.port, None)
              assert(
                !resp.startsWith("HTTP/1.1 200"),
                s"Required mode must reject a plain TLS client, got: $resp"
              )
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }

  test("Optional: plain TLS client without preamble is served (ClientHello seed replay)") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val cfg = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)
        .withProxyProtocolMode(ProxyProtocolMode.Optional)
        .copy(acceptorThreads = 1)

      HttpServer
        .scoped(cfg)(handler) { server =>
          server.start.flatMap { address =>
            Eru.effect {
              val resp = preambleThenTlsGet(address.host, address.port, None)
              assert(
                resp.startsWith("HTTP/1.1 200"),
                s"Optional mode must serve a plain TLS client (seeded ClientHello replay), got: $resp"
              )
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }
}
