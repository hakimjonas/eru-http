package net.ghoula.eru.http

import munit.FunSuite

import net.ghoula.eru.*

import TestHelpers.*

class UriParserSpec extends FunSuite {

  test("Uri.parse - simple HTTP URI") {
    val uri = Uri.parse("http://example.com/path").assertSuccess

    assertEquals(uri.scheme, Some("http"))
    assertEquals(uri.host, Some("example.com"))
    // No port specified in URI, so parser returns None (default ports are assigned by Uri.http/https helpers)
    assertEquals(uri.port, None)
    assertEquals(uri.path, "/path")
    assertEquals(uri.query, None)
    assertEquals(uri.fragment, None)
  }

  test("Uri.parse - HTTPS with port") {
    val uri = Uri.parse("https://example.com:8443/path").assertSuccess

    assertEquals(uri.scheme, Some("https"))
    assertEquals(uri.host, Some("example.com"))
    val portValue = uri.port.map(_.value)
    assertEquals(portValue, Some(8443))
    assertEquals(uri.path, "/path")
  }

  test("Uri.parse - with query string") {
    val uri = Uri.parse("https://api.example.com/users?page=1&limit=10").assertSuccess

    assertEquals(uri.scheme, Some("https"))
    assertEquals(uri.host, Some("api.example.com"))
    assertEquals(uri.path, "/users")
    assertEquals(uri.query, Some("page=1&limit=10"))
  }

  test("Uri.parse - with fragment") {
    val uri = Uri.parse("https://example.com/docs#section-1").assertSuccess

    assertEquals(uri.scheme, Some("https"))
    assertEquals(uri.path, "/docs")
    assertEquals(uri.fragment, Some("section-1"))
  }

  test("Uri.parse - with query and fragment") {
    val uri = Uri.parse("https://example.com/search?q=test#results").assertSuccess

    assertEquals(uri.path, "/search")
    assertEquals(uri.query, Some("q=test"))
    assertEquals(uri.fragment, Some("results"))
  }

  test("Uri.parse - with userinfo") {
    val uri = Uri.parse("https://user:pass@example.com/path").assertSuccess

    assertEquals(uri.host, Some("example.com"))
    val userInfo = uri.authority.flatMap(_.userInfo)
    assertEquals(userInfo, Some("user:pass"))
  }

  test("Uri.parse - path only") {
    val uri = Uri.parse("/relative/path").assertSuccess

    assertEquals(uri.scheme, None)
    assertEquals(uri.host, None)
    assertEquals(uri.path, "/relative/path")
    assert(uri.isRelative)
  }

  test("Uri.parse - scheme with valid characters") {
    val uri1 = Uri.parse("git+ssh://example.com").assertSuccess
    assertEquals(uri1.scheme, Some("git+ssh"))

    val uri2 = Uri.parse("custom-scheme://example.com").assertSuccess
    assertEquals(uri2.scheme, Some("custom-scheme"))

    val uri3 = Uri.parse("scheme.with.dots://example.com").assertSuccess
    assertEquals(uri3.scheme, Some("scheme.with.dots"))
  }

  test("Uri.parse - empty path with authority") {
    val uri = Uri.parse("http://example.com").assertSuccess
    assertEquals(uri.path, "")
  }

  test("Uri.parse - root path") {
    val uri = Uri.parse("http://example.com/").assertSuccess
    assertEquals(uri.path, "/")
  }

  test("Uri.parse - complex path") {
    val uri = Uri.parse("http://example.com/api/v1/users/123/profile").assertSuccess
    assertEquals(uri.path, "/api/v1/users/123/profile")
  }

  test("Uri.parse - port 80 explicit") {
    val uri = Uri.parse("http://example.com:80/path").assertSuccess
    val portValue = uri.port.map(_.value)
    assertEquals(portValue, Some(80))
  }

  test("Uri.parse - port 443 explicit") {
    val uri = Uri.parse("https://example.com:443/path").assertSuccess
    val portValue = uri.port.map(_.value)
    assertEquals(portValue, Some(443))
  }

  test("Uri.parse - fails on empty URI") {
    val result = Uri.parse("")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("cannot be empty"))
  }

  test("Uri.parse - fails on invalid port (too high)") {
    val result = Uri.parse("http://example.com:99999/path")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Invalid port"))
  }

  test("Uri.parse - fails on invalid port (zero)") {
    val result = Uri.parse("http://example.com:0/path")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Invalid port"))
  }

  test("Uri.parse - fails on invalid port (negative)") {
    val result = Uri.parse("http://example.com:-1/path")
    assert(result.isFailure)
  }

  test("Uri.parse - fails on invalid port (not a number)") {
    val result = Uri.parse("http://example.com:abc/path")
    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("not a number"))
  }

  test("Uri.value - reconstructs URI correctly") {
    val original = "https://user@example.com:8080/path?query=1#frag"
    val uri = Uri.parse(original).assertSuccess
    val reconstructed = uri.value

    assert(reconstructed.contains("https://"))
    assert(reconstructed.contains("user@"))
    assert(reconstructed.contains("example.com"))
    assert(reconstructed.contains("8080"))
    assert(reconstructed.contains("/path"))
    assert(reconstructed.contains("?query=1"))
    assert(reconstructed.contains("#frag"))
  }

  test("Uri.value - omits default port for HTTP") {
    val uri = Uri.parse("http://example.com:80/path").assertSuccess
    val value = uri.value

    assert(!value.contains(":80"))
    assertEquals(value, "http://example.com/path")
  }

  test("Uri.value - omits default port for HTTPS") {
    val uri = Uri.parse("https://example.com:443/path").assertSuccess
    val value = uri.value

    assert(!value.contains(":443"))
    assertEquals(value, "https://example.com/path")
  }

  test("Uri.value - includes non-default port") {
    val uri = Uri.parse("https://example.com:8443/path").assertSuccess
    val value = uri.value

    assert(value.contains(":8443"))
  }

  test("URL encoding - unreserved characters not encoded") {
    val uri = Uri.http("example.com")
    val result = uri.withQueryParam("key", "azAZ09-._~").assertSuccess

    assert(result.query.get.contains("azAZ09-._~"))
    assert(!result.query.get.contains("%"))
  }

  test("URL encoding - spaces encoded as %20") {
    val uri = Uri.http("example.com")
    val result = uri.withQueryParam("q", "hello world").assertSuccess

    assert(result.query.get.contains("%20"))
  }

  test("URL encoding - special characters encoded") {
    val uri = Uri.http("example.com")
    val result = uri.withQueryParam("data", "a+b=c&d").assertSuccess

    assert(result.query.get.contains("%"))
  }

  test("URI round-trip - parse and reconstruct") {
    val uris = List(
      "http://example.com/path",
      "https://example.com:8080/path?q=1",
      "https://user@example.com/path#frag",
      "ftp://files.example.com:21/download"
    )

    uris.foreach { original =>
      val parsed = Uri.parse(original).assertSuccess
      val reconstructed = parsed.value

      // Scheme, host, path should be preserved
      assert(reconstructed.contains(parsed.host.get))
      assert(reconstructed.contains(parsed.path))
    }
  }
}
