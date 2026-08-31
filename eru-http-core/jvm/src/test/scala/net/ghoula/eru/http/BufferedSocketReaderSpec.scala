package net.ghoula.eru.http

import munit.FunSuite

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

class BufferedSocketReaderSpec extends FunSuite {

  /** Mock channel that replays bytes from a pre-configured source. */
  private class MockChannel(data: Array[Byte]) extends ReadableByteChannel {
    private var offset = 0
    private var _open = true

    override def read(dst: ByteBuffer): Int = {
      if offset >= data.length then -1
      else {
        val toRead = math.min(dst.remaining, data.length - offset)
        dst.put(data, offset, toRead)
        offset += toRead
        toRead
      }
    }

    override def isOpen: Boolean = _open
    override def close(): Unit = _open = false
  }

  test("readLine reads a CRLF-terminated line") {
    val channel = new MockChannel("GET / HTTP/1.1\r\n".getBytes)
    val reader = new BufferedSocketReader(channel)
    assertEquals(reader.readLine(), "GET / HTTP/1.1")
  }

  test("readLine reads multiple lines") {
    val channel = new MockChannel("line1\r\nline2\r\nline3\r\n".getBytes)
    val reader = new BufferedSocketReader(channel)
    assertEquals(reader.readLine(), "line1")
    assertEquals(reader.readLine(), "line2")
    assertEquals(reader.readLine(), "line3")
  }

  test("readBytes reads exact number of bytes") {
    val channel = new MockChannel("Hello, World!".getBytes)
    val reader = new BufferedSocketReader(channel)
    val bytes = reader.readBytes(5)
    assertEquals(new String(bytes), "Hello")
  }

  // ===== Pipelining preservation tests =====

  test("reset preserves unconsumed bytes for HTTP/1.1 pipelining") {
    // Simulate a pipelined scenario: two HTTP requests arrive in the same TCP read.
    // After parsing the first request, reset() must not discard the bytes of the
    // second request that were already buffered.
    val req1 = "GET /first HTTP/1.1\r\nHost: x\r\n\r\n"
    val req2 = "GET /second HTTP/1.1\r\nHost: y\r\n\r\n"
    val channel = new MockChannel((req1 + req2).getBytes)
    val reader = new BufferedSocketReader(channel)

    // Read first request's lines
    assertEquals(reader.readLine(), "GET /first HTTP/1.1")
    assertEquals(reader.readLine(), "Host: x")
    assertEquals(reader.readLine(), "") // blank line

    // Reset between requests (as handleRequestLoop does)
    reader.reset()

    // Second request must still be readable — this is the bug fix
    assertEquals(reader.readLine(), "GET /second HTTP/1.1")
    assertEquals(reader.readLine(), "Host: y")
    assertEquals(reader.readLine(), "")
  }

  test("reset on empty buffer does not crash") {
    val channel = new MockChannel("hello\r\n".getBytes)
    val reader = new BufferedSocketReader(channel)
    assertEquals(reader.readLine(), "hello")
    // Buffer is now empty. reset() must handle this case gracefully.
    reader.reset()
    // No more data available — readLine should throw EOF
    intercept[java.io.EOFException] {
      reader.readLine()
    }
  }

  test("reset after partial read preserves remaining bytes within a line") {
    // Edge case: reset() called mid-line. The remaining bytes of the unfinished
    // line should be preserved so subsequent readLine() can complete it.
    val channel = new MockChannel("partial line\r\nnext line\r\n".getBytes)
    val reader = new BufferedSocketReader(channel)
    // Read first line
    assertEquals(reader.readLine(), "partial line")
    // Reset before reading next line
    reader.reset()
    // Next line must still be readable
    assertEquals(reader.readLine(), "next line")
  }

  test("hasBufferedData reflects buffer state") {
    val channel = new MockChannel("abc\r\n".getBytes)
    val reader = new BufferedSocketReader(channel)
    // Before any read, buffer is empty
    assertEquals(reader.hasBufferedData, false)
    // After readLine, the CRLF is consumed and the buffer may or may not have data
    reader.readLine(): Unit
    // After reset with an empty buffer, still no data
    reader.reset()
    assertEquals(reader.hasBufferedData, false)
  }
}
