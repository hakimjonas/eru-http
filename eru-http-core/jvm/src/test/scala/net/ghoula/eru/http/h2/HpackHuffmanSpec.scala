package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HPACK Huffman encoding/decoding per RFC 7541 Section 5.2 and Appendix B.
  *
  * Test vectors from RFC 7541 Appendix C.
  */
class HpackHuffmanSpec extends FunSuite {

  // ============================================================================
  // Basic Round-Trip Tests
  // ============================================================================

  test("Round-trip encoding/decoding for simple ASCII string") {
    val input = "www.example.com".getBytes("UTF-8")
    val buffer = ByteBuffer.allocate(100)

    HpackHuffman.encode(input, buffer).assertSuccess

    buffer.flip()
    val encoded = new Array[Byte](buffer.remaining)
    buffer.get(encoded)

    val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
    assertEquals(new String(decoded, "UTF-8"), "www.example.com")
  }

  test("Round-trip for common header values") {
    val testStrings = List(
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
      val input = s.getBytes("UTF-8")
      val buffer = ByteBuffer.allocate(100)

      HpackHuffman.encode(input, buffer).assertSuccess

      buffer.flip()
      val encoded = new Array[Byte](buffer.remaining)
      buffer.get(encoded)

      val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
      assertEquals(new String(decoded, "UTF-8"), s, s"Round-trip failed for '$s'")
    }
  }

  // ============================================================================
  // Compression Ratio Tests
  // ============================================================================

  test("Huffman encoding compresses typical HTTP text") {
    val input = "www.example.com".getBytes("UTF-8")
    val encodedLength = HpackHuffman.encodedLength(input)

    // Huffman should compress this (typical HTTP text uses frequent characters)
    assert(encodedLength < input.length, s"Expected compression: raw=${input.length}, encoded=$encodedLength")
  }

  test("encodedLength matches actual encoding") {
    val testStrings = List(
      "www.example.com",
      "GET",
      "application/json; charset=utf-8",
      "Mon, 21 Oct 2013 20:13:21 GMT"
    )

    for (s <- testStrings) {
      val input = s.getBytes("UTF-8")
      val expectedLength = HpackHuffman.encodedLength(input)
      val buffer = ByteBuffer.allocate(100)

      HpackHuffman.encode(input, buffer).assertSuccess

      buffer.flip()
      assertEquals(buffer.remaining, expectedLength, s"Length mismatch for '$s'")
    }
  }

  // ============================================================================
  // Edge Cases
  // ============================================================================

  test("Empty input produces empty output") {
    val input = Array.empty[Byte]
    val buffer = ByteBuffer.allocate(100)

    val bytesWritten = HpackHuffman.encode(input, buffer).assertSuccess
    assertEquals(bytesWritten, 0)

    buffer.flip()
    assertEquals(buffer.remaining, 0)

    val decoded = HpackHuffman.decodeFromArray(Array.empty[Byte]).assertSuccess
    assertEquals(decoded.length, 0)
  }

  test("Single character encoding/decoding") {
    // Test characters with different code lengths
    val chars = List('a', 'e', 't', 'A', '0', ' ', '!', '@')

    for (c <- chars) {
      val input = Array(c.toByte)
      val buffer = ByteBuffer.allocate(10)

      HpackHuffman.encode(input, buffer).assertSuccess

      buffer.flip()
      val encoded = new Array[Byte](buffer.remaining)
      buffer.get(encoded)

      val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
      assertEquals(decoded.length, 1)
      assertEquals(decoded(0), c.toByte)
    }
  }

  test("High-byte characters (128-255) encode/decode correctly") {
    // These have longer codes (20-28 bits)
    val input = Array[Byte](0x80.toByte, 0xff.toByte, 0xfe.toByte)
    val buffer = ByteBuffer.allocate(20)

    HpackHuffman.encode(input, buffer).assertSuccess

    buffer.flip()
    val encoded = new Array[Byte](buffer.remaining)
    buffer.get(encoded)

    val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
    assertEquals(decoded.toList, input.toList)
  }

  // ============================================================================
  // RFC 7541 Test Vectors
  // ============================================================================

  test("RFC 7541 C.4.1: Literal Header Field with Indexing (custom-key)") {
    // "custom-key" Huffman encoded should be: 25a849e95ba97d7f
    // Per RFC 7541 Appendix C.4.1
    val input = "custom-key".getBytes("UTF-8")
    val buffer = ByteBuffer.allocate(20)

    HpackHuffman.encode(input, buffer).assertSuccess

    buffer.flip()
    val encoded = new Array[Byte](buffer.remaining)
    buffer.get(encoded)

    // Verify encoding length (should be 8 bytes per RFC)
    assertEquals(encoded.length, 8)

    // Verify decodes back correctly
    val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
    assertEquals(new String(decoded, "UTF-8"), "custom-key")
  }

  test("RFC 7541 C.4.1: custom-value Huffman encoding") {
    val input = "custom-value".getBytes("UTF-8")
    val buffer = ByteBuffer.allocate(20)

    HpackHuffman.encode(input, buffer).assertSuccess

    buffer.flip()
    val encoded = new Array[Byte](buffer.remaining)
    buffer.get(encoded)

    // Verify decodes back correctly
    val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
    assertEquals(new String(decoded, "UTF-8"), "custom-value")
  }

  // ============================================================================
  // Error Cases
  // ============================================================================

  test("Encode fails on buffer overflow") {
    val input = "This is a longer string that needs more buffer space".getBytes("UTF-8")
    val buffer = ByteBuffer.allocate(5) // Too small

    val result = HpackHuffman.encode(input, buffer)
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.message.contains("overflow"))
  }

  test("Decode fails on insufficient buffer") {
    val buffer = ByteBuffer.allocate(0)
    val result = HpackHuffman.decode(buffer, 10)
    assert(result.isFailure)
  }

  // ============================================================================
  // Padding Tests
  // ============================================================================

  test("Padding uses EOS prefix bits") {
    // When encoding doesn't end on byte boundary, padding should be EOS MSBs
    val input = "a".getBytes("UTF-8") // 'a' is 5 bits (00011), needs 3 bits padding
    val buffer = ByteBuffer.allocate(10)

    HpackHuffman.encode(input, buffer).assertSuccess

    buffer.flip()
    val encoded = new Array[Byte](buffer.remaining)
    buffer.get(encoded)

    // Should be 1 byte: 'a' (00011) + padding (111) = 00011111 = 0x1f
    assertEquals(encoded.length, 1)
    assertEquals(encoded(0) & 0xff, 0x1f)

    // Should decode back to 'a'
    val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
    assertEquals(new String(decoded, "UTF-8"), "a")
  }

  // ============================================================================
  // Performance / Large Input
  // ============================================================================

  test("Large string encoding/decoding") {
    val input = ("abcdefghijklmnopqrstuvwxyz" * 100).getBytes("UTF-8")
    val buffer = ByteBuffer.allocate(5000)

    HpackHuffman.encode(input, buffer).assertSuccess

    buffer.flip()
    val encoded = new Array[Byte](buffer.remaining)
    buffer.get(encoded)

    // Should compress (alphanumeric has 5-7 bit codes)
    assert(encoded.length < input.length)

    val decoded = HpackHuffman.decodeFromArray(encoded).assertSuccess
    assertEquals(decoded.toList, input.toList)
  }
}
