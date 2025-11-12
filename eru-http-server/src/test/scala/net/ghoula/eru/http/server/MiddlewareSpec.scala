package net.ghoula.eru.http.server

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

import TestHelpers.*

class MiddlewareSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  // Helper to parse URI
  private def uri(s: String): Uri =
    Uri.parse(s).assertSuccess

  // ===== Middleware Composition Tests =====

  test("Middleware - identity middleware does nothing") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Hello")))

    val identityMiddleware: Middleware = identity
    val app = identityMiddleware.apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.Ok)
    response.body match {
      case Body.Text(value, _, _) => assertEquals(value, "Hello")
      case other => fail(s"Expected Body.Text but got: $other")
    }
  }

  test("Middleware - andThen composes middleware correctly") {
    var executionOrder = List.empty[String]

    val middleware1: Middleware = handler => req =>
      for {
        _ <- Eru.effect { executionOrder = executionOrder :+ "m1-before" }
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        resp <- handler(req)
        _ <- Eru.effect { executionOrder = executionOrder :+ "m1-after" }
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      } yield resp

    val middleware2: Middleware = handler => req =>
      for {
        _ <- Eru.effect { executionOrder = executionOrder :+ "m2-before" }
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        resp <- handler(req)
        _ <- Eru.effect { executionOrder = executionOrder :+ "m2-after" }
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      } yield resp

    val handler: RequestHandler = _ =>
      for {
        _ <- Eru.effect { executionOrder = executionOrder :+ "handler" }
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      } yield Response(StatusCode.Ok, Headers.empty, Body.empty)

    val app = middleware1.andThen(middleware2).apply(handler)

    val request = Request.get(uri("http://localhost/"))
    app(request).assertSuccess

    // The execution order should be: m2-before, m1-before, handler, m1-after, m2-after
    // Because middleware1.andThen(middleware2) means middleware1 wraps middleware2
    // So middleware2 executes in its outer layer first
    assertEquals(
      executionOrder,
      List("m2-before", "m1-before", "handler", "m1-after", "m2-after")
    )
  }

  test("Middleware - combine creates middleware stack") {
    var count = 0

    val incrementer: Middleware = handler => req =>
      for {
        _ <- Eru.effect { count += 1 }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        resp <- handler(req)
      } yield resp

    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = Middleware.combine(incrementer, incrementer, incrementer).apply(handler)

    val request = Request.get(uri("http://localhost/"))
    app(request).assertSuccess

    assertEquals(count, 3)
  }

  // ===== Logging Middleware Tests =====

  test("Middleware - logging logs request and response") {
    var logs = List.empty[String]

    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Hello")))

    val app = Middleware.logging(msg => logs = logs :+ msg).apply(handler)

    val request = Request.get(uri("http://localhost/api/users"))
    app(request).assertSuccess

    assert(logs.exists(_.contains("→ GET /api/users")))
    assert(logs.exists(_.contains("← 200")))
  }

  test("Middleware - loggingSimple logs without error handling") {
    var logs = List.empty[String]

    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.empty))

    val app = Middleware.loggingSimple(msg => logs = logs :+ msg).apply(handler)

    val request = Request.get(uri("http://localhost/not-found"))
    app(request).assertSuccess

    assert(logs.exists(_.contains("GET /not-found")))
    assert(logs.exists(_.contains("404")))
  }

  // ===== CORS Middleware Tests =====

  test("Middleware - cors adds CORS headers to response") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = Middleware.cors().apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    val originHeader = response.headers.getFirst("Access-Control-Allow-Origin")
    assert(originHeader.isDefined)
    assertEquals(originHeader.get.value, "*")
  }

  test("Middleware - cors handles preflight OPTIONS request") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val corsConfig = CORSConfig(
      allowedOrigins = List("https://example.com"),
      allowedMethods = List(Method.GET, Method.POST),
      allowedHeaders = List("Content-Type")
    )

    val app = Middleware.cors(corsConfig).apply(handler)

    val request = Request(
      method = Method.OPTIONS,
      uri = uri("http://localhost/"),
      headers = Headers.empty,
      body = Body.empty
    )

    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.NoContent)
    assert(response.headers.getFirst("Access-Control-Allow-Origin").isDefined)
    assert(response.headers.getFirst("Access-Control-Allow-Methods").isDefined)
    assert(response.headers.getFirst("Access-Control-Allow-Headers").isDefined)
  }

  test("Middleware - corsPermissive allows all origins") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = Middleware.corsPermissive.apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    val originHeader = response.headers.getFirst("Access-Control-Allow-Origin")
    assert(originHeader.isDefined)
    assertEquals(originHeader.get.value, "*")
  }

  test("Middleware - CORS config with credentials") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val corsConfig = CORSConfig(
      allowedOrigins = List("https://example.com"),
      allowCredentials = true
    )

    val app = Middleware.cors(corsConfig).apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    val credentialsHeader = response.headers.getFirst("Access-Control-Allow-Credentials")
    assert(credentialsHeader.isDefined)
    assertEquals(credentialsHeader.get.value, "true")
  }

  test("Middleware - CORS config with max age") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val corsConfig = CORSConfig(maxAge = Some(3600))

    val app = Middleware.cors(corsConfig).apply(handler)

    val request = Request(
      method = Method.OPTIONS,
      uri = uri("http://localhost/"),
      headers = Headers.empty,
      body = Body.empty
    )

    val response = app(request).assertSuccess

    val maxAgeHeader = response.headers.getFirst("Access-Control-Max-Age")
    assert(maxAgeHeader.isDefined)
    assertEquals(maxAgeHeader.get.value, "3600")
  }

  // ===== Authentication Middleware Tests =====

  test("Middleware - auth allows authenticated requests") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Secret data")))

    val checkAuth: Request[Body] => Boolean = req =>
      req.headers.getFirst("Authorization").exists(_.value == "secret")

    val app = Middleware.auth(checkAuth, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    val request = Request
      .get(uri("http://localhost/"))
      .setHeader("Authorization", "secret")
      .assertSuccess

    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.Ok)
    response.body match {
      case Body.Text(value, _, _) => assertEquals(value, "Secret data")
      case other => fail(s"Expected Body.Text but got: $other")
    }
  }

  test("Middleware - auth blocks unauthenticated requests") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Secret data")))

    val checkAuth: Request[Body] => Boolean = req =>
      req.headers.getFirst("Authorization").exists(_.value == "secret")

    val app = Middleware.auth(checkAuth, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.Unauthorized)
  }

  test("Middleware - bearerAuth validates Bearer token") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Protected")))

    val validateToken: String => Boolean = token => token == "valid-token"

    val app = Middleware.bearerAuth(validateToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    val request = Request
      .get(uri("http://localhost/"))
      .setHeader("Authorization", "Bearer valid-token")
      .assertSuccess

    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.Ok)
  }

  test("Middleware - bearerAuth blocks invalid Bearer token") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Protected")))

    val validateToken: String => Boolean = token => token == "valid-token"

    val app = Middleware.bearerAuth(validateToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    val request = Request
      .get(uri("http://localhost/"))
      .setHeader("Authorization", "Bearer invalid-token")
      .assertSuccess

    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.Unauthorized)
  }

  test("Middleware - bearerAuth blocks requests without Bearer prefix") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Protected")))

    val validateToken: String => Boolean = _ => true

    val app = Middleware.bearerAuth(validateToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    val request = Request
      .get(uri("http://localhost/"))
      .setHeader("Authorization", "valid-token")
      .assertSuccess

    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.Unauthorized)
  }

  // ===== Request ID Middleware Tests =====

  test("Middleware - requestId adds unique ID to response") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = Middleware.requestId().apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    val requestIdHeader = response.headers.getFirst("X-Request-ID")
    assert(requestIdHeader.isDefined)
    assert(requestIdHeader.get.value.nonEmpty)
  }

  test("Middleware - requestId uses custom header name") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = Middleware.requestId("X-Trace-ID").apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    val traceIdHeader = response.headers.getFirst("X-Trace-ID")
    assert(traceIdHeader.isDefined)
    assert(traceIdHeader.get.value.nonEmpty)
  }

  test("Middleware - requestId generates different IDs for each request") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = Middleware.requestId().apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response1 = app(request).assertSuccess
    val response2 = app(request).assertSuccess

    val id1 = response1.headers.getFirst("X-Request-ID").get.value
    val id2 = response2.headers.getFirst("X-Request-ID").get.value

    assert(id1 != id2)
  }

  // ===== Error Handler Middleware Tests =====

  test("Middleware - errorHandler catches and transforms errors") {
    val handler: RequestHandler = _ =>
      Eru.fail(HttpError.InvalidRequest(InvalidRequest("Bad input", "RFC")))

    val handleError: HttpError => Response[Body] = {
      case HttpError.InvalidRequest(err) =>
        Response(StatusCode.BadRequest, Headers.empty, Body.text(s"Error: ${err.reason}"))
      case _ =>
        Response(StatusCode.InternalServerError, Headers.empty, Body.empty)
    }

    val app = Middleware.errorHandler(handleError).apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.BadRequest)
    response.body match {
      case Body.Text(value, _, _) => assertEquals(value, "Error: Bad input")
      case other => fail(s"Expected Body.Text but got: $other")
    }
  }

  test("Middleware - errorHandlerDefault converts InvalidRequest to 400") {
    val handler: RequestHandler = _ =>
      Eru.fail(HttpError.InvalidRequest(InvalidRequest("Invalid input", "RFC")))

    val app = Middleware.errorHandlerDefault.apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.BadRequest)
  }

  test("Middleware - errorHandlerDefault converts NetworkError to 500") {
    val handler: RequestHandler = _ =>
      Eru.fail(HttpError.NetworkError("Connection failed", None))

    val app = Middleware.errorHandlerDefault.apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.InternalServerError)
  }

  // ===== Conditional Middleware Tests =====

  test("Middleware - when applies middleware conditionally") {
    var applied = false

    val conditionalMiddleware = Middleware.when(_.uri.path.startsWith("/api")) { handler => req =>
      for {
        _ <- Eru.effect { applied = true }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        resp <- handler(req)
      } yield resp
    }

    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = conditionalMiddleware.apply(handler)

    // Should apply to /api paths
    applied = false
    val apiRequest = Request.get(uri("http://localhost/api/users"))
    app(apiRequest).assertSuccess
    assert(applied)

    // Should not apply to other paths
    applied = false
    val otherRequest = Request.get(uri("http://localhost/public"))
    app(otherRequest).assertSuccess
    assert(!applied)
  }

  test("Middleware - forPath applies middleware to specific path prefix") {
    var applied = false

    val pathMiddleware = Middleware.forPath("/admin") { handler => req =>
      for {
        _ <- Eru.effect { applied = true }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        resp <- handler(req)
      } yield resp
    }

    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = pathMiddleware.apply(handler)

    // Should apply to /admin paths
    applied = false
    val adminRequest = Request.get(uri("http://localhost/admin/settings"))
    app(adminRequest).assertSuccess
    assert(applied)

    // Should not apply to other paths
    applied = false
    val publicRequest = Request.get(uri("http://localhost/public"))
    app(publicRequest).assertSuccess
    assert(!applied)
  }

  test("Middleware - forMethod applies middleware to specific HTTP method") {
    var applied = false

    val methodMiddleware = Middleware.forMethod(Method.POST) { handler => req =>
      for {
        _ <- Eru.effect { applied = true }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        resp <- handler(req)
      } yield resp
    }

    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.empty))

    val app = methodMiddleware.apply(handler)

    // Should apply to POST requests
    applied = false
    val postRequest = Request.post(uri("http://localhost/"), Body.empty)
    app(postRequest).assertSuccess
    assert(applied)

    // Should not apply to GET requests
    applied = false
    val getRequest = Request.get(uri("http://localhost/"))
    app(getRequest).assertSuccess
    assert(!applied)
  }

  // ===== Integration Tests =====

  test("Middleware - multiple middleware chain together correctly") {
    var logs = List.empty[String]

    val handler: RequestHandler = req =>
      for {
        body <- BodyDecoder[String].decode(req.body).mapError(e => HttpError.BodyDecodeError(e))
      } yield Response(StatusCode.Ok, Headers.empty, Body.text(s"Echo: $body"))

    val app = Middleware
      .logging(msg => logs = logs :+ msg)
      .andThen(Middleware.cors())
      .andThen(Middleware.requestId())
      .apply(handler)

    val request = Request.post(uri("http://localhost/echo"), Body.text("Hello"))
    val response = app(request).assertSuccess

    // Check logging
    assert(logs.exists(_.contains("→ POST /echo")))
    assert(logs.exists(_.contains("← 200")))

    // Check CORS headers
    assert(response.headers.getFirst("Access-Control-Allow-Origin").isDefined)

    // Check request ID
    assert(response.headers.getFirst("X-Request-ID").isDefined)

    // Check response body
    response.body match {
      case Body.Text(value, _, _) => assertEquals(value, "Echo: Hello")
      case other => fail(s"Expected Body.Text but got: $other")
    }
  }

  test("Middleware - auth and CORS work together") {
    val handler: RequestHandler = _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Protected resource")))

    val checkAuth: Request[Body] => Boolean = req =>
      req.headers.getFirst("Authorization").exists(_.value.startsWith("Bearer "))

    // Apply auth first, then CORS - this way CORS headers are added to all responses
    val app = Middleware
      .auth(checkAuth, UnauthorizedHandler(() => Middleware.defaultUnauthorized()))
      .andThen(Middleware.cors())
      .apply(handler)

    // Authenticated request should succeed with CORS headers
    val authRequest = Request
      .get(uri("http://localhost/"))
      .setHeader("Authorization", "Bearer token")
      .assertSuccess

    val authResponse = app(authRequest).assertSuccess
    assertEquals(authResponse.status, StatusCode.Ok)
    assert(authResponse.headers.getFirst("Access-Control-Allow-Origin").isDefined)

    // Unauthenticated request should fail with CORS headers
    val unauthRequest = Request.get(uri("http://localhost/"))
    val unauthResponse = app(unauthRequest).assertSuccess
    assertEquals(unauthResponse.status, StatusCode.Unauthorized)
    assert(unauthResponse.headers.getFirst("Access-Control-Allow-Origin").isDefined)
  }

  test("Middleware - error handler with logging") {
    var logs = List.empty[String]

    val handler: RequestHandler = _ =>
      Eru.fail(HttpError.InvalidRequest(InvalidRequest("Bad request", "RFC")))

    // Apply error handler first, then logging - this way logging sees the recovered response
    val app = Middleware
      .errorHandlerDefault
      .andThen(Middleware.logging(msg => logs = logs :+ msg))
      .apply(handler)

    val request = Request.get(uri("http://localhost/"))
    val response = app(request).assertSuccess

    assertEquals(response.status, StatusCode.BadRequest)
    assert(logs.exists(_.contains("→ GET /")))
    assert(logs.exists(_.contains("← 400")))
  }

  test("Middleware - conditional auth for API routes only") {
    val handler: RequestHandler = req =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(s"Path: ${req.uri.path}")))

    val checkToken: Request[Body] => Boolean = req =>
      req.headers.getFirst("Authorization").exists(_.value == "valid")

    val app = Middleware
      .when(_.uri.path.startsWith("/api"))(Middleware.auth(checkToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized())))
      .apply(handler)

    // Public route should work without auth
    val publicRequest = Request.get(uri("http://localhost/public"))
    val publicResponse = app(publicRequest).assertSuccess
    assertEquals(publicResponse.status, StatusCode.Ok)

    // API route should require auth
    val apiRequestNoAuth = Request.get(uri("http://localhost/api/users"))
    val apiResponseNoAuth = app(apiRequestNoAuth).assertSuccess
    assertEquals(apiResponseNoAuth.status, StatusCode.Unauthorized)

    // API route with valid auth should work
    val apiRequestWithAuth = Request
      .get(uri("http://localhost/api/users"))
      .setHeader("Authorization", "valid")
      .assertSuccess
    val apiResponseWithAuth = app(apiRequestWithAuth).assertSuccess
    assertEquals(apiResponseWithAuth.status, StatusCode.Ok)
  }

  // ===== CORSConfig Tests =====

  test("CORSConfig - default configuration") {
    val config = CORSConfig.default
    assertEquals(config.allowedOrigins, List("*"))
    assert(config.allowedMethods.contains(Method.GET))
    assert(config.allowedMethods.contains(Method.POST))
    assertEquals(config.allowCredentials, false)
  }

  test("CORSConfig - permissive configuration") {
    val config = CORSConfig.permissive
    assertEquals(config.allowedOrigins, List("*"))
    assertEquals(config.allowCredentials, false)
  }

  test("CORSConfig - forOrigins creates config with specific origins") {
    val config = CORSConfig.forOrigins("https://example.com", "https://app.example.com")
    assertEquals(config.allowedOrigins, List("https://example.com", "https://app.example.com"))
  }
}
