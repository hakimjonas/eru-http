package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP request as defined in RFC 9110.
  *
  * @tparam A
  *   the body type
  * @param method
  *   the request method
  * @param uri
  *   the request target URI
  * @param headers
  *   the header fields
  * @param body
  *   the request body
  * @param version
  *   the HTTP version, HTTP/1.1 by default
  * @param clientAddress
  *   the client address the server resolved for this request (TCP peer, PROXY-protocol-derived, or
  *   trusted-XFF-derived); `None` on client-constructed requests
  * @param attributes
  *   typed, server-attached attributes (request ids, trace context); they survive `copy` and the
  *   `with*` builders
  */
final case class Request[+A](
  method: Method,
  uri: Uri,
  headers: Headers,
  body: A,
  version: HttpVersion = HttpVersion.HTTP_1_1,
  clientAddress: Option[ClientAddress] = None,
  attributes: Map[AttributeKey[?], Any] = Map.empty
) {

  /** Reads the attribute stored under `key`, if any.
    *
    * The key's `ClassTag` checks the stored value's runtime class, so a mis-typed store surfaces as
    * `None` rather than a surprise `ClassCastException` at the consumer.
    */
  def attribute[A](key: AttributeKey[A]): Option[A] =
    attributes.get(key).flatMap(key.classTag.unapply)

  /** Attaches `value` under `key`, replacing any previous value for that key.
    */
  def withAttribute[B](key: AttributeKey[B], value: B): Request[A] =
    copy(attributes = attributes.updated(key, value))

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

  /** Sets the Authorization header.
    */
  def withAuthorization(auth: String): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    setHeader(HeaderNames.Authorization, auth)

  /** Sets a bearer token authorization.
    */
  def withBearerToken(token: String): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Request[A]] =
    withAuthorization(s"Bearer $token")

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
      _ <- validateQueryContentType
      _ <- validateForbiddenHeaderCombinations
    } yield this
  }

  @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
  private def validateMethodBodyCombination: Eru[InvalidRequest, Unit] = {
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

  /** RFC 10008 Section 2: a QUERY request defines its query by request content and its media type;
    * servers MUST fail the request if Content-Type is missing or inconsistent with the content.
    * Consistency with the content is application knowledge, so the model enforces presence;
    * handlers that understand the media type enforce the rest (415/400/422).
    */
  private def validateQueryContentType: Eru[InvalidRequest, Unit] = {
    if method == Method.QUERY && !headers.contains(HeaderNames.ContentType) then {
      Eru.fail(
        InvalidRequest(
          "Content-Type header is required for QUERY requests",
          "RFC 10008 Section 2"
        )
      )
    } else {
      Eru.unit
    }
  }

  private def validateForbiddenHeaderCombinations: Eru[InvalidRequest, Unit] = {
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

/** Typed key for [[Request.attributes]].
  *
  * Equality is by name plus the tag's runtime class, so two independently-created keys for the same
  * (name, type) pair are the same key. Prefer shared constants over ad-hoc construction.
  *
  * @tparam A
  *   the value type stored under this key
  */
final case class AttributeKey[A](name: String)(using val classTag: scala.reflect.ClassTag[A]) derives CanEqual {
  @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
  override def equals(that: Any): Boolean = that match {
    case other: AttributeKey[?] =>
      other.name == name && other.classTag.runtimeClass == classTag.runtimeClass
    case _ => false
  }

  override def hashCode: Int = name.hashCode * 31 + classTag.runtimeClass.hashCode()
}

object Request {

  /** Creates a GET request with empty body.
    */
  def get(uri: Uri): Request[Body] =
    Request(Method.GET, uri, Headers.empty, Body.Empty)

  /** Creates a DELETE request with empty body.
    */
  def delete(uri: Uri): Request[Body] =
    Request(Method.DELETE, uri, Headers.empty, Body.Empty)

  /** Creates a POST request with a body.
    */
  def post(uri: Uri, body: Body): Request[Body] =
    Request(Method.POST, uri, Headers.empty, body)

  /** Creates a PUT request with a body.
    */
  def put(uri: Uri, body: Body): Request[Body] =
    Request(Method.PUT, uri, Headers.empty, body)

  /** Creates a QUERY request with a query body. RFC 10008 Section 2.
    */
  def query(uri: Uri, body: Body): Request[Body] =
    Request(Method.QUERY, uri, Headers.empty, body)
}

/** HTTP version.
  */
enum HttpVersion(val major: Int, val minor: Int) derives CanEqual {
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
) {
  def message: String = s"Invalid request: $reason ($rfc)"
}
