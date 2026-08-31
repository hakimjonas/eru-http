package net.ghoula.eru.http

import munit.*

import TestHelpers.*

/** Tests for Cache-Control parsing and directives.
  *
  * `CacheControl.has` checks directive presence by type, not by value.
  */
class CacheControlSpec extends FunSuite {

  test("parse single no-cache directive") {
    val result = CacheControl.parse("no-cache").assertSuccess
    assertEquals(result.directives.length, 1)
    assertEquals(result.directives.head, CacheDirective.NoCache)
  }

  test("parse single no-store directive") {
    val result = CacheControl.parse("no-store").assertSuccess
    assertEquals(result.directives.length, 1)
    assertEquals(result.directives.head, CacheDirective.NoStore)
  }

  test("parse max-age directive") {
    val result = CacheControl.parse("max-age=3600").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.MaxAge(seconds) => assertEquals(seconds, 3600)
      case _ => fail("Expected MaxAge directive")
    }
  }

  test("parse s-maxage directive") {
    val result = CacheControl.parse("s-maxage=1800").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.SMaxAge(seconds) => assertEquals(seconds, 1800)
      case _ => fail("Expected SMaxAge directive")
    }
  }

  test("parse private directive without fields") {
    val result = CacheControl.parse("private").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.Private(fields) => assertEquals(fields, None)
      case _ => fail("Expected Private directive")
    }
  }

  test("parse private directive with fields") {
    val result = CacheControl.parse("private=\"Set-Cookie, Cookie\"").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.Private(Some(fields)) =>
        assertEquals(fields, List("Set-Cookie", "Cookie"))
      case _ => fail("Expected Private directive with fields")
    }
  }

  test("parse multiple directives") {
    val result = CacheControl.parse("public, max-age=3600, must-revalidate").assertSuccess
    assertEquals(result.directives.length, 3)
    assert(result.directives.contains(CacheDirective.Public))
    assert(result.directives.contains(CacheDirective.MustRevalidate))
    assert(result.directives.exists {
      case CacheDirective.MaxAge(3600) => true
      case _ => false
    })
  }

  test("parse immutable directive") {
    val result = CacheControl.parse("immutable").assertSuccess
    assertEquals(result.directives.length, 1)
    assertEquals(result.directives.head, CacheDirective.Immutable)
  }

  test("parse stale-while-revalidate directive") {
    val result = CacheControl.parse("stale-while-revalidate=86400").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.StaleWhileRevalidate(seconds) => assertEquals(seconds, 86400)
      case _ => fail("Expected StaleWhileRevalidate directive")
    }
  }

  test("parse stale-if-error directive") {
    val result = CacheControl.parse("stale-if-error=300").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.StaleIfError(seconds) => assertEquals(seconds, 300)
      case _ => fail("Expected StaleIfError directive")
    }
  }

  test("parse max-stale without value") {
    val result = CacheControl.parse("max-stale").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.MaxStale(None) =>
      case _ => fail("Expected MaxStale without value")
    }
  }

  test("parse max-stale with value") {
    val result = CacheControl.parse("max-stale=60").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.MaxStale(Some(seconds)) => assertEquals(seconds, 60)
      case _ => fail("Expected MaxStale with value")
    }
  }

  test("parse custom directive") {
    val result = CacheControl.parse("custom-directive=value").assertSuccess
    assertEquals(result.directives.length, 1)
    result.directives.head match {
      case CacheDirective.Custom(name, Some(value)) =>
        assertEquals(name, "custom-directive")
        assertEquals(value, "value")
      case _ => fail("Expected Custom directive")
    }
  }

  test("serialize no-cache") {
    val cc = CacheControl(List(CacheDirective.NoCache))
    assertEquals(cc.value, "no-cache")
  }

  test("serialize max-age") {
    val cc = CacheControl(List(CacheDirective.MaxAge(3600)))
    assertEquals(cc.value, "max-age=3600")
  }

  test("serialize private with fields") {
    val cc = CacheControl(List(CacheDirective.Private(Some(List("Set-Cookie", "Cookie")))))
    assertEquals(cc.value, "private=\"Set-Cookie, Cookie\"")
  }

  test("serialize multiple directives") {
    val cc = CacheControl(
      List(
        CacheDirective.Public,
        CacheDirective.MaxAge(3600),
        CacheDirective.MustRevalidate
      )
    )
    assertEquals(cc.value, "public, max-age=3600, must-revalidate")
  }

  test("round-trip parsing and serialization") {
    val original = "public, max-age=3600, must-revalidate"
    val parsed = CacheControl.parse(original).assertSuccess
    val serialized = parsed.value
    val reparsed = CacheControl.parse(serialized).assertSuccess

    assertEquals(parsed.directives.length, reparsed.directives.length)
    assertEquals(parsed.directives, reparsed.directives)
  }

  test("has method checks directive presence") {
    val cc = CacheControl.parse("public, max-age=3600").assertSuccess

    assert(cc.has(CacheDirective.Public))
    assert(cc.has(CacheDirective.MaxAge(0)))
    assert(!cc.has(CacheDirective.NoCache))
  }

  test("add method adds directive") {
    val cc = CacheControl.parse("public").assertSuccess
    val updated = cc.add(CacheDirective.MaxAge(3600))

    assertEquals(updated.directives.length, 2)
    assert(updated.has(CacheDirective.Public))
    assert(updated.has(CacheDirective.MaxAge(0)))
  }

  test("maxAge helper returns value") {
    val cc = CacheControl.parse("public, max-age=3600").assertSuccess
    assertEquals(cc.maxAge, Some(3600))
  }

  test("maxAge helper returns None when not present") {
    val cc = CacheControl.parse("public").assertSuccess
    assertEquals(cc.maxAge, None)
  }

  test("isNoCache checks for no-cache or no-store") {
    assert(CacheControl.parse("no-cache").assertSuccess.isNoCache)
    assert(CacheControl.parse("no-store").assertSuccess.isNoCache)
    assert(!CacheControl.parse("public").assertSuccess.isNoCache)
  }

  test("isPublic checks for public directive") {
    assert(CacheControl.parse("public").assertSuccess.isPublic)
    assert(!CacheControl.parse("private").assertSuccess.isPublic)
  }

  test("isPrivate checks for private directive") {
    assert(CacheControl.parse("private").assertSuccess.isPrivate)
    assert(!CacheControl.parse("public").assertSuccess.isPrivate)
  }

  test("presets: noStore") {
    val cc = CacheControl.noStore
    assertEquals(cc.directives.length, 1)
    assertEquals(cc.directives.head, CacheDirective.NoStore)
  }

  test("presets: noCache") {
    val cc = CacheControl.noCache
    assertEquals(cc.directives.length, 1)
    assertEquals(cc.directives.head, CacheDirective.NoCache)
  }

  test("presets: publicCache") {
    val cc = CacheControl.publicCache
    assertEquals(cc.directives.length, 1)
    assertEquals(cc.directives.head, CacheDirective.Public)
  }

  test("presets: privateCache") {
    val cc = CacheControl.privateCache
    assertEquals(cc.directives.length, 1)
    assertEquals(cc.directives.head, CacheDirective.Private(None))
  }

  test("presets: maxAge") {
    val cc = CacheControl.maxAge(3600)
    assertEquals(cc.directives.length, 1)
    cc.directives.head match {
      case CacheDirective.MaxAge(seconds) => assertEquals(seconds, 3600)
      case _ => fail("Expected MaxAge directive")
    }
  }

  test("presets: publicMaxAge") {
    val cc = CacheControl.publicMaxAge(7200)
    assertEquals(cc.directives.length, 2)
    assert(cc.has(CacheDirective.Public))
    assert(cc.maxAge.contains(7200))
  }

  test("presets: immutable") {
    val cc = CacheControl.immutable(31536000)
    assertEquals(cc.directives.length, 2)
    assert(cc.has(CacheDirective.Immutable))
    assert(cc.maxAge.contains(31536000))
  }

  test("parse fails on invalid max-age value") {
    assert(CacheControl.parse("max-age=invalid").isFailure)
  }

  test("parse fails on negative max-age") {
    assert(CacheControl.parse("max-age=-100").isFailure)
  }

  test("parse handles whitespace") {
    val result = CacheControl.parse("  public  ,  max-age=3600  ").assertSuccess
    assertEquals(result.directives.length, 2)
    assert(result.has(CacheDirective.Public))
    assert(result.maxAge.contains(3600))
  }

  test("parse empty string returns empty directives") {
    val result = CacheControl.parse("").assertSuccess
    assertEquals(result.directives.length, 0)
  }

  test("case insensitive directive parsing") {
    val result = CacheControl.parse("NO-CACHE, MAX-AGE=3600").assertSuccess
    assertEquals(result.directives.length, 2)
    assert(result.has(CacheDirective.NoCache))
    assert(result.maxAge.contains(3600))
  }
}
