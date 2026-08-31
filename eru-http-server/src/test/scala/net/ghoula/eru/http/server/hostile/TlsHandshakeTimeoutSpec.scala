package net.ghoula.eru.http.server.hostile

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.{Socket, SocketTimeoutException}
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, SSLSocket, SSLSocketFactory}
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** TLS handshake timeout (Slowloris-over-TLS).
  *
  * The server sets `setSoTimeout(config.tlsHandshakeTimeout)` on the underlying socket before
  * calling `doHandshake()`, and resets it to 0 (infinite) after. A stalled handshake trips
  * `SocketTimeoutException` → `SSLHandshakeException` → the connection closes. `readHeaderTimeout`
  * is applied AFTER the handshake completes and does not cover the handshake phase.
  *
  * Scenarios:
  *   1. A client that sends 1 ClientHello byte and stalls is disconnected within
  *      `tlsHandshakeTimeout + slack`. The client may observe a clean FIN, an RST, or its own read
  *      timeout — any outcome proving the server killed the connection within the timeout + slack
  *      (and did not park the VT indefinitely) is acceptable.
  *   2. A legitimate TLS handshake on a concurrent connection completes successfully — the
  *      handshake-timeout setting must not affect well-behaved clients.
  *   3. After a handshake completes, subsequent request-phase reads are NOT bound by
  *      `tlsHandshakeTimeout` (they honor `readHeaderTimeout`/`idleTimeout` instead). Scenario 3
  *      sets `tlsHandshakeTimeout` far shorter than `readHeaderTimeout` and pauses between TLS and
  *      HTTP to prove the split.
  */
class TlsHandshakeTimeoutSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private def trustAllSocketFactory: SSLSocketFactory = {
    val trustAll = new javax.net.ssl.X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      override def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    }
    val ctx = SSLContext.getInstance("TLS")
    // scalafix:off DisableSyntax.null
    ctx.init(null, Array(trustAll), SecureRandom.getInstanceStrong)
    // scalafix:on DisableSyntax.null
    ctx.getSocketFactory
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("secret")))

  test("TLS handshake: stalled partial ClientHello is disconnected within tlsHandshakeTimeout") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val handshakeTimeout = 500.millis
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)
        .withTlsHandshakeTimeout(handshakeTimeout)
        .copy(acceptorThreads = 1)

      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.flatMap { addr =>
            Eru.effect {
              val s = new Socket(addr.host, addr.port)
              try {
                s.getOutputStream.write(Array[Byte](0x16))
                s.getOutputStream.flush()
                s.setSoTimeout((handshakeTimeout.toMillis + 3000).toInt)
                val in = s.getInputStream
                val start = System.nanoTime()
                val outcome: Either[Throwable, Int] =
                  try Right(in.read())
                  catch { case e: Throwable => Left(e) }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L

                val maxAcceptableMs = handshakeTimeout.toMillis + 3000
                assert(
                  elapsedMs < maxAcceptableMs,
                  s"Server did not disconnect within ${maxAcceptableMs}ms — elapsed=${elapsedMs}ms, " +
                    s"outcome=$outcome. TLS handshake timeout is not being enforced."
                )
                outcome match {
                  case Right(-1) => ()
                  case Left(_: SocketTimeoutException) => ()
                  case Left(_: IOException) => ()
                  case Right(b) => fail(s"Server sent byte $b to a stalled handshake — unexpected")
                  case Left(e) => fail(s"Unexpected exception: ${e.getClass.getName}: ${e.getMessage}")
                }
              } finally Try(s.close()): Unit
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }

  test("TLS handshake: legitimate client succeeds while another client stalls mid-handshake") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val handshakeTimeout = 1.second
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)
        .withTlsHandshakeTimeout(handshakeTimeout)
        .copy(acceptorThreads = 2)

      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.flatMap { addr =>
            Eru.effect {
              val stallSocket = new Socket(addr.host, addr.port)
              stallSocket.getOutputStream.write(Array[Byte](0x16))
              stallSocket.getOutputStream.flush()

              try {
                val factory = trustAllSocketFactory
                val tls = factory.createSocket(addr.host, addr.port) match {
                  case ssl: SSLSocket => ssl
                  case other => fail(s"Expected SSLSocket, got ${other.getClass.getName}")
                }
                try {
                  tls.setSoTimeout(5000)
                  tls.startHandshake()
                  tls.getOutputStream.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes)
                  tls.getOutputStream.flush()
                  val in = new BufferedReader(new InputStreamReader(tls.getInputStream))
                  val statusLine = Option(in.readLine())
                  assert(
                    statusLine.exists(_.startsWith("HTTP/1.1 200")),
                    s"legit client must succeed during concurrent handshake flood; got: ${statusLine.getOrElse("<EOF>")}"
                  )
                } finally Try(tls.close()): Unit
              } finally Try(stallSocket.close()): Unit
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }

  test("TLS handshake: post-handshake reads are not bound by tlsHandshakeTimeout") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)
        .withTlsHandshakeTimeout(300.millis)
        .withReadHeaderTimeout(5.seconds)
        .copy(acceptorThreads = 1)

      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.flatMap { addr =>
            Eru.effect {
              val factory = trustAllSocketFactory
              val tls = factory.createSocket(addr.host, addr.port) match {
                case ssl: SSLSocket => ssl
                case other => fail(s"Expected SSLSocket, got ${other.getClass.getName}")
              }
              try {
                tls.setSoTimeout(6000)
                tls.startHandshake()
                Thread.sleep(700)
                tls.getOutputStream.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes)
                tls.getOutputStream.flush()
                val in = new BufferedReader(new InputStreamReader(tls.getInputStream))
                val statusLine = Option(in.readLine())
                assert(
                  statusLine.exists(_.startsWith("HTTP/1.1 200")),
                  s"post-handshake idle > tlsHandshakeTimeout should still succeed; got: ${statusLine.getOrElse("<EOF>")}"
                )
              } finally Try(tls.close()): Unit
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }
}
