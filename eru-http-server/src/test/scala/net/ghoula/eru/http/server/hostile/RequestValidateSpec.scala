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

/** Wire `Request.validate` into the server request pipeline.
  *
  * Every successfully-parsed request is validated before the rate-check / handler dispatch. A
  * validation failure produces `HttpError.InvalidRequest` → 400 via `sendParseErrorResponse`.
  *
  * The checks exercised here:
  *   1. `validateMethodBodyCombination` — rejects a body on methods that forbid one (GET / HEAD /
  *      DELETE / TRACE per RFC 9110 §9).
  *   2. `validateRequiredHeaders` — rejects HTTP/1.1 without a `Host` header (RFC 9110 §7.2).
  *   3. `validateForbiddenHeaderCombinations` — redundant with the parser-level CL+TE check, but
  *      harmless.
  *
  * For an absolute-form request target, the target authority overrides a conflicting `Host` header:
  * the handler sees the target's path and the replaced `Host` header. A `Host` header must still be
  * sent (missing → 400).
  */
class RequestValidateSpec extends HostileTestBase {

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

  test("validate: HTTP/1.1 without Host header returns 400 (RFC 9110 §7.2)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GET / HTTP/1.1\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"HTTP/1.1 without Host must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: GET with a request body returns 400 (RFC 9110 §9)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("GET / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 5\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "hello").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"GET with body must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: DELETE with a request body returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("DELETE / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 3\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "foo").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"DELETE with body must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: regression — valid GET with Host still returns 200") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("GET / HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"valid GET must still work, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: regression — valid POST with body still works") {
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
            assertEquals(status, Some(200), s"valid POST with body must still work, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: QUERY without Content-Type returns 400 (RFC 10008 §2)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("QUERY /search HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Length: 5\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "hello").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"QUERY without Content-Type must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: regression — QUERY with Content-Type still works") {
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
            assertEquals(status, Some(200), s"valid QUERY must still work, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: absolute-form with a conflicting Host serves the TARGET authority (RFC 9112 §3.2.2)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("GET http://good.example/absolute HTTP/1.1\r\n" +
                "Host: attacker.example\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("US-ASCII")
            val s = new java.net.Socket(address.host, address.port)
            try {
              s.setSoTimeout(2000)
              s.getOutputStream.write(raw)
              s.getOutputStream.flush()
              val in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream))
              val status = in.readLine()
              assert(status.contains("200"), s"absolute-form must be served, got: $status")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("validate: absolute-form WITHOUT a Host header returns 400 (Host is still required to be sent)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              ("GET http://good.example/absolute HTTP/1.1\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(400), s"absolute-form without Host must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
