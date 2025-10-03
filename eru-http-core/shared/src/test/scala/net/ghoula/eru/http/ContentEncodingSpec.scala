package net.ghoula.eru.http

import munit.FunSuite

import TestHelpers.*

class ContentEncodingSpec extends FunSuite {

  // ===== Basic Parsing Tests =====

  test("ContentEncoding - parse gzip") {
    val encoding = ContentEncoding.parse("gzip").assertSuccess
    assertEquals(encoding, ContentEncoding.Gzip)
    assertEquals(encoding.value, "gzip")
  }

  test("ContentEncoding - parse x-gzip (alias for gzip)") {
    val encoding = ContentEncoding.parse("x-gzip").assertSuccess
    assertEquals(encoding, ContentEncoding.Gzip)
  }

  test("ContentEncoding - parse deflate") {
    val encoding = ContentEncoding.parse("deflate").assertSuccess
    assertEquals(encoding, ContentEncoding.Deflate)
    assertEquals(encoding.value, "deflate")
  }

  test("ContentEncoding - parse br (Brotli)") {
    val encoding = ContentEncoding.parse("br").assertSuccess
    assertEquals(encoding, ContentEncoding.Brotli)
    assertEquals(encoding.value, "br")
  }

  test("ContentEncoding - parse identity") {
    val encoding = ContentEncoding.parse("identity").assertSuccess
    assertEquals(encoding, ContentEncoding.Identity)
    assertEquals(encoding.value, "identity")
  }

  test("ContentEncoding - parse compress") {
    val encoding = ContentEncoding.parse("compress").assertSuccess
    assertEquals(encoding, ContentEncoding.Compress)
    assertEquals(encoding.value, "compress")
  }

  test("ContentEncoding - parse x-compress (alias for compress)") {
    val encoding = ContentEncoding.parse("x-compress").assertSuccess
    assertEquals(encoding, ContentEncoding.Compress)
  }

  test("ContentEncoding - parse custom encoding") {
    val encoding = ContentEncoding.parse("x-custom").assertSuccess
    encoding match {
      case ContentEncoding.Custom(name) => assertEquals(name, "x-custom")
      case _ => fail("Expected Custom encoding")
    }
  }

  test("ContentEncoding - parse is case-insensitive") {
    assertEquals(ContentEncoding.parse("GZIP").assertSuccess, ContentEncoding.Gzip)
    assertEquals(ContentEncoding.parse("Deflate").assertSuccess, ContentEncoding.Deflate)
    assertEquals(ContentEncoding.parse("BR").assertSuccess, ContentEncoding.Brotli)
  }

  test("ContentEncoding - parse trims whitespace") {
    assertEquals(ContentEncoding.parse("  gzip  ").assertSuccess, ContentEncoding.Gzip)
    assertEquals(ContentEncoding.parse("\tdeflate\n").assertSuccess, ContentEncoding.Deflate)
  }

  test("ContentEncoding - parse fails on empty string") {
    val result = ContentEncoding.parse("")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Empty"))
  }

  test("ContentEncoding - parse fails on whitespace-only string") {
    val result = ContentEncoding.parse("   ")
    assert(result.isFailure)
  }

  // ===== isSupported Tests =====

  test("ContentEncoding - gzip is supported") {
    assert(ContentEncoding.Gzip.isSupported)
  }

  test("ContentEncoding - deflate is supported") {
    assert(ContentEncoding.Deflate.isSupported)
  }

  test("ContentEncoding - brotli is supported") {
    assert(ContentEncoding.Brotli.isSupported)
  }

  test("ContentEncoding - identity is supported (no-op)") {
    assert(ContentEncoding.Identity.isSupported)
  }

  test("ContentEncoding - custom is not supported") {
    assert(!ContentEncoding.Custom("x-test").isSupported)
  }

  // ===== Multiple Encodings Tests =====

  test("ContentEncoding - parseMultiple with single encoding") {
    val encodings = ContentEncoding.parseMultiple("gzip").assertSuccess
    assertEquals(encodings, List(ContentEncoding.Gzip))
  }

  test("ContentEncoding - parseMultiple with multiple encodings") {
    val encodings = ContentEncoding.parseMultiple("gzip, deflate").assertSuccess
    assertEquals(encodings, List(ContentEncoding.Gzip, ContentEncoding.Deflate))
  }

  test("ContentEncoding - parseMultiple with three encodings") {
    val encodings = ContentEncoding.parseMultiple("gzip, deflate, br").assertSuccess
    assertEquals(encodings, List(ContentEncoding.Gzip, ContentEncoding.Deflate, ContentEncoding.Brotli))
  }

  test("ContentEncoding - parseMultiple trims whitespace") {
    val encodings = ContentEncoding.parseMultiple("  gzip  ,  deflate  ").assertSuccess
    assertEquals(encodings, List(ContentEncoding.Gzip, ContentEncoding.Deflate))
  }

  test("ContentEncoding - parseMultiple handles extra commas") {
    val encodings = ContentEncoding.parseMultiple("gzip,, deflate").assertSuccess
    assertEquals(encodings, List(ContentEncoding.Gzip, ContentEncoding.Deflate))
  }

  test("ContentEncoding - parseMultiple returns empty list for empty string") {
    val encodings = ContentEncoding.parseMultiple("").assertSuccess
    assertEquals(encodings, List.empty)
  }

  test("ContentEncoding - parseMultiple returns empty list for comma-only string") {
    val encodings = ContentEncoding.parseMultiple(",,,").assertSuccess
    assertEquals(encodings, List.empty)
  }

  // ===== Accept-Encoding Tests =====

  test("ContentEncoding - parseAcceptEncoding with single encoding") {
    val result = ContentEncoding.parseAcceptEncoding("gzip").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 1.0)))
  }

  test("ContentEncoding - parseAcceptEncoding with multiple encodings") {
    val result = ContentEncoding.parseAcceptEncoding("gzip, deflate").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 1.0), (ContentEncoding.Deflate, 1.0)))
  }

  test("ContentEncoding - parseAcceptEncoding with qvalues") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=0.9, deflate;q=0.8").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 0.9), (ContentEncoding.Deflate, 0.8)))
  }

  test("ContentEncoding - parseAcceptEncoding sorts by qvalue descending") {
    val result = ContentEncoding.parseAcceptEncoding("deflate;q=0.5, gzip;q=1.0, br;q=0.8").assertSuccess
    assertEquals(
      result,
      List(
        (ContentEncoding.Gzip, 1.0),
        (ContentEncoding.Brotli, 0.8),
        (ContentEncoding.Deflate, 0.5)
      )
    )
  }

  test("ContentEncoding - parseAcceptEncoding with wildcard") {
    val result = ContentEncoding.parseAcceptEncoding("gzip, *;q=0.1").assertSuccess
    // Wildcard maps to Identity
    assertEquals(result, List((ContentEncoding.Gzip, 1.0), (ContentEncoding.Identity, 0.1)))
  }

  test("ContentEncoding - parseAcceptEncoding with explicit qvalue 1.0") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=1.0").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 1.0)))
  }

  test("ContentEncoding - parseAcceptEncoding with qvalue 0.0") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=0.0").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 0.0)))
  }

  test("ContentEncoding - parseAcceptEncoding handles whitespace in qvalue") {
    val result = ContentEncoding.parseAcceptEncoding("gzip ; q = 0.9").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 0.9)))
  }

  test("ContentEncoding - parseAcceptEncoding fails on invalid qvalue > 1.0") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=1.5")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("between 0.0 and 1.0"))
  }

  test("ContentEncoding - parseAcceptEncoding fails on invalid qvalue < 0.0") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=-0.5")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("between 0.0 and 1.0"))
  }

  test("ContentEncoding - parseAcceptEncoding fails on non-numeric qvalue") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=abc")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Invalid qvalue"))
  }

  test("ContentEncoding - parseAcceptEncoding returns empty list for empty string") {
    val result = ContentEncoding.parseAcceptEncoding("").assertSuccess
    assertEquals(result, List.empty)
  }

  // ===== Real-World Examples =====

  test("ContentEncoding - parseAcceptEncoding real-world example 1") {
    // Chrome/Edge typical Accept-Encoding
    val result = ContentEncoding.parseAcceptEncoding("gzip, deflate, br").assertSuccess
    assertEquals(
      result,
      List(
        (ContentEncoding.Gzip, 1.0),
        (ContentEncoding.Deflate, 1.0),
        (ContentEncoding.Brotli, 1.0)
      )
    )
  }

  test("ContentEncoding - parseAcceptEncoding real-world example 2") {
    // Server with preference
    val result = ContentEncoding.parseAcceptEncoding("br;q=1.0, gzip;q=0.8, *;q=0.1").assertSuccess
    assertEquals(
      result,
      List(
        (ContentEncoding.Brotli, 1.0),
        (ContentEncoding.Gzip, 0.8),
        (ContentEncoding.Identity, 0.1)
      )
    )
  }

  test("ContentEncoding - parseAcceptEncoding real-world example 3") {
    // Client that doesn't want compression for this request
    val result = ContentEncoding.parseAcceptEncoding("identity").assertSuccess
    assertEquals(result, List((ContentEncoding.Identity, 1.0)))
  }

  // ===== Edge Cases =====

  test("ContentEncoding - parseAcceptEncoding with multiple qvalue parameters") {
    // Only the first q parameter should be used
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=0.9;q=0.5").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 0.9)))
  }

  test("ContentEncoding - parseAcceptEncoding ignores unknown parameters") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;level=6;q=0.9").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 0.9)))
  }

  test("ContentEncoding - parseAcceptEncoding with decimal qvalues") {
    val result = ContentEncoding.parseAcceptEncoding("gzip;q=0.95, deflate;q=0.85").assertSuccess
    assertEquals(result, List((ContentEncoding.Gzip, 0.95), (ContentEncoding.Deflate, 0.85)))
  }
}
