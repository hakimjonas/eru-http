package net.ghoula.eru.http

import munit.FunSuite

import java.nio.file.Files
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** HTTP/2 flow control tests.
  *
  * These tests verify that the HTTP/2 implementation correctly handles flow control for large
  * request/response bodies that exceed the initial flow control window (65KB).
  *
  * The key issue being tested: Without proper concurrent frame reading, large bodies would cause a
  * deadlock because:
  *   1. Sender blocks waiting for WINDOW_UPDATE when flow control window is exhausted
  *   2. No fiber is reading frames to receive the WINDOW_UPDATE
  *   3. DEADLOCK
  *
  * The fix: Fork response/request handlers so the main loop continues reading frames (including
  * WINDOW_UPDATE), which unblocks the waiting sender.
  *
  * The multi-size test uses response sizes straddling the 65KB boundary — 50KB below it, 80KB and
  * 120KB above — so both pre-window and post-window responses are exercised.
  */
class H2FlowControlSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def munitTimeout: Duration = 60.seconds

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  test("HTTP/2 - large response body triggers flow control (>65KB)") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    val testPort = 18790

    try {
      val largeBody = "x" * 100000

      val handler: RequestHandler = _ => {
        Eru.succeed(
          Response(
            status = StatusCode.Ok,
            headers = Headers.empty,
            body = Body.Text(largeBody, None, Charset.UTF8)
          )
        )
      }

      val serverTls = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.default
        .copy(port = testPort)
        .withTls(serverTls)

      val test: Eru[HttpError, Unit] = HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            _ <- server.start

            client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.insecure))

            uri <- Uri.parse(s"https://localhost:$testPort/large").mapError(e => HttpError.InvalidUri(e))
            request = Request.get(uri)

            response <- client.send(request)

            _ <- client.shutdown
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

            val bodyText = response.body.asString(Charset.UTF8)
            assertEquals(bodyText.length, 100000, "Response body should be 100KB")
            assertEquals(bodyText, largeBody, "Response body content should match")
          }
        }

      test.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - large request body triggers flow control (>65KB)") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    val testPort = 18791

    try {
      val largeBody = "y" * 100000

      val handler: RequestHandler = req => {
        val bodyLength = req.body match {
          case Body.Binary(bytes, _) => bytes.length
          case Body.Text(text, _, cs) => text.getBytes(cs.toJavaCharset).length
          case _ => 0
        }
        Eru.succeed(
          Response(
            status = StatusCode.Ok,
            headers = Headers.empty,
            body = Body.Text(s"received $bodyLength bytes", None, Charset.UTF8)
          )
        )
      }

      val serverTls = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.default
        .copy(port = testPort)
        .withTls(serverTls)

      val test: Eru[HttpError, Unit] = HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            _ <- server.start

            client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.insecure))

            uri <- Uri.parse(s"https://localhost:$testPort/upload").mapError(e => HttpError.InvalidUri(e))
            request <- Request
              .post(uri, Body.Text(largeBody, Some(MediaType.textPlain)))
              .addHeader("Content-Type", "text/plain")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC 9110")))

            response <- client.send(request)

            _ <- client.shutdown
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

            val bodyText = response.body.asString(Charset.UTF8)
            assertEquals(bodyText, "received 100000 bytes", "Server should receive full request body")
          }
        }

      test.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - large QUERY body triggers flow control (>65KB)") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    val testPort = 18793

    try {
      val largeQuery = "q" * 100000

      val handler: RequestHandler = req => {
        val bodyLength = req.body match {
          case Body.Binary(bytes, _) => bytes.length
          case Body.Text(text, _, cs) => text.getBytes(cs.toJavaCharset).length
          case _ => 0
        }
        Eru.succeed(
          Response(
            status = StatusCode.Ok,
            headers = Headers.empty,
            body = Body.Text(s"received $bodyLength bytes", None, Charset.UTF8)
          )
        )
      }

      val serverTls = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.default
        .copy(port = testPort)
        .withTls(serverTls)

      val test: Eru[HttpError, Unit] = HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            _ <- server.start

            client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.insecure))

            uri <- Uri.parse(s"https://localhost:$testPort/search").mapError(e => HttpError.InvalidUri(e))
            request = Request.query(uri, Body.Text(largeQuery, Some(MediaType.textPlain)))

            response <- client.send(request)

            _ <- client.shutdown
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")
            assertEquals(response.body.asString(Charset.UTF8), "received 100000 bytes")
          }
        }

      test.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - multiple concurrent large responses") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    val testPort = 18792

    try {
      val sizes = List(50000, 80000, 120000)

      val handler: RequestHandler = req => {
        val size = req.uri.query.flatMap { q =>
          q.split("&").find(_.startsWith("size=")).map(_.drop(5).toInt)
        }.getOrElse(1000)

        val body = "z" * size
        Eru.succeed(
          Response(
            status = StatusCode.Ok,
            headers = Headers.empty,
            body = Body.Text(body, None, Charset.UTF8)
          )
        )
      }

      val serverTls = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.default
        .copy(port = testPort)
        .withTls(serverTls)

      val test: Eru[HttpError, Unit] = HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            _ <- server.start

            client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.insecure))

            results <- Eru.foreach(sizes) { size =>
              for {
                uri <- Uri.parse(s"https://localhost:$testPort/?size=$size").mapError(e => HttpError.InvalidUri(e))
                response <- client.send(Request.get(uri))
              } yield (size, response)
            }

            _ <- client.shutdown
          } yield {
            results.foreach { case (expectedSize, response) =>
              assertEquals(response.status, StatusCode.Ok)
              assertEquals(response.version, HttpVersion.HTTP_2_0, s"Expected HTTP/2 for size $expectedSize")
              assertEquals(
                response.body.asString(Charset.UTF8).length,
                expectedSize,
                s"Response body should be $expectedSize bytes"
              )
            }
          }
        }

      test.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - QUERY method carries request content over h2") {
    val testPort = 18768
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text(s"${req.method.value}:${req.body match {
              case Body.Binary(bytes, _) => bytes.asString(Charset.UTF8)
              case _ => ""
            }}")
        )
      )

    val serverTls = TlsConfig.default
      .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
    val serverConfig = HttpServerConfig.default
      .copy(port = testPort)
      .withTls(serverTls)

    try {
      val test: Eru[HttpError, Unit] = HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            _ <- server.start
            client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.insecure))
            uri <- Uri.parse(s"https://localhost:$testPort/search").mapError(e => HttpError.InvalidUri(e))
            request = Request.query(uri, Body.text("find me", MediaType.textPlain))
            response <- client.send(request)
            _ <- client.shutdown
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")
            assertEquals(response.body.asString(Charset.UTF8), "QUERY:find me")
          }
        }

      test.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }
}
