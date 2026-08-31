package net.ghoula.eru.http.autobahn

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.websocket.*
import net.ghoula.eru.prelude.*

/** WebSocket echo server for Autobahn|Testsuite conformance testing.
  *
  * This server implements a simple echo protocol:
  *   - Text messages are echoed back as text
  *   - Binary messages are echoed back as binary
  *   - Ping/Pong handled automatically by the WebSocket layer
  *   - Close frames trigger proper close handshake
  *
  * Run with: sbt "examples/runMain net.ghoula.eru.http.autobahn.AutobahnEchoServer"
  *
  * Then run Autobahn fuzzing client against it: docker run -it --rm \ -v ./autobahn/config:/config
  * \ -v ./autobahn/reports:/reports \ --add-host=host.docker.internal:host-gateway \
  * crossbario/autobahn-testsuite \ wstest -m fuzzingclient -s /config/fuzzingclient.json
  */
object AutobahnEchoServer {

  def main(args: Array[String]): Unit = {
    given runtime: EruRuntime = EruRuntime.shared

    val port = args.headOption.flatMap(_.toIntOption).getOrElse(9002)

    println(s"Starting Autobahn echo server on port $port...")
    println("Press Ctrl+C to stop")
    println()
    println("Run Autobahn tests with:")
    println("  docker run -it --rm \\")
    println("    -v ./autobahn/config:/config \\")
    println("    -v ./autobahn/reports:/reports \\")
    println("    --add-host=host.docker.internal:host-gateway \\")
    println("    crossbario/autobahn-testsuite \\")
    println("    wstest -m fuzzingclient -s /config/fuzzingclient.json")
    println()

    val wsHandler: WebSocketHandler = conn => {
      def loop(): Eru[WebSocketError | HttpError, Unit] = {
        conn.receive().flatMap {
          case WebSocketMessage.Text(text) =>
            conn.sendText(text).flatMap(_ => loop())
          case WebSocketMessage.Binary(data) =>
            conn.sendBinary(data).flatMap(_ => loop())
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
          body = Body.Text("Autobahn Echo Server - Connect via WebSocket", None, Charset.UTF8)
        )
      )
    }

    val serverConfig = HttpServerConfig.default.copy(port = port)
    val wsConfig = WebSocketServerConfig.default.copy(
      maxMessageSize = 16 * 1024 * 1024,
      maxFrameSize = 16 * 1024 * 1024
    )

    val server = WebSocketServer
      .create(serverConfig, wsConfig)(wsHandler)(httpHandler)
      .flatMap { server =>
        server.start.flatMap { addr =>
          println(s"Server started on ${addr.host}:${addr.port}")
          Eru.effect {
            Thread.currentThread().join()
          }
        }
      }

    server.attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        System.err.println(s"Server failed: $e")
        System.exit(1)
    }
  }
}
