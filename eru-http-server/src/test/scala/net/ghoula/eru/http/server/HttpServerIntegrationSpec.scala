package net.ghoula.eru.http.server

import munit.FunSuite

import java.io.{BufferedReader, InputStreamReader}
import java.net.Socket
import java.util.concurrent.{CountDownLatch, Executors}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

import TestHelpers.*

/** Integration tests for HTTP server protocol compliance.
  *
  * These tests verify HTTP/1.1 protocol compliance, connection management, and behavior under
  * concurrent load.
  */
class HttpServerIntegrationSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  test("HttpServer - sends Connection: keep-alive header by default") {
    val handler: RequestHandler = _ =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("Hello")
        )
      )

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val response = SimpleHttpClient.get(s"http://${address}", connectionClose = false)
            assertEquals(response.status, 200)

            val connectionHeader = response.headers.get("connection")
            assert(connectionHeader.isDefined, "Connection header should be present")
            assertEquals(connectionHeader.get.toLowerCase, "keep-alive")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - reuses TCP connection for multiple requests") {
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
            Using.resource(new Socket(address.host, address.port)) { socket =>
              val out = socket.getOutputStream
              val in = new BufferedReader(new InputStreamReader(socket.getInputStream))

              out.write("GET / HTTP/1.1\r\n".getBytes)
              out.write(s"Host: ${address.host}:${address.port}\r\n".getBytes)
              out.write("Connection: keep-alive\r\n".getBytes)
              out.write("\r\n".getBytes)
              out.flush()

              val response1 = readHttpResponse(in)
              assert(response1.contains("HTTP/1.1 200"), s"Expected 200 OK, got: ${response1.take(100)}")
              assert(
                response1.toLowerCase.contains("connection: keep-alive"),
                s"First response should have keep-alive. Got:\n$response1"
              )

              out.write("GET / HTTP/1.1\r\n".getBytes)
              out.write(s"Host: ${address.host}:${address.port}\r\n".getBytes)
              out.write("Connection: keep-alive\r\n".getBytes)
              out.write("\r\n".getBytes)
              out.flush()

              val response2 = readHttpResponse(in)
              assert(
                response2.contains("HTTP/1.1 200"),
                s"Expected 200 OK on reused connection, got: ${response2.take(100)}"
              )
              assert(response2.toLowerCase.contains("connection: keep-alive"), "Second response should have keep-alive")
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - closes connection when Connection: close requested") {
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
            Using.resource(new Socket(address.host, address.port)) { socket =>
              val out = socket.getOutputStream
              val in = new BufferedReader(new InputStreamReader(socket.getInputStream))

              out.write("GET / HTTP/1.1\r\n".getBytes)
              out.write(s"Host: ${address.host}:${address.port}\r\n".getBytes)
              out.write("Connection: close\r\n".getBytes)
              out.write("\r\n".getBytes)
              out.flush()

              val response = readHttpResponse(in)
              assert(response.contains("HTTP/1.1 200"), "Should get 200 OK")
              assert(
                response.toLowerCase.contains("connection: close"),
                s"Response should have Connection: close. Got:\n$response"
              )

              while in.ready() do { in.read(); () }

              Thread.sleep(200)

              val isClosed = socket.isClosed || socket.isInputShutdown
              if !isClosed then {
                val nextChar = in.read()
                assertEquals(
                  nextChar,
                  -1,
                  s"Connection should be closed after Connection: close, but read returned $nextChar"
                )
              }
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handles concurrent requests correctly") {
    var requestCount = 0
    val handler: RequestHandler = _ =>
      Eru.effect {
        synchronized { requestCount += 1 }
        Thread.sleep(10)
      }.mapError { case e: Exception =>
        HttpError.NetworkError(s"Handler error: ${e.getMessage}", Some(e))
      }.map { _ =>
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("OK")
        )
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val executor = Executors.newFixedThreadPool(10)
            given ExecutionContext = ExecutionContext.fromExecutor(executor)

            try {
              val latch = new CountDownLatch(50)
              (1 to 50).foreach { _ =>
                Future {
                  try {
                    val response = SimpleHttpClient.get(s"http://${address}")
                    assertEquals(response.status, 200)
                  } finally {
                    latch.countDown()
                  }
                }
              }

              val completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
              assert(completed, "All concurrent requests should complete within 10s")

              assertEquals(requestCount, 50, "All concurrent requests should be handled")
            } finally {
              executor.shutdown()
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - maintains separate state for concurrent requests") {
    val handler: RequestHandler = req =>
      Eru.effect {
        val id = req.uri.query.flatMap { q =>
          q.split("&").find(_.startsWith("id=")).map(_.drop(3))
        }.getOrElse("unknown")

        Thread.sleep(50)
        id
      }.mapError { case e: Exception =>
        HttpError.NetworkError(s"Handler error: ${e.getMessage}", Some(e))
      }.map { id =>
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(s"Request $id")
        )
      }

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val executor = Executors.newFixedThreadPool(10)
            given ExecutionContext = ExecutionContext.fromExecutor(executor)

            try {
              val futures = (1 to 20).map { i =>
                Future {
                  val response = SimpleHttpClient.get(s"http://${address}?id=$i")
                  assertEquals(response.status, 200)
                  assertEquals(response.body, s"Request $i", s"Response should match request ID $i")
                }
              }

              import scala.concurrent.Await
              import scala.concurrent.duration.*
              Await.result(Future.sequence(futures), 10.seconds)
            } finally {
              executor.shutdown()
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("HttpServer - handles errors gracefully under concurrent load") {
    val handler: RequestHandler = req =>
      if req.uri.path.contains("error") then
        Eru.fail(HttpError.InvalidRequest(InvalidRequest("Simulated error", "RFC")))
      else Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("OK")))

    HttpServer
      .scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val executor = Executors.newFixedThreadPool(10)
            given ExecutionContext = ExecutionContext.fromExecutor(executor)

            try {
              var successCount = 0
              var errorCount = 0
              val latch = new CountDownLatch(40)

              (1 to 40).foreach { i =>
                Future {
                  try {
                    val path = if i % 2 == 0 then "/error" else "/success"
                    val response = SimpleHttpClient.get(s"http://${address}$path")

                    synchronized {
                      if response.status == 200 then successCount += 1
                      else if response.status == 400 then errorCount += 1
                    }
                  } finally {
                    latch.countDown()
                  }
                }
              }

              latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

              assert(successCount >= 15, s"Should have at least 15 successful requests, got $successCount")
              assert(errorCount >= 15, s"Should have at least 15 error requests, got $errorCount")
            } finally {
              executor.shutdown()
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  private def readHttpResponse(in: BufferedReader): String = {
    val response = new StringBuilder
    var contentLength = 0

    var continue = true
    while continue do {
      Option(in.readLine()) match {
        case Some(line) if line.nonEmpty =>
          if line.contains("Content-Length:") then {
            contentLength = line.split(":")(1).trim.toInt
          }
          response.append(line).append("\n"): Unit
        case _ =>
          continue = false
      }
    }

    response.append("\n")

    if contentLength > 0 then {
      val bodyChars = new Array[Char](contentLength)
      in.read(bodyChars, 0, contentLength)
      response.append(bodyChars)
    }

    response.toString
  }
}
