package net.ghoula.eru.http.server

import munit.FunSuite

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

import TestHelpers.*

/** HTTP QUERY method (RFC 9110 Section 18.2) end-to-end: QUERY carries request content, is framed
  * like POST (Content-Length and chunked are both legal), and is not a state-changing method.
  *
  * The chunked-over-limit case is a pinning test: `Body.Stream` cannot surface typed errors, so the
  * handler sees a truncated body and the server answers 200, not 413. The server responds and
  * closes (Connection: close) as soon as the cap trips, which can race the client's remaining
  * writes — a RST may discard the response. The pin therefore accepts either the full
  * 200-with-truncated-body response or a clean close; what MUST hold is that the server never
  * hangs, never answers 413, and never buffers beyond the cap.
  */
class QueryMethodSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared
  private def bodyText(body: Body): Eru[HttpError, String] = body match {
    case Body.Empty => Eru.succeed("")
    case t: Body.Text => Eru.succeed(t.bytes.asString(Charset.UTF8))
    case Body.Binary(bytes, _) => Eru.succeed(bytes.asString(Charset.UTF8))
    case s: Body.Stream => s.chunks.flatMap(cs => cs.toBytes).map(_.asString(Charset.UTF8))
  }

  private def echoHandler: RequestHandler = req =>
    for {
      text <- bodyText(req.body).mapError(e =>
        HttpError.InvalidRequest(InvalidRequest(s"handler: ${e.message}", "RFC 9110"))
      )
    } yield Response(
      status = StatusCode.Ok,
      headers = Headers.empty,
      body = Body.text(s"${req.method.value}:$text")
    )

  private def sendRawAndReadBody(host: String, port: Int, raw: Array[Byte]): (Option[Int], String) = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(2000)
      s.getOutputStream.write(raw)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      val status =
        statusLine.flatMap(l => "HTTP/1.1 (\\d+)".r.findFirstMatchIn(l).flatMap(m => Option(m.group(1))).map(_.toInt))
      val body = new StringBuilder
      var line = Option(in.readLine())
      while line.exists(_.nonEmpty) do line = Option(in.readLine())
      var contentLine = Option(in.readLine())
      while contentLine.nonEmpty do {
        body.append(contentLine.get)
        contentLine = Option(in.readLine())
      }
      (status, body.toString)
    } finally s.close()
  }

  test("QUERY with Content-Length body reaches the handler") {
    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(echoHandler) { server =>
        server.start.map { address =>
          val raw =
            ("QUERY /search HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Content-Type: text/plain\r\n" +
              "Content-Length: 5\r\n" +
              "Connection: close\r\n" +
              "\r\n" +
              "hello").getBytes("US-ASCII")
          val (status, body) = sendRawAndReadBody(address.host, address.port, raw)
          assertEquals(status, Some(200))
          assertEquals(body, "QUERY:hello")
        }
      }
      .assertSuccess
  }

  test("QUERY with chunked body reaches the handler") {
    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(echoHandler) { server =>
        server.start.map { address =>
          val raw =
            ("QUERY /search HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Content-Type: text/plain\r\n" +
              "Transfer-Encoding: chunked\r\n" +
              "Connection: close\r\n" +
              "\r\n" +
              "5\r\nhello\r\n" +
              "6\r\n world\r\n" +
              "0\r\n\r\n").getBytes("US-ASCII")
          val (status, body) = sendRawAndReadBody(address.host, address.port, raw)
          assertEquals(status, Some(200))
          assertEquals(body, "QUERY:hello world")
        }
      }
      .assertSuccess
  }

  test("QUERY without a body reaches the handler with an empty body") {
    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(echoHandler) { server =>
        server.start.map { address =>
          val raw =
            ("QUERY /search HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Content-Type: text/plain\r\n" +
              "Connection: close\r\n" +
              "\r\n").getBytes("US-ASCII")
          val (status, body) = sendRawAndReadBody(address.host, address.port, raw)
          assertEquals(status, Some(200))
          assertEquals(body, "QUERY:")
        }
      }
      .assertSuccess
  }

  test("QUERY request keeps the connection alive for the next request") {
    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(echoHandler) { server =>
        server.start.map { address =>
          val s = new Socket(address.host, address.port)
          try {
            s.setSoTimeout(2000)
            val out = s.getOutputStream
            val in = new BufferedReader(new InputStreamReader(s.getInputStream))
            out.write(
              ("QUERY /a HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 1\r\n" +
                "\r\n" +
                "q").getBytes("US-ASCII")
            )
            out.write(
              ("GET /b HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("US-ASCII")
            )
            out.flush()

            val status1 = Option(in.readLine()).getOrElse("")
            assert(status1.contains("200"), s"expected 200 for QUERY, got: $status1")
            var line = Option(in.readLine())
            while line.exists(_.nonEmpty) do line = Option(in.readLine())
            val bodyChars = new Array[Char](7)
            var read = 0
            while read < 7 do {
              val n = in.read(bodyChars, read, 7 - read)
              assert(n > 0, "unexpected EOF in QUERY body")
              read += n
            }
            assertEquals(new String(bodyChars), "QUERY:q")

            val status2 = in.readLine()
            assert(status2.contains("200"), s"expected 200 for pipelined GET, got: $status2")
          } finally s.close()
        }
      }
      .assertSuccess
  }

  test("QUERY without a Content-Type header is rejected with 400 (RFC 10008 Section 2)") {
    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(echoHandler) { server =>
        server.start.map { address =>
          val raw =
            ("QUERY /search HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Content-Length: 5\r\n" +
              "Connection: close\r\n" +
              "\r\n" +
              "hello").getBytes("US-ASCII")
          val (status, _) = sendRawAndReadBody(address.host, address.port, raw)
          assertEquals(status, Some(400), "QUERY without Content-Type must be rejected with 400")
        }
      }
      .assertSuccess
  }

  test("QUERY with Content-Length exceeding maxRequestSize returns 413") {
    val config = HttpServerConfig.localhost.withPort(0).copy(maxRequestSize = 10)

    HttpServer
      .scoped(config)(echoHandler) { server =>
        server.start.map { address =>
          val raw =
            ("QUERY /search HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Content-Type: text/plain\r\n" +
              "Content-Length: 100\r\n" +
              "Connection: close\r\n" +
              "\r\n" +
              "x" * 100).getBytes("US-ASCII")
          val (status, _) = sendRawAndReadBody(address.host, address.port, raw)
          assertEquals(status, Some(413), s"oversized QUERY must be rejected with 413, got $status")
        }
      }
      .assertSuccess
  }

  test("QUERY chunked body exceeding maxRequestSize answers 400 (no silent truncation)") {
    val config = HttpServerConfig.localhost.withPort(0).copy(maxRequestSize = 1024)

    HttpServer
      .scoped(config)(echoHandler) { server =>
        server.start.map { address =>
          val s = new Socket(address.host, address.port)
          try {
            s.setSoTimeout(5000)
            val out = s.getOutputStream
            val wrote =
              try {
                out.write(
                  "QUERY /search HTTP/1.1\r\nHost: x\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".getBytes
                )
                out.write("1F4\r\n".getBytes)
                out.write(Array.fill[Byte](500)('a'.toByte))
                out.write("\r\n".getBytes)
                out.write("1F4\r\n".getBytes)
                out.write(Array.fill[Byte](500)('b'.toByte))
                out.write("\r\n".getBytes)
                out.write("1F4\r\n".getBytes)
                out.write(Array.fill[Byte](500)('c'.toByte))
                out.write("\r\n".getBytes)
                out.write("0\r\n\r\n".getBytes)
                out.flush()
                true
              } catch {
                case _: Exception => false
              }

            val in = s.getInputStream
            val echoed = new java.io.ByteArrayOutputStream()
            val buf = new Array[Byte](4096)
            var total = 0
            var done = false
            while !done do {
              val n =
                try in.read(buf)
                catch { case _: Exception => -1 }
              if n <= 0 then done = true
              else {
                echoed.write(buf, 0, n)
                total += n
                if total > 8192 then done = true
              }
            }
            val resp = new String(echoed.toByteArray, "UTF-8")
            if resp.isEmpty then {
              ()
            } else {
              assert(
                resp.startsWith("HTTP/1.1 400"),
                s"chunked-over-limit QUERY must answer 400 now that body-stream failures surface (previously: silent 200 truncation), got: ${resp.take(80)}"
              )
            }
            assert(wrote || resp.nonEmpty, "server must have produced either a clean close or a response")
          } finally Try(s.close()): Unit
        }
      }
      .assertSuccess
  }

  test("QUERY with a benign chunked trailer is consumed; connection stays aligned") {
    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(echoHandler) { server =>
        server.start.map { address =>
          val raw =
            ("QUERY /a HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Content-Type: text/plain\r\n" +
              "Transfer-Encoding: chunked\r\n" +
              "\r\n" +
              "5\r\nhello\r\n" +
              "0\r\n" +
              "X-Checksum: abc\r\n" +
              "\r\n" +
              "GET /b HTTP/1.1\r\n" +
              "Host: x\r\n" +
              "Connection: close\r\n" +
              "\r\n").getBytes("US-ASCII")
          val s = new Socket(address.host, address.port)
          try {
            s.setSoTimeout(5000)
            s.getOutputStream.write(raw)
            s.getOutputStream.flush()
            val in = new BufferedReader(new InputStreamReader(s.getInputStream))
            val status1 = Option(in.readLine()).getOrElse("")
            assert(status1.contains("200"), s"QUERY with trailer must answer 200, got: $status1")
            var line = Option(in.readLine())
            while line.exists(_.nonEmpty) do line = Option(in.readLine())
            val body1 = new Array[Char](11)
            var read = 0
            while read < 11 do {
              val n = in.read(body1, read, 11 - read)
              assert(n > 0, "unexpected EOF in QUERY body")
              read += n
            }
            assertEquals(new String(body1), "QUERY:hello")
            val status2 = in.readLine()
            assert(status2.contains("200"), s"pipelined GET after QUERY trailer must answer 200, got: $status2")
          } finally Try(s.close()): Unit
        }
      }
      .assertSuccess
  }
}
