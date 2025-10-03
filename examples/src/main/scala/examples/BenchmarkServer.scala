package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*

/** Simple HTTP server for performance benchmarking.
  *
  * Provides minimal endpoints for performance testing:
  *   - GET / - Minimal "Hello, World!" response
  *   - GET /json - JSON response
  *   - GET /plaintext - Plain text response
  */
object BenchmarkServer {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    val port = if args.length > 0 then args(0).toInt else 8080
    runServer(port)
  }

  def runServer(port: Int = 8080): Unit = {
    val program = for {
      server <- HttpServer.create(
        HttpServerConfig(host = "0.0.0.0", port = port, backlog = 1024),
        handler
      )
      address <- server.start
      _ <- Eru.effect {
        println(s"Benchmark server started at http://${address.host}:${address.port}")
        println("Endpoints:")
        println("  GET /           - Hello World")
        println("  GET /plaintext  - Plain text")
        println("  GET /json       - JSON response")
        println("\nPress Ctrl+C to stop")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      _ <- Eru.never // Keep running until interrupted
    } yield ()

    program.attempt.unsafeRunSync()
  }

  val handler: RequestHandler = req =>
    req.uri.path match {
      case "/" | "/plaintext" =>
        Response.ok(Body.text("Hello, World!"))

      case "/json" =>
        val json = """{"message":"Hello, World!"}"""
        for {
          body <- Eru.succeed(Body.text(json, MediaType.applicationJson))
          response <- Response.ok(body).withContentType(MediaType.applicationJson)
        } yield response

      case _ =>
        Response.notFound(Body.text("Not Found"))
    }
}
