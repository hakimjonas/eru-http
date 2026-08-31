package net.ghoula.eru.http

import munit.FunSuite

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

import net.ghoula.eru.http.TestHelpers.*

class HttpParserSpec extends FunSuite {

  /** Mock channel for feeding raw HTTP bytes to the parser. */
  private class MockChannel(data: Array[Byte]) extends ReadableByteChannel {
    private var offset = 0
    override def read(dst: ByteBuffer): Int = {
      if offset >= data.length then -1
      else {
        val toRead = math.min(dst.remaining, data.length - offset)
        dst.put(data, offset, toRead)
        offset += toRead
        toRead
      }
    }
    override def isOpen: Boolean = true
    override def close(): Unit = ()
  }

  private def reader(bytes: String): BufferedSocketReader =
    new BufferedSocketReader(new MockChannel(bytes.getBytes("UTF-8")))

  // ===== Baseline parsing =====

  test("parseRequest parses a simple GET") {
    val req = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    assertEquals(request.method.value, "GET")
    assertEquals(request.uri.path, "/foo")
  }

  test("parseRequest parses POST with Content-Length body") {
    val body = "hello world"
    val req = s"POST /x HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\n\r\n$body"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    request.body match {
      case Body.Binary(bytes, _) =>
        assertEquals(bytes.asString(Charset.UTF8), "hello world")
      case other => fail(s"Expected Body.Binary, got $other")
    }
  }

  // ===== maxBodySize enforcement =====

  test("parseRequest rejects Content-Length above maxBodySize with PayloadTooLarge") {
    // Declare a large body but don't send it — the parser must reject BEFORE allocating
    val req = "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1000000\r\n\r\n"
    val error = HttpParser.parseRequest(reader(req), maxBodySize = 1024).assertFailure
    error match {
      case HttpError.PayloadTooLarge(declared, max) =>
        assertEquals(declared, 1000000L)
        assertEquals(max, 1024)
      case other => fail(s"Expected PayloadTooLarge, got $other")
    }
  }

  test("parseRequest accepts Content-Length exactly at maxBodySize") {
    val body = "a" * 100
    val req = s"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n$body"
    val request = HttpParser.parseRequest(reader(req), maxBodySize = 100).assertSuccess
    request.body match {
      case Body.Binary(bytes, _) => assertEquals(bytes.length, 100)
      case other => fail(s"Expected Body.Binary, got $other")
    }
  }

  test("parseRequest rejects negative Content-Length") {
    val req = "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n"
    val error = HttpParser.parseRequest(reader(req)).assertFailure
    error match {
      case HttpError.InvalidRequest(e) =>
        assert(e.message.contains("Negative"), s"Expected negative CL error, got: ${e.message}")
      case other => fail(s"Expected InvalidRequest, got $other")
    }
  }

  test("parseRequest rejects non-numeric Content-Length") {
    val req = "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n"
    val error = HttpParser.parseRequest(reader(req)).assertFailure
    error match {
      case HttpError.InvalidRequest(e) =>
        assert(e.message.contains("Invalid"), s"Expected invalid CL error, got: ${e.message}")
      case other => fail(s"Expected InvalidRequest, got $other")
    }
  }

  test("parseRequest does NOT allocate for Content-Length larger than Int.MaxValue") {
    // This test verifies rejection happens before any allocation attempt.
    // With maxBodySize = 1024, a 2GB Content-Length must be rejected with
    // PayloadTooLarge, not with "Content-Length too large" OOM-adjacent error.
    val req = "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 2147483648\r\n\r\n"
    val error = HttpParser.parseRequest(reader(req), maxBodySize = 1024).assertFailure
    error match {
      case HttpError.PayloadTooLarge(_, _) => // expected — size check fires first
      case other => fail(s"Expected PayloadTooLarge for 2GB request, got $other")
    }
  }

  test("parseRequest default maxBodySize is Int.MaxValue (backwards compatible)") {
    // A moderate body size should be accepted when no limit is specified.
    val body = "a" * 10000
    val req = s"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 10000\r\n\r\n$body"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    request.body match {
      case Body.Binary(bytes, _) => assertEquals(bytes.length, 10000)
      case other => fail(s"Expected Body.Binary, got $other")
    }
  }

  test("parseRequest handles absolute-form: Host is replaced by the target authority (RFC 9112 §3.2.2)") {
    val req = "GET http://real.example.com:8080/foo HTTP/1.1\r\nHost: attacker.example\r\n\r\n"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    assertEquals(request.uri.path, "/foo")
    assertEquals(request.uri.host, Some("real.example.com"))
    val hostHeader = request.headers.getFirst(HeaderNames.Host).map(_.value)
    assertEquals(hostHeader, Some("real.example.com:8080"), "Host must reflect the target authority")
  }

  test("parseRequest handles absolute-form with a default port: Host omits the port") {
    val req = "GET http://real.example.com/foo HTTP/1.1\r\nHost: attacker.example\r\n\r\n"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    val hostHeader = request.headers.getFirst(HeaderNames.Host).map(_.value)
    assertEquals(hostHeader, Some("real.example.com"))
  }

  test("parseRequest leaves a missing Host header missing in absolute-form (server rejects it)") {
    val req = "GET http://real.example.com/foo HTTP/1.1\r\n\r\n"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    assertEquals(request.headers.getFirst(HeaderNames.Host), None)
  }

  test("parseRequest leaves origin-form requests untouched") {
    val req = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"
    val request = HttpParser.parseRequest(reader(req)).assertSuccess
    assertEquals(request.headers.getFirst(HeaderNames.Host).map(_.value), Some("example.com"))
  }
}
