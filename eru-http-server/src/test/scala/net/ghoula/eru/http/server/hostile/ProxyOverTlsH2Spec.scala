package net.ghoula.eru.http.server.hostile

import java.io.DataInputStream
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
import net.ghoula.eru.prelude.*

/** PROXY v2 + TLS + ALPN-negotiated HTTP/2: the full front-door chain.
  *
  * The PROXY preamble arrives before the ClientHello, the gate parses it on the raw socket, the TLS
  * handshake negotiates h2 via ALPN, and the request then flows through the HTTP/2 stack —
  * exercising `SSLSocketChannel`'s pre-read seed drain and per-read deadlines on the h2 path.
  *
  * The HTTP/2 client here is hand-rolled: connection preface, empty SETTINGS, and a HEADERS frame
  * carrying four headers as HPACK literal-without-indexing fields (no Huffman), with END_STREAM.
  * Assertions are frame-level: SETTINGS ACK, a HEADERS response on stream 1, and no GOAWAY/RST.
  *
  * Scenarios:
  *   1. Required mode + preamble + TLS(h2): request served over HTTP/2.
  *   2. Optional mode + plain TLS(h2) client (no preamble): ClientHello seed replay + served.
  */
class ProxyOverTlsH2Spec extends HostileTestBase {

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
    out(12) = 0x21.toByte
    out(13) = 0x11.toByte
    out(14) = ((payload.length >>> 8) & 0xff).toByte
    out(15) = (payload.length & 0xff).toByte
    System.arraycopy(payload, 0, out, 16, payload.length)
    out
  }

  /** HPACK literal header field without indexing, new name, no Huffman. */
  private def literalHeader(name: String, value: String): Array[Byte] = {
    def string(s: String): Array[Byte] = {
      val bytes = s.getBytes("US-ASCII")
      val out = new Array[Byte](1 + bytes.length)
      out(0) = bytes.length.toByte // no-Huffman flag 0, length fits 7 bits here
      System.arraycopy(bytes, 0, out, 1, bytes.length)
      out
    }
    val out = new Array[Byte](1)
    out(0) = 0x00.toByte // literal without indexing, index 0
    out ++ string(name) ++ string(value)
  }

  private def h2Frame(`type`: Int, flags: Int, streamId: Int, payload: Array[Byte]): Array[Byte] = {
    val out = new Array[Byte](9 + payload.length)
    out(0) = ((payload.length >>> 16) & 0xff).toByte
    out(1) = ((payload.length >>> 8) & 0xff).toByte
    out(2) = (payload.length & 0xff).toByte
    out(3) = `type`.toByte
    out(4) = flags.toByte
    out(5) = ((streamId >>> 24) & 0x7f).toByte
    out(6) = ((streamId >>> 16) & 0xff).toByte
    out(7) = ((streamId >>> 8) & 0xff).toByte
    out(8) = (streamId & 0xff).toByte
    System.arraycopy(payload, 0, out, 9, payload.length)
    out
  }

  private final case class H2Frame(length: Int, frameType: Int, flags: Int, streamId: Int, payload: Array[Byte])

  private def readH2Frame(in: DataInputStream): H2Frame = {
    val b = new Array[Byte](9)
    in.readFully(b)
    val length = ((b(0) & 0xff) << 16) | ((b(1) & 0xff) << 8) | (b(2) & 0xff)
    val frameType = b(3) & 0xff
    val flags = b(4) & 0xff
    val streamId = ((b(5) & 0x7f) << 24) | ((b(6) & 0xff) << 16) | ((b(7) & 0xff) << 8) | (b(8) & 0xff)
    val payload = new Array[Byte](length)
    if length > 0 then in.readFully(payload)
    H2Frame(length, frameType, flags, streamId, payload)
  }

  /** Completes PROXY (optional) + TLS with ALPN h2, then sends a GET as an HTTP/2 request and
    * returns the frames the server sends back (after our preface + SETTINGS).
    */
  private def h2Get(
    host: String,
    port: Int,
    preamble: Option[Array[Byte]]
  ): List[H2Frame] = Try {
    val raw = new Socket(host, port)
    try {
      preamble.foreach(raw.getOutputStream.write)
      raw.getOutputStream.flush()

      trustAllSocketFactory.createSocket(raw, host, port, true) match {
        case tls: SSLSocket =>
          val params = tls.getSSLParameters
          params.setApplicationProtocols(Array("h2"))
          tls.setSSLParameters(params)
          tls.setSoTimeout(4000)
          tls.startHandshake()

          val out = tls.getOutputStream
          val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("US-ASCII")
          out.write(preface)
          out.write(h2Frame(0x4, 0, 0, Array.emptyByteArray)) // empty SETTINGS
          val headers = Array.concat(
            literalHeader(":method", "GET"),
            literalHeader(":scheme", "https"),
            literalHeader(":path", "/"),
            literalHeader(":authority", s"$host:$port")
          )
          out.write(h2Frame(0x1, 0x05, 1, headers)) // HEADERS, END_HEADERS | END_STREAM
          out.flush()

          val in = new DataInputStream(tls.getInputStream)
          // Collect frames until a HEADERS response on stream 1 (or give up).
          var frames = List.empty[H2Frame]
          var done = false
          while !done do {
            val f = readH2Frame(in)
            frames = frames :+ f
            if f.frameType == 0x1 && f.streamId == 1 then done = true
            if f.frameType == 0x7 then done = true // GOAWAY: give up, assertions will fail
          }
          frames
        case other => fail(s"expected an SSLSocket, got: ${other.getClass.getName}")
      }
    } finally Try(raw.close()): Unit
  }.toOption.getOrElse(List.empty)

  private def withTlsH2Server[A](
    mode: ProxyProtocolMode
  )(body: ServerAddress => Eru[HttpError, A]): Eru[HttpError, A] = {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    val tlsConfig = TlsConfig.default.copy(
      keyStorePath = Some(keystorePath.toString),
      keyStorePassword = Some(password)
    )
    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .withTls(tlsConfig)
      .withProxyProtocolMode(mode)
      .copy(acceptorThreads = 1, enableHttp2 = true)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap(body)
      }
      .ensure(Eru.effect(Files.deleteIfExists(keystorePath)).attempt.map(_ => ()))
  }

  test("Required: PROXY preamble over TLS with ALPN h2 serves an HTTP/2 request") {
    requireHostileMode()

    withTlsH2Server(ProxyProtocolMode.Required) { address =>
      Eru.effect {
        val frames = h2Get(address.host, address.port, Some(buildPreamble(address.port)))
        assert(frames.exists(f => f.frameType == 0x4 && (f.flags & 0x01) != 0), "expected SETTINGS ACK")
        val responseHeaders = frames.find(f => f.frameType == 0x1 && f.streamId == 1)
        assert(responseHeaders.isDefined, s"expected a HEADERS response on stream 1, got: ${frames.map(_.frameType)}")
        assert(!frames.exists(_.frameType == 0x7), "server sent GOAWAY instead of serving the request")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }
  }

  test("Optional: plain TLS h2 client without preamble is served (seed replay on the h2 path)") {
    requireHostileMode()

    withTlsH2Server(ProxyProtocolMode.Optional) { address =>
      Eru.effect {
        val frames = h2Get(address.host, address.port, None)
        assert(frames.exists(f => f.frameType == 0x4 && (f.flags & 0x01) != 0), "expected SETTINGS ACK")
        val responseHeaders = frames.find(f => f.frameType == 0x1 && f.streamId == 1)
        assert(responseHeaders.isDefined, s"expected a HEADERS response on stream 1, got: ${frames.map(_.frameType)}")
        assert(!frames.exists(_.frameType == 0x7), "server sent GOAWAY instead of serving the request")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }
  }
}
