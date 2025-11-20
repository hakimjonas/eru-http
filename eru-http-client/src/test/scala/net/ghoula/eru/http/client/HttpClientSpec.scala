package net.ghoula.eru.http.client

import munit.FunSuite

import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*

import TestHelpers.*

class HttpClientSpec extends FunSuite {

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
      case "put" => Request.put(uri, body)
      case "delete" => Request.delete(uri)
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

  // ===== Client Lifecycle Tests =====

  test("HttpClient - create with default config") {
    val client = HttpClient.create.assertSuccess
    client.shutdown.assertSuccess
  }

  test("HttpClient - create with custom config") {
    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 10.seconds,
      maxConnections = 50
    )
    val client = HttpClient.create(config).assertSuccess
    client.shutdown.assertSuccess
  }

  test("HttpClient - scoped automatically shuts down client") {
    val result = HttpClient
      .scoped(HttpClientConfig.default) { client =>
        Eru.succeed(client)
      }
      .assertSuccess

    assert(Option(result).isDefined)
  }

  // ===== HTTP Method Tests =====

  test("HttpClient - GET request returns 200 OK") {
    val server = TestHttpServer.simple(body = "Hello, World!")
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), "Hello, World!")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - POST request with body") {
    val server = TestHttpServer.echo()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/api/users"))
            request <- createRequest("post", uri, Body.Text("""{"name":"Alice"}"""))
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assertEquals(response.status, StatusCode.Ok)
            assert(body.contains("\"method\":\"POST\""))
            assert(body.contains("\"path\":\"/api/users\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - PUT request with body") {
    val server = TestHttpServer.echo()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/api/users/1"))
            request <- createRequest("put", uri, Body.Text("""{"name":"Bob"}"""))
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assertEquals(response.status, StatusCode.Ok)
            assert(body.contains("\"method\":\"PUT\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - DELETE request") {
    val server = TestHttpServer.echo()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/api/users/1"))
            request <- createRequest("delete", uri)
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assertEquals(response.status, StatusCode.Ok)
            assert(body.contains("\"method\":\"DELETE\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Headers and Content Types =====

  test("HttpClient - custom headers are sent") {
    val server = TestHttpServer.echo()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            requestWithHeader <- request
              .addHeader("X-Custom-Header", "test-value")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC")))
            response <- client.send(requestWithHeader)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - Content-Type header is respected") {
    val server = TestHttpServer.simple(
      body = """{"result":"ok"}""",
      headers = Map("Content-Type" -> "application/json")
    )
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
            contentType <- response.headers.contentType
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid content type: $e", "RFC")))
          } yield {
            assert(contentType.isDefined)
            assert(contentType.get.isJson)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Body Types =====

  test("HttpClient - text body encoding and decoding") {
    val server = TestHttpServer.simple(body = "Plain text response")
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val text = response.body.asString(Charset.UTF8)
            assertEquals(text, "Plain text response")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - empty response body") {
    val server = TestHttpServer.simple(status = StatusCode.NoContent, body = "")
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status.value, 204)
            assert(response.body.isEmpty)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Status Code Handling =====

  test("HttpClient - handles 404 Not Found") {
    val server = TestHttpServer.simple(
      status = StatusCode.NotFound,
      body = "Not found"
    )
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.NotFound)
            assert(response.status.isClientError)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - handles 500 Internal Server Error") {
    val server = TestHttpServer.simple(
      status = StatusCode.InternalServerError,
      body = "Server error"
    )
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.InternalServerError)
            assert(response.status.isServerError)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Redirect Tests =====

  test("HttpClient - follows redirects when enabled") {
    val targetServer = TestHttpServer.simple(body = "Final destination")
    val redirectServer = TestHttpServer.create(
      handler = (_, _) =>
        TestHttpServer.ResponseConfig(
          redirectTo = Some(targetServer.url("/final"))
        )
    )

    try {
      val config = HttpClientConfig.default.copy(followRedirects = true)
      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(redirectServer.url("/start"))
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(body, "Final destination")
          }
        }
        .assertSuccess
    } finally {
      redirectServer.shutdown()
      targetServer.shutdown()
    }
  }

  test("HttpClient - does not follow redirects when disabled") {
    val targetServer = TestHttpServer.simple(body = "Should not reach here")
    val redirectServer = TestHttpServer.create(
      handler = (_, _) =>
        TestHttpServer.ResponseConfig(
          redirectTo = Some(targetServer.url("/final"))
        )
    )

    try {
      val config = HttpClientConfig.default.copy(followRedirects = false)
      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(redirectServer.url("/start"))
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status.value, 302)
            assert(response.headers.contains(HeaderNames.Location))
          }
        }
        .assertSuccess
    } finally {
      redirectServer.shutdown()
      targetServer.shutdown()
    }
  }

  // ===== Error Handling Tests =====

  test("HttpClient - connection refused returns ConnectionError") {
    val result = HttpClient.scoped(HttpClientConfig.default) { client =>
      for {
        uri <- parseUri("http://localhost:1") // Port 1 should be refused
        request <- createRequest("get", uri)
        response <- client.send(request)
      } yield response
    }

    assert(result.isFailure)
    val error = result.assertFailure
    error match {
      case HttpError.ConnectionError(msg, _) =>
        assert(msg.contains("Connection refused") || msg.contains("connection") || msg.contains("connect"))
      case other =>
        fail(s"Expected ConnectionError but got: $other")
    }
  }

  test("HttpClient - request timeout returns TimeoutError") {
    val server = TestHttpServer.create(
      handler = (_, _) => TestHttpServer.ResponseConfig(delay = 5.seconds)
    )

    try {
      val config = HttpClientConfig.default.copy(requestTimeout = 100.millis)
      val result = HttpClient.scoped(config) { client =>
        for {
          uri <- parseUri(server.url())
          request <- createRequest("get", uri)
          response <- client.send(request)
        } yield response
      }

      assert(result.isFailure)
      val error = result.assertFailure
      error match {
        case HttpError.TimeoutError(msg) =>
          assert(msg.contains("timeout") || msg.contains("Timeout"))
        case HttpError.NetworkError(msg, _) if msg.toLowerCase.contains("timeout") =>
          // Also acceptable
          assert(true)
        case other =>
          fail(s"Expected TimeoutError but got: $other")
      }
    } finally {
      server.shutdown()
    }
  }

  // ===== Integration Tests =====

  test("HttpClient - multiple sequential requests") {
    val server = TestHttpServer.echo()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/test"))
            request <- createRequest("get", uri)
            response1 <- client.send(request)
            response2 <- client.send(request)
            response3 <- client.send(request)
          } yield {
            val body1 = response1.body.asString(Charset.UTF8)
            val body2 = response2.body.asString(Charset.UTF8)
            val body3 = response3.body.asString(Charset.UTF8)
            // Each request should get a unique request counter (verify they're all different)
            assert(
              body1 != body2 && body2 != body3 && body1 != body3,
              s"Request counters should be unique:\n  body1=$body1\n  body2=$body2\n  body3=$body3"
            )
            // All should have same method and path
            assert(body1.contains("\"method\":\"GET\""))
            assert(body1.contains("\"path\":\"/test\""))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - query parameters are sent correctly") {
    val server = TestHttpServer.echo()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          (for {
            uri <- parseUri(server.url("/search"))
            u1 <- uri.withQueryParam("q", "test")
            uriWithQuery <- u1.withQueryParam("page", "1")
            request <- createRequest("get", uriWithQuery)
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            assert(body.contains("\"path\":\"/search?q=test&page=1\""))
          }).mapError {
            case e: HttpError => e
            case e => HttpError.InvalidRequest(InvalidRequest(s"Error: $e", "RFC"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Automatic Decompression Tests =====

  test("HttpClient - automatically decompresses gzip responses") {
    // Create server that returns compressed response
    val server = TestHttpServer.create(handler = (_, _) => {
      val largeText = "x" * 10240
      val compressed =
        Compression.compress(Bytes.fromString(largeText, Charset.UTF8), ContentEncoding.Gzip).unsafeRunSync()
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        binaryBody = Some(compressed), // Send as binary
        headers = Map(
          "Content-Encoding" -> "gzip",
          "Content-Length" -> compressed.length.toString
        )
      )
    })

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client => // automaticDecompression = true by default
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            // Response should be automatically decompressed
            assertEquals(response.body.length, 10240)
            assertEquals(response.body.asString(Charset.UTF8), "x" * 10240)
            // Content-Encoding header should be removed
            assert(response.headers.getFirst(HeaderNames.ContentEncoding).isEmpty)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - skips decompression when automaticDecompression is false") {
    val server = TestHttpServer.create(handler = (_, _) => {
      val largeText = "x" * 10240
      val compressed =
        Compression.compress(Bytes.fromString(largeText, Charset.UTF8), ContentEncoding.Gzip).unsafeRunSync()
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        binaryBody = Some(compressed), // Send as binary
        headers = Map(
          "Content-Encoding" -> "gzip",
          "Content-Length" -> compressed.length.toString
        )
      )
    })

    try {
      HttpClient
        .scoped(HttpClientConfig.default.withAutomaticDecompression(false)) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            // Response should stay compressed
            assert(response.body.length < 1000) // Compressed size ~45 bytes
            // Content-Encoding header should remain
            assert(response.headers.getFirst(HeaderNames.ContentEncoding).isDefined)
            assertEquals(response.headers.getFirst(HeaderNames.ContentEncoding).get.value, "gzip")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - handles responses without Content-Encoding") {
    val server = TestHttpServer.simple(body = "Plain text response")

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.body.asString(Charset.UTF8), "Plain text response")
            assert(response.headers.getFirst(HeaderNames.ContentEncoding).isEmpty)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - adds Accept-Encoding header when automaticDecompression is enabled") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client => // automaticDecompression = true
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            // Response should contain the Accept-Encoding header we sent
            assert(body.contains("accept-encoding") || body.contains("Accept-Encoding"))
            assert(body.contains("gzip") && body.contains("deflate"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - does not add Accept-Encoding when automaticDecompression is disabled") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default.withAutomaticDecompression(false)) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
            // Should not automatically add Accept-Encoding
            assert(!body.toLowerCase.contains("accept-encoding"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }
}
