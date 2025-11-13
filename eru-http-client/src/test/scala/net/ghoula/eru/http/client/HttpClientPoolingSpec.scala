package net.ghoula.eru.http.client

import munit.FunSuite

import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.defaultRuntime

import TestHelpers.*

/** Integration tests for HTTP client connection pooling.
  *
  * These tests validate end-to-end connection reuse behavior with real HTTP requests/responses.
  */
class HttpClientPoolingSpec extends FunSuite {

  // Helper to parse URI
  private def parseUri(url: String): Eru[HttpError, Uri] =
    Uri
      .parse(url)
      .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid URI: ${e.reason}", e.rfc)))

  // ===== Basic Pooling Tests =====

  test("HttpClient - single request works with pooling") {
    val server = TestHttpServer.simple(body = "Hello, pooling!")
    try {
      HttpClient
        .scoped() { client =>
          for {
            uri <- parseUri(server.url())
            request = Request.get(uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), "Hello, pooling!")
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sequential requests reuse connection") {
    val requestCounter = new AtomicInteger(0)
    val server = TestHttpServer.create(handler = (method, path) =>
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        body = s"Request ${requestCounter.incrementAndGet()}",
        headers = Map(
          HeaderNames.Connection -> "keep-alive" // Explicit keep-alive
        )
      )
    )

    try {
      val config = HttpClientConfig.default.withMaxConnectionsPerHost(1)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // Make 5 sequential requests
            response1 <- client.send(Request.get(uri))
            _ = assertEquals(response1.status, StatusCode.Ok)
            _ = assertEquals(response1.body.asString(Charset.UTF8), "Request 1")

            response2 <- client.send(Request.get(uri))
            _ = assertEquals(response2.status, StatusCode.Ok)
            _ = assertEquals(response2.body.asString(Charset.UTF8), "Request 2")

            response3 <- client.send(Request.get(uri))
            _ = assertEquals(response3.status, StatusCode.Ok)
            _ = assertEquals(response3.body.asString(Charset.UTF8), "Request 3")

            response4 <- client.send(Request.get(uri))
            _ = assertEquals(response4.status, StatusCode.Ok)
            _ = assertEquals(response4.body.asString(Charset.UTF8), "Request 4")

            response5 <- client.send(Request.get(uri))
            _ = assertEquals(response5.status, StatusCode.Ok)
            _ = assertEquals(response5.body.asString(Charset.UTF8), "Request 5")

          } yield {
            // All 5 requests succeeded
            assertEquals(requestCounter.get(), 5)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - respects Connection: close from server") {
    val requestCounter = new AtomicInteger(0)
    val server = TestHttpServer.create(handler = (method, path) => {
      val count = requestCounter.incrementAndGet()
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        body = s"Request $count",
        headers = Map(
          HeaderNames.Connection -> (if count == 2 then "close" else "keep-alive")
        )
      )
    })

    try {
      val config = HttpClientConfig.default.withMaxConnectionsPerHost(2)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // First request - keep-alive
            response1 <- client.send(Request.get(uri))
            _ = assertEquals(response1.status, StatusCode.Ok)
            _ = assert(response1.headers.getFirst(HeaderNames.Connection).exists(_.value == "keep-alive"))

            // Second request - server sends Connection: close
            response2 <- client.send(Request.get(uri))
            _ = assertEquals(response2.status, StatusCode.Ok)
            _ = assert(response2.headers.getFirst(HeaderNames.Connection).exists(_.value == "close"))

            // Third request - should create new connection
            response3 <- client.send(Request.get(uri))
            _ = assertEquals(response3.status, StatusCode.Ok)

          } yield {
            assertEquals(requestCounter.get(), 3)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - handles connection errors gracefully") {
    val server = TestHttpServer.simple(body = "test")
    val serverPort = server.port
    try {
      val config = HttpClientConfig.default.withRequestTimeout(2.seconds)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(s"http://localhost:$serverPort/")

            // First request succeeds
            response1 <- client.send(Request.get(uri))
            _ = assertEquals(response1.status, StatusCode.Ok)

            // Shutdown server (simulates connection failure)
            _ <- Eru.effect(server.shutdown()).mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

            // Second request should fail (connection error)
            result2 <- client.send(Request.get(uri)).attempt

          } yield {
            // Should get a connection error
            result2 match {
              case Result.Failure(_: HttpError) =>
                () // Expected
              case Result.Success(_) =>
                fail("Expected connection error after server shutdown")
            }
          }
        }
        .assertSuccess
    } finally {
      // Server already shutdown in test
    }
  }

  // ===== Concurrent Request Tests =====

  test("HttpClient - concurrent requests use multiple connections") {
    val requestCounter = new AtomicInteger(0)
    val server = TestHttpServer.create(handler = (method, path) =>
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        body = s"Request ${requestCounter.incrementAndGet()}",
        delay = 100.milliseconds // Slow response to force concurrency
      )
    )

    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(5)
        .withRequestTimeout(10.seconds)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // Launch 10 concurrent requests
            fibers <- Eru.foreach(1 to 10) { _ =>
              client.send(Request.get(uri)).fork
            }

            // Wait for all to complete
            responses <- Eru.foreach(fibers)(_.join)

          } yield {
            // All requests should succeed
            assertEquals(responses.length, 10)
            assert(responses.forall(_.status == StatusCode.Ok))
            assertEquals(requestCounter.get(), 10)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - pool limits prevent over-connection") {
    val activeConnections = new AtomicInteger(0)
    val maxConcurrent = new AtomicInteger(0)

    val server = TestHttpServer.create(handler = (method, path) => {
      val active = activeConnections.incrementAndGet()
      val max = maxConcurrent.get()
      if active > max then maxConcurrent.set(active)

      try {
        TestHttpServer.ResponseConfig(
          status = StatusCode.Ok,
          body = "OK",
          delay = 200.milliseconds
        )
      } finally {
        activeConnections.decrementAndGet()
      }
    })

    try {
      val maxPerHost = 3
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(maxPerHost)
        .withRequestTimeout(10.seconds)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // Launch 10 concurrent requests
            fibers <- Eru.foreach(1 to 10) { _ =>
              client.send(Request.get(uri)).fork
            }

            // Wait for all to complete
            responses <- Eru.foreach(fibers)(_.join)

          } yield {
            // All requests should succeed
            assertEquals(responses.length, 10)
            assert(responses.forall(_.status == StatusCode.Ok))

            // Max concurrent should not exceed pool limit
            assert(
              maxConcurrent.get() <= maxPerHost,
              s"Max concurrent ${maxConcurrent.get()} exceeded pool limit $maxPerHost"
            )
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== Stress Tests =====

  test("HttpClient - 100 concurrent requests with small pool") {
    val requestCounter = new AtomicInteger(0)
    val server = TestHttpServer.create(handler = (method, path) =>
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        body = s"Request ${requestCounter.incrementAndGet()}"
      )
    )

    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(10)
        .withMaxConnections(50)
        .withRequestTimeout(30.seconds)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // Launch 100 concurrent requests
            fibers <- Eru.foreach(1 to 100) { _ =>
              client.send(Request.get(uri)).fork
            }

            // Wait for all to complete
            responses <- Eru.foreach(fibers)(_.join)

          } yield {
            // All requests should succeed
            assertEquals(responses.length, 100)
            assert(responses.forall(_.status == StatusCode.Ok))
            assertEquals(requestCounter.get(), 100)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - mixed sequential and concurrent requests") {
    val requestCounter = new AtomicInteger(0)
    val server = TestHttpServer.create(handler = (method, path) =>
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        body = s"Request ${requestCounter.incrementAndGet()}"
      )
    )

    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(5)
        .withRequestTimeout(10.seconds)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // 10 sequential requests
            _ <- Eru.foreach(1 to 10) { _ =>
              client.send(Request.get(uri))
            }

            // 20 concurrent requests
            fibers1 <- Eru.foreach(1 to 20) { _ =>
              client.send(Request.get(uri)).fork
            }
            _ <- Eru.foreach(fibers1)(_.join)

            // 10 more sequential
            _ <- Eru.foreach(1 to 10) { _ =>
              client.send(Request.get(uri))
            }

            // 30 more concurrent
            fibers2 <- Eru.foreach(1 to 30) { _ =>
              client.send(Request.get(uri)).fork
            }
            responses <- Eru.foreach(fibers2)(_.join)

          } yield {
            // Total: 10 + 20 + 10 + 30 = 70 requests
            assertEquals(requestCounter.get(), 70)
            assert(responses.forall(_.status == StatusCode.Ok))
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }

  // ===== HTTP/1.1 Keep-Alive Semantics Tests =====

  test("HttpClient - HTTP/1.1 defaults to keep-alive") {
    val requestCounter = new AtomicInteger(0)
    val server = TestHttpServer.create(handler = (method, path) =>
      TestHttpServer.ResponseConfig(
        status = StatusCode.Ok,
        body = s"Request ${requestCounter.incrementAndGet()}"
        // No explicit Connection header - should default to keep-alive for HTTP/1.1
      )
    )

    try {
      val config = HttpClientConfig.default.withMaxConnectionsPerHost(1)

      HttpClient
        .scoped(config) { client =>
          for {
            uri <- parseUri(server.url())

            // Make 3 sequential requests
            response1 <- client.send(Request.get(uri))
            _ = assertEquals(response1.status, StatusCode.Ok)

            response2 <- client.send(Request.get(uri))
            _ = assertEquals(response2.status, StatusCode.Ok)

            response3 <- client.send(Request.get(uri))
            _ = assertEquals(response3.status, StatusCode.Ok)

          } yield {
            // All 3 requests succeeded, connection should be reused
            assertEquals(requestCounter.get(), 3)
          }
        }
        .assertSuccess
    } finally {
      server.shutdown()
    }
  }
}
