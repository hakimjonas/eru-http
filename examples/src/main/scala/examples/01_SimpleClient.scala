package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*

/** Basic HTTP client usage examples.
  *
  * Demonstrates:
  *   - Creating an HTTP client
  *   - Making simple GET and POST requests
  *   - Handling responses
  *   - Proper resource cleanup
  *   - Error handling with Eru effects
  */
object SimpleClient {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    // Example 1: Simple GET request
    simpleGet()

    // Example 2: POST request with body
    postWithBody()

    // Example 3: Using scoped client (automatic cleanup)
    scopedClientExample()
  }

  /** Makes a simple GET request to retrieve data.
    */
  def simpleGet(): Unit = {
    println("\n=== Example 1: Simple GET Request ===")

    val program = for {
      // Create HTTP client
      client <- HttpClient.create(HttpClientConfig.default)

      // Parse URI
      uri <- Uri.parse("https://api.github.com/zen")

      // Create GET request
      request = Request.get(uri)

      // Execute request - response body is decoded as String
      response <- client.execute[Body, String](request)

      // Process response
      _ <- Eru.effect {
        println(s"Status: ${response.status.value}")
        println(s"Body: ${response.body}")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      // Cleanup
      _ <- client.shutdown
    } yield ()

    // Run the program
    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Request successful")
      case Result.Failure(error) =>
        println(s"Request failed: $error")
    }
  }

  /** Makes a POST request with a body.
    */
  def postWithBody(): Unit = {
    println("\n=== Example 2: POST Request with Body ===")

    val program = for {
      client <- HttpClient.create(HttpClientConfig.default)

      // Parse URI
      uri <- Uri.parse("https://httpbin.org/post")

      // Create request body
      body = Body.text("Hello, World!", MediaType.textPlain)

      // Create POST request
      request = Request.post(uri, body)

      // Execute request
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"Status: ${response.status.value}")
        println(s"Response preview: ${response.body.take(200)}...")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("POST request successful")
      case Result.Failure(error) =>
        println(s"POST request failed: $error")
    }
  }

  /** Uses scoped client for automatic resource management.
    *
    * The scoped pattern ensures the client is properly shut down even if an error occurs.
    */
  def scopedClientExample(): Unit = {
    println("\n=== Example 3: Scoped Client (Automatic Cleanup) ===")

    val program = HttpClient.scoped { client =>
      for {
        uri <- Uri.parse("https://api.github.com/users/github")
        request = Request.get(uri)
        response <- client.execute[Body, String](request)
        _ <- Eru.effect {
          println(s"GitHub user data preview: ${response.body.take(150)}...")
        }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      } yield ()
    }

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Scoped request successful")
      case Result.Failure(error) =>
        println(s"Scoped request failed: $error")
    }
  }
}
