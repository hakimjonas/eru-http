package net.ghoula.eru.http.client

import munit.FunSuite

import java.nio.file.Files

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.TestHelpers.*
import net.ghoula.eru.http.server.*

/** Integration tests for TLS/SSL support.
  *
  * These tests verify end-to-end HTTPS communication:
  *   1. Server starts with TLS enabled using self-signed certificate
  *   2. Client connects with TlsConfig.insecure (for self-signed certs)
  *   3. Request/response cycle works over encrypted connection
  */
class TlsIntegrationSpec extends FunSuite {

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

  test("TLS - client can connect to HTTPS server with self-signed certificate") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val handler: RequestHandler =
        _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("Hello from HTTPS!")))

      val tlsConfig = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)

      val testProgram = for {
        server <- HttpServer.create(serverConfig, handler)
        addr <- server.start

        clientConfig = HttpClientConfig.default.withInsecureTls

        result <- HttpClient.scoped(clientConfig) { client =>
          for {
            uri <- parseUri(s"https://localhost:${addr.port}/test")
            request = Request.get(uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), "Hello from HTTPS!")
          }
        }

        _ <- server.shutdown
      } yield result

      testProgram.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("TLS - HTTPS request with POST body") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val handler: RequestHandler = request => {
        val bodyText = request.body match {
          case Body.Text(text, _, _) => text
          case Body.Binary(bytes, _) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
          case _ => ""
        }
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(s"Received: $bodyText")))
      }

      val tlsConfig = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)

      val testProgram = for {
        server <- HttpServer.create(serverConfig, handler)
        addr <- server.start

        clientConfig = HttpClientConfig.default.withInsecureTls

        result <- HttpClient.scoped(clientConfig) { client =>
          for {
            uri <- parseUri(s"https://localhost:${addr.port}/submit")
            request = Request.post(uri, Body.text("Secure data"))
            response <- client.send(request)
          } yield {
            assertEquals(response.status, StatusCode.Ok)
            assertEquals(response.body.asString(Charset.UTF8), "Received: Secure data")
          }
        }

        _ <- server.shutdown
      } yield result

      testProgram.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("TLS - multiple HTTPS requests on same connection") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      var requestCount = 0
      val handler: RequestHandler = _ => {
        requestCount += 1
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(s"Request #$requestCount")))
      }

      val tlsConfig = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)

      val testProgram = for {
        server <- HttpServer.create(serverConfig, handler)
        addr <- server.start

        clientConfig = HttpClientConfig.default.withInsecureTls

        result <- HttpClient.scoped(clientConfig) { client =>
          for {
            uri <- parseUri(s"https://localhost:${addr.port}/test")
            response1 <- client.send(Request.get(uri))
            response2 <- client.send(Request.get(uri))
            response3 <- client.send(Request.get(uri))
          } yield {
            assertEquals(response1.body.asString(Charset.UTF8), "Request #1")
            assertEquals(response2.body.asString(Charset.UTF8), "Request #2")
            assertEquals(response3.body.asString(Charset.UTF8), "Request #3")
          }
        }

        _ <- server.shutdown
      } yield result

      testProgram.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("TLS - HTTPS with compression") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val largeText = "x" * 10240
      val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(largeText)))
      val compressedHandler = Middleware.compression(CompressionConfig.default).apply(handler)

      val tlsConfig = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.localhost
        .withPort(0)
        .withTls(tlsConfig)

      val testProgram = for {
        server <- HttpServer.create(serverConfig, compressedHandler)
        addr <- server.start

        clientConfig = HttpClientConfig.default.withInsecureTls

        result <- HttpClient.scoped(clientConfig) { client =>
          for {
            uri <- parseUri(s"https://localhost:${addr.port}/test")
            request = Request.get(uri)
            response <- client.send(request)
          } yield {
            assertEquals(response.body.length, 10240)
            assertEquals(response.body.asString(Charset.UTF8), largeText)
          }
        }

        _ <- server.shutdown
      } yield result

      testProgram.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }
}
