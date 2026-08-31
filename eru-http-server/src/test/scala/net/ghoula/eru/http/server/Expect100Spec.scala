package net.ghoula.eru.http.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** `Expect: 100-continue` handling (RFC 9110 Section 10.1.1).
  *
  * A client that declares the expectation withholds the request body until it sees the interim
  * `100 Continue` response. Previously the server never sent one, so such clients stalled against
  * the readHeaderTimeout. Scenarios:
  *
  *   1. With Expect: 100-continue, the interim 100 arrives before the client sends the body, and
  *      the final response follows the body.
  *   2. Without Expect, no interim response is sent (the client times out waiting for one, sends
  *      the body, and gets the final response).
  */
class Expect100Spec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = req =>
    BodyDecoder[String]
      .decode(req.body)
      .mapError(HttpError.BodyDecodeError.apply)
      .flatMap(text => Eru.succeed(Response.ok(Body.text(s"got:$text"))))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 16, acceptorThreads = 1, readHeaderTimeout = scala.concurrent.duration.Duration(5, "s"))

  test("Expect: 100-continue receives the interim response before the body is sent") {
    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val out = s.getOutputStream
              out.write(
                "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n"
                  .getBytes("US-ASCII")
              )
              out.flush()

              // The client has NOT sent the body yet: the next thing on the wire must be the
              // interim 100. (Previously this read timed out — the server was waiting for a body the
              // client was withholding.)
              val firstByte = {
                val in = new BufferedReader(new InputStreamReader(s.getInputStream))
                Option(in.readLine())
              }
              assert(
                firstByte.exists(_.startsWith("HTTP/1.1 100")),
                s"expected 100 Continue while the body is withheld, got: $firstByte"
              )

              // Now send the body and read everything the server sends until EOF.
              out.write("hello".getBytes("US-ASCII"))
              out.flush()
              val collected = new java.io.ByteArrayOutputStream()
              val buf = new Array[Byte](1024)
              var done = false
              while !done do {
                val n =
                  try s.getInputStream.read(buf)
                  catch { case _: java.io.IOException => -1 }
                if n <= 0 then done = true else collected.write(buf, 0, n)
              }
              val wire = collected.toString("UTF-8")
              assert(wire.contains("HTTP/1.1 200"), s"expected 200 after the body, got: $wire")
              assert(wire.contains("got:hello"), s"expected the handler's body, got: $wire")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("no Expect header means no interim response") {
    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(500)
              val out = s.getOutputStream
              out.write("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\n".getBytes("US-ASCII"))
              out.flush()

              // No Expect: the server must NOT send 100. The read times out (nothing to read
              // until the body arrives), then the body goes out and the final response arrives.
              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val nothing = Try(Option(in.readLine())).toOption.flatten
              assert(nothing.isEmpty, s"no interim response expected without Expect, got: $nothing")

              s.setSoTimeout(3000)
              out.write("hello".getBytes("US-ASCII"))
              out.flush()
              var finalLine = Option(in.readLine())
              while finalLine.exists(_.isEmpty) do finalLine = Option(in.readLine())
              assert(
                finalLine.exists(_.startsWith("HTTP/1.1 200")),
                s"expected 200 after the body, got: $finalLine"
              )
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
