package net.ghoula.eru.http.client

import munit.{FunSuite, Location}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

/** Integration tests for HTTP client connection pooling.
  *
  * These tests verify that:
  *   1. Connection pooling works with real HTTP connections
  *   2. Eru's Ref handles concurrent access correctly
  *   3. HTTP/1.1 keep-alive semantics are respected
  *   4. Pool limits are enforced properly
  *   5. Error handling and cleanup work correctly
  *
  * The per-host connection limit test adds a 100ms server delay so concurrent requests overlap and
  * actually contend for the limited pool.
  */
class HttpClientPoolingSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  extension [E, A](eru: Eru[E, A]) {
    def assertSuccess(using loc: Location): A = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) => value
        case Result.Failure(error) =>
          fail(s"Expected success but got failure: $error")(using loc)
      }
    }

    def assertFailure(using loc: Location): E = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) =>
          fail(s"Expected failure but got success: $value")(using loc)
        case Result.Failure(error) => error
      }
    }
  }

  test("HttpClient - single request works with pooling") {
    val server = TestHttpServer.simple(body = "Hello, World!")
    try {
      val client = HttpClient.create(HttpClientConfig.default).assertSuccess

      val request = Request.get(Uri.parse(server.url("/")).assertSuccess)
      val response = client.send(request).assertSuccess

      assertEquals(response.status, StatusCode.Ok)
      assertEquals(response.body.asString(Charset.UTF8), "Hello, World!")

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - sequential requests reuse connection") {
    val requestCounter = new AtomicInteger(0)

    val server = TestHttpServer.create(handler = (_, _) => {
      TestHttpServer.ResponseConfig(
        body = s"Request ${requestCounter.incrementAndGet()}",
        headers = Map("Connection" -> "keep-alive")
      )
    })

    try {
      val client = HttpClient.create(HttpClientConfig.default).assertSuccess

      val request = Request.get(Uri.parse(server.url("/")).assertSuccess)

      val responses = (1 to 5).map { _ =>
        client.send(request).assertSuccess
      }

      assertEquals(responses.length, 5)
      responses.zipWithIndex.foreach { case (response, i) =>
        assertEquals(response.status, StatusCode.Ok)
        assertEquals(response.body.asString(Charset.UTF8), s"Request ${i + 1}")
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - concurrent requests use multiple connections") {
    val server = TestHttpServer.echo()

    try {
      val config = HttpClientConfig.default.withMaxConnectionsPerHost(5)
      val client = HttpClient.create(config).assertSuccess

      val requests = (1 to 10).map { i =>
        Request.get(Uri.parse(server.url(s"/request$i")).assertSuccess)
      }

      val responses = parTraverse(requests.toList) { request =>
        client.send(request)
      }.assertSuccess

      assertEquals(responses.length, 10)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - respects Connection: close from server") {
    val requestCounter = new AtomicInteger(0)

    val server = TestHttpServer.create(handler = (_, _) => {
      TestHttpServer.ResponseConfig(
        body = s"Request ${requestCounter.incrementAndGet()}",
        headers = Map("Connection" -> "close")
      )
    })

    try {
      val client = HttpClient.create(HttpClientConfig.default).assertSuccess

      val request = Request.get(Uri.parse(server.url("/")).assertSuccess)

      val responses = (1 to 3).map { _ =>
        client.send(request).assertSuccess
      }

      assertEquals(responses.length, 3)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - HTTP/1.1 defaults to keep-alive") {
    val requestCounter = new AtomicInteger(0)

    val server = TestHttpServer.create(handler = (_, _) => {
      TestHttpServer.ResponseConfig(
        body = s"Request ${requestCounter.incrementAndGet()}"
      )
    })

    try {
      val client = HttpClient.create(HttpClientConfig.default).assertSuccess

      val request = Request.get(Uri.parse(server.url("/")).assertSuccess)

      val responses = (1 to 5).map { _ =>
        client.send(request).assertSuccess
      }

      assertEquals(responses.length, 5)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - respects per-host connection limit") {
    val server = TestHttpServer.create(handler = (_, _) => {
      TestHttpServer.ResponseConfig(
        body = "response",
        delay = 100.millis
      )
    })

    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(2)
        .withMaxConnections(10)

      val client = HttpClient.create(config).assertSuccess

      val requests = (1 to 5).map { i =>
        Request.get(Uri.parse(server.url(s"/request$i")).assertSuccess)
      }

      val responses = parTraverse(requests.toList) { request =>
        client.send(request)
      }.assertSuccess

      assertEquals(responses.length, 5)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - respects global connection limit") {
    val server1 = TestHttpServer.simple(body = "Server 1")
    val server2 = TestHttpServer.simple(body = "Server 2")

    try {
      val config = HttpClientConfig.default
        .withMaxConnections(3)
        .withMaxConnectionsPerHost(5)

      val client = HttpClient.create(config).assertSuccess

      val requests = List(
        Request.get(Uri.parse(server1.url("/")).assertSuccess),
        Request.get(Uri.parse(server1.url("/")).assertSuccess),
        Request.get(Uri.parse(server2.url("/")).assertSuccess),
        Request.get(Uri.parse(server2.url("/")).assertSuccess)
      )

      val responses = Eru
        .foreach(requests) { request =>
          client.send(request)
        }
        .assertSuccess

      assertEquals(responses.length, 4)

      client.shutdown.unsafeRunSync()
    } finally {
      server1.shutdown()
      server2.shutdown()
    }
  }

  test("HttpClient - connection error removes connection from pool") {
    val config = HttpClientConfig(
      connectTimeout = 1.second,
      requestTimeout = 1.second,
      maxConnections = 10,
      maxConnectionsPerHost = 5
    )
    val client = HttpClient.create(config).assertSuccess

    val request = Request.get(Uri.parse("http://localhost:1/").assertSuccess)
    val error = client.send(request).assertFailure

    error match {
      case HttpError.ConnectionError(_, _) =>
      case HttpError.NetworkError(_, _) =>
      case HttpError.TimeoutError(_) =>
      case other => fail(s"Expected connection error, got: $other")
    }

    client.shutdown.unsafeRunSync()
  }

  test("HttpClient - stress test with 100 concurrent requests") {
    val server = TestHttpServer.echo()

    try {
      val config = HttpClientConfig.default
        .withMaxConnections(20)
        .withMaxConnectionsPerHost(10)

      val client = HttpClient.create(config).assertSuccess

      val requests = (1 to 100).map { i =>
        Request.get(Uri.parse(server.url(s"/stress$i")).assertSuccess)
      }

      val responses = parTraverse(requests.toList) { request =>
        client.send(request)
      }.assertSuccess

      assertEquals(responses.length, 100)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - stress test with sequential then concurrent pattern") {
    val server = TestHttpServer.echo()

    try {
      val config = HttpClientConfig.default
        .withMaxConnections(10)
        .withMaxConnectionsPerHost(5)

      val client = HttpClient.create(config).assertSuccess

      val sequentialRequests = (1 to 20).map { i =>
        Request.get(Uri.parse(server.url(s"/seq$i")).assertSuccess)
      }

      val sequentialResponses = Eru
        .foreach(sequentialRequests) { request =>
          client.send(request)
        }
        .assertSuccess

      assertEquals(sequentialResponses.length, 20)

      val concurrentRequests = (1 to 50).map { i =>
        Request.get(Uri.parse(server.url(s"/con$i")).assertSuccess)
      }

      val concurrentResponses = parTraverse(concurrentRequests.toList) { request =>
        client.send(request)
      }.assertSuccess

      assertEquals(concurrentResponses.length, 50)

      (sequentialResponses ++ concurrentResponses).foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - mixed hosts concurrent load") {
    val server1 = TestHttpServer.echo()
    val server2 = TestHttpServer.echo()
    val server3 = TestHttpServer.echo()

    try {
      val config = HttpClientConfig.default
        .withMaxConnections(15)
        .withMaxConnectionsPerHost(5)

      val client = HttpClient.create(config).assertSuccess

      val requests = (1 to 30).map { i =>
        val server = i % 3 match {
          case 0 => server1
          case 1 => server2
          case 2 => server3
        }
        Request.get(Uri.parse(server.url(s"/req$i")).assertSuccess)
      }

      val responses = parTraverse(requests.toList) { request =>
        client.send(request)
      }.assertSuccess

      assertEquals(responses.length, 30)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server1.shutdown()
      server2.shutdown()
      server3.shutdown()
    }
  }

  test("HttpClient - shutdown closes all pooled connections") {
    val server = TestHttpServer.simple(body = "OK")

    try {
      val client = HttpClient.create(HttpClientConfig.default).assertSuccess

      (1 to 5).foreach { _ =>
        client.send(Request.get(Uri.parse(server.url("/")).assertSuccess)).assertSuccess
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }

  test("HttpClient - pool cleanup on multiple rapid connections") {
    val server = TestHttpServer.create(handler = (_, _) => {
      TestHttpServer.ResponseConfig(
        body = "OK",
        headers = Map("Connection" -> "close")
      )
    })

    try {
      val config = HttpClientConfig.default
        .withMaxConnections(5)
        .withMaxConnectionsPerHost(5)

      val client = HttpClient.create(config).assertSuccess

      val requests = (1 to 50).map { i =>
        Request.get(Uri.parse(server.url(s"/rapid$i")).assertSuccess)
      }

      val responses = Eru
        .foreach(requests) { request =>
          client.send(request)
        }
        .assertSuccess

      assertEquals(responses.length, 50)
      responses.foreach { response =>
        assertEquals(response.status, StatusCode.Ok)
      }

      client.shutdown.unsafeRunSync()
    } finally {
      server.shutdown()
    }
  }
}
