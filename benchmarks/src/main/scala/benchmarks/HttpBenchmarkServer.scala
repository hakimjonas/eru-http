package benchmarks

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*

/** Simple HTTP server for performance benchmarking.
  *
  * Minimal server with basic endpoints for wrk testing.
  */
object HttpBenchmarkServer {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    val port = if args.length > 0 then args(0).toInt else 8080
    runServer(port)
  }

  def runServer(port: Int = 8080): Unit = {
    println("\n=== eru-http Benchmark Server ===\n")

    // Shared data for stateful endpoints
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)

    val handler: RequestHandler = req =>
      req.uri.path match {
        case "/" | "/plaintext" =>
          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty,
              body = Body.Text("Hello, World!")
            )
          )

        case "/json" =>
          val json = """{"message":"Hello, World!"}"""
          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty.add("Content-Type", "application/json").unsafeRunSync(),
              body = Body.Text(json, Some(MediaType.applicationJson))
            )
          )

        case "/json-large" =>
          // 1KB JSON response
          val json = """{"message":"Hello, World!","data":"""" + ("x" * 950) + """"}"""
          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty.add("Content-Type", "application/json").unsafeRunSync(),
              body = Body.Text(json, Some(MediaType.applicationJson))
            )
          )

        case "/large-10kb" =>
          // 10KB text response
          val text = "x" * 10240
          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty,
              body = Body.Text(text)
            )
          )

        case "/large-100kb" =>
          // 100KB streaming response using chunked encoding
          // Split into 8KB chunks to avoid buffering entire response
          val chunkSize = 8192
          val totalSize = 102400
          val numChunks = totalSize / chunkSize  // 12.5, so 13 chunks

          // Create an iterator of chunks
          val chunks = Iterator.tabulate(numChunks + 1) { i =>
            val remainingBytes = totalSize - (i * chunkSize)
            val thisChunkSize = math.min(chunkSize, remainingBytes)
            if thisChunkSize > 0 then
              Chunk.fromString("x" * thisChunkSize)
            else
              Chunk.empty
          }.filter(_.nonEmpty)

          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty,
              body = Body.Stream(
                chunks = Eru.succeed(ChunkStream.fromIterator(chunks)),
                contentLength = None  // Use chunked encoding
              )
            )
          )

        case "/echo" =>
          // Echo POST body back
          for {
            body <- BodyDecoder[String].decode(req.body).mapError(e => HttpError.BodyDecodeError(e))
          } yield Response(
            status = StatusCode.Ok,
            headers = Headers.empty,
            body = Body.Text(body)
          )

        case "/echo-chunked" =>
          // Echo chunked POST body back without buffering
          // This tests streaming request body parsing
          req.body match {
            case Body.Stream(chunks, _, _) =>
              // Count chunks as they stream through (for verification)
              val chunkCountRef = new java.util.concurrent.atomic.AtomicInteger(0)
              val transformedChunks = chunks.map { stream =>
                stream.map { chunk =>
                  chunkCountRef.incrementAndGet()
                  chunk
                }
              }
              Eru.succeed(
                Response(
                  status = StatusCode.Ok,
                  headers = Headers.empty.add("X-Chunk-Count", "streaming").unsafeRunSync(),
                  body = Body.Stream(transformedChunks, None)
                )
              )
            case _ =>
              // Non-streaming body - just echo it back
              for {
                body <- BodyDecoder[String].decode(req.body).mapError(e => HttpError.BodyDecodeError(e))
              } yield Response(
                status = StatusCode.Ok,
                headers = Headers.empty,
                body = Body.Text(body)
              )
          }

        case "/counter" =>
          // Stateful endpoint
          val count = counter.incrementAndGet()
          Eru.succeed(
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty,
              body = Body.Text(count.toString)
            )
          )

        case "/error-400" =>
          Eru.succeed(
            Response(
              status = StatusCode.BadRequest,
              headers = Headers.empty,
              body = Body.Text("Bad Request")
            )
          )

        case "/error-500" =>
          Eru.succeed(
            Response(
              status = StatusCode.InternalServerError,
              headers = Headers.empty,
              body = Body.Text("Internal Server Error")
            )
          )

        case "/slow" =>
          // Simulate slow endpoint (10ms delay)
          Eru.effect {
            Thread.sleep(10)
          }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e))).map { _ =>
            Response(
              status = StatusCode.Ok,
              headers = Headers.empty,
              body = Body.Text("Slow response")
            )
          }

        case _ =>
          Eru.succeed(
            Response(
              status = StatusCode.NotFound,
              headers = Headers.empty,
              body = Body.Text("Not Found")
            )
          )
      }

    // Read maxConnections from system property (for benchmark matrix testing)
    val maxConnections = sys.props.get("eru.http.maxConnections").map(_.toInt).getOrElse(1024)

    val config = HttpServerConfig(
      host = "0.0.0.0",
      port = port,
      backlog = 1024,
      maxConnections = maxConnections
    )

    // Wrap handler with compression middleware
    val compressedHandler = Middleware.compression(CompressionConfig.default).apply(handler)

    val program = for {
      server <- HttpServer.create(config, compressedHandler)
      address <- server.start
      _ <- Eru.effect {
        println(s"Server started at http://${address.host}:${address.port}")
        println("\nEndpoints:")
        println("  GET  /              - Hello World (plaintext, 13 bytes)")
        println("  GET  /plaintext     - Hello World (plaintext, 13 bytes)")
        println("  GET  /json          - JSON response (30 bytes)")
        println("  GET  /json-large    - Large JSON response (~1KB)")
        println("  GET  /large-10kb    - Large text response (10KB)")
        println("  GET  /large-100kb   - Large text response (100KB, chunked)")
        println("  POST /echo          - Echo request body back")
        println("  POST /echo-chunked  - Echo chunked request body (streaming)")
        println("  GET  /counter       - Stateful counter")
        println("  GET  /error-400     - Bad Request (400)")
        println("  GET  /error-500     - Internal Server Error (500)")
        println("  GET  /slow          - Slow endpoint (10ms delay)")
        println("\nReady for benchmarking. Press Ctrl+C to stop.\n")
        // Keep server running
        while true do Thread.sleep(1000)
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Server stopped")
      case Result.Failure(error) =>
        println(s"Server error: $error")
    }
  }
}
