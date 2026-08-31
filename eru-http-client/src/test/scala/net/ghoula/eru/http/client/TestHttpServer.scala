package net.ghoula.eru.http.client

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.{HttpServer, HttpServerConfig}
import net.ghoula.eru.prelude.*

/** Simple HTTP server for testing HTTP client.
  *
  * Native implementation using blocking NIO + Virtual Threads. Supports configurable responses,
  * headers, delays, redirects, and error conditions.
  */
final class TestHttpServer private (
  val port: Int,
  private val server: HttpServer,
  private val runtime: EruRuntime
) {

  def shutdown(): Unit = {
    @annotation.nowarn("msg=unused")
    given EruRuntime = runtime
    server.shutdown.unsafeRunSync()
  }

  def url(path: String = "/"): String = s"http://localhost:$port$path"
}

object TestHttpServer {

  /** Response configuration for test server.
    *
    * When `binaryBody` is set it is served instead of `body`, allowing binary payloads such as
    * compressed data.
    */
  case class ResponseConfig(
    status: StatusCode = StatusCode.Ok,
    body: String = "",
    headers: Map[String, String] = Map.empty,
    delay: Duration = Duration.Zero,
    redirectTo: Option[String] = None,
    redirectStatus: StatusCode = StatusCode.Found,
    binaryBody: Option[Bytes] = None
  )

  private val requestCounter = new AtomicInteger(0)

  /** Creates a test server with configurable response behavior.
    *
    * @param port
    *   Port to bind to (0 for random port)
    * @param handler
    *   Function that maps (method, path) to ResponseConfig
    * @param runtime
    *   Implicit EruRuntime for execution
    */
  def create(
    port: Int = 0,
    handler: (String, String) => ResponseConfig = (_, _) => ResponseConfig()
  )(using runtime: EruRuntime): TestHttpServer = {
    val requestHandler: Request[Body] => Eru[HttpError, Response[Body]] = req => {
      Eru.effect {
        val fullPath = req.uri.path + req.uri.query.fold("")("?" + _)
        val config = handler(req.method.value, fullPath)

        if config.delay > Duration.Zero then {
          Thread.sleep(config.delay.toMillis)
        }

        config.redirectTo match {
          case Some(location) =>
            val redirectHeaders = Headers.empty
              .add(HeaderNames.Location, location)
              .unsafeRunSync()

            Response(
              status = config.redirectStatus,
              headers = redirectHeaders,
              body = Body.Empty
            )

          case None =>
            var responseHeaders = Headers.empty
            config.headers.foreach { case (name, value) =>
              responseHeaders = responseHeaders.add(name, value).unsafeRunSync()
            }

            val responseBody = config.binaryBody match {
              case Some(bytes) => Body.Binary(bytes)
              case None => Body.Text(config.body)
            }

            Response(
              status = config.status,
              headers = responseHeaders,
              body = responseBody
            )
        }
      }.mapError(e => HttpError.NetworkError(s"Test handler error: ${e.getMessage}", Some(e)))
    }

    val config = HttpServerConfig.localhost.withPort(port)

    val startEffect = for {
      srv <- HttpServer.create(config, requestHandler)
      addr <- srv.start
    } yield (srv, addr.port)

    val (server, actualPort) = startEffect.unsafeRunSync()

    new TestHttpServer(actualPort, server, runtime)
  }

  /** Creates a simple test server that returns the same response for all requests.
    */
  def simple(
    port: Int = 0,
    status: StatusCode = StatusCode.Ok,
    body: String = "",
    headers: Map[String, String] = Map.empty
  )(using runtime: EruRuntime): TestHttpServer = {
    create(port, (_, _) => ResponseConfig(status, body, headers))
  }

  /** Creates a test server that echoes back request information.
    */
  def echo(port: Int = 0)(using runtime: EruRuntime): TestHttpServer = {
    create(
      port,
      (method, path) => {
        val bodyJson = s"""{"method":"$method","path":"$path","request":"${requestCounter.incrementAndGet()}"}"""
        ResponseConfig(
          status = StatusCode.Ok,
          body = bodyJson,
          headers = Map("Content-Type" -> "application/json")
        )
      }
    )
  }

  /** Creates a test server that allows full access to the request for echoing.
    */
  def echoWithHeaders(port: Int = 0)(using runtime: EruRuntime): TestHttpServer = {
    val requestHandler: Request[Body] => Eru[HttpError, Response[Body]] = req => {
      for {
        bodyContent <- req.body match {
          case Body.Empty => Eru.succeed("")
          case Body.Text(text, _, _) => Eru.succeed(text)
          case Body.Binary(value, _) => Eru.succeed(value.asString(Charset.UTF8))
          case Body.Stream(_, _, _) => Eru.succeed("")
        }

        headersJson = req.headers.toList.map { case (name, value) =>
          s""""${name.toLowerCase}":"$value""""
        }.mkString(",")

        escapedBody = bodyContent.replaceAll("\"", "\\\\\"")

        fullPath = req.uri.path + req.uri.query.fold("")("?" + _)

        bodyJson =
          if bodyContent.nonEmpty then {
            s"""{"method":"${req.method.value}","path":"$fullPath",$headersJson,"body":"$escapedBody"}"""
          } else {
            s"""{"method":"${req.method.value}","path":"$fullPath",$headersJson}"""
          }

        responseHeaders <- Headers.empty
          .add(HeaderNames.ContentType, "application/json")
          .mapError(e => HttpError.NetworkError(s"Failed to add header: $e", None))

      } yield Response(
        status = StatusCode.Ok,
        headers = responseHeaders,
        body = Body.Text(bodyJson)
      )
    }

    val config = HttpServerConfig.localhost.withPort(port)

    val startEffect = for {
      srv <- HttpServer.create(config, requestHandler)
      addr <- srv.start
    } yield (srv, addr.port)

    val (server, actualPort) = startEffect.unsafeRunSync()

    new TestHttpServer(actualPort, server, runtime)
  }

  /** Creates a test server that returns chunked (Transfer-Encoding: chunked) responses.
    *
    * This is used to test that the client properly handles streaming responses. A streaming body
    * with no Content-Length triggers chunked transfer encoding.
    */
  def chunked(
    port: Int = 0,
    body: String = "Hello from chunked response!"
  )(using runtime: EruRuntime): TestHttpServer = {
    val requestHandler: Request[Body] => Eru[HttpError, Response[Body]] = _ => {
      val bytes = Bytes.fromString(body, Charset.UTF8)
      val chunkStream = ChunkStream.fromBytes(bytes)
      val streamBody = Body.Stream(Eru.succeed(chunkStream), contentLength = None)
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, streamBody))
    }

    val config = HttpServerConfig.localhost.withPort(port)

    val startEffect = for {
      srv <- HttpServer.create(config, requestHandler)
      addr <- srv.start
    } yield (srv, addr.port)

    val (server, actualPort) = startEffect.unsafeRunSync()

    new TestHttpServer(actualPort, server, runtime)
  }
}
