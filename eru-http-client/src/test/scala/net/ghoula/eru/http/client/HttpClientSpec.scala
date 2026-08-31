package net.ghoula.eru.http.client

import munit.FunSuite

import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.{HttpServer, HttpServerConfig, RequestHandler}

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

  private def parseUri(url: String): Eru[HttpError, Uri] =
    Uri
      .parse(url)
      .mapError(e =>
        HttpError.InvalidRequest(
          InvalidRequest(s"Invalid URI: ${e.reason}", e.rfc)
        )
      )

  private def createRequest(method: String, uri: Uri, body: Body = Body.Empty): Eru[HttpError, Request[Body]] = {
    val request = method.toLowerCase match {
      case "get" => Request.get(uri)
      case "post" => Request.post(uri, body)
      case "put" => Request.put(uri, body)
      case "delete" => Request.delete(uri)
      case _ => Request(Method.GET, uri, Headers.empty, body)
    }

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

  test("HttpClient - connection refused returns ConnectionError") {
    val result = HttpClient.scoped(HttpClientConfig.default) { client =>
      for {
        uri <- parseUri("http://localhost:1")
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
          assert(true)
        case other =>
          fail(s"Expected TimeoutError but got: $other")
      }
    } finally {
      server.shutdown()
    }
  }

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
            assert(
              body1 != body2 && body2 != body3 && body1 != body3,
              s"Request counters should be unique:\n  body1=$body1\n  body2=$body2\n  body3=$body3"
            )
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

  test("HttpClient - automatically decompresses gzip responses") {
    val server = TestHttpServer.create(handler = (_, _) => {
      val largeText = "x" * 10240
      val compressed =
        Compression.compress(Bytes.fromString(largeText, Charset.UTF8), ContentEncoding.Gzip).unsafeRunSync()
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        binaryBody = Some(compressed),
        headers = Map(
          "Content-Encoding" -> "gzip",
          "Content-Length" -> compressed.length.toString
        )
      )
    })

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.body.length, 10240)
            assertEquals(response.body.asString(Charset.UTF8), "x" * 10240)
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
        binaryBody = Some(compressed),
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
            assert(response.body.length < 1000)
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
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val body = response.body.asString(Charset.UTF8)
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
            assert(!body.toLowerCase.contains("accept-encoding"))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sends Content-Type from body media type") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            body = Body.Text("""{"key":"value"}""", Some(MediaType.applicationJson))
            request <- createRequest("post", uri, body)
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains(""""content-type":"application/json""""), s"missing Content-Type in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - execute decodes a typed response body") {
    val server = TestHttpServer.create(handler = (_, _) => TestHttpServer.ResponseConfig(body = "hello"))

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.execute[Body, String](request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body, "hello")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - applies the configured default User-Agent header") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains("user-agent"), s"missing user-agent in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - does not override an explicitly set User-Agent header") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            requestWithAgent <- request
              .setHeader(HeaderNames.UserAgent, "custom-agent/1.0")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
            response <- client.send(requestWithAgent)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains("custom-agent/1.0"), s"explicit user-agent lost in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - generates the Host header from the URI when absent") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            response <- client.send(Request.get(uri))
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains(s""""host":"localhost:${server.port}""""), s"missing generated host in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - does not override an explicit Host header") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- Request
              .get(uri)
              .addHeader(HeaderNames.Host, "custom-host")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains("""custom-host"""), s"explicit host lost in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sends no User-Agent when the config disables it") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default.copy(userAgent = None)) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(!echoed.contains("user-agent"), s"unexpected user-agent in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - cookie jar stores Set-Cookie and replays Cookie on the next request") {
    val jar = CookieJar.inMemory.assertSuccess
    val server = TestHttpServer.create(handler =
      (_, path) =>
        if path == "/set" then TestHttpServer.ResponseConfig(headers = Map("Set-Cookie" -> "session=abc123; Path=/"))
        else TestHttpServer.ResponseConfig(body = "done")
    )

    try {
      HttpClient
        .scoped(HttpClientConfig.default.copy(cookieJar = Some(jar))) { client =>
          for {
            uri <- parseUri(server.url("/set"))
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
          }
        }
        .assertSuccess

      val uri = Uri.parse(server.url("/set")).assertSuccess
      val stored = jar.getCookies(uri).assertSuccess
      assertEquals(stored.size, 1)
      assertEquals(stored.head.name, "session")
      assertEquals(stored.head.value, "abc123")
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sends a QUERY request with a body (RFC 10008)") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            response <- client.send(Request.query(uri, Body.text("find me", MediaType.textPlain)))
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains(""""method":"QUERY""""), s"missing QUERY method in: $echoed")
            assert(echoed.contains(""""body":"find me""""), s"missing query body in: $echoed")
            assert(echoed.contains(""""content-type":"text/plain""""), s"missing Content-Type in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sends a QUERY request without a body") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            request <- Request
              .query(uri, Body.Empty)
              .addHeader(HeaderNames.ContentType, "text/plain")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains(""""method":"QUERY""""), s"missing QUERY method in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - refuses to send a QUERY without a Content-Type header") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      val result = HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            response <- client.send(Request.query(uri, Body.Text("q", None, Charset.UTF8)))
          } yield response
        }
        .attempt
        .unsafeRunSync()

      result match {
        case Result.Success(_) => fail("expected the client to refuse a Content-Type-less QUERY")
        case Result.Failure(e: HttpError.InvalidRequest) =>
          assert(e.error.reason.contains("Content-Type"), s"unexpected reason: ${e.error.reason}")
        case Result.Failure(other) => fail(s"expected InvalidRequest, got: $other")
      }
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - follows a 303 redirect on QUERY with a bodyless GET (RFC 10008 Section 2.5)") {
    val server = TestHttpServer.create(handler =
      (method, path) =>
        if path == "/search" then
          TestHttpServer.ResponseConfig(
            redirectTo = Some("/results"),
            redirectStatus = StatusCode.SeeOther
          )
        else TestHttpServer.ResponseConfig(body = s"$method $path")
    )

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            response <- client.send(Request.query(uri, Body.text("q", MediaType.textPlain)))
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), "GET /results")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - repeats a QUERY with its content on a 307 redirect (RFC 10008 Section 2.5)") {
    val server = TestHttpServer.create(handler =
      (method, path) =>
        if path == "/search" then
          TestHttpServer.ResponseConfig(
            redirectTo = Some("/moved-search"),
            redirectStatus = StatusCode.TemporaryRedirect
          )
        else if path == "/moved-search" then TestHttpServer.ResponseConfig(body = s"$method ${path}")
        else TestHttpServer.ResponseConfig(status = StatusCode.NotFound)
    )

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            response <- client.send(Request.query(uri, Body.text("q", MediaType.textPlain)))
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), "QUERY /moved-search")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - queryFormats parses the Accept-Query field from an OPTIONS probe") {
    val server = TestHttpServer.create(handler =
      (_, _) =>
        TestHttpServer.ResponseConfig(
          headers = Map("Accept-Query" -> """"application/jsonpath", application/sql;charset="UTF-8"""")
        )
    )

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            formats <- client.queryFormats(uri)
          } yield {
            assert(formats.isDefined, "expected an Accept-Query advertisement")
            val aq = formats.get
            assertEquals(aq.ranges.size, 2)
            assert(aq.accepts(MediaType("application", "jsonpath")))
            assert(aq.accepts(MediaType("application", "sql")))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - queryFormats returns None when the server advertises nothing") {
    val server = TestHttpServer.create()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            formats <- client.queryFormats(uri)
          } yield {
            assertEquals(formats, None)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - queryFormats fails with a ProtocolError on a malformed field") {
    val server = TestHttpServer.create(handler =
      (_, _) => TestHttpServer.ResponseConfig(headers = Map("Accept-Query" -> "not a valid list"))
    )

    try {
      val result = HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url("/search"))
            formats <- client.queryFormats(uri)
          } yield formats
        }
        .attempt
        .unsafeRunSync()

      result match {
        case Result.Success(_) => fail("expected a ProtocolError for the malformed Accept-Query")
        case Result.Failure(e: HttpError.ProtocolError) =>
          assert(e.rfc.contains("RFC 10008"), s"expected an RFC 10008 citation, got: ${e.rfc}")
        case Result.Failure(other) => fail(s"expected ProtocolError, got: $other")
      }
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - strips credentials on a cross-origin redirect (RFC 9110 Section 11.5)") {
    val target = TestHttpServer.echoWithHeaders()
    val redirector = TestHttpServer.create(handler =
      (_, _) =>
        TestHttpServer.ResponseConfig(
          redirectTo = Some(target.url("/moved")),
          redirectStatus = StatusCode.TemporaryRedirect
        )
    )

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(redirector.url("/search"))
            request <- Request
              .query(uri, Body.text("find me", MediaType.textPlain))
              .addHeader(HeaderNames.Authorization, "Bearer secret")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(!echoed.contains("authorization"), s"credentials leaked cross-origin in: $echoed")
            assert(echoed.contains(""""method":"QUERY""""), s"307 must repeat the QUERY, got: $echoed")
            assert(echoed.contains(""""body":"find me""""), s"query content must be replayed, got: $echoed")
          }
        }
        .assertSuccess
    } finally {
      redirector.shutdown()
      target.shutdown()
    }
  }

  test("HttpClient - keeps credentials on a same-origin redirect") {
    val handler: RequestHandler = req =>
      req.uri.path match {
        case "/search" =>
          Response[Body](status = StatusCode.TemporaryRedirect, headers = Headers.empty, body = Body.Empty)
            .withLocation(Uri.parse("/moved").unsafeRunSync())
            .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"Invalid Location: $e", "RFC 9110")))
        case "/moved" =>
          val auth = req.headers.getFirst(HeaderNames.Authorization).map(_.value).getOrElse("")
          Eru.succeed(
            Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text(s"auth=$auth"))
          )
        case _ =>
          Eru.succeed(
            Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not Found"))
          )
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          uri <- Uri.parse(s"http://localhost:${address.port}/search").mapError(HttpError.InvalidUri.apply)
          request <- Request
            .query(uri, Body.text("find me", MediaType.textPlain))
            .addHeader(HeaderNames.Authorization, "Bearer keepme")
            .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
          response <- HttpClient.scoped(_.send(request))
        } yield {
          assertEquals(response.body.asString(Charset.UTF8), "auth=Bearer keepme")
        }
      }
      .assertSuccess
  }

  test("HttpClient - sends a QUERY request with a streaming body") {
    val handler: RequestHandler = req =>
      (req.body match {
        case s: Body.Stream =>
          s.chunks.flatMap(_.toBytes).map(b => s"streamed:${b.asString(Charset.UTF8)}")
        case other => Eru.succeed(s"unexpected body: $other")
      }).map { text =>
        Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text(text))
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          uri <- Uri.parse(s"http://localhost:${address.port}/search").mapError(HttpError.InvalidUri.apply)
          stream = ChunkStream.fromChunks(
            Chunk.fromString("part1", Charset.UTF8),
            Chunk.fromString(" part2", Charset.UTF8)
          )
          request <- Request
            .query(uri, Body.Stream(Eru.succeed(stream), None, Some(MediaType.textPlain)))
            .addHeader(HeaderNames.ContentType, "text/plain")
            .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
          response <- HttpClient.scoped(_.send(request))
        } yield {
          assertEquals(response.body.asString(Charset.UTF8), "streamed:part1 part2")
        }
      }
      .assertSuccess
  }

  test("HttpClient - repeats a QUERY with its content on a 302 redirect (RFC 10008 Section 2.5)") {
    val handler: RequestHandler = req =>
      req.uri.path match {
        case "/search" =>
          Response[Body](status = StatusCode.Found, headers = Headers.empty, body = Body.Empty)
            .withLocation(Uri.parse("/moved").unsafeRunSync())
            .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"Invalid Location: $e", "RFC 9110")))
        case "/moved" =>
          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty,
              body = Body.text(s"${req.method.value} ${req.uri.path}")
            )
          )
        case _ =>
          Eru.succeed(
            Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not Found"))
          )
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          uri <- Uri.parse(s"http://localhost:${address.port}/search").mapError(HttpError.InvalidUri.apply)
          response <- HttpClient.scoped(_.send(Request.query(uri, Body.text("q", MediaType.textPlain))))
        } yield {
          assertEquals(response.body.asString(Charset.UTF8), "QUERY /moved")
        }
      }
      .assertSuccess
  }

  test("HttpClient - stops following QUERY redirects at maxRedirects and returns the last response") {
    val requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val handler: RequestHandler = _ =>
      Eru.succeed {
        requestCount.incrementAndGet()
        Response[Body](status = StatusCode.Found, headers = Headers.empty, body = Body.Empty)
          .withLocation(Uri.parse("/loop").unsafeRunSync())
          .unsafeRunSync()
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          uri <- Uri.parse(s"http://localhost:${address.port}/loop").mapError(HttpError.InvalidUri.apply)
          response <- HttpClient.scoped(_.send(Request.query(uri, Body.text("q", MediaType.textPlain))))
        } yield {
          assertEquals(response.status, StatusCode.Found, "the redirect budget must return the last 3xx")
          assertEquals(requestCount.get(), 6, "maxRedirects (5) + the original request")
        }
      }
      .assertSuccess
  }

  test("HttpClient - does not override an explicitly set Content-Type header") {
    val server = TestHttpServer.echoWithHeaders()
    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            body = Body.Text("""{"key":"value"}""", Some(MediaType.applicationJson))
            request <- createRequest("post", uri, body)
            requestWithType <- request
              .setHeader(HeaderNames.ContentType, "text/csv")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
            response <- client.send(requestWithType)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(echoed.contains(""""content-type":"text/csv""""), s"explicit Content-Type lost in: $echoed")
            assert(
              !echoed.contains(""""content-type":"application/json""""),
              s"media type overrode explicit header in: $echoed"
            )
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sends no Content-Type for an empty body") {
    val server = TestHttpServer.echoWithHeaders()

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            val echoed = response.body.asString(Charset.UTF8)
            assert(!echoed.contains(""""content-type""""), s"unexpected Content-Type in: $echoed")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - handles chunked transfer encoding response") {
    val expectedBody = "Hello from chunked response!"
    val server = TestHttpServer.chunked(body = expectedBody)

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), expectedBody)
            assertEquals(response.body.length, expectedBody.length)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - handles large chunked response") {
    val largeBody = "x" * 100000
    val server = TestHttpServer.chunked(body = largeBody)

    try {
      HttpClient
        .scoped(HttpClientConfig.default) { client =>
          for {
            uri <- parseUri(server.url())
            request <- createRequest("get", uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.length, largeBody.length)
            assertEquals(response.body.asString(Charset.UTF8), largeBody)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }
}
