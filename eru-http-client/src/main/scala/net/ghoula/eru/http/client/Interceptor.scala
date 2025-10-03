package net.ghoula.eru.http.client

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** Request interceptor transforms requests before sending.
  *
  * Interceptors work with Eru effects, enabling:
  * - Composable transformations
  * - Error handling
  * - Async operations (e.g., fetch token from cache)
  * - Type-safe composition
  *
  * @example {{{
  *   val addAuth: RequestInterceptor = req =>
  *     Eru.succeed(req.setHeader("Authorization", s"Bearer \$token"))
  *
  *   val addRequestId: RequestInterceptor = req =>
  *     Eru.succeed(req.setHeader("X-Request-ID", UUID.randomUUID().toString))
  *
  *   val composed = addAuth.andThen(addRequestId)
  * }}}
  */
type RequestInterceptor = Request[Body] => Eru[HttpError, Request[Body]]

/** Response interceptor transforms responses after receiving.
  *
  * Can be used for:
  * - Logging
  * - Metrics
  * - Error transformation
  * - Response validation
  */
type ResponseInterceptor = Response[Body] => Eru[HttpError, Response[Body]]

/** Extension methods for composing interceptors. */
extension (self: RequestInterceptor) {
  /** Compose this interceptor with another, applying this one first.
    *
    * Uses Eru's flatMap for pure functional composition.
    */
  @scala.annotation.targetName("andThenRequest")
  inline def andThen(next: RequestInterceptor): RequestInterceptor = req =>
    self(req).flatMap(next)

  /** Compose with multiple interceptors in sequence. */
  @scala.annotation.targetName("andThenAllRequests")
  inline def andThenAll(interceptors: RequestInterceptor*): RequestInterceptor =
    interceptors.foldLeft(self)((a, b) => a.andThen(b))
}

extension (self: ResponseInterceptor) {
  /** Compose this interceptor with another, applying this one first. */
  @scala.annotation.targetName("andThenResponse")
  inline def andThen(next: ResponseInterceptor): ResponseInterceptor = resp =>
    self(resp).flatMap(next)

  /** Compose with multiple interceptors in sequence. */
  @scala.annotation.targetName("andThenAllResponses")
  inline def andThenAll(interceptors: ResponseInterceptor*): ResponseInterceptor =
    interceptors.foldLeft(self)((a, b) => a.andThen(b))
}

/** Built-in interceptors for common use cases. */
object Interceptor {

  /** Add a header to all requests. */
  inline def addHeader(name: String, value: String): RequestInterceptor = req =>
    req.setHeader(name, value).mapError {
      case e: HeaderName.InvalidHeaderName =>
        HttpError.InvalidRequest(InvalidRequest(s"Invalid header name: ${e.getMessage}", "RFC 9110"))
      case e: HeaderValue.InvalidHeaderValue =>
        HttpError.InvalidRequest(InvalidRequest(s"Invalid header value: ${e.getMessage}", "RFC 9110"))
    }

  /** Add multiple headers to all requests. */
  inline def addHeaders(headers: (String, String)*): RequestInterceptor = req =>
    headers.foldLeft(Eru.succeed(req)) { case (acc, (name, value)) =>
      acc.flatMap(r => addHeader(name, value)(r))
    }

  /** Add Bearer token authentication. */
  inline def bearerAuth(token: String): RequestInterceptor =
    addHeader("Authorization", s"Bearer $token")

  /** Add Basic authentication. */
  inline def basicAuth(username: String, password: String): RequestInterceptor = {
    import java.util.Base64
    val credentials = Base64.getEncoder.encodeToString(s"$username:$password".getBytes)
    addHeader("Authorization", s"Basic $credentials")
  }

  /** Add User-Agent header. */
  inline def userAgent(agent: String): RequestInterceptor =
    addHeader("User-Agent", agent)

  /** Add custom header with dynamic value (e.g., request ID). */
  inline def withHeader(name: String)(getValue: => String): RequestInterceptor = req =>
    addHeader(name, getValue)(req)

  /** Request logging interceptor. */
  inline def logRequest(log: String => Unit): RequestInterceptor = req =>
    Eru.effect {
      log(s"${req.method} ${req.uri}")
      req
    }.mapError(e => HttpError.NetworkError(s"Logging failed: ${e.getMessage}", Some(e)))

  /** Response logging interceptor. */
  inline def logResponse(log: String => Unit): ResponseInterceptor = resp =>
    Eru.effect {
      log(s"${resp.status} (${resp.body.contentLength.getOrElse(0)} bytes)")
      resp
    }.mapError(e => HttpError.NetworkError(s"Logging failed: ${e.getMessage}", Some(e)))

  /** Logging interceptor for both request and response. */
  inline def logging(log: String => Unit): (RequestInterceptor, ResponseInterceptor) =
    (logRequest(log), logResponse(log))

  /** Transform request based on predicate. */
  inline def when(
    condition: Request[Body] => Boolean
  )(transform: RequestInterceptor): RequestInterceptor = req =>
    if condition(req) then transform(req) else Eru.succeed(req)

  /** Transform response based on predicate. */
  inline def whenResponse(
    condition: Response[Body] => Boolean
  )(transform: ResponseInterceptor): ResponseInterceptor = resp =>
    if condition(resp) then transform(resp) else Eru.succeed(resp)
}
