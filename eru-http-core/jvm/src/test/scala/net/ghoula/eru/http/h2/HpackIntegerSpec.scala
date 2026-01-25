package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HPACK integer encoding/decoding per RFC 7541 Section 5.1.
  *
  * Test vectors from RFC 7541 Appendix C.1.
  */
class HpackIntegerSpec extends FunSuite {

  // ============================================================================
  // RFC 7541 Section 5.1 Examples
  // ============================================================================

  test("RFC 7541 C.1.1: Encoding 10 with 5-bit prefix") {
    // From RFC: "The value 10 is to be encoded with a 5-bit prefix.
    // 10 is less than 31 (2^5 - 1) and is represented using the 5-bit prefix."
    // Result: 0x0a (binary: 0 0 0 0 1 0 1 0)
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(10, 5, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    assertEquals(buffer.get() & 0xff, 0x0a)
  }

  test("RFC 7541 C.1.1: Decoding 10 with 5-bit prefix") {
    val bytes = Array[Byte](0x0a)
    val (value, consumed) = HpackInteger.decodeFromArray(bytes, 5).assertSuccess

    assertEquals(value, 10)
    assertEquals(consumed, 1)
  }

  test("RFC 7541 C.1.2: Encoding 1337 with 5-bit prefix") {
    // From RFC: "The value 1337 is to be encoded with a 5-bit prefix.
    // 1337 is greater than 31 (2^5 - 1).
    // The 5-bit prefix is filled with its max value (31).
    // I = 1337 - 31 = 1306
    // 1306 >= 128, so: 1306 % 128 + 128 = 154, 1306 / 128 = 10
    // 10 < 128, so encode 10
    // Result: 31 (0x1f), 154 (0x9a), 10 (0x0a)"
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(1337, 5, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 3)
    assertEquals(buffer.get() & 0xff, 0x1f) // 31 = prefix filled
    assertEquals(buffer.get() & 0xff, 0x9a) // 154 = (1306 % 128) + 128
    assertEquals(buffer.get() & 0xff, 0x0a) // 10 = 1306 / 128
  }

  test("RFC 7541 C.1.2: Decoding 1337 with 5-bit prefix") {
    val bytes = Array[Byte](0x1f.toByte, 0x9a.toByte, 0x0a.toByte)
    val (value, consumed) = HpackInteger.decodeFromArray(bytes, 5).assertSuccess

    assertEquals(value, 1337)
    assertEquals(consumed, 3)
  }

  test("RFC 7541 C.1.3: Encoding 42 starting on a byte boundary (8-bit prefix)") {
    // From RFC: "The value 42 is to be encoded starting at an octet boundary.
    // This is done by encoding with an 8-bit prefix."
    // 42 < 255 (2^8 - 1), so encode directly
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(42, 8, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    assertEquals(buffer.get() & 0xff, 42)
  }

  test("RFC 7541 C.1.3: Decoding 42 with 8-bit prefix") {
    val bytes = Array[Byte](42)
    val (value, consumed) = HpackInteger.decodeFromArray(bytes, 8).assertSuccess

    assertEquals(value, 42)
    assertEquals(consumed, 1)
  }

  // ============================================================================
  // Edge Cases
  // ============================================================================

  test("Encode/decode 0") {
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(0, 5, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    assertEquals(buffer.get() & 0xff, 0)

    val (value, _) = HpackInteger.decodeFromArray(Array[Byte](0), 5).assertSuccess
    assertEquals(value, 0)
  }

  test("Encode/decode max prefix value minus 1 (should fit in prefix)") {
    // 5-bit prefix max is 31, so 30 should fit
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(30, 5, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    assertEquals(buffer.get() & 0xff, 30)

    val (value, _) = HpackInteger.decodeFromArray(Array[Byte](30), 5).assertSuccess
    assertEquals(value, 30)
  }

  test("Encode/decode exact max prefix value (should need continuation)") {
    // 5-bit prefix max is 31, encoding 31 needs continuation
    // 31 = 31 + 0, so: prefix=31, continuation=0
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(31, 5, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 2)
    assertEquals(buffer.get() & 0xff, 0x1f) // 31 = prefix filled
    assertEquals(buffer.get() & 0xff, 0x00) // 0 = remainder

    val bytes = Array[Byte](0x1f.toByte, 0x00.toByte)
    val (value, _) = HpackInteger.decodeFromArray(bytes, 5).assertSuccess
    assertEquals(value, 31)
  }

  test("Encode with firstByteMask preserves high bits") {
    // Encode 10 with 5-bit prefix, but set high 3 bits to 0b11100000 = 0xe0
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(10, 5, 0xe0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    // Should be 0xe0 | 0x0a = 0xea
    assertEquals(buffer.get() & 0xff, 0xea)
  }

  test("Decode ignores high bits above prefix") {
    // Byte 0xea = 0b11101010, with 5-bit prefix should extract 10
    val bytes = Array[Byte](0xea.toByte)
    val (value, _) = HpackInteger.decodeFromArray(bytes, 5).assertSuccess

    assertEquals(value, 10)
  }

  // ============================================================================
  // Prefix Sizes
  // ============================================================================

  test("Encode/decode with 1-bit prefix") {
    // 1-bit prefix max is 1
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(0, 1, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    assertEquals(buffer.get() & 0xff, 0)
  }

  test("Encode/decode with 7-bit prefix") {
    // 7-bit prefix max is 127
    val buffer = ByteBuffer.allocate(10)
    HpackInteger.encode(100, 7, 0, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 1)
    assertEquals(buffer.get() & 0xff, 100)

    val (value, _) = HpackInteger.decodeFromArray(Array[Byte](100), 7).assertSuccess
    assertEquals(value, 100)
  }

  // ============================================================================
  // encodedLength
  // ============================================================================

  test("encodedLength for small values") {
    assertEquals(HpackInteger.encodedLength(0, 5), 1)
    assertEquals(HpackInteger.encodedLength(10, 5), 1)
    assertEquals(HpackInteger.encodedLength(30, 5), 1)
  }

  test("encodedLength for values requiring continuation") {
    assertEquals(HpackInteger.encodedLength(31, 5), 2) // 31 = prefix + 1 byte
    assertEquals(HpackInteger.encodedLength(1337, 5), 3) // 1337 needs 3 bytes
  }

  test("encodedLength matches actual encoding") {
    val testValues = List(0, 10, 30, 31, 100, 1337, 10000, 100000)
    val prefixBits = List(1, 3, 5, 7, 8)

    for {
      value <- testValues
      prefix <- prefixBits
    } {
      val expectedLength = HpackInteger.encodedLength(value, prefix)
      val buffer = ByteBuffer.allocate(20)
      HpackInteger.encode(value, prefix, 0, buffer).assertSuccess
      buffer.flip()
      assertEquals(
        buffer.remaining,
        expectedLength,
        s"Mismatch for value=$value, prefix=$prefix"
      )
    }
  }

  // ============================================================================
  // Round-trip
  // ============================================================================

  test("Round-trip encoding/decoding for various values") {
    val testValues = List(0, 1, 10, 30, 31, 32, 100, 127, 128, 255, 256, 1337, 10000, 65535, 100000)

    for (value <- testValues) {
      val buffer = ByteBuffer.allocate(20)
      HpackInteger.encode(value, 5, 0, buffer).assertSuccess

      buffer.flip()
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)

      val (decoded, _) = HpackInteger.decodeFromArray(bytes, 5).assertSuccess
      assertEquals(decoded, value, s"Round-trip failed for $value")
    }
  }

  // ============================================================================
  // Error Cases
  // ============================================================================

  test("Encode rejects negative values") {
    val buffer = ByteBuffer.allocate(10)
    val result = HpackInteger.encode(-1, 5, 0, buffer)
    assert(result.isFailure)
    assert(result.assertFailure.message.contains("non-negative"))
  }

  test("Encode rejects invalid prefix bits") {
    val buffer = ByteBuffer.allocate(10)

    val result0 = HpackInteger.encode(10, 0, 0, buffer)
    assert(result0.isFailure)

    val result9 = HpackInteger.encode(10, 9, 0, buffer)
    assert(result9.isFailure)
  }

  test("Decode rejects invalid prefix bits") {
    val result0 = HpackInteger.decode(10, 0, ByteBuffer.allocate(0))
    assert(result0.isFailure)

    val result9 = HpackInteger.decode(10, 9, ByteBuffer.allocate(0))
    assert(result9.isFailure)
  }

  test("Decode fails on truncated continuation") {
    // Start with max prefix but no continuation bytes
    val bytes = Array[Byte](0x1f.toByte) // 31 = max 5-bit prefix, needs more
    val result = HpackInteger.decodeFromArray(bytes, 5)
    assert(result.isFailure)
    assert(result.assertFailure.message.contains("Unexpected end"))
  }

  test("Encode fails on buffer overflow") {
    val buffer = ByteBuffer.allocate(1)
    // 1337 needs 3 bytes, buffer only has 1
    val result = HpackInteger.encode(1337, 5, 0, buffer)
    assert(result.isFailure)
    val errorMsg = result.assertFailure.message
    assert(errorMsg.contains("overflow") || errorMsg.contains("capacity"))
  }
}
