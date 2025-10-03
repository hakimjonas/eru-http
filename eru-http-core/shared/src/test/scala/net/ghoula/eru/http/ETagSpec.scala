package net.ghoula.eru.http

import munit.*

import TestHelpers.*

class ETagSpec extends FunSuite {

  test("parse strong ETag") {
    val result = ETag.parse("\"abc123\"").assertSuccess
    assertEquals(result.value, "abc123")
    assertEquals(result.weak, false)
  }

  test("parse weak ETag with W/ prefix") {
    val result = ETag.parse("W/\"abc123\"").assertSuccess
    assertEquals(result.value, "abc123")
    assertEquals(result.weak, true)
  }

  test("parse weak ETag with lowercase w/ prefix") {
    val result = ETag.parse("w/\"abc123\"").assertSuccess
    assertEquals(result.value, "abc123")
    assertEquals(result.weak, true)
  }

  test("parse ETag with special characters") {
    val result = ETag.parse("\"abc-123_xyz.html\"").assertSuccess
    assertEquals(result.value, "abc-123_xyz.html")
  }

  test("parse fails on unquoted value") {
    assert(ETag.parse("abc123").isFailure)
  }

  test("parse fails on empty string") {
    assert(ETag.parse("").isFailure)
  }

  test("parse fails on missing closing quote") {
    assert(ETag.parse("\"abc123").isFailure)
  }

  test("parse fails on missing opening quote") {
    assert(ETag.parse("abc123\"").isFailure)
  }

  test("parseMultiple handles single ETag") {
    val result = ETag.parseMultiple("\"abc123\"").assertSuccess
    assertEquals(result.length, 1)
    assertEquals(result.head.value, "abc123")
  }

  test("parseMultiple handles multiple ETags") {
    val result = ETag.parseMultiple("\"abc123\", \"def456\", W/\"ghi789\"").assertSuccess
    assertEquals(result.length, 3)
    assertEquals(result(0).value, "abc123")
    assertEquals(result(0).weak, false)
    assertEquals(result(1).value, "def456")
    assertEquals(result(1).weak, false)
    assertEquals(result(2).value, "ghi789")
    assertEquals(result(2).weak, true)
  }

  test("parseMultiple handles wildcard *") {
    val result = ETag.parseMultiple("*").assertSuccess
    assertEquals(result.length, 0) // Empty list represents "match all"
  }

  test("parseMultiple handles whitespace") {
    val result = ETag.parseMultiple("  \"abc123\"  ,  \"def456\"  ").assertSuccess
    assertEquals(result.length, 2)
  }

  test("strong constructor creates strong ETag") {
    val etag = ETag.strong("test123")
    assertEquals(etag.value, "test123")
    assertEquals(etag.weak, false)
  }

  test("weak constructor creates weak ETag") {
    val etag = ETag.weak("test123")
    assertEquals(etag.value, "test123")
    assertEquals(etag.weak, true)
  }

  test("headerValue formats strong ETag") {
    val etag = ETag.strong("abc123")
    assertEquals(etag.headerValue, "\"abc123\"")
  }

  test("headerValue formats weak ETag") {
    val etag = ETag.weak("abc123")
    assertEquals(etag.headerValue, "W/\"abc123\"")
  }

  test("matches: strong ETags match with weak comparison") {
    val etag1 = ETag.strong("abc123")
    val etag2 = ETag.strong("abc123")
    assert(etag1.matches(etag2, strongComparison = false))
  }

  test("matches: strong ETags match with strong comparison") {
    val etag1 = ETag.strong("abc123")
    val etag2 = ETag.strong("abc123")
    assert(etag1.matches(etag2, strongComparison = true))
  }

  test("matches: weak and strong ETags match with weak comparison") {
    val etag1 = ETag.weak("abc123")
    val etag2 = ETag.strong("abc123")
    assert(etag1.matches(etag2, strongComparison = false))
  }

  test("matches: weak and strong ETags don't match with strong comparison") {
    val etag1 = ETag.weak("abc123")
    val etag2 = ETag.strong("abc123")
    assert(!etag1.matches(etag2, strongComparison = true))
  }

  test("matches: two weak ETags match with weak comparison") {
    val etag1 = ETag.weak("abc123")
    val etag2 = ETag.weak("abc123")
    assert(etag1.matches(etag2, strongComparison = false))
  }

  test("matches: two weak ETags don't match with strong comparison") {
    val etag1 = ETag.weak("abc123")
    val etag2 = ETag.weak("abc123")
    assert(!etag1.matches(etag2, strongComparison = true))
  }

  test("matches: different values don't match") {
    val etag1 = ETag.strong("abc123")
    val etag2 = ETag.strong("def456")
    assert(!etag1.matches(etag2, strongComparison = false))
    assert(!etag1.matches(etag2, strongComparison = true))
  }

  test("matchesAny: finds matching ETag in list") {
    val etag = ETag.strong("abc123")
    val others = List(
      ETag.strong("def456"),
      ETag.strong("abc123"),
      ETag.strong("ghi789")
    )
    assert(etag.matchesAny(others, strongComparison = false))
  }

  test("matchesAny: returns false when no match") {
    val etag = ETag.strong("xyz999")
    val others = List(
      ETag.strong("def456"),
      ETag.strong("abc123"),
      ETag.strong("ghi789")
    )
    assert(!etag.matchesAny(others, strongComparison = false))
  }

  test("fromBytes generates consistent ETag") {
    val bytes = Bytes.fromString("Hello, World!", Charset.UTF8)
    val etag1 = ETag.fromBytes(bytes)
    val etag2 = ETag.fromBytes(bytes)

    assertEquals(etag1.value, etag2.value)
    assertEquals(etag1.weak, false)
  }

  test("fromBytes generates different ETags for different content") {
    val bytes1 = Bytes.fromString("Hello, World!", Charset.UTF8)
    val bytes2 = Bytes.fromString("Goodbye, World!", Charset.UTF8)

    val etag1 = ETag.fromBytes(bytes1)
    val etag2 = ETag.fromBytes(bytes2)

    assert(etag1.value != etag2.value)
  }

  test("fromContent generates ETag from Text body") {
    val body = Body.Text("Hello, World!", None, Charset.UTF8)
    val etag = ETag.fromContent(body).assertSuccess

    assertEquals(etag.weak, false)
    assert(etag.value.nonEmpty)
  }

  test("fromContent generates ETag from Binary body") {
    val bytes = Bytes.fromString("Binary content", Charset.UTF8)
    val body = Body.Binary(bytes, None)
    val etag = ETag.fromContent(body).assertSuccess

    assertEquals(etag.weak, false)
    assert(etag.value.nonEmpty)
  }

  test("fromContent generates ETag \"0\" for Empty body") {
    val body = Body.Empty
    val etag = ETag.fromContent(body).assertSuccess

    assertEquals(etag.value, "0")
    assertEquals(etag.weak, false)
  }

  test("fromContentWeak generates weak ETag") {
    val body = Body.Text("Test content", None, Charset.UTF8)
    val etag = ETag.fromContentWeak(body).assertSuccess

    assertEquals(etag.weak, true)
  }

  test("round-trip: parse and serialize strong ETag") {
    val original = "\"abc123def456\""
    val parsed = ETag.parse(original).assertSuccess
    val serialized = parsed.headerValue

    assertEquals(serialized, original)
  }

  test("round-trip: parse and serialize weak ETag") {
    val original = "W/\"abc123def456\""
    val parsed = ETag.parse(original).assertSuccess
    val serialized = parsed.headerValue

    assertEquals(serialized, original)
  }

  test("SHA-256 hash is 64 hex characters") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val etag = ETag.fromBytes(bytes)

    // SHA-256 produces 32 bytes = 64 hex characters
    assertEquals(etag.value.length, 64)
    assert(etag.value.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))
  }

  test("ETag handles Unicode content") {
    val bytes = Bytes.fromString("Hello 世界 🌍", Charset.UTF8)
    val etag = ETag.fromBytes(bytes)

    // Should generate a valid hex hash
    assert(etag.value.length == 64)
    assert(etag.value.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))
  }

  test("parseMultiple handles empty string") {
    val result = ETag.parseMultiple("").assertSuccess
    assertEquals(result.length, 0)
  }

  test("parse handles ETag with spaces in value") {
    val result = ETag.parse("\"value with spaces\"").assertSuccess
    assertEquals(result.value, "value with spaces")
  }
}
