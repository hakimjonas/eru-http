package net.ghoula.eru.http.client

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.TestHelpers.*
import net.ghoula.eru.http.server.*

/** Integration tests for server compression middleware and client automatic decompression.
  *
  * These tests verify the full end-to-end cycle of:
  *   1. Client sends Accept-Encoding header
  *   2. Server compresses response with compression middleware
  *   3. Client automatically decompresses response
  *   4. Client receives original uncompressed data
  *
  * The small-response test relies on "Hello, World!" (13 bytes) being below the default compression
  * minSize threshold, so the server leaves it uncompressed.
  */
class CompressionIntegrationSpec extends FunSuite {

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

  test("Integration - client decompresses server-compressed gzip response") {
    val largeText = "x" * 10240
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(largeText)))
    val compressedHandler = Middleware.compression(CompressionConfig.default).apply(handler)

    val serverConfig = HttpServerConfig.localhost.withPort(0)

    val testProgram = for {
      server <- HttpServer.create(serverConfig, compressedHandler)
      addr <- server.start

      result <- HttpClient.scoped(HttpClientConfig.default) { client =>
        for {
          uri <- parseUri(s"http://localhost:${addr.port}/test")
          request = Request.get(uri)
          response <- client.send(request)
        } yield {
          assertEquals(response.body.length, 10240)
          assertEquals(response.body.asString(Charset.UTF8), largeText)
          assert(response.headers.getFirst(HeaderNames.ContentEncoding).isEmpty)
        }
      }

      _ <- server.shutdown
    } yield result

    testProgram.assertSuccess
  }

  test("Integration - client receives compressed data when decompression disabled") {
    val largeText = "x" * 10240
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(largeText)))
    val compressedHandler = Middleware.compression(CompressionConfig.default).apply(handler)

    val serverConfig = HttpServerConfig.localhost.withPort(0)

    val testProgram = for {
      server <- HttpServer.create(serverConfig, compressedHandler)
      addr <- server.start

      result <- HttpClient.scoped(HttpClientConfig.default.withAutomaticDecompression(false)) { client =>
        for {
          uri <- parseUri(s"http://localhost:${addr.port}/test")
          request <- Request
            .get(uri)
            .headers
            .add(HeaderNames.AcceptEncoding, "gzip")
            .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
            .map(h => Request.get(uri).copy(headers = h))
          response <- client.send(request)
        } yield {
          assert(response.body.length < 1000)
          assert(response.headers.getFirst(HeaderNames.ContentEncoding).isDefined)
          assertEquals(response.headers.getFirst(HeaderNames.ContentEncoding).get.value, "gzip")
        }
      }

      _ <- server.shutdown
    } yield result

    testProgram.assertSuccess
  }

  test("Integration - server skips compression for small responses") {
    val smallText = "Hello, World!"
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(smallText)))
    val compressedHandler = Middleware.compression(CompressionConfig.default).apply(handler)

    val serverConfig = HttpServerConfig.localhost.withPort(0)

    val testProgram = for {
      server <- HttpServer.create(serverConfig, compressedHandler)
      addr <- server.start

      result <- HttpClient.scoped(HttpClientConfig.default) { client =>
        for {
          uri <- parseUri(s"http://localhost:${addr.port}/test")
          request = Request.get(uri)
          response <- client.send(request)
        } yield {
          assertEquals(response.body.asString(Charset.UTF8), smallText)
          assert(response.headers.getFirst(HeaderNames.ContentEncoding).isEmpty)
        }
      }

      _ <- server.shutdown
    } yield result

    testProgram.assertSuccess
  }

  test("Integration - server respects client's Accept-Encoding preferences") {
    val largeText = "x" * 10240
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(largeText)))

    val config = CompressionConfig(
      minSize = 1024,
      preferredEncodings = List(ContentEncoding.Brotli, ContentEncoding.Gzip, ContentEncoding.Deflate)
    )
    val compressedHandler = Middleware.compression(config).apply(handler)

    val serverConfig = HttpServerConfig.localhost.withPort(0)

    val testProgram = for {
      server <- HttpServer.create(serverConfig, compressedHandler)
      addr <- server.start

      clientConfig = HttpClientConfig.default.withAcceptEncoding(List(ContentEncoding.Gzip))

      result <- HttpClient.scoped(clientConfig) { client =>
        for {
          uri <- parseUri(s"http://localhost:${addr.port}/test")
          request = Request.get(uri)
          response <- client.send(request)
        } yield {
          assertEquals(response.body.asString(Charset.UTF8), largeText)
          assert(response.headers.getFirst(HeaderNames.ContentEncoding).isEmpty)
        }
      }

      _ <- server.shutdown
    } yield result

    testProgram.assertSuccess
  }

  test("Integration - client without Accept-Encoding gets uncompressed response") {
    val largeText = "x" * 10240
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(largeText)))
    val compressedHandler = Middleware.compression(CompressionConfig.default).apply(handler)

    val serverConfig = HttpServerConfig.localhost.withPort(0)

    val testProgram = for {
      server <- HttpServer.create(serverConfig, compressedHandler)
      addr <- server.start

      clientConfig = HttpClientConfig.default
        .withAutomaticDecompression(false)
        .withAcceptEncoding(List.empty)

      result <- HttpClient.scoped(clientConfig) { client =>
        for {
          uri <- parseUri(s"http://localhost:${addr.port}/test")
          request = Request.get(uri)
          response <- client.send(request)
        } yield {
          assertEquals(response.body.length, 10240)
          assertEquals(response.body.asString(Charset.UTF8), largeText)
          assert(response.headers.getFirst(HeaderNames.ContentEncoding).isEmpty)
        }
      }

      _ <- server.shutdown
    } yield result

    testProgram.assertSuccess
  }
}
