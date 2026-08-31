package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Middleware composition: logging, CORS, request IDs, a custom timing middleware, and a default
  * error handler. Runs until interrupted (Ctrl+C).
  *
  * Run it: sbt "examples/runMain examples.ServerWithMiddleware" Try it: curl http://localhost:8080/
  * ; curl -X OPTIONS http://localhost:8080/ (CORS preflight)
  */
object ServerWithMiddleware {

  given runtime: EruRuntime = EruRuntime.create()

  val handler: RequestHandler = req =>
    req.uri.path match {
      case "/" =>
        Eru.succeed(Response.ok(Body.text("Hello, World!")))
      case "/api/data" =>
        Eru.succeed(Response.ok(Body.text("{\"value\": 42}")))
      case "/boom" =>
        Eru.fail(HttpError.ConnectionError("deliberate failure for the error handler", None))
      case _ =>
        Eru.succeed(
          Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not found"))
        )
    }

  /** A middleware is a handler transform: it can run effects before and after the inner handler.
    */
  val timingMiddleware: Middleware = handler =>
    req =>
      for
        start <- Eru
          .effect(System.nanoTime())
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
        response <- handler(req)
        _ <- Eru
          .effect(
            println(f"${req.method.value}%-6s ${req.uri.path} took ${(System.nanoTime() - start) / 1e6}%.2f ms")
          )
          .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      yield response

  val app: RequestHandler = Middleware
    .logging(line => println(s"[log] $line"))
    .andThen(Middleware.corsPermissive)
    .andThen(Middleware.requestId())
    .andThen(timingMiddleware)
    .andThen(Middleware.errorHandlerDefault)
    .apply(handler)

  def main(args: Array[String]): Unit = {
    val server = HttpServer
      .create(HttpServerConfig.localhost.withPort(8080), app)
      .unsafeRunSync()
    val address = server.start.unsafeRunSync()
    println(s"listening on ${address.host}:${address.port} - Ctrl+C to stop")
    new java.util.concurrent.CountDownLatch(1).await() // block so the server keeps serving; Ctrl+C to stop
  }
}
