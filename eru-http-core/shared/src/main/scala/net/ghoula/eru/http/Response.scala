package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP response as defined in RFC 9110.
  *
  * Type parameter A represents the body type.
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

  /** Changes the status code.
    */
  def withStatus(newStatus: StatusCode): Response[A] =
    copy(status = newStatus)

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

  /** Sets the Last-Modified header.
    *
    * @param date
    *   the last modified date
    * @return
    *   the updated response or an error
    */
  def withLastModified(
    date: java.time.Instant
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[A]] =
    setHeader(HeaderNames.LastModified, HttpDate.format(date))

  /** Creates a 304 Not Modified response from this response.
    *
    * Per RFC 9110, a 304 response must not contain a body and should include certain headers from
    * the original response (Cache-Control, Content-Location, Date, ETag, Expires, Vary).
    *
    * @return
    *   a 304 Not Modified response with appropriate headers
    */
  def notModified: Response[Body] = {
    // List of headers that should be preserved in 304 responses
    val preservedHeaders = Set(
      HeaderNames.CacheControl,
      HeaderNames.ContentLocation,
      HeaderNames.Date,
      HeaderNames.ETag,
      HeaderNames.Expires,
      HeaderNames.Vary
    )

    // Filter headers to keep only the preserved ones
    val filtered = headers.toList.filter { case (name, _) =>
      preservedHeaders.contains(name)
    }

    // Create new headers from filtered list
    val newHeaders = filtered.foldLeft(Headers.empty) { case (acc, (name, value)) =>
      acc.unsafeAdd(name, HeaderValue.unsafeFromString(value))
    }

    Response(
      status = StatusCode.NotModified,
      headers = newHeaders,
      body = Body.Empty,
      version = version
    )
  }

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
    // Check if body is empty
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
    // Content-Length and Transfer-Encoding are mutually exclusive
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

  /** Checks if this response is a redirect (3xx).
    */
  def isRedirect: Boolean = status.isRedirection

  /** Gets the Location header for redirects.
    */
  def location: Option[String] =
    headers.getFirst(HeaderNames.Location).map(_.value)

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
  // Success responses

  /** Creates a 200 OK response.
    */
  def ok(body: Body): Response[Body] =
    Response(StatusCode.Ok, Headers.empty, body)

  /** Creates a 200 OK response with encoded body.
    */
  def okEncoded[A](value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Response[Body]] =
    encoder.encode(value).map(body => Response(StatusCode.Ok, Headers.empty, body))

  /** Creates a 201 Created response with Location header.
    */
  def created(
    location: Uri,
    body: Body
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.Created, Headers.empty, body)
      .withLocation(location)

  /** Creates a 201 Created response with encoded body and Location header.
    */
  def createdEncoded[A](location: Uri, value: A)(using
    encoder: BodyEncoder[A]
  ): Eru[EncodeError | HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    encoder
      .encode(value)
      .flatMap(body => Response(StatusCode.Created, Headers.empty, body).withLocation(location))
      .mapError {
        case e: EncodeError => e: (EncodeError | HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue)
        case e: HeaderName.InvalidHeaderName =>
          e: (EncodeError | HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue)
        case e: HeaderValue.InvalidHeaderValue =>
          e: (EncodeError | HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue)
      }

  /** Creates a 202 Accepted response.
    */
  def accepted(body: Body): Response[Body] =
    Response(StatusCode.Accepted, Headers.empty, body)

  /** Creates a 202 Accepted response with encoded body.
    */
  def acceptedEncoded[A](value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Response[Body]] =
    encoder.encode(value).map(body => Response(StatusCode.Accepted, Headers.empty, body))

  /** Creates a 204 No Content response.
    */
  def noContent: Response[Body] =
    Response(StatusCode.NoContent, Headers.empty, Body.Empty)

  // Redirect responses

  /** Creates a 301 Moved Permanently response.
    */
  def movedPermanently(
    location: Uri
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.MovedPermanently, Headers.empty, Body.Empty)
      .withLocation(location)

  /** Creates a 302 Found response.
    */
  def found(location: Uri): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.Found, Headers.empty, Body.Empty)
      .withLocation(location)

  /** Creates a 303 See Other response.
    */
  def seeOther(location: Uri): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.SeeOther, Headers.empty, Body.Empty)
      .withLocation(location)

  /** Creates a 304 Not Modified response.
    */
  def notModified: Response[Body] =
    Response(StatusCode.NotModified, Headers.empty, Body.Empty)

  /** Creates a 307 Temporary Redirect response.
    */
  def temporaryRedirect(
    location: Uri
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.TemporaryRedirect, Headers.empty, Body.Empty)
      .withLocation(location)

  /** Creates a 308 Permanent Redirect response.
    */
  def permanentRedirect(
    location: Uri
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.PermanentRedirect, Headers.empty, Body.Empty)
      .withLocation(location)

  // Client error responses

  /** Creates a 400 Bad Request response.
    */
  def badRequest(body: Body): Response[Body] =
    Response(StatusCode.BadRequest, Headers.empty, body)

  /** Creates a 400 Bad Request response with encoded body.
    */
  def badRequestEncoded[A](value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Response[Body]] =
    encoder.encode(value).map(body => Response(StatusCode.BadRequest, Headers.empty, body))

  /** Creates a 401 Unauthorized response.
    */
  def unauthorized(
    challenge: String,
    body: Body
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.Unauthorized, Headers.empty, body)
      .setHeader(HeaderNames.WWWAuthenticate, challenge)

  /** Creates a 403 Forbidden response.
    */
  def forbidden(body: Body): Response[Body] =
    Response(StatusCode.Forbidden, Headers.empty, body)

  /** Creates a 403 Forbidden response with encoded body.
    */
  def forbiddenEncoded[A](value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Response[Body]] =
    encoder.encode(value).map(body => Response(StatusCode.Forbidden, Headers.empty, body))

  /** Creates a 404 Not Found response.
    */
  def notFound(body: Body): Response[Body] =
    Response(StatusCode.NotFound, Headers.empty, body)

  /** Creates a 404 Not Found response with encoded body.
    */
  def notFoundEncoded[A](value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Response[Body]] =
    encoder.encode(value).map(body => Response(StatusCode.NotFound, Headers.empty, body))

  /** Creates a 405 Method Not Allowed response.
    */
  def methodNotAllowed(
    allowedMethods: Set[Method]
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.MethodNotAllowed, Headers.empty, Body.Empty)
      .setHeader(HeaderNames.Allow, allowedMethods.map(_.value).mkString(", "))

  /** Creates a 409 Conflict response.
    */
  def conflict(body: Body): Response[Body] =
    Response(StatusCode.Conflict, Headers.empty, body)

  /** Creates a 410 Gone response.
    */
  def gone(body: Body): Response[Body] =
    Response(StatusCode.Gone, Headers.empty, body)

  /** Creates a 412 Precondition Failed response.
    */
  def preconditionFailed(body: Body): Response[Body] =
    Response(StatusCode.PreconditionFailed, Headers.empty, body)

  /** Creates a 429 Too Many Requests response.
    */
  def tooManyRequests(
    retryAfter: String,
    body: Body
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] =
    Response(StatusCode.TooManyRequests, Headers.empty, body)
      .setHeader(HeaderNames.RetryAfter, retryAfter)

  // Server error responses

  /** Creates a 500 Internal Server Error response.
    */
  def internalServerError(body: Body): Response[Body] =
    Response(StatusCode.InternalServerError, Headers.empty, body)

  /** Creates a 500 Internal Server Error response with encoded body.
    */
  def internalServerErrorEncoded[A](value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Response[Body]] =
    encoder.encode(value).map(body => Response(StatusCode.InternalServerError, Headers.empty, body))

  /** Creates a 501 Not Implemented response.
    */
  def notImplemented(body: Body): Response[Body] =
    Response(StatusCode.NotImplemented, Headers.empty, body)

  /** Creates a 502 Bad Gateway response.
    */
  def badGateway(body: Body): Response[Body] =
    Response(StatusCode.BadGateway, Headers.empty, body)

  /** Creates a 503 Service Unavailable response.
    */
  def serviceUnavailable(
    retryAfter: Option[String],
    body: Body
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Response[Body]] = {
    val base = Response(StatusCode.ServiceUnavailable, Headers.empty, body)
    retryAfter match {
      case Some(ra) => base.setHeader(HeaderNames.RetryAfter, ra)
      case None => Eru.succeed(base)
    }
  }

  /** Creates a 504 Gateway Timeout response.
    */
  def gatewayTimeout(body: Body): Response[Body] =
    Response(StatusCode.GatewayTimeout, Headers.empty, body)

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
) extends Exception(s"Invalid response: $reason ($rfc)")
