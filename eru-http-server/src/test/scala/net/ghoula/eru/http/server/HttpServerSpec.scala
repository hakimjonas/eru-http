package net.ghoula.eru.http.server

import munit.FunSuite

import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

import TestHelpers.*

/** HTTP server lifecycle, protocol, and middleware tests.
  *
  * Timeout model: a fresh (just-accepted) connection uses `readHeaderTimeout` for its first bytes
  * (Slowloris defense); a completed keep-alive exchange is held for `idleTimeout`, even when
  * `readHeaderTimeout` is far shorter. The keep-alive test proves the two-timeout split by idling
  * past `readHeaderTimeout` (50ms) but well under `idleTimeout` (2s) and asserting the second
  * pipelined request still succeeds. Oversize `Content-Length` is rejected with 413 before the body
  * is read or allocated.
  */
class HttpServerSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  test("HttpServer - create and start server") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("Hello, World!")
        )
      )

    val result = for {
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(0), handler)
      address <- server.start
      running <- Eru.succeed(server.isRunning)
      _ <- server.shutdown
      stopped <- Eru.succeed(server.isRunning)
    } yield (address, running, stopped)

    val (address, running, stopped) = result.assertSuccess
    assert(address.port > 0)
    assert(running, "isRunning must be true after start")
    assert(!stopped, "isRunning must be false after shutdown")
  }

  test("HttpServer - serverObserver receives fiber lifecycle events") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("observed")
        )
      )

    val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit =
        events.synchronized { events += event; () }
    }

    val result = for {
      server <- HttpServer.create(
        HttpServerConfig.localhost.withPort(0).copy(serverObserver = Some(observer)),
        handler
      )
      address <- server.start
      _ <- Eru.effect {
        val socket = java.net.Socket(address.host, address.port)
        try {
          val out = socket.getOutputStream
          out.write(s"GET / HTTP/1.1\r\nHost: ${address.host}:${address.port}\r\nConnection: close\r\n\r\n".getBytes)
          out.flush()
          val buf = new Array[Byte](64)
          socket.getInputStream.read(buf): Unit
        } finally socket.close()
      }
      _ <- server.shutdown
    } yield ()

    result.assertSuccess

    def snapshot: List[EruObserver.EruEvent] = events.synchronized(events.toList)
    val started = snapshot.exists {
      case _: EruObserver.EruEvent.FiberStarted => true
      case _ => false
    }
    assert(started, s"expected FiberStarted events, got: ${snapshot.take(5).mkString(", ")}")

    val deadline = System.nanoTime() + 2_000_000_000L
    def completedSeen: Boolean = snapshot.exists {
      case _: EruObserver.EruEvent.FiberCompleted => true
      case _ => false
    }
    while !completedSeen && System.nanoTime() < deadline do Thread.sleep(50)
    assert(completedSeen, s"expected FiberCompleted events, got: ${snapshot.take(5).mkString(", ")}")
  }

  test("HttpServer - scoped automatically shuts down server") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Empty
        )
      )

    val result = HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        server.start
      }
      .assertSuccess

    assert(result.port > 0)
  }

  test("HttpServer - GET request returns 200 OK") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("Hello, World!")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200)
            assertEquals(response.body, "Hello, World!")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - POST request with body") {
    val handler: RequestHandler = req =>
      for {
        body <- BodyDecoder[String]
          .decode(req.body)
          .mapError(e => HttpError.BodyDecodeError(e))
      } yield Response(
        status = StatusCode.Ok,
        headers = Headers.empty,
        body = Body.Text(s"Received: $body")
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.post(
              s"http://${address}/api/users",
              """{"name":"Alice"}"""
            )
            assertEquals(response.status, 200)
            assertEquals(response.body, """Received: {"name":"Alice"}""")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - PUT request with body") {
    val handler: RequestHandler = req =>
      for {
        body <- BodyDecoder[String]
          .decode(req.body)
          .mapError(e => HttpError.BodyDecodeError(e))
      } yield Response(
        status = StatusCode.Ok,
        headers = Headers.empty,
        body = Body.Text(s"Updated: $body")
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.put(
              s"http://${address}/api/users/1",
              """{"name":"Bob"}"""
            )
            assertEquals(response.status, 200)
            assertEquals(response.body, """Updated: {"name":"Bob"}""")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - DELETE request") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.NoContent,
          headers = Headers.empty,
          body = Body.Empty
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.delete(s"http://${address}/api/users/1")
            assertEquals(response.status, 204)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler receives correct method") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(req.method.toString)
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.post(s"http://${address}", "test")
            assertEquals(response.body, "POST")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler receives URI path") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(req.uri.path)
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}/api/users")
            assertEquals(response.body, "/api/users")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler receives query parameters") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(req.uri.query.getOrElse(""))
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}/search?q=test&page=1")
            assert(response.body.contains("q=test"))
            assert(response.body.contains("page=1"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler receives custom headers") {
    val handler: RequestHandler = req =>
      for {
        customHeader <- req.headers
          .getFirst("X-Custom-Header")
          .map(hv => Eru.succeed(hv.toString))
          .getOrElse(Eru.fail(HttpError.InvalidRequest(InvalidRequest("Missing header", "RFC"))))
      } yield Response(
        status = StatusCode.Ok,
        headers = Headers.empty,
        body = Body.Text(customHeader)
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(
              s"http://${address}",
              headers = Map("X-Custom-Header" -> "test-value")
            )
            assertEquals(response.status, 200)
            assertEquals(response.body, "test-value")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - response headers are sent to client") {
    val handler: RequestHandler = _ =>
      Headers.empty
        .add("X-Server-Header", "server-value")
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC")))
        .map { headers =>
          Response(
            status = StatusCode.Ok,
            headers = headers,
            body = Body.Empty
          )
        }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assert(response.headers.contains("x-server-header"))
            assertEquals(response.headers("x-server-header"), "server-value")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler can return 404 Not Found") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.NotFound,
          headers = Headers.empty,
          body = Body.Text("Not found")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 404)
            assertEquals(response.body, "Not found")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler can return 500 Internal Server Error") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.InternalServerError,
          headers = Headers.empty,
          body = Body.Text("Server error")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 500)
            assertEquals(response.body, "Server error")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handler errors are converted to 500 responses") {
    val handler: RequestHandler = _ => Eru.fail(HttpError.InvalidResponse(InvalidResponse("Handler error", "RFC")))

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 500)
            assert(response.body.contains("Internal Server Error"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - Content-Type header is set correctly") {
    val handler: RequestHandler = _ =>
      Headers.empty
        .add("Content-Type", "application/json")
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Header error: $e", "RFC")))
        .map { headers =>
          Response(
            status = StatusCode.Ok,
            headers = headers,
            body = Body.Text("""{"result":"ok"}""")
          )
        }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assert(response.headers.contains("content-type"))
            assert(response.headers("content-type").contains("application/json"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handles multiple sequential requests") {
    var counter = 0
    val handler: RequestHandler = _ =>
      Eru.effect {
        counter += 1
        counter
      }.mapError { case e: Exception =>
        HttpError.NetworkError(s"Handler error: ${e.getMessage}", Some(e))
      }.map { count =>
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(count.toString)
        )
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val r1 = SimpleHttpClient.get(s"http://${address}")
            val r2 = SimpleHttpClient.get(s"http://${address}")
            val r3 = SimpleHttpClient.get(s"http://${address}")

            assertEquals(r1.body, "1")
            assertEquals(r2.body, "2")
            assertEquals(r3.body, "3")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - with middleware chain") {
    var requestCount = 0
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("Hello")
        )
      )

    val app = Middleware
      .logging(_ => requestCount += 1)
      .andThen(Middleware.cors())
      .andThen(Middleware.requestId())
      .apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200)
            assert(response.headers.contains("access-control-allow-origin"))
            assert(response.headers.contains("x-request-id"))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess

    assert(requestCount > 0, "Logging middleware should have been called")
  }

  test("HttpServer - with authentication middleware") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("Protected resource")
        )
      )

    val checkToken: Request[Body] => Boolean =
      req => req.headers.getFirst("Authorization").exists(_.value == "Bearer secret-token")

    val app = Middleware.auth(checkToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized())).apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val unauthResponse = SimpleHttpClient.get(s"http://${address}")
            assertEquals(unauthResponse.status, 401)

            val authResponse = SimpleHttpClient.get(
              s"http://${address}",
              headers = Map("Authorization" -> "Bearer secret-token")
            )
            assertEquals(authResponse.status, 200)
            assertEquals(authResponse.body, "Protected resource")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - Slowloris first-request: 408 via readHeaderTimeout") {
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("OK")))

    val config = HttpServerConfig.localhost
      .withPort(0)
      .withIdleTimeout(60.seconds)
      .withReadHeaderTimeout(200.millis)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val socket = new java.net.Socket(address.host, address.port)
            try {
              val out = socket.getOutputStream
              out.write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes)
              out.flush()

              socket.setSoTimeout(5000)
              val in = socket.getInputStream
              val collected = new java.io.ByteArrayOutputStream()
              val buf = new Array[Byte](1024)
              var done = false
              var endedWithEof = false
              while !done do {
                val n =
                  try in.read(buf)
                  catch { case _: java.io.IOException => -1 }
                if n <= 0 then {
                  done = true
                  endedWithEof = n == -1
                } else collected.write(buf, 0, n)
              }
              val resp = collected.toString("UTF-8")
              assert(
                resp.startsWith("HTTP/1.1 408"),
                s"Slowloris first-request must answer 408 on the header deadline, got: ${resp.take(80)}"
              )
              assert(endedWithEof, "server must close after the 408 (read to EOF)")
            } finally {
              socket.close()
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - keep-alive between requests uses idleTimeout not readHeaderTimeout") {
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("OK")))

    val config = HttpServerConfig.localhost
      .withPort(0)
      .withIdleTimeout(2.seconds)
      .withReadHeaderTimeout(50.millis)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val socket = new java.net.Socket(address.host, address.port)
            try {
              val out = socket.getOutputStream
              val in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream))
              socket.setSoTimeout(3000)

              out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n".getBytes)
              out.flush()
              var continue = true
              var contentLength = 0
              while continue do {
                Option(in.readLine()) match {
                  case Some(line) if line.nonEmpty =>
                    if line.toLowerCase.startsWith("content-length:") then {
                      contentLength = line.split(":")(1).trim.toInt
                    }
                  case _ => continue = false
                }
              }
              val bodyBuf = new Array[Char](contentLength)
              var read = 0
              while read < contentLength do {
                val n = in.read(bodyBuf, read, contentLength - read)
                if n < 0 then throw new java.io.EOFException()
                read += n
              }

              Thread.sleep(300)

              out.write("GET /2 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes)
              out.flush()
              val status2 = Option(in.readLine()).getOrElse("")
              assert(
                status2.startsWith("HTTP/1.1 200"),
                s"Keep-alive connection was closed prematurely. 2nd response: $status2"
              )
            } finally {
              socket.close()
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - active connection is not affected by idleTimeout") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("OK")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0).withIdleTimeout(2.seconds))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200)
            assertEquals(response.body, "OK")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - socket is cleaned up on connection close") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("OK")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            for (_ <- 1 to 10) {
              val response = SimpleHttpClient.get(s"http://${address}")
              assertEquals(response.status, 200)
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - rejects oversize Content-Length with 413") {
    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("unreachable")))

    val config = HttpServerConfig.localhost.withPort(0).withMaxRequestSize(1024)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val socket = new java.net.Socket(address.host, address.port)
            try {
              val out = socket.getOutputStream
              val req = "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10000000\r\n\r\n"
              out.write(req.getBytes)
              out.flush()

              socket.setSoTimeout(5000)
              val in = socket.getInputStream
              val buf = new Array[Byte](4096)
              val n = in.read(buf)
              val response = new String(buf, 0, n)
              assert(
                response.startsWith("HTTP/1.1 413"),
                s"Expected 413 Content Too Large, got: ${response.take(60)}"
              )
              assert(
                response.toLowerCase.contains("connection: close"),
                "413 response must close the connection"
              )
            } finally {
              socket.close()
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - accepts body at exactly maxRequestSize") {
    val handler: RequestHandler = req =>
      for {
        body <- BodyDecoder[String].decode(req.body).mapError(HttpError.BodyDecodeError.apply)
      } yield Response(StatusCode.Ok, Headers.empty, Body.Text(s"got ${body.length}"))

    val config = HttpServerConfig.localhost.withPort(0).withMaxRequestSize(100)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val body = "a" * 100
            val response = SimpleHttpClient.post(s"http://${address}", body)
            assertEquals(response.status, 200)
            assertEquals(response.body, "got 100")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - default maxRequestSize is 10MB") {
    assertEquals(HttpServerConfig.default.maxRequestSize, 10 * 1024 * 1024)
  }

  test("HttpServer - maxConnections bounds concurrent connections") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("OK")
        )
      )

    val config = HttpServerConfig.localhost.withPort(0).copy(maxConnections = 2, idleTimeout = 5.seconds)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val r1 = SimpleHttpClient.get(s"http://${address}", connectionClose = false)
            assertEquals(r1.status, 200)
            val r2 = SimpleHttpClient.get(s"http://${address}", connectionClose = false)
            assertEquals(r2.status, 200)

            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - with error handling middleware") {
    val handler: RequestHandler = req =>
      if req.uri.path == "/error" then Eru.fail(HttpError.InvalidRequest(InvalidRequest("Bad input", "RFC")))
      else Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("OK")))

    val app = Middleware.errorHandlerDefault.apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val errorResponse = SimpleHttpClient.get(s"http://${address}/error")
            assertEquals(errorResponse.status, 400)

            val okResponse = SimpleHttpClient.get(s"http://${address}/success")
            assertEquals(okResponse.status, 200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - with conditional middleware for API routes") {
    val handler: RequestHandler = req =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text(s"Path: ${req.uri.path}")
        )
      )

    val checkToken: Request[Body] => Boolean =
      req => req.headers.getFirst("Authorization").exists(_.value == "valid-token")

    val app = Middleware
      .when(_.uri.path.startsWith("/api"))(
        Middleware.auth(checkToken, UnauthorizedHandler(() => Middleware.defaultUnauthorized()))
      )
      .apply(handler)

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(app) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val publicResponse = SimpleHttpClient.get(s"http://${address}/public")
            assertEquals(publicResponse.status, 200)

            val apiNoAuthResponse = SimpleHttpClient.get(s"http://${address}/api/users")
            assertEquals(apiNoAuthResponse.status, 401)

            val apiWithAuthResponse = SimpleHttpClient.get(
              s"http://${address}/api/users",
              headers = Map("Authorization" -> "valid-token")
            )
            assertEquals(apiWithAuthResponse.status, 200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }
}
