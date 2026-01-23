package net.ghoula.eru.http

import munit.FunSuite

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.sys.process.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.websocket.*
import net.ghoula.eru.prelude.*

class WebSocketIntegrationSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def munitTimeout: Duration = 30.seconds

  private def generateSelfSignedKeystore(): (java.nio.file.Path, String) = {
    val password = "testpassword"
    val tempDir = Files.createTempDirectory("ws-tls-test-")
    val tempFile = tempDir.resolve("keystore.p12")

    val cmd = Seq(
      "keytool",
      "-genkeypair",
      "-alias",
      "server",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "365",
      "-keystore",
      tempFile.toString,
      "-storepass",
      password,
      "-keypass",
      password,
      "-dname",
      "CN=localhost,O=Test,L=Test,C=US",
      "-storetype",
      "PKCS12"
    )

    val exitCode = cmd.!
    if exitCode != 0 then {
      throw new RuntimeException(s"keytool failed with exit code $exitCode")
    }

    (tempFile, password)
  }

  test("WebSocket echo server and client") {
    val testPort = 18765

    val wsHandler: WebSocketHandler = conn => {
      def loop(): Eru[WebSocketError | HttpError, Unit] = {
        conn.receive().flatMap {
          case WebSocketMessage.Text(text) => conn.sendText(s"echo: $text").flatMap(_ => loop())
          case WebSocketMessage.Binary(data) => conn.sendBinary(data).flatMap(_ => loop())
        }
      }
      loop().mapError {
        case e: WebSocketError.ConnectionClosed if e.clean => e
        case e => e
      }.attempt
        .map(_ => ())
    }

    val httpHandler: RequestHandler = _ => {
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("HTTP OK", None, Charset.UTF8)
        )
      )
    }

    val serverConfig = HttpServerConfig.default.copy(port = testPort)
    val wsConfig = WebSocketServerConfig.default

    val test =
      WebSocketServer.create(serverConfig, wsConfig)(wsHandler)(httpHandler).bracket(server => server.shutdown) {
        server =>
          for {
            _ <- server.start
            uri <- Uri.parse(s"ws://localhost:$testPort/ws").mapError(e => HttpError.InvalidUri(e))
            result <- WebSocketClient.scoped(uri, WebSocketClientConfig.default, Headers.empty) { conn =>
              for {
                _ <- conn.sendText("hello")
                msg1 <- conn.receive()
                binaryData = Bytes.fromString("binary test", Charset.UTF8)
                _ <- conn.sendBinary(binaryData)
                msg2 <- conn.receive()
                _ <- Eru.effectTotal {
                  msg1 match {
                    case WebSocketMessage.Text(text) => assertEquals(text, "echo: hello")
                    case _ => fail("Expected text message")
                  }
                  msg2 match {
                    case WebSocketMessage.Binary(data) => assert(data === binaryData)
                    case _ => fail("Expected binary message")
                  }
                }
                _ <- conn.close()
              } yield ()
            }
          } yield result
      }

    test.assertSuccess
  }

  test("WebSocket ping-pong") {
    val testPort = 18766

    val wsHandler: WebSocketHandler = conn => conn.receive().attempt.map(_ => ())

    val httpHandler: RequestHandler = _ => {
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("HTTP OK", None, Charset.UTF8)
        )
      )
    }

    val serverConfig = HttpServerConfig.default.copy(port = testPort)

    val test =
      WebSocketServer.create(serverConfig)(wsHandler)(httpHandler).bracket(server => server.shutdown) { server =>
        for {
          _ <- server.start
          uri <- Uri.parse(s"ws://localhost:$testPort/ws").mapError(e => HttpError.InvalidUri(e))
          result <- WebSocketClient.scoped(uri, WebSocketClientConfig.default, Headers.empty) { conn =>
            for {
              _ <- conn.sendPing(Bytes.fromString("ping data", Charset.UTF8))
              _ <- conn.close()
            } yield ()
          }
        } yield result
      }

    test.assertSuccess
  }

  test("HTTP requests still work with WebSocket handler") {
    val testPort = 18767

    val wsHandler: WebSocketHandler = _ => Eru.unit

    val httpHandler: RequestHandler = _ => {
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text("Hello from HTTP", None, Charset.UTF8)
        )
      )
    }

    val serverConfig = HttpServerConfig.default.copy(port = testPort)

    val test: Eru[HttpError, Unit] =
      WebSocketServer.create(serverConfig)(wsHandler)(httpHandler).bracket(server => server.shutdown) { server =>
        for {
          _ <- server.start
          httpClient <- HttpClient.create
          uri <- Uri.parse(s"http://localhost:$testPort/").mapError(e => HttpError.InvalidUri(e))
          request: Request[Body] = Request(
            method = Method.GET,
            uri = uri,
            headers = Headers.empty,
            body = Body.Empty
          )
          response <- httpClient.send(request)
          _ <- httpClient.shutdown
        } yield {
          assertEquals(response.status, StatusCode.Ok)
          assert(response.body.asString(Charset.UTF8).contains("Hello from HTTP"))
        }
      }

    test.assertSuccess
  }

  test("WebSocket over wss:// (TLS)") {
    val (keystorePath, password) = generateSelfSignedKeystore()
    val testPort = 18768

    try {
      val wsHandler: WebSocketHandler = conn => {
        conn
          .receive()
          .flatMap {
            case WebSocketMessage.Text(text) => conn.sendText(s"secure: $text")
            case _ => Eru.unit
          }
          .attempt
          .map(_ => ())
      }

      val httpHandler: RequestHandler =
        _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("HTTP OK", None, Charset.UTF8)))

      val tlsConfig = TlsConfig.default
        .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
      val serverConfig = HttpServerConfig.default
        .copy(port = testPort)
        .withTls(tlsConfig)

      val test =
        WebSocketServer.create(serverConfig)(wsHandler)(httpHandler).bracket(server => server.shutdown) { server =>
          for {
            _ <- server.start
            uri <- Uri.parse(s"wss://localhost:$testPort/ws").mapError(e => HttpError.InvalidUri(e))
            clientConfig = WebSocketClientConfig.default.copy(tlsConfig = Some(TlsConfig.insecure))
            result <- WebSocketClient.scoped(uri, clientConfig, Headers.empty) { conn =>
              for {
                _ <- conn.sendText("hello TLS")
                msg <- conn.receive()
                _ <- Eru.effectTotal {
                  msg match {
                    case WebSocketMessage.Text(text) => assertEquals(text, "secure: hello TLS")
                    case _ => fail("Expected text message")
                  }
                }
                _ <- conn.close()
              } yield ()
            }
          } yield result
        }

      test.assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("WebSocket large message streaming") {
    val testPort = 18769
    val largeMessageSize = 100 * 1024 // 100KB

    val wsHandler: WebSocketHandler = conn => {
      conn
        .receive()
        .flatMap {
          case WebSocketMessage.Binary(data) => conn.sendBinary(data)
          case _ => Eru.unit
        }
        .attempt
        .map(_ => ())
    }

    val httpHandler: RequestHandler =
      _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("HTTP OK", None, Charset.UTF8)))

    val serverConfig = HttpServerConfig.default.copy(port = testPort)
    val wsConfig = WebSocketServerConfig.highThroughput

    val test =
      WebSocketServer.create(serverConfig, wsConfig)(wsHandler)(httpHandler).bracket(server => server.shutdown) {
        server =>
          for {
            _ <- server.start
            uri <- Uri.parse(s"ws://localhost:$testPort/ws").mapError(e => HttpError.InvalidUri(e))
            clientConfig = WebSocketClientConfig.highThroughput
            largeData = Bytes.fromArray(Array.fill(largeMessageSize)(0x42.toByte))
            result <- WebSocketClient.scoped(uri, clientConfig, Headers.empty) { conn =>
              for {
                _ <- conn.sendBinary(largeData)
                msg <- conn.receive()
                _ <- Eru.effectTotal {
                  msg match {
                    case WebSocketMessage.Binary(data) =>
                      assertEquals(data.length, largeMessageSize)
                      assertEquals(data.toArray(0), 0x42.toByte)
                      assertEquals(data.toArray(largeMessageSize - 1), 0x42.toByte)
                    case _ => fail("Expected binary message")
                  }
                }
                _ <- conn.close()
              } yield ()
            }
          } yield result
      }

    test.assertSuccess
  }

  test("WebSocket message fragmentation") {
    val testPort = 18770
    val messageSize = 1000

    val wsHandler: WebSocketHandler = conn => {
      conn
        .receive()
        .flatMap {
          case WebSocketMessage.Text(text) => conn.sendText(text)
          case _ => Eru.unit
        }
        .attempt
        .map(_ => ())
    }

    val httpHandler: RequestHandler =
      _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("HTTP OK", None, Charset.UTF8)))

    val serverConfig = HttpServerConfig.default.copy(port = testPort)
    val wsConfig = WebSocketServerConfig.default.copy(maxFrameSize = 100) // Fragment at 100 bytes

    val test =
      WebSocketServer.create(serverConfig, wsConfig)(wsHandler)(httpHandler).bracket(server => server.shutdown) {
        server =>
          for {
            _ <- server.start
            uri <- Uri.parse(s"ws://localhost:$testPort/ws").mapError(e => HttpError.InvalidUri(e))
            clientConfig = WebSocketClientConfig.default.copy(maxFrameSize = 100) // Fragment at 100 bytes
            largeText = "A" * messageSize
            result <- WebSocketClient.scoped(uri, clientConfig, Headers.empty) { conn =>
              for {
                _ <- conn.sendText(largeText)
                msg <- conn.receive()
                _ <- Eru.effectTotal {
                  msg match {
                    case WebSocketMessage.Text(text) =>
                      assertEquals(text.length, messageSize)
                      assertEquals(text, largeText)
                    case _ => fail("Expected text message")
                  }
                }
                _ <- conn.close()
              } yield ()
            }
          } yield result
      }

    test.assertSuccess
  }
}
