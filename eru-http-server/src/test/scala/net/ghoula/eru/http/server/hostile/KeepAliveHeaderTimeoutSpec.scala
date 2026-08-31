package net.ghoula.eru.http.server.hostile

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocketFactory
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Hostile: the header-phase deadline applies to EVERY request, not just the first.
  *
  * The request loop bounds the first request's header phase with `readHeaderTimeout`. For
  * keep-alive requests the same deadline must hold: `idleTimeout` bounds only the silent gap
  * between the response and the client's first byte of the next request, and once bytes flow the
  * header phase is bounded by `readHeaderTimeout`. A client that dribbles request 2's headers over
  * more than `readHeaderTimeout` must be cut off, even though the drip stays under `idleTimeout`.
  *
  * Timing: readHeaderTimeout = 1s, idleTimeout = 4s, drip spans ~2.5s (beyond 1s, well inside 4s).
  */
class KeepAliveHeaderTimeoutSpec extends HostileTestBase {

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

  private val config = HttpServerConfig.localhost
    .withPort(0)
    .copy(acceptorThreads = 1, readHeaderTimeout = 1.second, idleTimeout = 4.seconds)

  /** Sends a complete GET and reads the ENTIRE response (status, headers, body), so a follow-up
    * read on the same connection starts at the next response.
    */
  private def completeRequest(s: Socket, path: String): Option[Int] = Try {
    s.getOutputStream.write(s"GET $path HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n".getBytes)
    s.getOutputStream.flush()
    val in = new BufferedReader(new InputStreamReader(s.getInputStream))
    val status = Option(in.readLine()).map(_.split(" ", 3)(1).toInt)
    val headers = Iterator.continually(Option(in.readLine())).takeWhile(_.exists(_.nonEmpty)).flatten.mkString("\n")
    val contentLength = """(?i)content-length:\s*(\d+)""".r
      .findFirstMatchIn(headers)
      .flatMap(m => Option(m.group(1)))
      .flatMap(_.toIntOption)
      .getOrElse(0)
    if contentLength > 0 then {
      val body = new Array[Char](contentLength)
      in.read(body): Unit
    }
    status
  }.toOption.flatten

  test("keep-alive header drip with gaps beyond readHeaderTimeout is cut off with 408") {
    requireHostileMode()

    val server = HttpServer.create(config, handler).assertSuccess
    val address = server.start.assertSuccess

    val s = new Socket(address.host, address.port)
    try {
      s.setSoTimeout(4000)
      // Request 1 completes normally and keeps the connection alive.
      assertEquals(completeRequest(s, "/first"), Some(200))

      // Request 2: request line arrives, then the client goes quiet for 1.5s — beyond the 1s
      // per-read header deadline. The server must answer 408 (the deadline is socket-level, so
      // the channel stays open) and close.
      s.getOutputStream.write("GET /second HTTP/1.1\r\n".getBytes)
      s.getOutputStream.flush()
      Thread.sleep(1500)
      s.getOutputStream.write("Host: x\r\nConnection: close\r\n\r\n".getBytes)
      s.getOutputStream.flush()

      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      assert(
        statusLine.exists(_.startsWith("HTTP/1.1 408")),
        s"expected 408 for keep-alive drip beyond the header deadline, got: $statusLine"
      )
    } finally {
      Try(s.close()): Unit
      server.shutdown.assertSuccess
    }
  }

  test("a silent keep-alive connection is still reaped by idleTimeout") {
    requireHostileMode()

    val server = HttpServer.create(config, handler).assertSuccess
    val address = server.start.assertSuccess

    val s = new Socket(address.host, address.port)
    try {
      s.setSoTimeout(6000)
      assertEquals(completeRequest(s, "/first"), Some(200))

      // Stay silent past idleTimeout (4s): the server must reap the connection - either by
      // answering 408 or by closing, both of which unblock the client read.
      Thread.sleep(4500)
      val outcome = Try {
        val in = new BufferedReader(new InputStreamReader(s.getInputStream))
        Option(in.readLine())
      }.toOption.flatten
      val reaped = outcome match {
        case None => true // EOF / reset: server closed
        case Some(line) => !line.startsWith("HTTP/1.1 200") // 408 or other non-served answer
      }
      assert(reaped, s"idle keep-alive connection was not reaped by idleTimeout: $outcome")
    } finally {
      Try(s.close()): Unit
      server.shutdown.assertSuccess
    }
  }

  test("header-phase stall answers 408 before close (plain)") {
    requireHostileMode()

    val server = HttpServer.create(config, handler).assertSuccess
    val address = server.start.assertSuccess

    val s = new Socket(address.host, address.port)
    try {
      s.setSoTimeout(4000)
      // Request line only, then stall past readHeaderTimeout (1s). The socket-level deadline
      // leaves the channel open, so the server can answer 408 before closing.
      s.getOutputStream.write("GET /stall HTTP/1.1\r\n".getBytes)
      s.getOutputStream.flush()
      Thread.sleep(1500)
      s.getOutputStream.write("Host: x\r\nConnection: close\r\n\r\n".getBytes)
      s.getOutputStream.flush()

      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      assert(
        statusLine.exists(_.startsWith("HTTP/1.1 408")),
        s"expected 408 after the header-phase deadline, got: $statusLine"
      )
    } finally {
      Try(s.close()): Unit
      server.shutdown.assertSuccess
    }
  }

  test("header-phase stall answers 408 before close (TLS)") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val tlsServerConfig = config
        .withTls(tlsConfig)
        .withTlsHandshakeTimeout(10.seconds)

      HttpServer
        .scoped(tlsServerConfig)(handler) { server =>
          server.start.flatMap { addr =>
            Eru.effect {
              trustAllSocketFactory.createSocket(addr.host, addr.port) match {
                case tls: javax.net.ssl.SSLSocket =>
                  tls.setSoTimeout(4000)
                  tls.startHandshake()
                  val out = tls.getOutputStream
                  out.write("GET /stall HTTP/1.1\r\n".getBytes)
                  out.flush()
                  Thread.sleep(1500)
                  out.write("Host: x\r\nConnection: close\r\n\r\n".getBytes)
                  out.flush()

                  val in = new BufferedReader(new InputStreamReader(tls.getInputStream))
                  val statusLine = Option(in.readLine())
                  assert(
                    statusLine.exists(_.startsWith("HTTP/1.1 408")),
                    s"expected 408 over TLS after the header-phase deadline, got: $statusLine"
                  )
                  Try(tls.close()): Unit
                case other =>
                  fail(s"expected an SSLSocket, got: ${other.getClass.getName}")
              }
            }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
          }
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }
}
