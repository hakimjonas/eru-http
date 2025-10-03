package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*

/** Server with middleware chain demonstrating composability.
  *
  * Demonstrates:
  *   - Creating middleware
  *   - Composing multiple middleware
  *   - CORS middleware
  *   - Logging middleware
  *   - Request ID middleware
  *   - Error handling middleware
  *   - Custom middleware
  */
object ServerWithMiddleware {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    runServer()
  }

  def runServer(): Unit = {
    println("=== Server with Middleware ===\n")

    // Create the middleware chain
    val app = createMiddlewareChain(handler)

    val program = for {
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(8080), app)
      address <- server.start

      _ <- Eru.effect {
        println(s"Server started at http://${address.host}:${address.port}")
        println("\nMiddleware chain:")
        println("  1. Logging (logs all requests/responses)")
        println("  2. CORS (adds CORS headers)")
        println("  3. Request ID (adds X-Request-ID)")
        println("  4. Timing (measures request duration)")
        println("  5. Error Handler (catches errors)")
        println("\nTry:")
        println(s"  curl http://localhost:8080/")
        println(s"  curl http://localhost:8080/api/data")
        println(s"  curl http://localhost:8080/error")
        println(s"  curl -X OPTIONS http://localhost:8080/")
        println("\nPress Enter to stop...")
        scala.io.StdIn.readLine()
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- server.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Server stopped successfully")
      case Result.Failure(error) =>
        println(s"Server error: $error")
    }
  }

  /** Creates the complete middleware chain.
    *
    * Middleware is applied from outermost to innermost: logging -> CORS -> requestId -> timing ->
    * errorHandler -> handler
    */
  def createMiddlewareChain(handler: RequestHandler): RequestHandler = {
    Middleware
      .logging(msg => println(s"[LOG] $msg")) // First: log requests/responses
      .andThen(Middleware.corsPermissive) // Second: add CORS headers
      .andThen(requestIdMiddleware) // Third: add request IDs
      .andThen(timingMiddleware) // Fourth: measure timing
      .andThen(Middleware.errorHandlerDefault) // Fifth: handle errors
      .apply(handler) // Finally: apply to handler
  }

  /** Custom middleware to add request ID to responses.
    */
  val requestIdMiddleware: Middleware = handler => req =>
    for {
      // Generate request ID
      requestId <- Eru.effect(java.util.UUID.randomUUID().toString).mapError(e =>
        HttpError.NetworkError(e.getMessage, Some(e))
      )

      // Process request
      response <- handler(req)

      // Add request ID to response
      responseWithId <- response
        .setHeader("X-Request-ID", requestId)
        .mapError {
          case e: HeaderName.InvalidHeaderName =>
            HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue =>
            HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
    } yield responseWithId

  /** Custom middleware to measure request timing.
    */
  val timingMiddleware: Middleware = handler => req =>
    for {
      // Record start time
      start <- Eru.effect(System.currentTimeMillis()).mapError(e =>
        HttpError.NetworkError(e.getMessage, Some(e))
      )

      // Process request
      response <- handler(req)

      // Record end time and add header
      end <- Eru.effect(System.currentTimeMillis()).mapError(e =>
        HttpError.NetworkError(e.getMessage, Some(e))
      )
      duration = end - start

      responseWithTiming <- response
        .setHeader("X-Response-Time", s"${duration}ms")
        .mapError {
          case e: HeaderName.InvalidHeaderName =>
            HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue =>
            HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
    } yield responseWithTiming

  /** Simple request handler.
    */
  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      case (Method.GET, "/") =>
        Response.ok(Body.text("Hello from middleware server!"))

      case (Method.GET, "/api/data") =>
        val data = """{"message":"This response went through all middleware","success":true}"""
        for {
          body <- Eru.succeed(Body.text(data, MediaType.applicationJson))
          response <- Response.ok(body).withContentType(MediaType.applicationJson)
        } yield response

      case (Method.GET, "/error") =>
        // This will trigger error handling middleware
        Eru.fail(
          HttpError.InvalidRequest(InvalidRequest("Simulated error for testing", "Example"))
        )

      case (Method.GET, "/slow") =>
        // Simulate slow endpoint to see timing middleware in action
        for {
          _ <- Eru
            .effect(Thread.sleep(500))
            .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
          response <- Response.ok(Body.text("This took a while"))
        } yield response

      case _ =>
        Response.notFound(Body.text("Not found"))
    }
}
