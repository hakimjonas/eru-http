package net.ghoula.eru.http

import munit.FunSuite

import net.ghoula.eru.*

import TestHelpers.*

class CoreTypesSpec extends FunSuite {

  test("Method properties are correct") {
    assert(Method.GET.isSafe)
    assert(Method.GET.isIdempotent)
    assert(Method.GET.isCacheable)
    assert(!Method.GET.allowsRequestBody)

    assert(!Method.POST.isSafe)
    assert(!Method.POST.isIdempotent)
    // RFC 9111 Section 3: POST responses are cacheable only with explicit freshness information,
    // so a method-only predicate cannot claim them as cacheable BY DEFAULT.
    assert(!Method.POST.isCacheable)
    assert(Method.POST.allowsRequestBody)

    assert(!Method.PUT.isSafe)
    assert(Method.PUT.isIdempotent)
    assert(!Method.PUT.isCacheable)
    assert(Method.PUT.allowsRequestBody)
  }

  test("QUERY method semantics per RFC 10008") {
    assert(Method.QUERY.isSafe, "QUERY is safe (RFC 10008 Section 2)")
    assert(Method.QUERY.isIdempotent, "QUERY is idempotent (RFC 10008 Section 2, IANA Table 2)")
    assert(Method.QUERY.isCacheable, "QUERY responses are cacheable (RFC 10008 Section 2.7)")
    assert(Method.QUERY.allowsRequestBody, "QUERY carries the query as request content")
    assertEquals(Method.QUERY.value, "QUERY")
  }

  test("Request.query builds a QUERY request whose body validates") {
    val uri = Uri.parse("http://example.com/search").assertSuccess
    val body = Body.text("query body", MediaType.textPlain)
    val request = Request
      .query(uri, body)
      .addHeader(HeaderNames.Host, "example.com")
      .assertSuccess
      .addHeader(HeaderNames.ContentType, "text/plain")
      .assertSuccess
    assertEquals(request.method, Method.QUERY)
    assertEquals(request.uri, uri)
    assertEquals(request.body, body)
    request.validate.assertSuccess
    Request
      .query(uri, Body.Empty)
      .addHeader(HeaderNames.Host, "example.com")
      .assertSuccess
      .addHeader(HeaderNames.ContentType, "text/plain")
      .assertSuccess
      .validate
      .assertSuccess
  }

  test("QUERY without a Content-Type header is invalid (RFC 10008 Section 2)") {
    val uri = Uri.parse("http://example.com/search").assertSuccess
    val request = Request
      .query(uri, Body.text("q"))
      .addHeader(HeaderNames.Host, "example.com")
      .assertSuccess
    val error = request.validate.assertFailure
    assert(error.reason.contains("Content-Type"), s"expected Content-Type requirement, got: ${error.reason}")
    assertEquals(error.rfc, "RFC 10008 Section 2")
  }

  test("StatusCode categorization works") {
    assert(StatusCode.Continue.isInformational)
    assert(!StatusCode.Continue.isSuccessful)

    assert(StatusCode.Ok.isSuccessful)
    assert(!StatusCode.Ok.isError)

    assert(StatusCode.MovedPermanently.isRedirection)
    assert(!StatusCode.MovedPermanently.isError)

    assert(StatusCode.NotFound.isClientError)
    assert(StatusCode.NotFound.isError)

    assert(StatusCode.InternalServerError.isServerError)
    assert(StatusCode.InternalServerError.isError)
    assert(StatusCode.InternalServerError.isRetryable)
  }

  test("StatusCode required headers") {
    val created = StatusCode.Created.requiredHeaders
    assert(created.contains("Location"))

    val methodNotAllowed = StatusCode.MethodNotAllowed.requiredHeaders
    assert(methodNotAllowed.contains("Allow"))

    val unauthorized = StatusCode.Unauthorized.requiredHeaders
    assert(unauthorized.contains("WWW-Authenticate"))
  }

  test("Headers are case-insensitive") {
    val headers = (for {
      h1 <- Headers.empty.add("Content-Type", "text/plain")
      h2 <- h1.add("content-type", "text/html")
    } yield h2).assertSuccess

    val values = headers.get("CONTENT-TYPE")
    assert(values.isDefined)
    assertEquals(values.get.size, 2)
    assertEquals(values.get.map(_.value), List("text/plain", "text/html"))
  }

  test("Uri construction and manipulation") {
    val uri = (for {
      u1 <- Uri.https("api.example.com", None, "/users").withQueryParam("page", "1")
      u2 <- u1.withQueryParam("limit", "10")
    } yield u2).assertSuccess

    assertEquals(uri.scheme, Some("https"))
    assertEquals(uri.host, Some("api.example.com"))
    assertEquals(uri.port, Some(Port.HTTPS))
    assertEquals(uri.path, "/users")
    assert(uri.query.isDefined)
    assert(uri.value.contains("page=1"))
    assert(uri.value.contains("limit=10"))
  }

  test("Uri path building") {
    val uri = (for {
      u1 <- Uri.https("api.example.com") / "users"
      u2 <- u1 / "123"
      u3 <- u2 / "profile"
    } yield u3).assertSuccess

    assertEquals(uri.path, "/users/123/profile")
  }

  test("MediaType parsing and matching") {
    val parsed = MediaType.parse("application/json; charset=utf-8")
    assert(parsed.isSuccess)

    val mediaType = parsed.assertSuccess
    assertEquals(mediaType.mainType, "application")
    assertEquals(mediaType.subType, "json")
    assertEquals(mediaType.charset, Some("utf-8"))
    assert(mediaType.isJson)
  }

  test("MediaType wildcard matching") {
    val json = MediaType.applicationJson
    assert(json.matches(MediaType.any))
    assert(json.matches(MediaType("application", "*")))
    assert(json.matches(MediaType.applicationJson))
    assert(!json.matches(MediaType.textHtml))
  }

  test("Request validation - GET with body should fail") {
    val request = Request
      .get(Uri.https("example.com"))
      .withBody("should not have body")

    val error = request.validate.assertFailure
    assert(error.reason.contains("does not allow a request body"))
  }

  test("Request validation - missing Host header in HTTP/1.1") {
    val request = Request(
      Method.GET,
      Uri.https("example.com"),
      Headers.empty,
      Body.Empty,
      HttpVersion.HTTP_1_1
    )

    val error = request.validate.assertFailure
    val hasHost = error.reason.contains("Host header is required")
    assert(hasHost)
  }

  test("Request builder methods") {
    val request = (for {
      r1 <- Request.post(Uri.https("api.example.com"), Body.Text("body")).withContentType(MediaType.applicationJson)
      r2 <- r1.withBearerToken("secret-token")
      r3 <- r2.addHeader("X-Custom", "value")
    } yield r3).assertSuccess

    val contentType = request.headers.getFirst(HeaderNames.ContentType).map(_.value)
    assertEquals(contentType, Some("application/json"))
    val authHeader = request.headers.getFirst(HeaderNames.Authorization).map(_.value)
    assertEquals(authHeader, Some("Bearer secret-token"))
    val customHeader = request.headers.getFirst("X-Custom").map(_.value)
    assertEquals(customHeader, Some("value"))
  }

  test("Response validation - 201 Created requires Location") {
    val response = Response(StatusCode.Created, Headers.empty, Body.Text("created"))

    val error = response.validate.assertFailure
    val hasLocation = error.reason.contains("requires headers: Location")
    assert(hasLocation)
  }

  test("Response builder methods") {
    val response = (for {
      r1 <- Response.ok(Body.Text("body")).withContentType(MediaType.applicationJson)
      r2 <- r1.withCacheControlString("public, max-age=3600")
      r3 <- r2.withETag(ETag.parse("\"123456\"").assertSuccess)
    } yield r3).assertSuccess

    assertEquals(response.status, StatusCode.Ok)
    val contentTypeRaw = response.headers.contentTypeRaw
    assertEquals(contentTypeRaw, Some("application/json"))
    val cacheControl = response.headers.getFirst(HeaderNames.CacheControl).map(_.value)
    assertEquals(cacheControl, Some("public, max-age=3600"))
    val etag = response.headers.getFirst(HeaderNames.ETag).map(_.value)
    assertEquals(etag, Some("\"123456\""))
  }

  test("Response convenience constructors") {
    val created = Response.created(Uri.https("example.com", None, "/resource/123"), Body.Text("created")).assertSuccess
    assertEquals(created.status, StatusCode.Created)
    val location = created.headers.getFirst(HeaderNames.Location).map(_.value)
    assertEquals(location, Some("https://example.com/resource/123"))

    val redirect = Response.movedPermanently(Uri.https("new.example.com")).assertSuccess
    assertEquals(redirect.status, StatusCode.MovedPermanently)
    assert(redirect.headers.contains(HeaderNames.Location))

    val notAllowed = Response.methodNotAllowed(Set(Method.GET, Method.POST)).assertSuccess
    assertEquals(notAllowed.status, StatusCode.MethodNotAllowed)
    assertEquals(notAllowed.headers.getFirst(HeaderNames.Allow).map(_.value), Some("GET, POST"))
  }

  test("HttpVersion enum") {
    assertEquals(HttpVersion.HTTP_1_1.value, "HTTP/1.1")
    assertEquals(HttpVersion.HTTP_2_0.value, "HTTP/2.0")
    assertEquals(HttpVersion.HTTP_3_0.value, "HTTP/3.0")
  }

  test("HttpError enum comprehensive coverage") {
    val methodError = HttpError.InvalidMethod(Method.InvalidMethod("INVALID", "reason"))
    assert(methodError.message.contains("Invalid HTTP method"))

    val networkError = HttpError.NetworkError("connection failed", Some(new Exception("cause")))
    assert(networkError.message == "connection failed")
    assert(Option(networkError.toException.getCause).isDefined)

    val timeoutError = HttpError.TimeoutError("request timed out")
    assert(timeoutError.toException.getMessage.contains("Timeout"))
  }

  test("Uri validation - path segment with slash fails") {
    val uri = Uri.https("example.com")
    val result = uri / "users/123"

    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("cannot contain '/'"))
  }

  test("Uri validation - empty path fails") {
    val uri = Uri.https("example.com")
    val result = uri.withPath("")

    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Path cannot be empty"))
  }

  test("Uri validation - empty query parameter key fails") {
    val uri = Uri.https("example.com")
    val result = uri.withQueryParam("", "value")

    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("key cannot be empty"))
  }

  test("Uri validation - empty segment fails") {
    val uri = Uri.https("example.com")
    val result = uri / ""

    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("segment cannot be empty"))
  }

  test("Uri validation - invalid scheme fails") {
    val uri = Uri.https("example.com")
    val result = uri.withScheme("ht@tp")

    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("invalid characters"))
  }

  test("Uri validation - empty host fails") {
    val uri = Uri.https("example.com")
    val result = uri.withHost("")

    assert(result.isFailure)
    val error = result.assertFailure
    assert(error.reason.contains("Host cannot be empty"))
  }

  test("Headers parsed accessors work correctly") {
    val contentTypeResult = (for {
      headers <- Headers.empty.add("Content-Type", "application/json; charset=utf-8")
      mediaType <- headers.contentType
    } yield (headers, mediaType)).assertSuccess

    val (headers, mediaType) = contentTypeResult
    assert(mediaType.isDefined)
    assertEquals(mediaType.get.mainType, "application")
    assertEquals(mediaType.get.subType, "json")
    assertEquals(mediaType.get.charset, Some("utf-8"))

    val acceptResult = (for {
      h <- headers.add("Accept", "application/json, text/html")
      mediaTypes <- h.accept
    } yield mediaTypes).assertSuccess

    assertEquals(acceptResult.size, 2)
    assert(acceptResult.exists(_.isJson))
    assert(acceptResult.exists(mt => mt.mainType == "text" && mt.subType == "html"))
  }

  test("Port validation and properties") {
    val port = Port(8080).assertSuccess
    assertEquals(port.value, 8080)
    assert(!port.isWellKnown)
    assert(port.isRegistered)
    assert(!port.requiresPrivileges)
    assertEquals(port.category, "registered")

    assert(Port(0).isFailure)
    assert(Port(-1).isFailure)
    assert(Port(65536).isFailure)
    assert(Port(100000).isFailure)

    assert(Port.HTTP.isWellKnown)
    assert(Port.HTTP.requiresPrivileges)
    assertEquals(Port.HTTP.serviceName, Some("HTTP"))

    val dynamicPort = Port(50000).assertSuccess
    assert(dynamicPort.isDynamic)
    assert(!dynamicPort.isWellKnown)
    assert(!dynamicPort.isRegistered)

    assertEquals(Port.parse("8080"), Port(8080))
    assert(Port.parse("not-a-number").isFailure)
    assert(Port.parse("99999").isFailure)
  }

  test("HeaderValue.parse accepts obs-text per RFC 9110 field-vchar") {
    // field-vchar = VCHAR / obs-text, so bytes 0x80-0xFF are legal in field values.
    // CR/LF and other control characters stay rejected.
    assert(HeaderValue.parse("café").isSuccess)
    assert(HeaderValue.parse("naïve-ü").isSuccess)
    assert(HeaderValue.parse("bad\rvalue").isFailure)
    assert(HeaderValue.parse("bad\nvalue").isFailure)
    assert(HeaderValue.parse("bad\u0000value").isFailure)
  }

  test("Headers.add accepts obs-text values and preserves them") {
    val headers = Headers.empty.add("X-Obs", "café").assertSuccess
    assertEquals(headers.getFirst("X-Obs").map(_.value), Some("café"))
  }
}
