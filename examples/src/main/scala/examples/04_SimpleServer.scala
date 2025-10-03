package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*

/** Simple HTTP server example.
  *
  * Demonstrates:
  *   - Creating an HTTP server
  *   - Defining request handlers
  *   - Pattern matching on routes
  *   - Handling different HTTP methods
  *   - Decoding request bodies
  *   - Sending various response types
  */
object SimpleServer {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    runServer()
  }

  /** Starts and runs the server.
    */
  def runServer(): Unit = {
    println("=== Simple HTTP Server ===\n")

    val program = for {
      // Create server with handler
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(8080), handler)

      // Start server
      address <- server.start

      // Server is now running
      _ <- Eru.effect {
        println(s"Server started at http://${address.host}:${address.port}")
        println("\nAvailable endpoints:")
        println("  GET  /              - Hello World")
        println("  GET  /health        - Health check")
        println("  GET  /time          - Current time")
        println("  POST /echo          - Echo request body")
        println("  POST /greet         - Greeting with name")
        println("\nTry:")
        println(s"  curl http://localhost:8080/")
        println(s"  curl http://localhost:8080/health")
        println(s"  curl -X POST http://localhost:8080/echo -d 'Hello'")
        println(s"  curl -X POST http://localhost:8080/greet -d 'Alice'")
        println("\nPress Enter to stop...")
        scala.io.StdIn.readLine()
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      // Shutdown server
      _ <- server.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Server stopped successfully")
      case Result.Failure(error) =>
        println(s"Server error: $error")
    }
  }

  /** Request handler that routes requests to appropriate handlers.
    */
  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      case (Method.GET, "/") =>
        handleRoot()

      case (Method.GET, "/health") =>
        handleHealth()

      case (Method.GET, "/time") =>
        handleTime()

      case (Method.POST, "/echo") =>
        handleEcho(req)

      case (Method.POST, "/greet") =>
        handleGreet(req)

      case (Method.GET, path) =>
        handleNotFound(path)

      case (method, path) =>
        handleMethodNotAllowed(method, path)
    }

  /** Handles root endpoint.
    */
  def handleRoot(): Eru[HttpError, Response[Body]] = {
    Response.ok(Body.text("Hello, World!"))
  }

  /** Handles health check endpoint.
    */
  def handleHealth(): Eru[HttpError, Response[Body]] = {
    val healthStatus = s"""{"status":"healthy","timestamp":"${java.time.Instant.now()}"}"""
    for {
      body <- Eru.succeed(Body.text(healthStatus, MediaType.applicationJson))
      response <- Response.ok(body).withContentType(MediaType.applicationJson)
    } yield response
  }

  /** Handles time endpoint.
    */
  def handleTime(): Eru[HttpError, Response[Body]] = {
    val now = java.time.Instant.now()
    Response.ok(Body.text(s"Current time: $now"))
  }

  /** Handles echo endpoint - echoes back the request body.
    */
  def handleEcho(req: Request[Body]): Eru[HttpError, Response[Body]] = {
    for {
      // Decode request body as string
      bodyText <- BodyDecoder[String]
        .decode(req.body)
        .mapError(HttpError.BodyDecodeError.apply)

      // Send it back
      response <- Response.ok(Body.text(s"Echo: $bodyText"))
    } yield response
  }

  /** Handles greet endpoint - greets the person by name.
    */
  def handleGreet(req: Request[Body]): Eru[HttpError, Response[Body]] = {
    for {
      // Decode request body as string (the name)
      name <- BodyDecoder[String]
        .decode(req.body)
        .mapError(HttpError.BodyDecodeError.apply)

      // Create greeting
      greeting = if name.trim.isEmpty then "Hello, stranger!"
      else s"Hello, ${name.trim}!"

      response <- Response.ok(Body.text(greeting))
    } yield response
  }

  /** Handles 404 Not Found.
    */
  def handleNotFound(path: String): Eru[HttpError, Response[Body]] = {
    Response.notFound(Body.text(s"Not found: $path"))
  }

  /** Handles 405 Method Not Allowed.
    */
  def handleMethodNotAllowed(method: Method, path: String): Eru[HttpError, Response[Body]] = {
    // Determine which methods are allowed for this path
    val allowedMethods = path match {
      case "/" | "/health" | "/time" => Set(Method.GET)
      case "/echo" | "/greet" => Set(Method.POST)
      case _ => Set.empty[Method]
    }

    if allowedMethods.isEmpty then handleNotFound(path)
    else Response.methodNotAllowed(allowedMethods)
  }
}
