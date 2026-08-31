package net.ghoula.eru.http.client

import munit.FunSuite

import java.nio.file.Files

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.server.*

/** HTTP/2 client integration tests over the library's own TLS + ALPN server.
  *
  * These were pointed at https://httpbin.org, which made CI dependent on a third-party site's
  * availability (a run failed with 503s while httpbin was down). They now run against a local
  * `HttpServer` with the same TLS + ALPN h2 negotiation, so the suite is hermetic and
  * deterministic.
  */
class H2IntegrationSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  private val (keystorePath, keystorePassword) = {
    val generated = TestKeystores.generateSelfSignedKeystore()
    sys.addShutdownHook { Files.deleteIfExists(generated._1): Unit }
    generated
  }

  override def afterAll(): Unit = {
    try EruRuntime.shared.cleanup()
    catch { case _: Exception => () }
    Files.deleteIfExists(keystorePath): Unit
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

  private val tlsConfig = TlsConfig.default.copy(
    keyStorePath = Some(keystorePath.toString),
    keyStorePassword = Some(keystorePassword)
  )

  private def serverConfig: HttpServerConfig =
    HttpServerConfig.localhost
      .withPort(0)
      .withTls(tlsConfig)
      .copy(enableHttp2 = true, acceptorThreads = 1)

  private val httpHandler: RequestHandler = req => {
    val path = req.uri.path
    val bodyE: Eru[HttpError, Body] =
      if req.method == Method.POST then
        BodyDecoder[String]
          .decode(req.body)
          .mapError(HttpError.BodyDecodeError.apply)
          .flatMap(text => Eru.succeed(Body.text(s"""{"echoed": "$text"}""")))
      else if path == "/get" then Eru.succeed(Body.text(s"""{"url": "https://localhost$path"}"""))
      else if path == "/large" then Eru.succeed(Body.text(("x" * 1100) + s"""{"gzipped": true, "path": "$path"}"""))
      else Eru.succeed(Body.text("ok"))
    bodyE.map(body => Response.ok(body))
  }

  private def withH2Server[A](
    wrapHandler: Middleware = identity
  )(
    body: (String, Int, HttpClientConfig) => Eru[HttpError, A]
  ): Eru[HttpError, A] =
    HttpServer.scoped(serverConfig)(wrapHandler.apply(httpHandler)) { server =>
      server.start.flatMap { address =>
        body(address.host, address.port, HttpClientConfig.default.withTls(TlsConfig.insecure))
      }
    }

  private def clientSend(config: HttpClientConfig, request: Request[Body]): Eru[HttpError, Response[Bytes]] =
    HttpClient.scoped(config) { client => client.send(request) }

  test("HTTP/2 - simple GET request with HTTP/2") {
    val testProgram = withH2Server() { (host, port, clientConfig) =>
      for {
        uri <- parseUri(s"https://$host:$port/status/200")
        response <- clientSend(clientConfig, Request.get(uri))
      } yield {
        assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
        assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")
      }
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - GET request returning JSON with HTTP/2") {
    val testProgram = withH2Server() { (host, port, clientConfig) =>
      for {
        uri <- parseUri(s"https://$host:$port/get")
        response <- clientSend(clientConfig, Request.get(uri))
      } yield {
        assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
        assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

        val bodyText = response.body.asString(Charset.UTF8)
        assert(bodyText.contains("\"url\""), "Body should contain JSON with url field")
      }
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - POST request with body") {
    val testProgram = withH2Server() { (host, port, clientConfig) =>
      for {
        uri <- parseUri(s"https://$host:$port/post")
        request <- Request
          .post(uri, Body.Text("hello http2", Some(MediaType.textPlain)))
          .addHeader("Content-Type", "text/plain")
          .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC 9110")))
        response <- clientSend(clientConfig, request)
      } yield {
        assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
        assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

        val bodyText = response.body.asString(Charset.UTF8)
        assert(bodyText.contains("hello http2"), "Body should contain echoed data")
      }
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - multiple requests on same connection") {
    val testProgram = withH2Server() { (host, port, clientConfig) =>
      for {
        uri1 <- parseUri(s"https://$host:$port/status/200")
        uri2 <- parseUri(s"https://$host:$port/get")
        response1 <- clientSend(clientConfig, Request.get(uri1))
        response2 <- clientSend(clientConfig, Request.get(uri2))
      } yield {
        assert(response1.status.isSuccessful, s"Request 1: Expected 2xx, got ${response1.status.value}")
        assert(response2.status.isSuccessful, s"Request 2: Expected 2xx, got ${response2.status.value}")

        assertEquals(response1.version, HttpVersion.HTTP_2_0, "Request 1 should use HTTP/2")
        assertEquals(response2.version, HttpVersion.HTTP_2_0, "Request 2 should use HTTP/2")
      }
    }

    testProgram.assertSuccess
  }

  test("HTTP/2 - handles compressed response") {
    val testProgram = withH2Server(Middleware.compression()) { (host, port, clientConfig) =>
      for {
        uri <- parseUri(s"https://$host:$port/large")
        response <- clientSend(clientConfig, Request.get(uri))
      } yield {
        assert(response.status.isSuccessful, s"Expected 2xx, got ${response.status.value}")
        assertEquals(response.version, HttpVersion.HTTP_2_0, "Expected HTTP/2 response")

        val bodyText = response.body.asString(Charset.UTF8)
        assert(bodyText.contains("\"gzipped\""), "Body should contain gzipped field")
      }
    }

    testProgram.assertSuccess
  }
}
