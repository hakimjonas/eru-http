package net.ghoula.eru.http.client

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** HTTP client built on Eru effects.
  *
  * Operations carry a typed `HttpError` error channel and implement RFC 9110 semantics, with RFC
  * 9112/9113 messaging and RFC 10008 QUERY support.
  */
trait HttpClient {

  /** Executes an HTTP request and decodes the response body.
    *
    * @param request
    *   The HTTP request to execute
    * @tparam A
    *   Request body type
    * @tparam B
    *   Response body type
    * @return
    *   An Eru effect containing the response or an HTTP error
    */
  def execute[A, B](
    request: Request[A]
  )(using encoder: BodyEncoder[A], decoder: BodyDecoder[B]): Eru[HttpError, Response[B]]

  /** Executes an HTTP request and returns the raw response.
    *
    * @param request
    *   The HTTP request to execute
    * @tparam A
    *   Request body type
    * @return
    *   An Eru effect containing the response with raw bytes or an HTTP error
    */
  def send[A](request: Request[A])(using encoder: BodyEncoder[A]): Eru[HttpError, Response[Bytes]]

  /** Discovers the query media types a resource accepts for QUERY requests (RFC 10008 Section 3) by
    * sending an OPTIONS request and reading the Accept-Query response field.
    *
    * Returns None when the response carries no Accept-Query field; fails with a ProtocolError when
    * the field is present but malformed.
    *
    * @param uri
    *   the target resource URI
    * @return
    *   the parsed Accept-Query value, or None if the resource does not advertise one
    */
  def queryFormats(uri: Uri): Eru[HttpError, Option[AcceptQuery]]

  /** Shuts down the client gracefully, closing all connections.
    *
    * @return
    *   An Eru effect that completes when shutdown is finished
    */
  def shutdown: Eru[Nothing, Unit]

  /** Apply a request interceptor to this client.
    *
    * Interceptors are applied in the order they are added (FIFO).
    *
    * @param interceptor
    *   The request interceptor to apply
    * @return
    *   A new client with the interceptor applied
    */
  def withRequestInterceptor(interceptor: RequestInterceptor): HttpClient

  /** Apply a response interceptor to this client.
    *
    * @param interceptor
    *   The response interceptor to apply
    * @return
    *   A new client with the interceptor applied
    */
  def withResponseInterceptor(interceptor: ResponseInterceptor): HttpClient

  /** Apply both request and response interceptors.
    *
    * @example
    *   {{{ val (reqLog, respLog) = Interceptor.logging(println) client.withInterceptor(reqLog,
    *   respLog) }}}
    */
  inline def withInterceptor(
    request: RequestInterceptor,
    response: ResponseInterceptor
  ): HttpClient =
    withRequestInterceptor(request).withResponseInterceptor(response)
}

object HttpClient {

  /** Creates a new HTTP client with default configuration.
    *
    * @return
    *   An Eru effect containing the created client or an error
    */
  def create(using runtime: EruRuntime): Eru[HttpError, HttpClient] =
    create(HttpClientConfig.default)

  /** Creates a new HTTP client with the specified configuration.
    *
    * @param config
    *   The client configuration
    * @return
    *   An Eru effect containing the created client or an error
    */
  def create(config: HttpClientConfig)(using runtime: EruRuntime): Eru[HttpError, HttpClient] =
    NativeHttpClient.create(config)

  /** Executes a request using a scoped client that is automatically cleaned up.
    *
    * Example: {{{HttpClient.scoped { client => client.send(Request.get(uri)) }}}}
    *
    * @param use
    *   A function that uses the client
    * @tparam A
    *   The result type
    * @return
    *   An Eru effect containing the result or an error
    */
  def scoped[A](use: HttpClient => Eru[HttpError, A])(using runtime: EruRuntime): Eru[HttpError, A] =
    scoped(HttpClientConfig.default)(use)

  /** Executes a request using a scoped client with custom configuration.
    *
    * @param config
    *   The client configuration
    * @param use
    *   A function that uses the client
    * @tparam A
    *   The result type
    * @return
    *   An Eru effect containing the result or an error
    */
  def scoped[A](
    config: HttpClientConfig
  )(use: HttpClient => Eru[HttpError, A])(using runtime: EruRuntime): Eru[HttpError, A] =
    for {
      client <- create(config)
      result <- use(client)
      _ <- client.shutdown
    } yield result
}
