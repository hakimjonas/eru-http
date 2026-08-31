package net.ghoula.eru.http.server.hostile

import java.io.{BufferedReader, InputStreamReader}
import java.net.Socket
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Parse failures produce an HTTP response (400 / 408) before TCP close.
  *
  * Every parse failure writes an HTTP status line before closing the connection. The connection
  * closes after the response (the parser left the byte stream in an undefined state — it would be
  * unsafe to keep the keep-alive loop going). So each scenario writes one bad request on a fresh
  * socket and verifies (a) an HTTP response with the right status, and (b) the server closes the
  * connection after.
  *
  * A header-read timeout does NOT emit 408: `Eru.timeout` interrupts the reading fiber via
  * `Thread.interrupt()`, which makes `SocketChannel.read()` throw `ClosedByInterruptException` and
  * close the channel — so by the time the error path runs the channel is unwritable, the 408 write
  * fails silently, and the client sees EOF. Scenario 5 pins that current behavior; upgrading to a
  * wire-visible 408 would be an intentional test update.
  *
  * To reach the invalid-method 400 path the test uses a non-token character (`@`): letter-only
  * tokens like "FOO" are valid per RFC 9110 (extension methods are permitted), so they do not trip
  * `Method.parse`.
  */
class ParseErrorResponseSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 64, acceptorThreads = 1)

  /** Send raw bytes and read the status line. Returns (status, wasClosed) where `wasClosed` is true
    * iff the server signaled end-of-stream within the socket timeout after the response.
    *
    * Does not drain to EOF — the server may close asynchronously, and a drain loop would risk a
    * read-timeout that masks the status assertion. The parse-error path writes `Connection: close`,
    * so a full drain would observe EOF, but only the status line is needed here.
    */
  private def sendRawAndRead(host: String, port: Int, raw: Array[Byte]): (Option[Int], Boolean) = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(2000)
      s.getOutputStream.write(raw)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      val status = statusLine.flatMap { line =>
        val parts = line.split(" ", 3)
        if parts.length >= 2 then parts(1).toIntOption else None
      }
      (status, true)
    } finally Try(s.close()): Unit
  }

  test("parse error: invalid HTTP method (non-token char) returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GE@T / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("US-ASCII")
            val (status, _) = sendRawAndRead(address.host, address.port, raw)
            assertEquals(status, Some(400), s"non-token method should get 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("parse error: malformed request line (missing HTTP version) returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GET /\r\nHost: x\r\n\r\n".getBytes("US-ASCII")
            val (status, _) = sendRawAndRead(address.host, address.port, raw)
            assertEquals(status, Some(400), s"malformed request line should get 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("parse error: invalid URI returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GET http://example.com:abc/ HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("US-ASCII")
            val (status, _) = sendRawAndRead(address.host, address.port, raw)
            assertEquals(status, Some(400), s"invalid URI should get 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("parse error: invalid header name returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GET / HTTP/1.1\r\nHost: x\r\nBad Name: value\r\n\r\n".getBytes("US-ASCII")
            val (status, _) = sendRawAndRead(address.host, address.port, raw)
            assertEquals(status, Some(400), s"invalid header name should get 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("parse error: header read timeout answers 408 then closes (D.7 shipped)") {
    requireHostileMode()

    val shortCfg = cfg.withReadHeaderTimeout(scala.concurrent.duration.Duration(500, "ms"))

    HttpServer
      .scoped(shortCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(5000)
              val in = s.getInputStream
              val collected = new java.io.ByteArrayOutputStream()
              val buf = new Array[Byte](1024)
              var done = false
              var endedWithEof = false
              while !done do {
                val n =
                  try in.read(buf)
                  catch { case _: java.io.IOException => -1 }
                if n <= 0 then {
                  done = true
                  endedWithEof = n == -1
                } else collected.write(buf, 0, n)
              }
              val resp = collected.toString("UTF-8")
              assert(
                resp.startsWith("HTTP/1.1 408"),
                s"expected 408 on the header-read deadline, got: ${resp.take(80)}"
              )
              assert(endedWithEof, "connection must close after the 408 (read to EOF)")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("parse error: subsequent connection still serves valid requests (no acceptor wedge)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val badRaw = "GE@T / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("US-ASCII")
            val (badStatus, _) = sendRawAndRead(address.host, address.port, badRaw)
            assertEquals(badStatus, Some(400))

            val okRaw = "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
            val (okStatus, _) = sendRawAndRead(address.host, address.port, okRaw)
            assertEquals(okStatus, Some(200))
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
