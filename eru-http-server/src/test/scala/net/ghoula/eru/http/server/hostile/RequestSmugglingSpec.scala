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

/** HTTP/1.1 request-smuggling rejection.
  *
  * RFC 9112 §6.1 requires that a receiver reject any request with BOTH `Content-Length` and
  * `Transfer-Encoding` headers — if a front proxy and origin disagree about which wins, an attacker
  * can smuggle a second request through the connection. RFC 9112 §6.2 requires rejection of
  * duplicate `Content-Length` with conflicting values. RFC 9110 §7.2 requires rejection of
  * duplicate `Host` headers.
  *
  * Policy is conservative on framing headers: RFC 9112 §6.2 permits accepting a single comma-joined
  * equal value (e.g. "10, 10"), but any duplicate framing header — even with equal values — is
  * rejected. This eliminates a whole class of parser-divergence smuggling.
  *
  * This spec drives the server with raw-socket requests, each representing a classic smuggling
  * payload, and asserts a 400 Bad Request response before the connection closes (parse failures
  * produce 400 before close).
  */
class RequestSmugglingSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 64, acceptorThreads = 1)

  private def sendRawAndReadStatus(host: String, port: Int, raw: Array[Byte]): Option[Int] = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(2000)
      s.getOutputStream.write(raw)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      statusLine.flatMap { line =>
        val parts = line.split(" ", 3)
        if parts.length >= 2 then parts(1).toIntOption else None
      }
    } finally Try(s.close()): Unit
  }

  test("smuggling: Content-Length + Transfer-Encoding co-presence returns 400 (RFC 9112 §6.1)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 5\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "0\r\n\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"CL+TE must be rejected with 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: duplicate Content-Length with different values returns 400 (RFC 9112 §6.2)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Length: 20\r\n" +
                "\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"duplicate CL must be rejected with 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: duplicate Content-Length with SAME values returns 400 (conservative)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"duplicate CL (even same value) must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: comma-separated Content-Length with different values returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 10, 20\r\n" +
                "\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"comma-joined CL with different values must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: duplicate Host header returns 400 (RFC 9110 §7.2)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("GET / HTTP/1.1\r\n" +
                "Host: a\r\n" +
                "Host: b\r\n" +
                "\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"duplicate Host must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: duplicate Transfer-Encoding header returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "0\r\n\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"duplicate TE must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: single Content-Length with body still works (regression check)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 5\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "hello").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"clean POST must still work, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: single Transfer-Encoding chunked still works (regression check)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("POST / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5\r\nhello\r\n0\r\n\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"clean chunked POST must still work, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: QUERY with Content-Length + Transfer-Encoding co-presence returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("QUERY /search HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 5\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "0\r\n\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"QUERY CL+TE must be rejected with 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: QUERY with duplicate Content-Length returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("QUERY /search HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Length: 5\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "hello").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"duplicate CL on QUERY must be rejected with 400, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("smuggling: clean QUERY with Content-Length still works (regression check)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("QUERY /search HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 5\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "hello").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"clean QUERY must still work, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
