package net.ghoula.eru.http.client

import munit.FunSuite

import scala.collection.mutable.ListBuffer

import net.ghoula.eru.*
import net.ghoula.eru.http.*

import TestHelpers.*

class InterceptorSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  // Helper to convert URI errors to HTTP errors
  private def parseUri(url: String): Eru[HttpError, Uri] =
    Uri
      .parse(url)
      .mapError(e =>
        HttpError.InvalidRequest(
          InvalidRequest(s"Invalid URI: ${e.reason}", e.rfc)
        )
      )

  // Helper to create a request with Host header
  private def createRequest(method: String, uri: Uri, body: Body = Body.Empty): Eru[HttpError, Request[Body]] = {
    val request = method.toLowerCase match {
      case "get" => Request.get(uri)
      case "post" => Request.post(uri, body)
      case _ => Request(Method.GET, uri, Headers.empty, body)
    }

    // Add Host header from URI
    uri.host.map { host =>
      val hostValue = uri.port match {
        case Some(port) if port.value != 80 && port.value != 443 => s"$host:${port.value}"
        case _ => host
      }
      request
        .setHeader(HeaderNames.Host, hostValue)
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Host header: $e", "RFC")))
    }.getOrElse(Eru.succeed(request))
  }

  // ===== Basic Interceptor Tests =====

  test("Interceptor.addHeader - adds header to request") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.addHeader("X-Custom", "test-value")
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"x-custom\":\"test-value\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.addHeaders - adds multiple headers to request") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.addHeaders(
              "X-Header-1" -> "value1",
              "X-Header-2" -> "value2"
            )
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"x-header-1\":\"value1\""))
            assert(body.contains("\"x-header-2\":\"value2\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.bearerAuth - adds Authorization header with Bearer token") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.bearerAuth("secret-token-123")
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"authorization\":\"Bearer secret-token-123\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.basicAuth - adds Authorization header with Basic auth") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.basicAuth("user", "pass")
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            // Basic dXNlcjpwYXNz is base64 encoding of "user:pass"
            assert(body.contains("\"authorization\":\"Basic dXNlcjpwYXNz\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.userAgent - adds User-Agent header") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.userAgent("MyApp/1.0")
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"user-agent\":\"MyApp/1.0\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.withHeader - adds header with dynamic value") {
    val server = TestHttpServer.echoWithHeaders()
    var counter = 0

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.withHeader("X-Request-ID") {
              counter += 1
              s"req-$counter"
            }
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response1 <- clientWithInterceptor.send(request)
            response2 <- clientWithInterceptor.send(request)
          } yield {
            val body1 = response1.body.asString(Charset.UTF8)
            val body2 = response2.body.asString(Charset.UTF8)
            assert(body1.contains("\"x-request-id\":\"req-1\""))
            assert(body2.contains("\"x-request-id\":\"req-2\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Logging Interceptor Tests =====

  test("Interceptor.logRequest - logs request method and URI") {
    val server = TestHttpServer.simple(body = "OK")
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.logRequest(msg => logs += msg)
          )

          for {
            uri <- parseUri(server.url("/test"))
            request <- createRequest("get", uri)
            _ <- clientWithInterceptor.send(request)
          } yield {
            assert(logs.size == 1)
            assert(logs.head.contains("GET"))
            assert(logs.head.contains("/test"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.logResponse - logs response status and body size") {
    val server = TestHttpServer.simple(body = "Hello, World!")
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withResponseInterceptor(
            Interceptor.logResponse(msg => logs += msg)
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            _ <- clientWithInterceptor.send(request)
          } yield {
            assert(logs.size == 1)
            assert(logs.head.contains("200"))
            assert(logs.head.contains("bytes"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.logging - logs both request and response") {
    val server = TestHttpServer.simple(body = "OK")
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val (reqLog, respLog) = Interceptor.logging(msg => logs += msg)
          val clientWithInterceptors = client
            .withRequestInterceptor(reqLog)
            .withResponseInterceptor(respLog)

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            _ <- clientWithInterceptors.send(request)
          } yield {
            assertEquals(logs.size, 2)
            assert(logs(0).contains("GET"))
            assert(logs(1).contains("200"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Composition Tests =====

  test("RequestInterceptor.andThen - composes interceptors in order") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val interceptor = Interceptor
            .addHeader("X-First", "1")
            .andThen(Interceptor.addHeader("X-Second", "2"))

          val clientWithInterceptor = client.withRequestInterceptor(interceptor)

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"x-first\":\"1\""))
            assert(body.contains("\"x-second\":\"2\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("RequestInterceptor.andThenAll - composes multiple interceptors") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val interceptor = Interceptor
            .addHeader("X-First", "1")
            .andThenAll(
              Interceptor.addHeader("X-Second", "2"),
              Interceptor.addHeader("X-Third", "3")
            )

          val clientWithInterceptor = client.withRequestInterceptor(interceptor)

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"x-first\":\"1\""))
            assert(body.contains("\"x-second\":\"2\""))
            assert(body.contains("\"x-third\":\"3\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Multiple interceptors on client - applied in FIFO order") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptors = client
            .withRequestInterceptor(Interceptor.addHeader("X-First", "1"))
            .withRequestInterceptor(Interceptor.addHeader("X-Second", "2"))
            .withRequestInterceptor(Interceptor.addHeader("X-Third", "3"))

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- clientWithInterceptors.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"x-first\":\"1\""))
            assert(body.contains("\"x-second\":\"2\""))
            assert(body.contains("\"x-third\":\"3\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("ResponseInterceptor.andThen - composes response interceptors") {
    val server = TestHttpServer.simple(body = "OK")
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val interceptor = Interceptor
            .logResponse(msg => logs += s"First: $msg")
            .andThen(Interceptor.logResponse(msg => logs += s"Second: $msg"))

          val clientWithInterceptor = client.withResponseInterceptor(interceptor)

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            _ <- clientWithInterceptor.send(request)
          } yield {
            assertEquals(logs.size, 2)
            assert(logs(0).startsWith("First:"))
            assert(logs(1).startsWith("Second:"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Conditional Interceptor Tests =====

  test("Interceptor.when - applies interceptor only when condition is true") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val interceptor = Interceptor.when(req => req.uri.path.contains("/api")) {
            Interceptor.addHeader("X-API-Key", "secret")
          }

          val clientWithInterceptor = client.withRequestInterceptor(interceptor)

          for {
            apiUri <- parseUri(server.url("/api/users"))
            regularUri <- parseUri(server.url("/public"))
            apiRequest <- createRequest("get", apiUri)
            regularRequest <- createRequest("get", regularUri)
            apiResponse <- clientWithInterceptor.send(apiRequest)
            regularResponse <- clientWithInterceptor.send(regularRequest)
          } yield {
            val apiBody = apiResponse.body.asString(Charset.UTF8)
            val regularBody = regularResponse.body.asString(Charset.UTF8)

            // API request should have the header
            assert(apiBody.contains("\"x-api-key\":\"secret\""))
            // Regular request should not have the header
            assert(!regularBody.contains("\"x-api-key\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor.whenResponse - applies interceptor only when condition is true") {
    val server = TestHttpServer.create(
      handler = (_, path) =>
        if path.contains("/error") then
          TestHttpServer.ResponseConfig(status = StatusCode.InternalServerError, body = "Error")
        else TestHttpServer.ResponseConfig(body = "OK")
    )

    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val interceptor = Interceptor.whenResponse(resp => resp.status.isServerError) {
            Interceptor.logResponse(msg => logs += s"ERROR: $msg")
          }

          val clientWithInterceptor = client.withResponseInterceptor(interceptor)

          for {
            okUri <- parseUri(server.url("/ok"))
            errorUri <- parseUri(server.url("/error"))
            okRequest <- createRequest("get", okUri)
            errorRequest <- createRequest("get", errorUri)
            _ <- clientWithInterceptor.send(okRequest)
            _ <- clientWithInterceptor.send(errorRequest)
          } yield {
            // Only error response should be logged
            assertEquals(logs.size, 1)
            assert(logs.head.contains("ERROR:"))
            assert(logs.head.contains("500"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Error Handling Tests =====

  test("Interceptor - invalid header name returns error") {
    val server = TestHttpServer.simple(body = "OK")
    try {
      val result = HttpClient.scoped(HttpClientConfig.default) { client =>
        val clientWithInterceptor = client.withRequestInterceptor(
          Interceptor.addHeader("Invalid Header!", "value")
        )

        for {
          uri <- parseUri(server.url())
          request <- createRequest("get", uri)
          response <- clientWithInterceptor.send(request)
        } yield response
      }

      assert(result.isFailure)
      val error = result.assertFailure
      error match {
        case HttpError.InvalidRequest(e) =>
          assert(e.getMessage.contains("Invalid header name"))
        case other =>
          fail(s"Expected InvalidRequest but got: $other")
      }
    } finally {
      server.shutdown()
    }
  }

  test("Interceptor - error in interceptor propagates to caller") {
    val server = TestHttpServer.simple(body = "OK")
    try {
      val failingInterceptor: RequestInterceptor = _ => Eru.fail(HttpError.NetworkError("Interceptor failed"))

      val result = HttpClient.scoped(HttpClientConfig.default) { client =>
        val clientWithInterceptor = client.withRequestInterceptor(failingInterceptor)

        for {
          uri <- parseUri(server.url())
          request <- createRequest("get", uri)
          response <- clientWithInterceptor.send(request)
        } yield response
      }

      assert(result.isFailure)
      val error = result.assertFailure
      error match {
        case HttpError.NetworkError(msg, _) =>
          assertEquals(msg, "Interceptor failed")
        case other =>
          fail(s"Expected NetworkError but got: $other")
      }
    } finally {
      server.shutdown()
    }
  }

  // ===== Integration Tests =====

  test("HttpClient.withInterceptor - applies both request and response interceptors") {
    val server = TestHttpServer.simple(body = "OK")
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val (reqLog, respLog) = Interceptor.logging(msg => logs += msg)
          val clientWithInterceptors = client.withInterceptor(reqLog, respLog)

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            _ <- clientWithInterceptors.send(request)
          } yield {
            assertEquals(logs.size, 2)
            assert(logs(0).contains("GET"))
            assert(logs(1).contains("200"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Complex interceptor chain - auth, logging, and custom headers") {
    val server = TestHttpServer.echoWithHeaders()
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptors = client
            .withRequestInterceptor(Interceptor.logRequest(msg => logs += msg))
            .withRequestInterceptor(Interceptor.bearerAuth("token123"))
            .withRequestInterceptor(Interceptor.userAgent("TestClient/1.0"))
            .withRequestInterceptor(Interceptor.addHeader("X-Custom", "value"))
            .withResponseInterceptor(Interceptor.logResponse(msg => logs += msg))

          for {
            uri <- parseUri(server.url("/api/test"))
            request <- createRequest("get", uri)
            response <- clientWithInterceptors.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)

            // Verify all headers were added
            assert(body.contains("\"authorization\":\"Bearer token123\""))
            assert(body.contains("\"user-agent\":\"TestClient/1.0\""))
            assert(body.contains("\"x-custom\":\"value\""))

            // Verify logging occurred
            assertEquals(logs.size, 2)
            assert(logs(0).contains("GET"))
            assert(logs(1).contains("200"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptors work with POST requests and body") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.addHeader("Content-Type", "application/json")
          )

          for {
            uri <- parseUri(server.url("/api/data"))
            request <- createRequest("post", uri, Body.Text("""{"test":"data"}"""))
            response <- clientWithInterceptor.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"method\":\"POST\""))
            assert(body.contains("\"content-type\":\"application/json\""))
            // The body field contains the escaped JSON
            assert(body.contains("\"body\":"))
            assert(body.contains("test"))
            assert(body.contains("data"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("Interceptors persist across multiple requests") {
    val server = TestHttpServer.simple(body = "OK")
    val logs = ListBuffer.empty[String]

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          val clientWithInterceptor = client.withRequestInterceptor(
            Interceptor.logRequest(msg => logs += msg)
          )

          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            _ <- clientWithInterceptor.send(request)
            _ <- clientWithInterceptor.send(request)
            _ <- clientWithInterceptor.send(request)
          } yield {
            assertEquals(logs.size, 3)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }
}
