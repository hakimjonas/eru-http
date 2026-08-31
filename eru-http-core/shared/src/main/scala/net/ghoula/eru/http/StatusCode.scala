package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP response status code as defined in RFC 9110 Section 15.
  *
  * This opaque type ensures only valid HTTP status codes (100-599) can exist. Status codes have
  * semantic meaning that affects caching, retries, and client behavior.
  */
opaque type StatusCode = Int

object StatusCode {

  /** 1xx Informational status codes indicate that the request was received and is being processed.
    * RFC 9110 Section 15.2
    */

  /** 100 Continue: Client should continue with the request. */
  val Continue: StatusCode = 100

  /** 101 Switching Protocols: Server is switching protocols as requested. */
  val SwitchingProtocols: StatusCode = 101

  /** 2xx Successful status codes indicate that the request was successfully received, understood,
    * and accepted. RFC 9110 Section 15.3
    */

  /** 200 OK: Standard successful response. */
  val Ok: StatusCode = 200

  /** 201 Created: Request has been fulfilled and new resource created. */
  val Created: StatusCode = 201

  /** 202 Accepted: Request accepted for processing but not yet completed. */
  val Accepted: StatusCode = 202

  /** 203 Non-Authoritative Information: Successful but transformed response. */
  val NonAuthoritativeInfo: StatusCode = 203

  /** 204 No Content: Successful request with no content to return. */
  val NoContent: StatusCode = 204

  /** 206 Partial Content: Partial GET request fulfilled. */
  val PartialContent: StatusCode = 206

  /** 3xx Redirection status codes indicate that further action needs to be taken to complete the
    * request. RFC 9110 Section 15.4
    */

  /** 300 Multiple Choices: Multiple options for the resource. */
  val MultipleChoices: StatusCode = 300

  /** 301 Moved Permanently: Resource permanently moved to new URI. */
  val MovedPermanently: StatusCode = 301

  /** 302 Found: Resource temporarily available at different URI. */
  val Found: StatusCode = 302

  /** 303 See Other: Response found at different URI using GET. */
  val SeeOther: StatusCode = 303

  /** 304 Not Modified: Resource has not been modified since last request. */
  val NotModified: StatusCode = 304

  /** 307 Temporary Redirect: Resource temporarily at different URI, preserve method. */
  val TemporaryRedirect: StatusCode = 307

  /** 308 Permanent Redirect: Resource permanently at different URI, preserve method (RFC 7538). */
  val PermanentRedirect: StatusCode = 308

  /** 4xx Client Error status codes indicate that the client seems to have made an error. RFC 9110
    * Section 15.5
    */

  /** 400 Bad Request: Server cannot process request due to client error. */
  val BadRequest: StatusCode = 400

  /** 401 Unauthorized: Authentication is required and has failed or not been provided. */
  val Unauthorized: StatusCode = 401

  /** 403 Forbidden: Server refuses to authorize the request. */
  val Forbidden: StatusCode = 403

  /** 404 Not Found: Requested resource not found. */
  val NotFound: StatusCode = 404

  /** 405 Method Not Allowed: Request method not supported for resource. */
  val MethodNotAllowed: StatusCode = 405

  /** 407 Proxy Authentication Required: Client must authenticate with proxy. */
  val ProxyAuthRequired: StatusCode = 407

  /** 408 Request Timeout: Server timed out waiting for request. */
  val RequestTimeout: StatusCode = 408

  /** 409 Conflict: Request conflicts with current state of server. */
  val Conflict: StatusCode = 409

  /** 410 Gone: Resource permanently removed. */
  val Gone: StatusCode = 410

  /** 411 Length Required: Content-Length header required. */
  val LengthRequired: StatusCode = 411

  /** 412 Precondition Failed: Precondition in headers evaluated to false. */
  val PreconditionFailed: StatusCode = 412

  /** 413 Content Too Large: Request payload too large. */
  val ContentTooLarge: StatusCode = 413

  /** 414 URI Too Long: Request URI too long. */
  val URITooLong: StatusCode = 414

  /** 415 Unsupported Media Type: Media type not supported. */
  val UnsupportedMediaType: StatusCode = 415

  /** 416 Range Not Satisfiable: Range header cannot be satisfied. */
  val RangeNotSatisfiable: StatusCode = 416

  /** 418 I'm a teapot: Server refuses to brew coffee with a teapot (RFC 2324, April Fools' RFC). */
  val ImATeapot: StatusCode = 418

  /** 429 Too Many Requests: User has sent too many requests in given time (RFC 6585). */
  val TooManyRequests: StatusCode = 429

  /** 5xx Server Error status codes indicate that the server failed to fulfill a valid request. RFC
    * 9110 Section 15.6
    */

  /** 500 Internal Server Error: Generic server error. */
  val InternalServerError: StatusCode = 500

  /** 501 Not Implemented: Server does not support functionality required. */
  val NotImplemented: StatusCode = 501

  /** 502 Bad Gateway: Invalid response from upstream server. */
  val BadGateway: StatusCode = 502

  /** 503 Service Unavailable: Server temporarily unable to handle request. */
  val ServiceUnavailable: StatusCode = 503

  /** 504 Gateway Timeout: Upstream server failed to send request in time. */
  val GatewayTimeout: StatusCode = 504

  /** Creates a StatusCode from an integer, validating the range.
    *
    * @param code
    *   the status code value to validate
    * @return
    *   a validated StatusCode or an InvalidStatusCode error
    */
  def apply(code: Int): Eru[InvalidStatusCode, StatusCode] = {
    if code >= 100 && code < 600 then {
      Eru.succeed(code)
    } else {
      Eru.fail(InvalidStatusCode(code, "Status code must be between 100 and 599"))
    }
  }

  extension (status: StatusCode) {

    /** The numeric value of this status code.
      *
      * @return
      *   the status code as an integer
      */
    def value: Int = status

    /** Status class per RFC 9110 Section 15. Total: `StatusCode.apply` is the only public
      * constructor and guarantees 100 ≤ status ≤ 599, so `status / 100` is in {1..5}.
      *
      * @return
      *   the status class (Informational, Successful, Redirection, ClientError, or ServerError)
      */
    def statusClass: StatusClass =
      if isInformational then StatusClass.Informational
      else if isSuccessful then StatusClass.Successful
      else if isRedirection then StatusClass.Redirection
      else if isClientError then StatusClass.ClientError
      else StatusClass.ServerError

    def isInformational: Boolean = status >= 100 && status < 200
    def isSuccessful: Boolean = status >= 200 && status < 300
    def isRedirection: Boolean = status >= 300 && status < 400
    def isClientError: Boolean = status >= 400 && status < 500
    def isServerError: Boolean = status >= 500 && status < 600
    def isError: Boolean = isClientError || isServerError

    /** Whether this status code indicates the request can be retried. The set follows common client
      * convention: 408, 429, 500, 502, 503, and 504.
      */
    def isRetryable: Boolean = status match {
      case RequestTimeout | TooManyRequests | InternalServerError | BadGateway | ServiceUnavailable | GatewayTimeout =>
        true
      case _ => false
    }

    /** Whether responses with this status are cacheable by default. RFC 9111 Section 3
      */
    def isCacheable: Boolean = status match {
      case Ok | NonAuthoritativeInfo | NoContent | PartialContent | MultipleChoices | MovedPermanently | NotFound |
          MethodNotAllowed | Gone | URITooLong | NotImplemented =>
        true
      case _ => false
    }

    /** Whether this status allows a response body. RFC 9110 Section 15.
      *
      * 1xx informational responses never carry bodies.
      */
    def allowsResponseBody: Boolean = status match {
      case NoContent | NotModified => false
      case s if s < 200 => false
      case _ => true
    }

    /** Headers this library requires on responses with this status.
      *
      * RFC 9110 phrases some of these as SHOULD rather than MUST (Location on 201, Section 15.3.2);
      * the model enforces them as required. For NotModified (304), at least one of ETag,
      * Cache-Control, Content-Location, Date, or Vary must be present, but we can't express "at
      * least one" with a simple Set. This needs special handling. For ServiceUnavailable (503),
      * Retry-After is recommended but not required.
      */
    def requiredHeaders: Set[String] = status match {
      case Created => Set("Location")
      case PartialContent => Set("Content-Range")
      case NotModified => Set.empty
      case MethodNotAllowed => Set("Allow")
      case ProxyAuthRequired => Set("Proxy-Authenticate")
      case Unauthorized => Set("WWW-Authenticate")
      case RangeNotSatisfiable => Set("Content-Range")
      case ServiceUnavailable => Set.empty
      case _ => Set.empty
    }

    /** Human-readable reason phrase. Note: HTTP/2 and HTTP/3 don't transmit reason phrases.
      */
    def reasonPhrase: String = status match {
      case Continue => "Continue"
      case SwitchingProtocols => "Switching Protocols"
      case Ok => "OK"
      case Created => "Created"
      case Accepted => "Accepted"
      case NoContent => "No Content"
      case MovedPermanently => "Moved Permanently"
      case Found => "Found"
      case SeeOther => "See Other"
      case NotModified => "Not Modified"
      case TemporaryRedirect => "Temporary Redirect"
      case PermanentRedirect => "Permanent Redirect"
      case BadRequest => "Bad Request"
      case Unauthorized => "Unauthorized"
      case Forbidden => "Forbidden"
      case NotFound => "Not Found"
      case MethodNotAllowed => "Method Not Allowed"
      case Conflict => "Conflict"
      case Gone => "Gone"
      case LengthRequired => "Length Required"
      case PreconditionFailed => "Precondition Failed"
      case ContentTooLarge => "Content Too Large"
      case URITooLong => "URI Too Long"
      case UnsupportedMediaType => "Unsupported Media Type"
      case ImATeapot => "I'm a teapot"
      case TooManyRequests => "Too Many Requests"
      case InternalServerError => "Internal Server Error"
      case NotImplemented => "Not Implemented"
      case BadGateway => "Bad Gateway"
      case ServiceUnavailable => "Service Unavailable"
      case GatewayTimeout => "Gateway Timeout"
      case _ => s"Status $status"
    }
  }

  /** Status code classes per RFC 9110 Section 15.
    *
    * Informational covers 1xx, Successful 2xx, Redirection 3xx, ClientError 4xx, and ServerError
    * 5xx.
    */
  enum StatusClass {
    case Informational
    case Successful
    case Redirection
    case ClientError
    case ServerError
  }

  /** Error for invalid status codes.
    */
  final case class InvalidStatusCode(
    code: Int,
    reason: String,
    rfc: String = "RFC 9110 Section 15"
  ) {
    def message: String = s"Invalid status code $code: $reason ($rfc)"
  }
}
