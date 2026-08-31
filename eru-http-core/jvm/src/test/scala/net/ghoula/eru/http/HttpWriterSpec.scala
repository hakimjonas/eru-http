package net.ghoula.eru.http

import munit.FunSuite

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

import net.ghoula.eru.*
import net.ghoula.eru.http.TestHelpers.*

class HttpWriterSpec extends FunSuite {

  /** Runs a request through both writer paths and asserts the bytes agree. The pooled-buffer writer
    * (writeRequestWithBuffer) is what production uses; the plain writer encodes via
    * getBytes(UTF-8). They must produce identical output for any request the model can represent.
    */
  test("buffered and unbuffered request writers produce identical bytes for non-ASCII targets") {
    val uri = Uri.parse("http://example.com/").assertSuccess
    val request = Request.get(uri.withQuery("q=中文"))

    val plain = new ByteArrayOutputStream()
    HttpWriter.writeRequest(Channels.newChannel(plain), request).assertSuccess

    val buffered = new ByteArrayOutputStream()
    HttpWriter.writeRequestWithBuffer(Channels.newChannel(buffered), request, ByteBuffer.allocate(8192)).assertSuccess

    assertEquals(buffered.toByteArray.toSeq, plain.toByteArray.toSeq)
    // The target must carry the UTF-8 encoding of 中 (E4 B8 AD), not a truncated low byte.
    assert(plain.toByteArray.contains(0xe4.toByte))
    assert(plain.toByteArray.contains(0xb8.toByte))
    assert(plain.toByteArray.contains(0xad.toByte))
  }

  test("Latin-1 range chars in header values are written byte-faithfully (obs-text)") {
    val headers = Headers.empty.add("X-Obs", "café").assertSuccess
    val request = Request(Method.GET, Uri.parse("http://example.com/").assertSuccess, headers, Body.Empty)

    val plain = new ByteArrayOutputStream()
    HttpWriter.writeRequest(Channels.newChannel(plain), request).assertSuccess

    val buffered = new ByteArrayOutputStream()
    HttpWriter.writeRequestWithBuffer(Channels.newChannel(buffered), request, ByteBuffer.allocate(8192)).assertSuccess

    assertEquals(buffered.toByteArray.toSeq, plain.toByteArray.toSeq)
    assert(plain.toByteArray.contains(0xe9.toByte)) // é as a single obs-text byte, not UTF-8 0xC3 0xA9
  }
}
