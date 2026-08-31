package net.ghoula.eru

/** Core types and utilities for HTTP as defined in RFC 9110.
  *
  * This package provides type-safe representations of HTTP concepts. Invalid values are rejected at
  * runtime by validated constructors before they reach the wire.
  *
  * Import `net.ghoula.eru.http.*` to get started.
  */
package object http {

  /** Common HTTP error type.
    */
  enum HttpError {
    case InvalidMethod(error: Method.InvalidMethod)
    case InvalidStatusCode(error: StatusCode.InvalidStatusCode)
    case InvalidUri(error: Uri.InvalidUri)
    case InvalidMediaType(error: MediaType.InvalidMediaType)
    case InvalidRequest(error: http.InvalidRequest)
    case InvalidResponse(error: http.InvalidResponse)
    case BodyEncodeError(error: http.EncodeError)
    case BodyDecodeError(error: http.DecodeError)
    case InvalidCookie(error: Cookie.InvalidCookie)
    case NetworkError(msg: String, cause: Option[Throwable] = None)
    case TimeoutError(msg: String)
    case ConnectionError(msg: String, cause: Option[Throwable] = None)
    case ProtocolError(msg: String, rfc: String)
    case PayloadTooLarge(declaredSize: Long, maxSize: Int)

    def message: String = this match {
      case InvalidMethod(e) => e.message
      case InvalidStatusCode(e) => e.message
      case InvalidUri(e) => e.message
      case InvalidMediaType(e) => e.message
      case InvalidRequest(e) => e.message
      case InvalidResponse(e) => e.message
      case BodyEncodeError(e) => e.message
      case BodyDecodeError(e) => e.message
      case InvalidCookie(e) => e.message
      case NetworkError(m, _) => m
      case TimeoutError(m) => m
      case ConnectionError(m, _) => m
      case ProtocolError(m, rfc) => s"$m ($rfc)"
      case PayloadTooLarge(declared, max) =>
        s"Request payload of $declared bytes exceeds maximum $max bytes (RFC 9110 Section 15.5.14)"
    }

    def toException: Exception = this match {
      case InvalidMethod(e) => new Exception(e.message)
      case InvalidStatusCode(e) => new Exception(e.message)
      case InvalidUri(e) => new Exception(e.message)
      case InvalidMediaType(e) => new Exception(e.message)
      case InvalidRequest(e) => new Exception(e.message)
      case InvalidResponse(e) => new Exception(e.message)
      case BodyEncodeError(e) => e.cause.fold(new Exception(e.message))(c => new Exception(e.message, c))
      case BodyDecodeError(e) => e.cause.fold(new Exception(e.message))(c => new Exception(e.message, c))
      case InvalidCookie(e) => new Exception(e.message)
      case NetworkError(m, Some(cause)) => new Exception(m, cause)
      case NetworkError(m, None) => new Exception(m)
      case TimeoutError(m) => new Exception(s"Timeout: $m")
      case ConnectionError(m, Some(cause)) => new Exception(m, cause)
      case ConnectionError(m, None) => new Exception(m)
      case ProtocolError(m, rfc) => new Exception(s"$m ($rfc)")
      case e: PayloadTooLarge => new Exception(e.message)
    }
  }

  /** Common media type shortcuts.
    */
  val json: MediaType = MediaType.applicationJson
  val xml: MediaType = MediaType.applicationXml
  val html: MediaType = MediaType.textHtml
  val text: MediaType = MediaType.textPlain
  val binary: MediaType = MediaType.applicationOctetStream
}
