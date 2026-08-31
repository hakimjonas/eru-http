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

/** Phase D.3: chunked trailer-section handling.
  *
  * Prior to D.3, `HttpParser.readNextChunkFromReader` consumed exactly ONE line after the
  * terminating `0`-sized chunk. Per RFC 9112 §7.1.2 the trailer section is `*( field-line CRLF )`
  * followed by a final `CRLF`, i.e. zero or more trailer lines then an empty line. Reading just one
  * line is correct ONLY when no trailers are present.
  *
  * When the client sends `0\r\nX-Trailer: foo\r\n\r\n`:
  *   - The one-line read consumed `X-Trailer: foo\r\n`.
  *   - The final empty `\r\n` stayed in the stream.
  *   - The NEXT pipelined request on the keep-alive connection started with `\r\n`, causing the
  *     parser to fail (or succeed with an empty request line, depending on the reader).
  *
  * This spec drives real chunked POSTs with various trailer shapes through the server and verifies
  * that (a) the request with trailers is served correctly, and (b) a pipelined subsequent request
  * on the same connection is also served correctly — which can only happen if the parser consumed
  * the trailer section fully, up to and including the terminating CRLF.
  */
class ChunkedTrailerSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  /** Handler echoes the body bytes back so we can verify the full chunked body was parsed. */
  private val handler: RequestHandler = req => {
    req.body match {
      case stream: Body.Stream =>
        stream.asString().map { body =>
          Response(StatusCode.Ok, Headers.empty, Body.Text(body))
        }
      case t: Body.Text => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text(t.value)))
      case Body.Binary(bytes, _) =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text(bytes.asString(Charset.UTF8))))
      case Body.Empty => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("")))
    }
  }

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 64, acceptorThreads = 1)

  private def readOneResponse(in: BufferedReader): (Int, String) = {
    val statusLine = Option(in.readLine()).getOrElse(throw new java.io.EOFException("no status line"))
    val status = statusLine.split(" ", 3)(1).toInt
    var contentLength = 0
    var line = Option(in.readLine())
    while line.exists(_.nonEmpty) do {
      val raw = line.get
      val colon = raw.indexOf(':')
      if colon > 0 then {
        val name = raw.substring(0, colon).trim.toLowerCase
        val value = raw.substring(colon + 1).trim
        if name == "content-length" then contentLength = value.toInt
      }
      line = Option(in.readLine())
    }
    val body = new Array[Char](contentLength)
    var read = 0
    while read < contentLength do {
      val n = in.read(body, read, contentLength - read)
      if n < 0 then throw new java.io.EOFException(s"EOF at $read of $contentLength")
      read += n
    }
    (status, new String(body, 0, read))
  }

  // --------------------------------------------------------------------
  // Scenario 1: chunked POST with trailer header, then pipelined GET
  // --------------------------------------------------------------------

  test("chunked: trailer field consumed; subsequent pipelined request parses correctly") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              // Request 1: chunked POST with a trailer. Body is "hello".
              val req1 =
                "POST / HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Transfer-Encoding: chunked\r\n" +
                  "Connection: keep-alive\r\n" +
                  "\r\n" +
                  "5\r\nhello\r\n" +
                  "0\r\n" +
                  "X-Trailer: trailer-value\r\n" +
                  "\r\n"
              // Request 2: simple GET, must parse correctly even though request 1's trailer
              // left state in the reader.
              val req2 =
                "GET /second HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Connection: close\r\n" +
                  "\r\n"
              s.getOutputStream.write((req1 + req2).getBytes("US-ASCII"))
              s.getOutputStream.flush()

              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val (status1, body1) = readOneResponse(in)
              val (status2, _) = readOneResponse(in)

              assertEquals(status1, 200, s"first (chunked) response status, got $status1")
              assertEquals(body1, "hello", s"first body should echo chunked payload, got '$body1'")
              assertEquals(status2, 200, s"second (pipelined) response must parse, got $status2 — trailer desync bug")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 2: chunked POST with multiple trailers
  // --------------------------------------------------------------------

  test("chunked: multiple trailer fields are consumed; pipeline stays aligned") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val req1 =
                "POST / HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Transfer-Encoding: chunked\r\n" +
                  "Connection: keep-alive\r\n" +
                  "\r\n" +
                  "3\r\nabc\r\n" +
                  "0\r\n" +
                  "X-A: one\r\n" +
                  "X-B: two\r\n" +
                  "X-C: three\r\n" +
                  "\r\n"
              val req2 =
                "GET /second HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Connection: close\r\n" +
                  "\r\n"
              s.getOutputStream.write((req1 + req2).getBytes("US-ASCII"))
              s.getOutputStream.flush()

              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val (status1, body1) = readOneResponse(in)
              val (status2, _) = readOneResponse(in)

              assertEquals(status1, 200)
              assertEquals(body1, "abc")
              assertEquals(status2, 200, "subsequent request must parse even after 3 trailer lines")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 3: regression — no trailers at all still works
  // --------------------------------------------------------------------

  test("chunked: no trailers (standard case) still works") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val req1 =
                "POST / HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Transfer-Encoding: chunked\r\n" +
                  "Connection: keep-alive\r\n" +
                  "\r\n" +
                  "4\r\ntest\r\n" +
                  "0\r\n\r\n"
              val req2 =
                "GET /second HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Connection: close\r\n" +
                  "\r\n"
              s.getOutputStream.write((req1 + req2).getBytes("US-ASCII"))
              s.getOutputStream.flush()

              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val (status1, body1) = readOneResponse(in)
              val (status2, _) = readOneResponse(in)

              assertEquals(status1, 200)
              assertEquals(body1, "test")
              assertEquals(status2, 200, "no-trailer chunked pipeline must still work")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 4: forbidden trailer — documented architectural gap
  // --------------------------------------------------------------------
  //
  // Per RFC 9112 §7.1.3, a Content-Length / Transfer-Encoding / Host header in a chunked
  // trailer section is forbidden because a downstream proxy that promotes trailers to headers
  // could get different framing from the upstream parser (smuggling).
  //
  // D.3 adds explicit validation in `consumeChunkedTrailers` that FAILS the parser effect for
  // such trailers. The rework of Body.Stream error semantics surfaces that failure to the
  // handler: the body stream ends in ChunkStream.Fail, the handler's decode fails, and the
  // request answers 400 (previously the behavior was a silent 200 with a truncated body).

  test("chunked: forbidden trailer (Content-Length) — parser rejection reaches the client as 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val req =
                "POST / HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Transfer-Encoding: chunked\r\n" +
                  "Connection: close\r\n" +
                  "\r\n" +
                  "5\r\nhello\r\n" +
                  "0\r\n" +
                  "Content-Length: 5\r\n" +
                  "\r\n"
              s.getOutputStream.write(req.getBytes("US-ASCII"))
              s.getOutputStream.flush()

              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val (status, _) = readOneResponse(in)
              assertEquals(
                status,
                400,
                "forbidden trailer must surface as 400, not a 200 with a truncated body"
              )
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("chunked: malformed chunk size — parser rejection reaches the client as 400") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val req =
                "POST / HTTP/1.1\r\n" +
                  "Host: x\r\n" +
                  "Transfer-Encoding: chunked\r\n" +
                  "Connection: close\r\n" +
                  "\r\n" +
                  "5\r\nhello\r\n" +
                  "ZZZ\r\n" +
                  "\r\n"
              s.getOutputStream.write(req.getBytes("US-ASCII"))
              s.getOutputStream.flush()

              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val (status, _) = readOneResponse(in)
              assertEquals(
                status,
                400,
                "malformed chunk size must surface as 400, not a 200 with a truncated body"
              )
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
