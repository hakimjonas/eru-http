package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** A small HTTP/1.1 server with three routes: GET /, GET /health, and POST /echo. Runs until
  * interrupted (Ctrl+C).
  *
  * Run it: sbt "examples/runMain examples.SimpleServer" Try it: curl http://localhost:8080/ ; curl
  * -X POST http://localhost:8080/echo -d 'Hello'
  */
object SimpleServer {

  given runtime: EruRuntime = EruRuntime.create()

  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      case (Method.GET, "/") =>
        Eru.succeed(Response.ok(Body.text("Hello, World!")))
      case (Method.GET, "/health") =>
        Eru.succeed(Response.ok(Body.text("ok")))
      case (Method.POST, "/echo") =>
        for body <- BodyDecoder[String]
            .decode(req.body)
            .mapError(HttpError.BodyDecodeError.apply)
        yield Response.ok(Body.text(s"Echo: $body"))
      case _ =>
        Eru.succeed(
          Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not found"))
        )
    }

  def main(args: Array[String]): Unit = {
    val server = HttpServer
      .create(HttpServerConfig.localhost.withPort(8080), handler)
      .unsafeRunSync()
    val address = server.start.unsafeRunSync()
    println(s"listening on ${address.host}:${address.port} - Ctrl+C to stop")
    new java.util.concurrent.CountDownLatch(1).await() // block so the server keeps serving; Ctrl+C to stop
  }
}
