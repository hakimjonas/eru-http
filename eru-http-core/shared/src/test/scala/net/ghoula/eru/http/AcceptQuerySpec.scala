package net.ghoula.eru.http

import munit.FunSuite

import net.ghoula.eru.*

import TestHelpers.*

/** Accept-Query (RFC 10008 Section 3): a Structured Fields List (RFC 9651) of media ranges.
  *
  * A hand-constructed range carrying a raw CR/LF in a parameter is rejected by the header layer
  * (HeaderValue.parse), even though the serializer would emit it.
  */
class AcceptQuerySpec extends FunSuite {

  test("parse a single token media range") {
    val parsed = AcceptQuery.parse("application/sql").assertSuccess
    assertEquals(parsed.ranges, List(QueryMediaRange("application/sql")))
    assertEquals(parsed.value, "application/sql")
  }

  test("parse a string media range (RFC 10008 example)") {
    val parsed = AcceptQuery.parse("\"application/jsonpath\"").assertSuccess
    assertEquals(parsed.ranges, List(QueryMediaRange("application/jsonpath")))
  }

  test("parse mixed token and string ranges with parameters (RFC 10008 example)") {
    val parsed = AcceptQuery.parse(""""application/jsonpath", application/sql;charset="UTF-8"""").assertSuccess
    assertEquals(parsed.ranges.size, 2)
    assertEquals(parsed.ranges.head, QueryMediaRange("application/jsonpath"))
    assertEquals(parsed.ranges(1), QueryMediaRange("application/sql", Map("charset" -> "UTF-8")))
  }

  test("parse wildcards") {
    val any = AcceptQuery.parse("*/*").assertSuccess
    assertEquals(any.ranges, List(QueryMediaRange("*/*")))
    val typed = AcceptQuery.parse("application/*").assertSuccess
    assertEquals(typed.ranges, List(QueryMediaRange("application/*")))
  }

  test("parse token parameter values") {
    val parsed = AcceptQuery.parse("application/graphql;strict=true").assertSuccess
    assertEquals(parsed.ranges, List(QueryMediaRange("application/graphql", Map("strict" -> "true"))))
  }

  test("parse multiple ranges, order preserved but insignificant") {
    val parsed = AcceptQuery.parse("a/b, c/d").assertSuccess
    assertEquals(parsed.ranges.map(_.range), List("a/b", "c/d"))
    assertEquals(parsed.value, "a/b, c/d")
  }

  test("empty value is rejected") {
    AcceptQuery.parse("").assertFailure
    AcceptQuery.parse("   ").assertFailure
  }

  test("invalid media ranges are rejected") {
    AcceptQuery.parse("not a range").assertFailure
    AcceptQuery.parse("a//b").assertFailure
    AcceptQuery.parse("*/").assertFailure
  }

  test("unclosed strings and bad escapes are rejected") {
    AcceptQuery.parse("\"unclosed").assertFailure
    AcceptQuery.parse("\"trailing\" junk").assertFailure
  }

  test("serialization round-trips through parse") {
    val original = AcceptQuery.fromMediaTypes(
      List(
        MediaType.applicationJson,
        MediaType("application", "sql", Map("charset" -> "UTF-8")),
        MediaType.any
      )
    )
    val reparsed = AcceptQuery.parse(original.value).assertSuccess
    assertEquals(reparsed.ranges, original.ranges)
  }

  test("fromMediaTypes drops media type parameters into range parameters") {
    val mt = MediaType("application", "sql", Map("charset" -> "UTF-8", "mode" -> "strict"))
    val aq = AcceptQuery.fromMediaTypes(List(mt))
    assertEquals(aq.ranges, List(QueryMediaRange("application/sql", Map("charset" -> "UTF-8", "mode" -> "strict"))))
  }

  test("accepts matches wildcards and exact ranges") {
    val aq = AcceptQuery
      .parse("application/json, text/*, */*")
      .assertSuccess
    assert(aq.accepts(MediaType.applicationJson))
    assert(aq.accepts(MediaType.textPlain))
    assert(aq.accepts(MediaType.applicationXml), "any-type wildcard must match everything")
    assert(!AcceptQuery.empty.accepts(MediaType.applicationJson))
  }

  test("leading-digit media ranges serialize as strings per RFC 10008") {
    val parsed = AcceptQuery.parse("\"1a/2b\"").assertSuccess
    assertEquals(parsed.value, "\"1a/2b\"")
  }

  test("isEmpty and nonEmpty reflect the range list") {
    assert(AcceptQuery.empty.isEmpty)
    assert(AcceptQuery.parse("a/b").assertSuccess.nonEmpty)
  }

  test("control characters in strings are rejected at parse time") {
    AcceptQuery.parse("\"a\r\nb\"").assertFailure
    AcceptQuery.parse("a/b;x=\"c\u0000d\"").assertFailure
  }

  test("control characters in constructed values are rejected at the header layer") {
    val evil = AcceptQuery(List(QueryMediaRange("application/sql", Map("charset" -> "u\r\ntf-8"))))
    val result = Response[Body](StatusCode.Ok, Headers.empty, Body.Empty).withAcceptQuery(evil).attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("header layer must reject CR/LF in parameter values")
      case Result.Failure(_) => ()
    }
  }
}
