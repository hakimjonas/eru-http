package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*

/** Client with authentication using interceptors.
  *
  * Demonstrates:
  *   - Bearer token authentication
  *   - Basic authentication
  *   - Custom request/response interceptors
  *   - Composing interceptors
  *   - Logging interceptors
  */
object ClientWithAuth {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    // Example 1: Bearer token authentication
    bearerAuthExample()

    // Example 2: Basic authentication
    basicAuthExample()

    // Example 3: Multiple interceptors (auth + logging)
    multipleInterceptorsExample()

    // Example 4: Custom interceptors
    customInterceptorExample()
  }

  /** Demonstrates Bearer token authentication.
    */
  def bearerAuthExample(): Unit = {
    println("\n=== Example 1: Bearer Token Authentication ===")

    val token = "your-api-token-here"

    val program = for {
      // Create base client
      baseClient <- HttpClient.create(HttpClientConfig.default)

      // Add bearer auth interceptor
      client = baseClient.withRequestInterceptor(Interceptor.bearerAuth(token))

      // Make authenticated request
      uri <- Uri.parse("https://api.github.com/user")
      request = Request.get(uri)
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"Status: ${response.status.value}")
        if response.isSuccess then
          println(s"Response: ${response.body.take(200)}...")
        else
          println(s"Authentication failed")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Bearer auth example completed")
      case Result.Failure(error) =>
        println(s"Bearer auth failed: $error")
    }
  }

  /** Demonstrates Basic authentication.
    */
  def basicAuthExample(): Unit = {
    println("\n=== Example 2: Basic Authentication ===")

    val username = "user"
    val password = "pass"

    val program = for {
      baseClient <- HttpClient.create(HttpClientConfig.default)

      // Add basic auth interceptor
      client = baseClient.withRequestInterceptor(Interceptor.basicAuth(username, password))

      uri <- Uri.parse("https://httpbin.org/basic-auth/user/pass")
      request = Request.get(uri)
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"Status: ${response.status.value}")
        if response.isSuccess then
          println("Basic authentication successful")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Basic auth example completed")
      case Result.Failure(error) =>
        println(s"Basic auth failed: $error")
    }
  }

  /** Demonstrates composing multiple interceptors.
    */
  def multipleInterceptorsExample(): Unit = {
    println("\n=== Example 3: Multiple Interceptors (Auth + Logging) ===")

    val token = "demo-token"

    val program = for {
      baseClient <- HttpClient.create(HttpClientConfig.default)

      // Compose multiple interceptors
      client = baseClient
        .withRequestInterceptor(Interceptor.bearerAuth(token))
        .withRequestInterceptor(Interceptor.userAgent("EruHttp-Demo/1.0"))
        .withRequestInterceptor(Interceptor.logRequest(msg => println(s"[REQUEST] $msg")))
        .withResponseInterceptor(Interceptor.logResponse(msg => println(s"[RESPONSE] $msg")))

      uri <- Uri.parse("https://httpbin.org/get")
      request = Request.get(uri)
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"\nFinal response status: ${response.status.value}")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Multiple interceptors example completed")
      case Result.Failure(error) =>
        println(s"Multiple interceptors failed: $error")
    }
  }

  /** Demonstrates creating custom interceptors.
    */
  def customInterceptorExample(): Unit = {
    println("\n=== Example 4: Custom Interceptors ===")

    // Custom interceptor to add a request ID header
    val requestIdInterceptor: RequestInterceptor = req =>
      val requestId = java.util.UUID.randomUUID().toString
      Interceptor.addHeader("X-Request-ID", requestId)(req)

    // Custom interceptor to measure request timing
    val timingInterceptor: ResponseInterceptor = resp =>
      Eru.effect {
        println(s"[TIMING] Request completed at ${java.time.Instant.now()}")
        resp
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

    val program = for {
      baseClient <- HttpClient.create(HttpClientConfig.default)

      // Apply custom interceptors
      client = baseClient
        .withRequestInterceptor(requestIdInterceptor)
        .withResponseInterceptor(timingInterceptor)

      uri <- Uri.parse("https://httpbin.org/headers")
      request = Request.get(uri)
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"\nResponse shows headers including X-Request-ID")
        println(s"Body preview: ${response.body.take(300)}...")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Custom interceptors example completed")
      case Result.Failure(error) =>
        println(s"Custom interceptors failed: $error")
    }
  }
}
