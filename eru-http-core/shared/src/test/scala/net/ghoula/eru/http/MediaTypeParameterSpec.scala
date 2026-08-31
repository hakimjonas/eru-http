package net.ghoula.eru.http

import munit.FunSuite

import TestHelpers.*

class MediaTypeParameterSpec extends FunSuite {

  test("MediaType - parse parameter with quoted-string value") {
    val mt = MediaType.parse("text/plain; charset=\"utf-8\"").assertSuccess

    assertEquals(mt.charset, Some("utf-8"))
  }

  test("MediaType - parse quoted-string with spaces") {
    val mt = MediaType.parse("text/plain; name=\"hello world\"").assertSuccess

    val name = mt.parameters.get("name")
    assertEquals(name, Some("hello world"))
  }

  test("MediaType - parse quoted-string with escaped quote") {
    val mt = MediaType.parse("""text/plain; value="has \"quotes\" inside"""").assertSuccess

    val value = mt.parameters.get("value")
    assertEquals(value, Some("""has "quotes" inside"""))
  }

  test("MediaType - parse quoted-string with escaped backslash") {
    val mt = MediaType.parse("""text/plain; path="C:\\Users\\file.txt"""").assertSuccess

    val path = mt.parameters.get("path")
    assertEquals(path, Some("""C:\Users\file.txt"""))
  }

  test("MediaType - parse quoted-string with semicolon") {
    val mt = MediaType.parse("""text/plain; data="a;b;c"""").assertSuccess

    val data = mt.parameters.get("data")
    assertEquals(data, Some("a;b;c"))
  }

  test("MediaType - parse quoted-string with comma") {
    val mt = MediaType.parse("""text/plain; list="a,b,c"""").assertSuccess

    val list = mt.parameters.get("list")
    assertEquals(list, Some("a,b,c"))
  }

  test("MediaType - parse quoted-string with special characters") {
    val mt = MediaType.parse("""text/plain; special="!@#$%^&*()"""").assertSuccess

    val special = mt.parameters.get("special")
    assertEquals(special, Some("!@#$%^&*()"))
  }

  test("MediaType - parse empty quoted-string") {
    val mt = MediaType.parse("text/plain; empty=\"\"").assertSuccess

    val empty = mt.parameters.get("empty")
    assertEquals(empty, Some(""))
  }

  test("MediaType - parse unquoted token value") {
    val mt = MediaType.parse("text/plain; charset=utf-8").assertSuccess

    assertEquals(mt.charset, Some("utf-8"))
  }

  test("MediaType - fails on unclosed quoted-string") {
    val result = MediaType.parse("""text/plain; value="unclosed""")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Unclosed"))
  }

  test("MediaType - fails on incomplete escape sequence") {
    val result = MediaType.parse("""text/plain; value="ends with\"""")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Unclosed"))
  }

  test("MediaType - fails on characters after closing quote") {
    val result = MediaType.parse("""text/plain; value="test"extra""")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("after closing quote"))
  }

  test("MediaType - fails on invalid unquoted value") {
    val result = MediaType.parse("text/plain; value=has spaces")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("must be a valid token"))
  }

  test("MediaType - value encodes parameter with spaces") {
    val mt = MediaType("text", "plain").withParameter("name", "hello world")
    val value = mt.value

    assert(value.contains("""name="hello world""""))
  }

  test("MediaType - value encodes parameter with quotes") {
    val mt = MediaType("text", "plain").withParameter("value", """has "quotes"""")
    val value = mt.value

    assert(value.contains("""value="has \"quotes\"""""))
  }

  test("MediaType - value encodes parameter with backslash") {
    val mt = MediaType("text", "plain").withParameter("path", """C:\Users""")
    val value = mt.value

    assert(value.contains("""path="C:\\Users""""))
  }

  test("MediaType - value encodes parameter with semicolon") {
    val mt = MediaType("text", "plain").withParameter("data", "a;b")
    val value = mt.value

    assert(value.contains("""data="a;b""""))
  }

  test("MediaType - value does not quote valid tokens") {
    val mt = MediaType("text", "plain").withParameter("charset", "utf-8")
    val value = mt.value

    assert(value.contains("charset=utf-8"))
    assert(!value.contains("charset=\"utf-8\""))
  }

  test("MediaType - round-trip with quoted parameters") {
    val original = """application/json; charset="utf-8"; name="test file"""".trim
    val parsed = MediaType.parse(original).assertSuccess
    val reconstructed = parsed.value

    val reparsed = MediaType.parse(reconstructed).assertSuccess

    assertEquals(reparsed.charset, Some("utf-8"))
    val name = reparsed.parameters.get("name")
    assertEquals(name, Some("test file"))
  }

  test("MediaType - round-trip with escaped characters") {
    val mt1 = MediaType("text", "plain")
      .withParameter("value", """has "quotes" and \backslashes\""")

    val serialized = mt1.value
    val mt2 = MediaType.parse(serialized).assertSuccess

    val value = mt2.parameters.get("value")
    assertEquals(value, Some("""has "quotes" and \backslashes\"""))
  }

  test("MediaType - round-trip preserves parameter values") {
    val testValues = List(
      "simple",
      "with spaces",
      "with;semicolon",
      "with,comma",
      """with"quotes"""",
      """with\backslash""",
      "!@#$%^&*()",
      "multi word value here"
    )

    testValues.foreach { testValue =>
      val mt1 = MediaType("text", "plain").withParameter("test", testValue)
      val serialized = mt1.value
      val mt2 = MediaType.parse(serialized).assertSuccess
      val parsed = mt2.parameters.get("test")

      assertEquals(parsed, Some(testValue), s"Failed for value: $testValue")
    }
  }

  test("MediaType - parse multiple parameters with quoted-strings") {
    val mt = MediaType.parse("""multipart/form-data; boundary="----12345"; charset="utf-8"""").assertSuccess

    assertEquals(mt.boundary, Some("----12345"))
    assertEquals(mt.charset, Some("utf-8"))
  }

  test("MediaType - value with multiple parameters") {
    val mt = MediaType("multipart", "form-data")
      .withParameter("boundary", "----12345")
      .withParameter("charset", "utf-8")

    val value = mt.value

    assert(value.contains("boundary=----12345"))
    assert(value.contains("charset=utf-8"))
  }

  test("MediaType - parse parameter name is case-insensitive") {
    val mt = MediaType.parse("text/plain; CHARSET=utf-8").assertSuccess

    assertEquals(mt.charset, Some("utf-8"))
  }

  test("MediaType - quoted-string preserves case") {
    val mt = MediaType.parse("""text/plain; value="UPPER lower"""").assertSuccess

    val value = mt.parameters.get("value")
    assertEquals(value, Some("UPPER lower"))
  }

  test("MediaType - handles HTAB in quoted-string") {
    val mt = MediaType.parse("text/plain; value=\"has\ttab\"").assertSuccess

    val value = mt.parameters.get("value")
    assertEquals(value, Some("has\ttab"))
  }
}
