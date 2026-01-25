package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HPACK encoder and decoder per RFC 7541.
  *
  * Test vectors from RFC 7541 Appendix C.
  */
class HpackEncoderDecoderSpec extends FunSuite {

  // ============================================================================
  // Basic Round-Trip Tests
  // ============================================================================

  test("Round-trip encoding/decoding single header") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val headers = List(("content-type", "text/html"))
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess
    assertEquals(decoded.map(h => (h._1, h._2)), headers)
  }

  test("Round-trip encoding/decoding multiple headers") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val headers = List(
      (":method", "GET"),
      (":path", "/index.html"),
      (":scheme", "https"),
      (":authority", "www.example.com"),
      ("accept", "text/html")
    )
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess
    assertEquals(decoded.map(h => (h._1, h._2)), headers)
  }

  test("Round-trip with sensitive headers marked as never-indexed") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val headers = List(
      ("authorization", "Bearer secret-token"),
      ("content-type", "application/json")
    )
    val sensitive = Set("authorization")
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer, sensitive).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess

    // Authorization should be marked as sensitive
    val authHeader = decoded.find(_._1 == "authorization")
    assert(authHeader.isDefined)
    assert(authHeader.get._3, "Authorization header should be marked as sensitive")

    // Content-type should not be sensitive
    val ctHeader = decoded.find(_._1 == "content-type")
    assert(ctHeader.isDefined)
    assert(!ctHeader.get._3, "Content-type header should not be marked as sensitive")
  }

  // ============================================================================
  // Static Table Tests
  // ============================================================================

  test("Indexed representation for static table headers") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    // :method: GET is at static index 2
    val headers = List((":method", "GET"))
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    // Should be encoded as a single byte (indexed representation)
    assert(buffer.remaining <= 2, s"Expected small encoding, got ${buffer.remaining} bytes")

    val decoded = decoder.decode(buffer).assertSuccess
    assertEquals(decoded.map(h => (h._1, h._2)), headers)
  }

  test("Name-indexed representation for static table names") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    // :status exists in static table but value 201 doesn't
    val headers = List((":status", "201"))
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess
    assertEquals(decoded.map(h => (h._1, h._2)), headers)
  }

  // ============================================================================
  // Dynamic Table Tests
  // ============================================================================

  test("Dynamic table builds up across multiple encodes") {
    val encoder = HpackEncoder()

    // First request - new headers get added to dynamic table
    val headers1 = List(("custom-header", "value1"))
    val buffer1 = ByteBuffer.allocate(1024)
    encoder.encode(headers1, buffer1).assertSuccess
    val firstEncodingSize = buffer1.position()

    // Second request - same header should use indexed
    val buffer2 = ByteBuffer.allocate(1024)
    encoder.encode(headers1, buffer2).assertSuccess
    val secondEncodingSize = buffer2.position()

    // Second encoding should be smaller (indexed vs literal)
    assert(
      secondEncodingSize < firstEncodingSize,
      s"Expected indexed encoding to be smaller: first=$firstEncodingSize, second=$secondEncodingSize"
    )
  }

  test("Decoder dynamic table matches encoder") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    // Encode some headers to populate encoder's dynamic table
    val headers = List(
      ("x-custom-header", "custom-value"),
      ("x-another-header", "another-value")
    )
    val buffer = ByteBuffer.allocate(1024)
    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    decoder.decode(buffer).assertSuccess

    // Both should have same dynamic table state
    assertEquals(
      encoder.getDynamicTable.length,
      decoder.getDynamicTable.length
    )
  }

  // ============================================================================
  // RFC 7541 Appendix C Test Vectors
  // ============================================================================

  test("RFC 7541 C.3: Request Examples without Huffman Coding - First Request") {
    // This tests the decoder against known byte sequences
    val decoder = HpackDecoder()

    // C.3.1 First Request
    // :method: GET
    // :scheme: http
    // :path: /
    // :authority: www.example.com
    //
    // Encoded:
    // 82                                      | == Indexed - Add ==
    //                                         |   idx = 2
    //                                         | -> :method: GET
    // 86                                      | == Indexed - Add ==
    //                                         |   idx = 6
    //                                         | -> :scheme: http
    // 84                                      | == Indexed - Add ==
    //                                         |   idx = 4
    //                                         | -> :path: /
    // 41                                      | == Literal indexed ==
    //                                         |   Indexed name (idx = 1)
    //                                         |     :authority
    // 0f                                      |   Literal value (len = 15)
    // 77 77 77 2e 65 78 61 6d 70 6c 65 2e 63 6f 6d
    //                                         | www.example.com

    val bytes = Array[Byte](
      0x82.toByte,
      0x86.toByte,
      0x84.toByte,
      0x41.toByte,
      0x0f.toByte,
      0x77,
      0x77,
      0x77,
      0x2e,
      0x65,
      0x78,
      0x61,
      0x6d,
      0x70,
      0x6c,
      0x65,
      0x2e,
      0x63,
      0x6f,
      0x6d
    )
    val buffer = ByteBuffer.wrap(bytes)

    val decoded = decoder.decode(buffer).assertSuccess
    val headers = decoded.map(h => (h._1, h._2))

    assertEquals(headers.length, 4)
    assertEquals(headers(0), (":method", "GET"))
    assertEquals(headers(1), (":scheme", "http"))
    assertEquals(headers(2), (":path", "/"))
    assertEquals(headers(3), (":authority", "www.example.com"))

    // Dynamic table should now contain :authority: www.example.com
    assertEquals(decoder.getDynamicTable.length, 1)
  }

  test("RFC 7541 C.4: Request Examples with Huffman Coding - First Request") {
    val decoder = HpackDecoder()

    // C.4.1 First Request (with Huffman)
    // custom-key: custom-value
    //
    // 40                                      | == Literal indexed ==
    // 88                                      |   Literal name (len = 8)
    //                                         |     Huffman encoded:
    // 25 a8 49 e9 5b a9 7d 7f                | custom-key
    // 89                                      |   Literal value (len = 9)
    //                                         |     Huffman encoded:
    // 25 a8 49 e9 5b b8 e8 b4 bf             | custom-value

    val bytes = Array[Byte](
      0x40.toByte,
      0x88.toByte,
      0x25.toByte,
      0xa8.toByte,
      0x49.toByte,
      0xe9.toByte,
      0x5b.toByte,
      0xa9.toByte,
      0x7d.toByte,
      0x7f.toByte,
      0x89.toByte,
      0x25.toByte,
      0xa8.toByte,
      0x49.toByte,
      0xe9.toByte,
      0x5b.toByte,
      0xb8.toByte,
      0xe8.toByte,
      0xb4.toByte,
      0xbf.toByte
    )
    val buffer = ByteBuffer.wrap(bytes)

    val decoded = decoder.decode(buffer).assertSuccess
    val headers = decoded.map(h => (h._1, h._2))

    assertEquals(headers.length, 1)
    assertEquals(headers(0), ("custom-key", "custom-value"))

    // Dynamic table should contain this entry
    assertEquals(decoder.getDynamicTable.length, 1)
  }

  // ============================================================================
  // Table Size Update Tests
  // ============================================================================

  test("Encoder can emit table size update") {
    val encoder = HpackEncoder(4096)
    val decoder = HpackDecoder(4096)

    val buffer = ByteBuffer.allocate(1024)

    // Encode a size update followed by headers
    encoder.encodeTableSizeUpdate(2048, buffer).assertSuccess

    val headers = List((":method", "GET"))
    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess
    assertEquals(decoded.map(h => (h._1, h._2)), headers)

    // Decoder should have updated its table size
    assertEquals(decoder.getDynamicTable.maxTableSize, 2048)
  }

  // ============================================================================
  // Error Handling Tests
  // ============================================================================

  test("Decoder rejects invalid index 0") {
    val decoder = HpackDecoder()

    // Indexed representation with index 0 is invalid
    val bytes = Array[Byte](0x80.toByte) // index = 0 with indexed bit set
    val buffer = ByteBuffer.wrap(bytes)

    val result = decoder.decode(buffer)
    assert(result.isFailure)
    assert(result.assertFailure.message.contains("index"))
  }

  test("Decoder rejects out-of-range dynamic table index") {
    val decoder = HpackDecoder()

    // Index 100 doesn't exist in static or dynamic table
    // Encode as indexed: 0x80 | 100 (but 100 > 127, so need continuation)
    // Actually, let's use a simpler approach: index 62 with empty dynamic table
    val bytes = Array[Byte](0xbe.toByte) // 0x80 | 62 = 0xbe (first dynamic index)
    val buffer = ByteBuffer.wrap(bytes)

    val result = decoder.decode(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // Header Name Normalization Tests
  // ============================================================================

  test("Encoder normalizes header names to lowercase") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val headers = List(("Content-Type", "text/html"))
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess

    // Name should be lowercased
    assertEquals(decoded(0)._1, "content-type")
    assertEquals(decoded(0)._2, "text/html")
  }

  // ============================================================================
  // Large Header Tests
  // ============================================================================

  test("Encode and decode large header value") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val largeValue = "x" * 1000
    val headers = List(("x-large-header", largeValue))
    val buffer = ByteBuffer.allocate(2048)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess

    assertEquals(decoded(0)._1, "x-large-header")
    assertEquals(decoded(0)._2, largeValue)
  }

  test("Encode and decode many headers") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val headers = (1 to 50).map(i => (s"x-header-$i", s"value-$i")).toList
    val buffer = ByteBuffer.allocate(8192)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    val decoded = decoder.decode(buffer).assertSuccess

    assertEquals(decoded.map(h => (h._1, h._2)), headers)
  }

  // ============================================================================
  // Empty Header Block Tests
  // ============================================================================

  test("Encode and decode empty header block") {
    val encoder = HpackEncoder()
    val decoder = HpackDecoder()

    val headers = List.empty[(String, String)]
    val buffer = ByteBuffer.allocate(1024)

    encoder.encode(headers, buffer).assertSuccess

    buffer.flip()
    assertEquals(buffer.remaining, 0)

    val decoded = decoder.decode(buffer).assertSuccess
    assertEquals(decoded, List.empty)
  }
}
