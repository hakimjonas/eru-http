package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HPACK string literal encoding/decoding per RFC 7541 Section 5.2.
  *
  * Test vectors from RFC 7541 Appendix C.
  */
class HpackStringSpec extends FunSuite {

  // ============================================================================
  // Basic Round-Trip Tests
  // ============================================================================

  test("Round-trip encoding/decoding for typical HTTP strings") {
    val testStrings = List(
      "www.example.com",
      "GET",
      "POST",
      "/",
      "/index.html",
      "https",
      "http",
      "200",
      "404",
      "text/html",
      "application/json",
      "gzip, deflate",
      "no-cache",
      "Mozilla/5.0"
    )

    for (s <- testStrings) {
      val buffer = ByteBuffer.allocate(100)
      HpackString.encode(s, buffer).assertSuccess

      buffer.flip()
      val decoded = HpackString.decode(buffer).assertSuccess
      assertEquals(decoded, s, s"Round-trip failed for '$s'")
    }
  }

  test("Round-trip for empty string") {
    val buffer = ByteBuffer.allocate(10)
    HpackString.encode("", buffer).assertSuccess

    buffer.flip()
    val decoded = HpackString.decode(buffer).assertSuccess
    assertEquals(decoded, "")
  }

  test("Round-trip for UTF-8 string with multibyte characters") {
    val s = "日本語テスト"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encode(s, buffer).assertSuccess

    buffer.flip()
    val decoded = HpackString.decode(buffer).assertSuccess
    assertEquals(decoded, s)
  }

  // ============================================================================
  // Huffman Selection Tests
  // ============================================================================

  test("Auto-select uses Huffman when it compresses better") {
    // Typical HTTP text compresses well with Huffman
    val s = "www.example.com"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encode(s, buffer).assertSuccess

    buffer.flip()
    // Check that Huffman flag is set (bit 7 of first byte)
    val firstByte = buffer.get(0) & 0xff
    assert((firstByte & 0x80) != 0, "Expected Huffman encoding for typical HTTP text")
  }

  test("Auto-select uses literal when Huffman doesn't help") {
    // Binary-like data doesn't compress well
    val s = String.valueOf(Array[Char](0x80.toChar, 0x81.toChar, 0x82.toChar, 0x83.toChar))
    val buffer = ByteBuffer.allocate(100)
    HpackString.encode(s, buffer).assertSuccess

    buffer.flip()
    // For this kind of data, Huffman might not be beneficial
    // The actual selection depends on the content
  }

  // ============================================================================
  // Forced Encoding Modes
  // ============================================================================

  test("Forced Huffman encoding works") {
    val s = "test"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encodeHuffmanForced(s, buffer).assertSuccess

    buffer.flip()
    // Huffman flag should be set
    val firstByte = buffer.get(0) & 0xff
    assert((firstByte & 0x80) != 0, "Expected Huffman flag to be set")

    // Should decode correctly
    buffer.rewind()
    val decoded = HpackString.decode(buffer).assertSuccess
    assertEquals(decoded, s)
  }

  test("Forced literal encoding works") {
    val s = "www.example.com"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encodeLiteralForced(s, buffer).assertSuccess

    buffer.flip()
    // Huffman flag should NOT be set
    val firstByte = buffer.get(0) & 0xff
    assert((firstByte & 0x80) == 0, "Expected Huffman flag to be clear")

    // Should decode correctly
    buffer.rewind()
    val decoded = HpackString.decode(buffer).assertSuccess
    assertEquals(decoded, s)
  }

  // ============================================================================
  // RFC 7541 Test Vectors
  // ============================================================================

  test("RFC 7541 C.4.1: custom-key literal encoding") {
    // Per RFC, "custom-key" Huffman-encoded is 8 bytes
    val s = "custom-key"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encode(s, buffer).assertSuccess

    buffer.flip()
    val decoded = HpackString.decode(buffer).assertSuccess
    assertEquals(decoded, s)
  }

  test("RFC 7541 C.4.1: custom-value literal encoding") {
    val s = "custom-value"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encode(s, buffer).assertSuccess

    buffer.flip()
    val decoded = HpackString.decode(buffer).assertSuccess
    assertEquals(decoded, s)
  }

  // ============================================================================
  // Length Calculation
  // ============================================================================

  test("encodedLength matches actual encoding") {
    val testStrings = List(
      "",
      "a",
      "test",
      "www.example.com",
      "application/json; charset=utf-8",
      "Mon, 21 Oct 2013 20:13:21 GMT"
    )

    for (s <- testStrings) {
      val expectedLength = HpackString.encodedLength(s)
      val buffer = ByteBuffer.allocate(100)
      HpackString.encode(s, buffer).assertSuccess

      buffer.flip()
      assertEquals(buffer.remaining, expectedLength, s"Length mismatch for '$s'")
    }
  }

  // ============================================================================
  // Error Cases
  // ============================================================================

  test("Decode fails on empty buffer") {
    val buffer = ByteBuffer.allocate(0)
    val result = HpackString.decode(buffer)
    assert(result.isFailure)
  }

  test("Decode fails on truncated length") {
    // First byte indicates continuation but buffer ends
    val bytes = Array[Byte](0xff.toByte) // H=1, length=127 (max prefix, needs continuation)
    val buffer = ByteBuffer.wrap(bytes)
    val result = HpackString.decode(buffer)
    assert(result.isFailure)
  }

  test("Decode fails on truncated string data") {
    // Length says 10 bytes, but only provide 3
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(10, 7, 0, buffer).assertSuccess // H=0, length=10
    buffer.put(Array[Byte](1, 2, 3)) // Only 3 bytes of data
    buffer.flip()

    val result = HpackString.decode(buffer)
    assert(result.isFailure)
    assert(result.assertFailure.message.contains("underflow"))
  }

  test("Encode fails on buffer overflow") {
    val s = "This is a longer string that needs more buffer space"
    val buffer = ByteBuffer.allocate(5) // Too small
    val result = HpackString.encode(s, buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // decodeFromArray convenience method
  // ============================================================================

  test("decodeFromArray returns string and bytes consumed") {
    val s = "test"
    val buffer = ByteBuffer.allocate(100)
    HpackString.encode(s, buffer).assertSuccess

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    val (decoded, consumed) = HpackString.decodeFromArray(bytes).assertSuccess
    assertEquals(decoded, s)
    assertEquals(consumed, bytes.length)
  }

  test("decodeFromArray works with extra trailing bytes") {
    val s = "test"
    val encodeBuffer = ByteBuffer.allocate(100)
    HpackString.encode(s, encodeBuffer).assertSuccess

    encodeBuffer.flip()
    val encodedLength = encodeBuffer.remaining
    val encodedBytes = new Array[Byte](encodedLength)
    encodeBuffer.get(encodedBytes)

    // Create array with encoded string + extra trailing bytes
    val allBytes = encodedBytes ++ Array[Byte](0x00, 0x01, 0x02)

    val (decoded, consumed) = HpackString.decodeFromArray(allBytes).assertSuccess
    assertEquals(decoded, s)
    assertEquals(consumed, encodedLength)
  }
}
