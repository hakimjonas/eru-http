package net.ghoula.eru.http.server

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

import TestHelpers.*

class HttpServerSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  // ===== Server Lifecycle Tests =====

  test("HttpServer - create and start server") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("Hello, World!")
        )
      )

    val result = for {
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(0), handler)
      address <- server.start
      _ <- server.shutdown
    } yield address

    val address = result.assertSuccess
    assert(address.port > 0)
  }

  test("HttpServer - scoped automatically shuts down server") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Empty
        )
      )

    val result = HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        server.start
      }
      .assertSuccess

    assert(result.port > 0)
  }

  // ===== HTTP Method Tests =====

  test("HttpServer - GET request returns 200 OK") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("Hello, World!")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200)
            assertEquals(response.body, "Hello, World!")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - POST request with body") {
    val handler: RequestHandler = req =>
      for {
        body <- BodyDecoder[String]
          .decode(req.body)
          .mapError(e => HttpError.BodyDecodeError(e))
      } yield Response(
        status = StatusCode.Ok,
        headers = Headers.empty,
        body = Body.Text(s"Received: $body")
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.post(
              s"http://${address}/api/users",
              """{"name":"Alice"}"""
            )
            assertEquals(response.status, 200)
            assertEquals(response.body, """Received: {"name":"Alice"}""")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - PUT request with body") {
    val handler: RequestHandler = req =>
      for {
        body <- BodyDecoder[String]
          .decode(req.body)
          .mapError(e => HttpError.BodyDecodeError(e))
      } yield Response(
        status = StatusCode.Ok,
        headers = Headers.empty,
        body = Body.Text(s"Updated: $body")
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.put(
              s"http://${address}/api/users/1",
              """{"name":"Bob"}"""
            )
            assertEquals(response.status, 200)
            assertEquals(response.body, """Updated: {"name":"Bob"}""")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - DELETE request") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.NoContent,
          headers = Headers.empty,
          body = Body.Empty
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.delete(s"http://${address}/api/users/1")
            assertEquals(response.status, 204)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Request Inspection Tests =====

  test("HttpServer - handler receives correct method") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(req.method.toString)
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.post(s"http://${address}", "test")
            assertEquals(response.body, "POST")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler receives URI path") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(req.uri.path)
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}/api/users")
            assertEquals(response.body, "/api/users")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler receives query parameters") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(req.uri.query.getOrElse(""))
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}/search?q=test&page=1")
            assert(response.body.contains("q=test"))
            assert(response.body.contains("page=1"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Headers Tests =====

  test("HttpServer - handler receives custom headers") {
    val handler: RequestHandler = req =>
      for {
        customHeader <- req.headers
          .getFirst("X-Custom-Header")
          .map(hv => Eru.succeed(hv.toString))
          .getOrElse(Eru.fail(HttpError.InvalidRequest(InvalidRequest("Missing header", "RFC"))))
      } yield Response(
        status = StatusCode.Ok,
        headers = Headers.empty,
        body = Body.Text(customHeader)
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(
              s"http://${address}",
              headers = Map("X-Custom-Header" -> "test-value")
            )
            assertEquals(response.status, 200)
            assertEquals(response.body, "test-value")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - response headers are sent to client") {
    val handler: RequestHandler = _ =>
      Headers.empty
        .add("X-Server-Header", "server-value")
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC")))
        .map { headers =>
          Response(
            status = StatusCode.Ok,
            headers = headers,
            body = Body.Empty
          )
        }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assert(response.headers.contains("x-server-header"))
            assertEquals(response.headers("x-server-header"), "server-value")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Status Code Tests =====

  test("HttpServer - handler can return 404 Not Found") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.NotFound,
          headers = Headers.empty,
          body = Body.Text("Not found")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 404)
            assertEquals(response.body, "Not found")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler can return 500 Internal Server Error") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.InternalServerError,
          headers = Headers.empty,
          body = Body.Text("Server error")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 500)
            assertEquals(response.body, "Server error")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Error Handling Tests =====

  test("HttpServer - handler errors are converted to 500 responses") {
    val handler: RequestHandler = _ => Eru.fail(HttpError.InvalidResponse(InvalidResponse("Handler error", "RFC")))

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 500)
            assert(response.body.contains("Internal Server Error"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Content-Type Tests =====

  test("HttpServer - Content-Type header is set correctly") {
    val handler: RequestHandler = _ =>
      Headers.empty
        .add("Content-Type", "application/json")
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC")))
        .map { headers =>
          Response(
            status = StatusCode.Ok,
            headers = headers,
            body = Body.Text("""{"result":"ok"}""")
          )
        }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assert(response.headers.contains("content-type"))
            assert(response.headers("content-type").contains("application/json"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Integration Tests =====

  test("HttpServer - handles multiple sequential requests") {
    var counter = 0
    val handler: RequestHandler = _ =>
      Eru.effect {
        counter += 1
        counter
      }.mapError { case e: Exception =>
        HttpError.NetworkError(s"Handler error: ${e.getMessage}", Some(e))
      }.map { count =>
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(count.toString)
        )
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val r1 = SimpleHttpClient.get(s"http://${address}")
            val r2 = SimpleHttpClient.get(s"http://${address}")
            val r3 = SimpleHttpClient.get(s"http://${address}")

            assertEquals(r1.body, "1")
            assertEquals(r2.body, "2")
            assertEquals(r3.body, "3")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  // ===== Middleware Tests =====

  test("HttpServer - with middleware chain") {
    var requestCount = 0
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("Hello")
        )
      )

    val app = Middleware
      .logging(_ => requestCount += 1)
      .andThen(Middleware.cors())
      .andThen(Middleware.requestId())
      .apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200)
            assert(response.headers.contains("access-control-allow-origin"))
            assert(response.headers.contains("x-request-id"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess

    assert(requestCount > 0, "Logging middleware should have been called")
  }

  test("HttpServer - with authentication middleware") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("Protected resource")
        )
      )

    val checkToken: Request[Body] => Boolean =
      req => req.headers.getFirst("Authorization").exists(_.value == "Bearer secret-token")

    val app = Middleware.auth(checkToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            // Request without token should be rejected
            val unauthResponse = SimpleHttpClient.get(s"http://${address}")
            assertEquals(unauthResponse.status, 401)

            // Request with valid token should succeed
            val authResponse = SimpleHttpClient.get(
              s"http://${address}",
              headers = Map("Authorization" -> "Bearer secret-token")
            )
            assertEquals(authResponse.status, 200)
            assertEquals(authResponse.body, "Protected resource")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - with error handling middleware") {
    val handler: RequestHandler = req =>
      if req.uri.path == "/error" then Eru.fail(HttpError.InvalidRequest(InvalidRequest("Bad input", "RFC")))
      else Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("OK")))

    val app = Middleware.errorHandlerDefault.apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            // Error path should return 400
            val errorResponse = SimpleHttpClient.get(s"http://${address}/error")
            assertEquals(errorResponse.status, 400)

            // Success path should return 200
            val okResponse = SimpleHttpClient.get(s"http://${address}/success")
            assertEquals(okResponse.status, 200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - with conditional middleware for API routes") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text(s"Path: ${req.uri.path}")
        )
      )

    val checkToken: Request[Body] => Boolean =
      req => req.headers.getFirst("Authorization").exists(_.value == "valid-token")

    val app = Middleware
      .when(_.uri.path.startsWith("/api"))(
        Middleware.auth(checkToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized()))
      )
      .apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            // Public routes should work without auth
            val publicResponse = SimpleHttpClient.get(s"http://${address}/public")
            assertEquals(publicResponse.status, 200)

            // API routes should require auth
            val apiNoAuthResponse = SimpleHttpClient.get(s"http://${address}/api/users")
            assertEquals(apiNoAuthResponse.status, 401)

            // API routes with auth should work
            val apiWithAuthResponse = SimpleHttpClient.get(
              s"http://${address}/api/users",
              headers = Map("Authorization" -> "valid-token")
            )
            assertEquals(apiWithAuthResponse.status, 200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }
}
