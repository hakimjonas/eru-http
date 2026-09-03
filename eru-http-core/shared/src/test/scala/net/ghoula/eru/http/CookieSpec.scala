package net.ghoula.eru.http

import munit.FunSuite

import java.time.Instant
import java.time.temporal.ChronoUnit

import TestHelpers.*

class CookieSpec extends FunSuite {

  test("Cookie - parse simple Set-Cookie header") {
    val cookie = Cookie.parseSetCookie("sessionId=abc123").assertSuccess

    assertEquals(cookie.name, "sessionId")
    assertEquals(cookie.value, "abc123")
    assertEquals(cookie.domain, None)
    assertEquals(cookie.path, None)
    assertEquals(cookie.secure, false)
    assertEquals(cookie.httpOnly, false)
    assertEquals(cookie.sameSite, None)
  }

  test("Cookie - parse Set-Cookie with domain") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; Domain=example.com").assertSuccess

    assertEquals(cookie.name, "id")
    assertEquals(cookie.value, "a3fWa")
    assertEquals(cookie.domain, Some("example.com"))
  }

  test("Cookie - parse Set-Cookie with domain starting with dot") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; Domain=.example.com").assertSuccess

    assertEquals(cookie.domain, Some("example.com"))
  }

  test("Cookie - parse Set-Cookie with path") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; Path=/docs").assertSuccess

    assertEquals(cookie.path, Some("/docs"))
  }

  test("Cookie - parse Set-Cookie with Expires") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; Expires=Wed, 21 Oct 2015 07:28:00 GMT").assertSuccess

    assert(cookie.expires.isDefined)
  }

  test("Cookie - parse Set-Cookie with Max-Age") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; Max-Age=3600").assertSuccess

    assertEquals(cookie.maxAge, Some(3600L))
  }

  test("Cookie - parse Set-Cookie with Secure flag") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; Secure").assertSuccess

    assertEquals(cookie.secure, true)
  }

  test("Cookie - parse Set-Cookie with HttpOnly flag") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; HttpOnly").assertSuccess

    assertEquals(cookie.httpOnly, true)
  }

  test("Cookie - parse Set-Cookie with SameSite=Strict") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; SameSite=Strict").assertSuccess

    assertEquals(cookie.sameSite, Some(SameSite.Strict))
  }

  test("Cookie - parse Set-Cookie with SameSite=Lax") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; SameSite=Lax").assertSuccess

    assertEquals(cookie.sameSite, Some(SameSite.Lax))
  }

  test("Cookie - parse Set-Cookie with SameSite=None") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; SameSite=None").assertSuccess

    assertEquals(cookie.sameSite, Some(SameSite.None))
  }

  test("Cookie - parse Set-Cookie with all attributes") {
    val cookie = Cookie
      .parseSetCookie(
        "id=a3fWa; Domain=example.com; Path=/; Max-Age=3600; Secure; HttpOnly; SameSite=Strict"
      )
      .assertSuccess

    assertEquals(cookie.name, "id")
    assertEquals(cookie.value, "a3fWa")
    assertEquals(cookie.domain, Some("example.com"))
    assertEquals(cookie.path, Some("/"))
    assertEquals(cookie.maxAge, Some(3600L))
    assertEquals(cookie.secure, true)
    assertEquals(cookie.httpOnly, true)
    assertEquals(cookie.sameSite, Some(SameSite.Strict))
  }

  test("Cookie - parse Set-Cookie ignores unknown attributes") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; UnknownAttr=value").assertSuccess

    assertEquals(cookie.name, "id")
    assertEquals(cookie.value, "a3fWa")
  }

  test("Cookie - parse Set-Cookie with case-insensitive attributes") {
    val cookie = Cookie.parseSetCookie("id=a3fWa; SECURE; httponly; samesite=strict").assertSuccess

    assertEquals(cookie.secure, true)
    assertEquals(cookie.httpOnly, true)
    assertEquals(cookie.sameSite, Some(SameSite.Strict))
  }

  test("Cookie - fails on empty Set-Cookie header") {
    val result = Cookie.parseSetCookie("")
    assert(result.isFailure)
  }

  test("Cookie - fails on Set-Cookie without value") {
    val result = Cookie.parseSetCookie("sessionId")
    assert(result.isFailure)
  }

  test("Cookie - fails on invalid cookie name") {
    val result = Cookie.parseSetCookie("session id=abc123")
    assert(result.isFailure)
  }

  test("Cookie - parse simple Cookie header") {
    val cookies = Cookie.parseCookie("sessionId=abc123").assertSuccess

    assertEquals(cookies.length, 1)
    assertEquals(cookies(0).name, "sessionId")
    assertEquals(cookies(0).value, "abc123")
  }

  test("Cookie - parse Cookie header with multiple cookies") {
    val cookies = Cookie.parseCookie("sessionId=abc123; userId=xyz789").assertSuccess

    assertEquals(cookies.length, 2)
    assertEquals(cookies(0).name, "sessionId")
    assertEquals(cookies(0).value, "abc123")
    assertEquals(cookies(1).name, "userId")
    assertEquals(cookies(1).value, "xyz789")
  }

  test("Cookie - parse empty Cookie header") {
    val cookies = Cookie.parseCookie("").assertSuccess

    assertEquals(cookies.length, 0)
  }

  test("Cookie - fails on Cookie header without value") {
    val result = Cookie.parseCookie("sessionId")
    assert(result.isFailure)
  }

  test("Cookie - toSetCookieHeader simple") {
    val cookie = Cookie("sessionId", "abc123")
    val header = cookie.toSetCookieHeader

    assertEquals(header, "sessionId=abc123")
  }

  test("Cookie - toSetCookieHeader with domain") {
    val cookie = Cookie("sessionId", "abc123", domain = Some("example.com"))
    val header = cookie.toSetCookieHeader

    assert(header.contains("Domain=example.com"))
  }

  test("Cookie - toSetCookieHeader with path") {
    val cookie = Cookie("sessionId", "abc123", path = Some("/docs"))
    val header = cookie.toSetCookieHeader

    assert(header.contains("Path=/docs"))
  }

  test("Cookie - toSetCookieHeader with Max-Age") {
    val cookie = Cookie("sessionId", "abc123", maxAge = Some(3600))
    val header = cookie.toSetCookieHeader

    assert(header.contains("Max-Age=3600"))
  }

  test("Cookie - toSetCookieHeader with Secure") {
    val cookie = Cookie("sessionId", "abc123", secure = true)
    val header = cookie.toSetCookieHeader

    assert(header.contains("Secure"))
  }

  test("Cookie - toSetCookieHeader with HttpOnly") {
    val cookie = Cookie("sessionId", "abc123", httpOnly = true)
    val header = cookie.toSetCookieHeader

    assert(header.contains("HttpOnly"))
  }

  test("Cookie - toSetCookieHeader with SameSite") {
    val cookie = Cookie("sessionId", "abc123", sameSite = Some(SameSite.Strict))
    val header = cookie.toSetCookieHeader

    assert(header.contains("SameSite=Strict"))
  }

  test("Cookie - toSetCookieHeader with all attributes") {
    val cookie = Cookie(
      name = "sessionId",
      value = "abc123",
      domain = Some("example.com"),
      path = Some("/"),
      maxAge = Some(3600),
      secure = true,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )
    val header = cookie.toSetCookieHeader

    assert(header.startsWith("sessionId=abc123"))
    assert(header.contains("Domain=example.com"))
    assert(header.contains("Path=/"))
    assert(header.contains("Max-Age=3600"))
    assert(header.contains("Secure"))
    assert(header.contains("HttpOnly"))
    assert(header.contains("SameSite=Lax"))
  }

  test("Cookie - toCookieHeader") {
    val cookie = Cookie(
      name = "sessionId",
      value = "abc123",
      domain = Some("example.com"),
      path = Some("/")
    )
    val header = cookie.toCookieHeader

    assertEquals(header, "sessionId=abc123")
  }

  test("Cookie - round-trip Set-Cookie parsing and serialization") {
    val original = "sessionId=abc123; Domain=example.com; Path=/; Secure; HttpOnly"
    val cookie = Cookie.parseSetCookie(original).assertSuccess
    val serialized = cookie.toSetCookieHeader

    val reparsed = Cookie.parseSetCookie(serialized).assertSuccess

    assertEquals(reparsed.name, cookie.name)
    assertEquals(reparsed.value, cookie.value)
    assertEquals(reparsed.domain, cookie.domain)
    assertEquals(reparsed.path, cookie.path)
    assertEquals(reparsed.secure, cookie.secure)
    assertEquals(reparsed.httpOnly, cookie.httpOnly)
  }

  test("Cookie - domainMatches exact match") {
    val cookie = Cookie("id", "value", domain = Some("example.com"))

    assert(cookie.domainMatches("example.com"))
  }

  test("Cookie - domainMatches subdomain") {
    val cookie = Cookie("id", "value", domain = Some("example.com"))

    assert(cookie.domainMatches("sub.example.com"))
    assert(cookie.domainMatches("deep.sub.example.com"))
  }

  test("Cookie - domainMatches does not match parent domain") {
    val cookie = Cookie("id", "value", domain = Some("sub.example.com"))

    assert(!cookie.domainMatches("example.com"))
  }

  test("Cookie - domainMatches no domain restriction") {
    val cookie = Cookie("id", "value", domain = None)

    assert(cookie.domainMatches("example.com"))
    assert(cookie.domainMatches("other.com"))
  }

  test("Cookie - domainMatches is case-insensitive") {
    val cookie = Cookie("id", "value", domain = Some("Example.COM"))

    assert(cookie.domainMatches("example.com"))
    assert(cookie.domainMatches("EXAMPLE.COM"))
    assert(cookie.domainMatches("sub.EXAMPLE.com"))
  }

  test("Cookie - pathMatches exact match") {
    val cookie = Cookie("id", "value", path = Some("/docs"))

    assert(cookie.pathMatches("/docs"))
  }

  test("Cookie - pathMatches subpath") {
    val cookie = Cookie("id", "value", path = Some("/docs"))

    assert(cookie.pathMatches("/docs/"))
    assert(cookie.pathMatches("/docs/api"))
    assert(cookie.pathMatches("/docs/api/v1"))
  }

  test("Cookie - pathMatches does not match different path") {
    val cookie = Cookie("id", "value", path = Some("/docs"))

    assert(!cookie.pathMatches("/other"))
    assert(!cookie.pathMatches("/documentation"))
  }

  test("Cookie - pathMatches no path restriction") {
    val cookie = Cookie("id", "value", path = None)

    assert(cookie.pathMatches("/"))
    assert(cookie.pathMatches("/docs"))
    assert(cookie.pathMatches("/api/v1"))
  }

  test("Cookie - pathMatches root path") {
    val cookie = Cookie("id", "value", path = Some("/"))

    assert(cookie.pathMatches("/"))
    assert(cookie.pathMatches("/docs"))
    assert(cookie.pathMatches("/api"))
  }

  test("Cookie - isExpired with Max-Age zero") {
    val cookie = Cookie("id", "value", maxAge = Some(0))

    assert(cookie.isExpired())
  }

  test("Cookie - isExpired with negative Max-Age") {
    val cookie = Cookie("id", "value", maxAge = Some(-1))

    assert(cookie.isExpired())
  }

  test("Cookie - isExpired with positive Max-Age") {
    val cookie = Cookie("id", "value", maxAge = Some(3600))

    assert(!cookie.isExpired())
  }

  test("Cookie - isExpired with Expires in past") {
    val past = Instant.now().minus(1, ChronoUnit.DAYS)
    val cookie = Cookie("id", "value", expires = Some(past))

    assert(cookie.isExpired())
  }

  test("Cookie - isExpired with Expires in future") {
    val future = Instant.now().plus(1, ChronoUnit.DAYS)
    val cookie = Cookie("id", "value", expires = Some(future))

    assert(!cookie.isExpired())
  }

  test("Cookie - isExpired with no expiration") {
    val cookie = Cookie("id", "value")

    assert(!cookie.isExpired())
  }

  test("SameSite - parse Strict") {
    val sameSite = SameSite.parse("Strict").assertSuccess
    assertEquals(sameSite, SameSite.Strict)
  }

  test("SameSite - parse Lax") {
    val sameSite = SameSite.parse("Lax").assertSuccess
    assertEquals(sameSite, SameSite.Lax)
  }

  test("SameSite - parse None") {
    val sameSite = SameSite.parse("None").assertSuccess
    assertEquals(sameSite, SameSite.None)
  }

  test("SameSite - parse is case-insensitive") {
    assertEquals(SameSite.parse("strict").assertSuccess, SameSite.Strict)
    assertEquals(SameSite.parse("STRICT").assertSuccess, SameSite.Strict)
    assertEquals(SameSite.parse("lax").assertSuccess, SameSite.Lax)
    assertEquals(SameSite.parse("none").assertSuccess, SameSite.None)
  }

  test("SameSite - fails on invalid value") {
    val result = SameSite.parse("Invalid")
    assert(result.isFailure)
  }

  test("Response.addCookie appends and composes") {
    val session = Cookie("sid", "abc123", httpOnly = true, sameSite = Some(SameSite.Lax))
    val csrf = Cookie("__csrf", "token", secure = true)

    val response = (for {
      r1 <- Response.ok(Body.Empty).addCookie(session)
      r2 <- r1.addCookie(csrf)
    } yield r2).assertSuccess

    // Set-Cookie is exempt from list combination (RFC 9110 Section 5.5): both cookies must
    // coexist as separate values, in insertion order.
    assertEquals(
      response.headers.get(HeaderNames.SetCookie).map(_.map(_.value)),
      Some(List(session.toSetCookieHeader, csrf.toSetCookieHeader))
    )
    response.validate.assertSuccess
  }
}
