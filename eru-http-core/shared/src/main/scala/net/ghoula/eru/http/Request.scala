package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP request as defined in RFC 9110.
  *
  * Type parameter A represents the body type.
  */
final case class Request[+A](
  method: Method,
  uri: Uri,
  headers: Headers,
  body: A,
  version: HttpVersion = HttpVersion.HTTP_1_1
) {

  /** Adds a header to the request with validation.
    */
  def addHeader(
    name: String,
    value: String
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    headers.add(name, value).map(h => copy(headers = h))

  /** Sets a header with validation, replacing any existing values.
    */
  def setHeader(
    name: String,
    value: String
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    headers.set(name, value).map(h => copy(headers = h))

  /** Removes a header.
    */
  def removeHeader(name: String): Request[A] =
    copy(headers = headers.remove(name))

  /** Transforms the body.
    */
  def mapBody[B](f: A => B): Request[B] =
    copy(body = f(body))

  /** Replaces the body.
    */
  def withBody[B](newBody: B): Request[B] =
    copy(body = newBody)

  /** Changes the method.
    */
  def withMethod(newMethod: Method): Request[A] =
    copy(method = newMethod)

  /** Changes the URI.
    */
  def withUri(newUri: Uri): Request[A] =
    copy(uri = newUri)

  /** Sets the Content-Type header.
    */
  def withContentType(
    mediaType: MediaType
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.ContentType, mediaType.value)

  /** Sets the Accept header.
    */
  def withAccept(mediaType: MediaType): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.Accept, mediaType.value)

  /** Sets the Authorization header.
    */
  def withAuthorization(auth: String): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.Authorization, auth)

  /** Sets a bearer token authorization.
    */
  def withBearerToken(token: String): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    withAuthorization(s"Bearer $token")

  /** Sets basic authentication.
    */
  def withBasicAuth(
    username: String,
    password: String
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] = {
    val credentials = java.util.Base64.getEncoder.encodeToString(
      s"$username:$password".getBytes("UTF-8")
    )
    withAuthorization(s"Basic $credentials")
  }

  /** Sets the If-None-Match header for conditional requests.
    *
    * Used to make a request conditional based on ETags. The server will only return the resource if
    * the ETag doesn't match.
    *
    * @param etag
    *   the ETag to check against
    * @return
    *   the updated request or an error
    */
  def withIfNoneMatch(etag: ETag): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.IfNoneMatch, etag.headerValue)

  /** Sets the If-Match header for conditional requests.
    *
    * Used to make a request conditional based on ETags. The server will only process the request if
    * the ETag matches.
    *
    * @param etag
    *   the ETag to check against
    * @return
    *   the updated request or an error
    */
  def withIfMatch(etag: ETag): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.IfMatch, etag.headerValue)

  /** Sets the If-Modified-Since header for conditional requests.
    *
    * The server will only return the resource if it has been modified since the given date.
    *
    * @param date
    *   the date to check against
    * @return
    *   the updated request or an error
    */
  def withIfModifiedSince(
    date: java.time.Instant
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.IfModifiedSince, HttpDate.format(date))

  /** Sets the If-Unmodified-Since header for conditional requests.
    *
    * The server will only process the request if the resource has not been modified since the given
    * date.
    *
    * @param date
    *   the date to check against
    * @return
    *   the updated request or an error
    */
  def withIfUnmodifiedSince(
    date: java.time.Instant
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.IfUnmodifiedSince, HttpDate.format(date))

  /** Encodes a value as the request body using a BodyEncoder.
    */
  def withEncodedBody[B](value: B)(using
    encoder: BodyEncoder[B]
  ): Eru[EncodeError | HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[Body]] = {
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

  /** Decodes the request body using a BodyDecoder. Only works when body type is Body.
    */
  def decodeBody[B](using decoder: BodyDecoder[B])(using ev: A <:< Body): Eru[DecodeError, B] = {
    decoder.decode(body)
  }

  /** Validates this request per RFC 9110.
    */
  def validate: Eru[InvalidRequest, Request[A]] = {
    for {
      _ <- validateMethodBodyCombination
      _ <- validateRequiredHeaders
      _ <- validateForbiddenHeaderCombinations
    } yield this
  }

  @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
  private def validateMethodBodyCombination: Eru[InvalidRequest, Unit] = {
    // Check if body is empty
    val hasBody = body match {
      case b: Body => !b.isEmpty
      case _ => true
    }

    if !method.allowsRequestBody && hasBody then {
      Eru.fail(
        InvalidRequest(
          s"Method ${method.value} does not allow a request body",
          "RFC 9110 Section 9"
        )
      )
    } else {
      Eru.unit
    }
  }

  private def validateRequiredHeaders: Eru[InvalidRequest, Unit] = {
    // Host header is required for HTTP/1.1
    if version == HttpVersion.HTTP_1_1 && !headers.contains(HeaderNames.Host) then {
      Eru.fail(
        InvalidRequest(
          "Host header is required for HTTP/1.1",
          "RFC 9110 Section 7.2"
        )
      )
    } else {
      Eru.unit
    }
  }

  private def validateForbiddenHeaderCombinations: Eru[InvalidRequest, Unit] = {
    // Content-Length and Transfer-Encoding are mutually exclusive
    if headers.contains(HeaderNames.ContentLength) &&
      headers.contains(HeaderNames.TransferEncoding)
    then {
      Eru.fail(
        InvalidRequest(
          "Content-Length and Transfer-Encoding headers are mutually exclusive",
          "RFC 9110 Section 6.1"
        )
      )
    } else {
      Eru.unit
    }
  }
}

object Request {

  /** Creates a GET request with empty body.
    */
  def get(uri: Uri): Request[Body] =
    Request(Method.GET, uri, Headers.empty, Body.Empty)

  /** Creates a HEAD request with empty body.
    */
  def head(uri: Uri): Request[Body] =
    Request(Method.HEAD, uri, Headers.empty, Body.Empty)

  /** Creates a DELETE request with empty body.
    */
  def delete(uri: Uri): Request[Body] =
    Request(Method.DELETE, uri, Headers.empty, Body.Empty)

  /** Creates a POST request with a body.
    */
  def post(uri: Uri, body: Body): Request[Body] =
    Request(Method.POST, uri, Headers.empty, body)

  /** Creates a POST request with an encoded body.
    */
  def postEncoded[A](uri: Uri, value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Request[Body]] =
    encoder.encode(value).map(body => Request(Method.POST, uri, Headers.empty, body))

  /** Creates a PUT request with a body.
    */
  def put(uri: Uri, body: Body): Request[Body] =
    Request(Method.PUT, uri, Headers.empty, body)

  /** Creates a PUT request with an encoded body.
    */
  def putEncoded[A](uri: Uri, value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Request[Body]] =
    encoder.encode(value).map(body => Request(Method.PUT, uri, Headers.empty, body))

  /** Creates a PATCH request with a body.
    */
  def patch(uri: Uri, body: Body): Request[Body] =
    Request(Method.PATCH, uri, Headers.empty, body)

  /** Creates a PATCH request with an encoded body.
    */
  def patchEncoded[A](uri: Uri, value: A)(using encoder: BodyEncoder[A]): Eru[EncodeError, Request[Body]] =
    encoder.encode(value).map(body => Request(Method.PATCH, uri, Headers.empty, body))

  /** Creates an OPTIONS request with empty body.
    */
  def options(uri: Uri): Request[Body] =
    Request(Method.OPTIONS, uri, Headers.empty, Body.Empty)
}

/** HTTP version.
  */
enum HttpVersion(val major: Int, val minor: Int) {
  case HTTP_1_0 extends HttpVersion(1, 0)
  case HTTP_1_1 extends HttpVersion(1, 1)
  case HTTP_2_0 extends HttpVersion(2, 0)
  case HTTP_3_0 extends HttpVersion(3, 0)

  def value: String = s"HTTP/$major.$minor"
}

/** Request validation error.
  */
final case class InvalidRequest(
  reason: String,
  rfc: String
) extends Exception(s"Invalid request: $reason ($rfc)")
