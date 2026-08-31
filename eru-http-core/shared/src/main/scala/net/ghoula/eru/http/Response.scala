package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP response as defined in RFC 9110.
  *
  * @tparam A
  *   the body type
  * @param status
  *   the response status code
  * @param headers
  *   the header fields
  * @param body
  *   the response body
  * @param version
  *   the HTTP version, HTTP/1.1 by default
  */
final case class Response[+A](
  status: StatusCode,
  headers: Headers,
  body: A,
  version: HttpVersion = HttpVersion.HTTP_1_1
) {

  /** Adds a header to the response with validation.
    */
  def addHeader(
    name: String,
    value: String
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    headers.add(name, value).map(h => copy(headers = h))

  /** Sets a header with validation, replacing any existing values.
    */
  def setHeader(
    name: String,
    value: String
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    headers.set(name, value).map(h => copy(headers = h))

  /** Removes a header.
    */
  def removeHeader(name: String): Response[A] =
    copy(headers = headers.remove(name))

  /** Transforms the body.
    */
  def mapBody[B](f: A => B): Response[B] =
    copy(body = f(body))

  /** Replaces the body.
    */
  def withBody[B](newBody: B): Response[B] =
    copy(body = newBody)

  /** Sets the Content-Type header.
    */
  def withContentType(
    mediaType: MediaType
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.ContentType, mediaType.value)

  /** Sets the Location header (for redirects and created resources).
    */
  def withLocation(uri: Uri): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.Location, uri.value)

  /** Sets cache control directives using a CacheControl object.
    *
    * @param cc
    *   the cache control directives
    * @return
    *   the updated response or an error
    */
  def withCacheControl(
    cc: CacheControl
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.CacheControl, cc.value)

  /** Sets the Accept-Query field (RFC 10008 Section 3) advertising the query media types this
    * resource accepts for QUERY requests.
    */
  def withAcceptQuery(
    acceptQuery: AcceptQuery
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.AcceptQuery, acceptQuery.value)

  /** Sets cache control directives from a string (for backward compatibility).
    *
    * @param directives
    *   the cache control directive string
    * @return
    *   the updated response or an error
    */
  def withCacheControlString(
    directives: String
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.CacheControl, directives)

  /** Sets the response to not be cached.
    *
    * Equivalent to: Cache-Control: no-cache, no-store, must-revalidate
    *
    * @return
    *   the updated response or an error
    */
  def noCache: Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    withCacheControl(
      CacheControl(
        List(
          CacheDirective.NoCache,
          CacheDirective.NoStore,
          CacheDirective.MustRevalidate
        )
      )
    )

  /** Sets an ETag for the response.
    *
    * @param etag
    *   the ETag to set
    * @return
    *   the updated response or an error
    */
  def withETag(etag: ETag): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.ETag, etag.headerValue)

  /** Encodes a value as the response body using a BodyEncoder.
    */
  def withEncodedBody[B](value: B)(using
    encoder: BodyEncoder[B]
  ): Eru[EncodeError | HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] = {
    for {
      encoded <- encoder.encode(value)
      withBody = copy(body = encoded)
      result <- encoded.mediaType match {
        case Some(mt) =>
          withBody.withContentType(mt).mapError {
            case e: HeaderName.InvalidHeaderName => e: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue)
            case e: HeaderValue.InvalidHeaderValue => e: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue)
          }
        case None => Eru.succeed(withBody)
      }
    } yield result
  }

  /** Decodes the response body using a BodyDecoder. Only works when body type is Body.
    */
  def decodeBody[B](using decoder: BodyDecoder[B])(using ev: A <:< Body): Eru[DecodeError, B] = {
    decoder.decode(body)
  }

  /** Validates this response per RFC 9110.
    */
  def validate: Eru[InvalidResponse, Response[A]] = {
    for {
      _ <- validateRequiredHeaders
      _ <- validateBodyRestrictions
      _ <- validateHeaderCombinations
    } yield this
  }

  private def validateRequiredHeaders: Eru[InvalidResponse, Unit] = {
    val missing = status.requiredHeaders.filter(h => !headers.contains(h))
    if missing.nonEmpty then {
      Eru.fail(
        InvalidResponse(
          s"Status ${status.value} requires headers: ${missing.mkString(", ")}",
          "RFC 9110 Section 15"
        )
      )
    } else {
      Eru.unit
    }
  }

  @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
  private def validateBodyRestrictions: Eru[InvalidResponse, Unit] = {
    val hasBody = body match {
      case b: Body => !b.isEmpty
      case _ => true
    }

    if !status.allowsResponseBody && hasBody then {
      Eru.fail(
        InvalidResponse(
          s"Status ${status.value} does not allow a response body",
          "RFC 9110 Section 15"
        )
      )
    } else {
      Eru.unit
    }
  }

  private def validateHeaderCombinations: Eru[InvalidResponse, Unit] = {
    if headers.contains(HeaderNames.ContentLength) &&
      headers.contains(HeaderNames.TransferEncoding)
    then {
      Eru.fail(
        InvalidResponse(
          "Content-Length and Transfer-Encoding headers are mutually exclusive",
          "RFC 9110 Section 6.1"
        )
      )
    } else {
      Eru.unit
    }
  }

  /** Checks if this response is successful (2xx).
    */
  def isSuccess: Boolean = status.isSuccessful

  /** Checks if this response is a client error (4xx).
    */
  def isClientError: Boolean = status.isClientError

  /** Checks if this response is a server error (5xx).
    */
  def isServerError: Boolean = status.isServerError

  /** Checks if this response is any error (4xx or 5xx).
    */
  def isError: Boolean = status.isError

  /** Creates an SSE (Server-Sent Events) response with proper headers.
    *
    * Automatically sets:
    *   - Content-Type: text/event-stream
    *   - Cache-Control: no-cache
    *   - Connection: keep-alive
    *
    * @param events
    *   the chunk stream of SSE events
    * @return
    *   an SSE response with streaming body
    */
  def withSSE(
    events: ChunkStream
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] = {
    val body = Body.Stream(
      chunks = Eru.succeed(events),
      contentLength = None,
      mediaType = Some(MediaType.textEventStream)
    )

    for {
      r1 <- copy(body = body).withContentType(MediaType.textEventStream)
      r2 <- r1.setHeader(HeaderNames.CacheControl, "no-cache")
      r3 <- r2.setHeader(HeaderNames.Connection, "keep-alive")
    } yield r3
  }
}

object Response {

  /** Creates a 200 OK response.
    */
  def ok(body: Body): Response[Body] =
    Response(StatusCode.Ok, Headers.empty, body)

  /** Creates a 201 Created response with Location header.
    */
  def created(
    location: Uri,
    body: Body
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.Created, Headers.empty, body)
      .withLocation(location)

  /** Creates a 204 No Content response.
    */
  def noContent: Response[Body] =
    Response(StatusCode.NoContent, Headers.empty, Body.Empty)

  /** Creates a 301 Moved Permanently response.
    */
  def movedPermanently(
    location: Uri
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.MovedPermanently, Headers.empty, Body.Empty)
      .withLocation(location)

  /** Creates a 400 Bad Request response.
    */
  def badRequest(body: Body): Response[Body] =
    Response(StatusCode.BadRequest, Headers.empty, body)

  /** Creates a 401 Unauthorized response.
    */
  def unauthorized(
    challenge: String,
    body: Body
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.Unauthorized, Headers.empty, body)
      .setHeader(HeaderNames.WWWAuthenticate, challenge)

  /** Creates a 405 Method Not Allowed response.
    */
  def methodNotAllowed(
    allowedMethods: Set[Method]
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.MethodNotAllowed, Headers.empty, Body.Empty)
      .setHeader(HeaderNames.Allow, allowedMethods.map(_.value).mkString(", "))

  /** Creates a 500 Internal Server Error response.
    */
  def internalServerError(body: Body): Response[Body] =
    Response(StatusCode.InternalServerError, Headers.empty, body)

  /** Creates an SSE (Server-Sent Events) response from an event stream.
    *
    * SSE is a standard for pushing real-time updates from server to client over HTTP. This helper
    * creates a 200 OK response with proper SSE headers:
    *   - Content-Type: text/event-stream
    *   - Cache-Control: no-cache
    *   - Connection: keep-alive
    *
    * @param events
    *   the chunk stream of SSE-formatted events
    * @return
    *   a 200 OK response configured for SSE
    *
    * @example
    *   {{{
    * val events = List(
    *   ServerSentEvent.data("Hello"),
    *   ServerSentEvent.event("update", "New message")
    * )
    * val stream = ServerSentEvent.toChunkStream(events)
    * Response.sse(stream)
    *   }}}
    */
  def sse(events: ChunkStream): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] = {
    Response(StatusCode.Ok, Headers.empty, Body.Empty).withSSE(events)
  }
}

/** Response validation error.
  */
final case class InvalidResponse(
  reason: String,
  rfc: String
) {
  def message: String = s"Invalid response: $reason ($rfc)"
}
