package net.ghoula.eru.http.acme

import munit.FunSuite

/** Unit tests for the minimal JSON model ACME runs on. */
class AcmeJsonSpec extends FunSuite {

  test("parse: objects, arrays, strings, booleans, null, numbers") {
    val parsed = Json.parse("""{"a":"x","b":[1,2],"c":true,"d":null,"e":-3.5e2}""")
    assert(parsed.isRight)

    val value = parsed.toOption.get
    assertEquals(value.stringField("a"), Some("x"))
    assertEquals(value.field("b").flatMap(_.asArray).map(_.length), Some(2))
    assertEquals(value.field("c"), Some(Json.Bool(true)))
    assertEquals(value.field("d"), Some(Json.Null))
    assertEquals(value.field("e"), Some(Json.Num("-3.5e2")))
  }

  test("parse: string escapes round-trip") {
    val json = Json.parse("""{"s":"line\nbreak\ttab \"quoted\" \u0041\\/"}""")
    assertEquals(json.toOption.get.stringField("s"), Some("line\nbreak\ttab \"quoted\" A\\/"))
  }

  test("parse: rejects malformed input") {
    assert(Json.parse("{").isLeft)
    assert(Json.parse("{} trailing").isLeft)
    assert(Json.parse("{\"a\" 1}").isLeft)
    assert(Json.parse("").isLeft)
  }

  test("encode: compact and escaped") {
    val value = Json.obj(
      "b" -> Json.Arr(List(Json.num(1), Json.str("x\ny"))),
      "a" -> Json.Bool(false)
    )
    assertEquals(value.encode, """{"b":[1,"x\ny"],"a":false}""")
  }

  test("round-trip: parse(encode(x)) preserves the value") {
    val original = Json.obj(
      "identifiers" -> Json.Arr(List(Json.obj("type" -> Json.str("dns"), "value" -> Json.str("example.com")))),
      "wildcard" -> Json.Bool(true)
    )
    val reparsed = Json.parse(original.encode)
    assertEquals(reparsed, Right(original))
  }
}
