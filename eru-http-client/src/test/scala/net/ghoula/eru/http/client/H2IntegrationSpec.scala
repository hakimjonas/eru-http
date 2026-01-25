package net.ghoula.eru.http.client

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.TestHelpers.*

/** End-to-end HTTP/2 integration tests.
  *
  * These tests verify HTTP/2 communication against real HTTP/2 servers:
  *   1. ALPN negotiation selects h2 protocol
  *   2. Request/response cycle works over HTTP/2
  *   3. Response indicates HTTP/2 was used
  *
  * Note: These tests require network access and may fail if the external servers are unavailable.
  */
class H2IntegrationSpec extends FunSuite {

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

  test("HTTP/2 - simple GET request with HTTP/2") {
    // httpbin.org supports HTTP/2
    val testProgram = for {
      uri <- parseUri("https://httpbin.org/status/200")
      request = Request.get(uri)
      client <- HttpClient.create(
        HttpClientConfig.default
          .withTls(TlsConfig.default) // Use default TLS (verifies certificates)
      )
      response <- client.send(request)
    } yield {
      // Verify response is successful
      assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")

      // Verify HTTP/2 was used (response should have HTTP/2 version)
      assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - GET request to httpbin.org with HTTP/2") {
    // httpbin.org also supports HTTP/2
    val testProgram = for {
      uri <- parseUri("https://httpbin.org/get")
      request = Request.get(uri)
      client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.default))
      response <- client.send(request)
    } yield {
      assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
      assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

      // Verify body contains expected JSON
      val bodyText = response.body.asString(Charset.UTF8)
      assert(bodyText.contains("\"url\""), "Body should contain JSON with url field")
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - POST request with body") {
    val testProgram = for {
      uri <- parseUri("https://httpbin.org/post")
      request <- Request
        .post(uri, Body.Text("hello http2", Some(MediaType.textPlain)))
        .addHeader("Content-Type", "text/plain")
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC 9110")))
      client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.default))
      response <- client.send(request)
    } yield {
      assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
      assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

      // httpbin.org echoes the data back in the response
      val bodyText = response.body.asString(Charset.UTF8)
      assert(bodyText.contains("hello http2"), "Body should contain echoed data")
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - multiple requests on same connection") {
    val testProgram = for {
      uri1 <- parseUri("https://httpbin.org/status/200")
      uri2 <- parseUri("https://httpbin.org/headers")
      client <- HttpClient.create(HttpClientConfig.default.withTls(TlsConfig.default))

      // Make multiple requests - HTTP/2 should reuse the connection
      response1 <- client.send(Request.get(uri1))
      response2 <- client.send(Request.get(uri2))
    } yield {
      assert(response1.status.isSuccessful, s"Request 1: Expected 2xx, got ${response1.status.value}")
      assert(response2.status.isSuccessful, s"Request 2: Expected 2xx, got ${response2.status.value}")

      assertEquals(response1.version, HttpVersion.HTTP_2_0, "Request 1 should use HTTP/2")
      assertEquals(response2.version, HttpVersion.HTTP_2_0, "Request 2 should use HTTP/2")
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - handles compressed response") {
    val testProgram = for {
      uri <- parseUri("https://httpbin.org/gzip")
      request = Request.get(uri)
      client <- HttpClient.create(
        HttpClientConfig.default
          .withTls(TlsConfig.default)
          .withAutomaticDecompression(true) // Enable automatic decompression
      )
      response <- client.send(request)
    } yield {
      assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
      assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

      // httpbin.org/gzip returns JSON with "gzipped": true
      val bodyText = response.body.asString(Charset.UTF8)
      assert(bodyText.contains("\"gzipped\""), "Body should contain gzipped field")
    }

    testProgram.assertSuccess
  }
}
