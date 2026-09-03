package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP response status code as defined in RFC 9110 Section 15.
  *
  * This opaque type ensures only valid HTTP status codes (100-599) can exist. Status codes have
  * semantic meaning that affects caching, retries, and client behavior.
  */
opaque type StatusCode = Int

object StatusCode {

  /** The registry below covers every status code in the IANA HTTP Status Code Registry at the time
    * of writing — RFC 9110 plus the registered extensions (RFC 4918, 5842, 6585, 7725, 8297, 8470,
    * 3229, 2295, 2774, 2324). Unregistered codes in 100-599 remain constructible via
    * [[StatusCode.apply]] and render their reason phrase as `Status N`.
    */
  /** 1xx Informational status codes indicate that the request was received and is being processed.
    * RFC 9110 Section 15.2
    */

  /** 100 Continue: Client should continue with the request. */
  val Continue: StatusCode = 100

  /** 101 Switching Protocols: Server is switching protocols as requested. */
  val SwitchingProtocols: StatusCode = 101

  /** 102 Processing: Request received, processing continues (RFC 4918 Section 10.1). */
  val Processing: StatusCode = 102

  /** 103 Early Hints: Preliminary response headers before final response (RFC 8297). */
  val EarlyHints: StatusCode = 103

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

  /** 205 Reset Content: Client should reset the document view; cannot contain content. */
  val ResetContent: StatusCode = 205

  /** 206 Partial Content: Partial GET request fulfilled. */
  val PartialContent: StatusCode = 206

  /** 207 Multi-Status: Multiple status codes in one XML body (RFC 4918 Section 10.2). */
  val MultiStatus: StatusCode = 207

  /** 208 Already Reported: Members of a DAV binding already reported (RFC 5842 Section 7.1). */
  val AlreadyReported: StatusCode = 208

  /** 226 IM Used: Instance manipulation applied (RFC 3229 Section 10.4.1). */
  val IMUsed: StatusCode = 226

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

  /** 305 Use Proxy: Request must be sent through a proxy. Deprecated by RFC 9110 Section 15.4.6. */
  val UseProxy: StatusCode = 305

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

  /** 402 Payment Required: Reserved for future use (RFC 9110 Section 15.5.3). */
  val PaymentRequired: StatusCode = 402

  /** 403 Forbidden: Server refuses to authorize the request. */
  val Forbidden: StatusCode = 403

  /** 404 Not Found: Requested resource not found. */
  val NotFound: StatusCode = 404

  /** 405 Method Not Allowed: Request method not supported for resource. */
  val MethodNotAllowed: StatusCode = 405

  /** 406 Not Acceptable: Server cannot produce a response matching the request's Accept. */
  val NotAcceptable: StatusCode = 406

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

  /** 417 Expectation Failed: Expect header cannot be met. */
  val ExpectationFailed: StatusCode = 417

  /** 418 I'm a teapot: Server refuses to brew coffee with a teapot (RFC 2324, April Fools' RFC). */
  val ImATeapot: StatusCode = 418

  /** 421 Misdirected Request: Request sent to a server that cannot produce a response. */
  val MisdirectedRequest: StatusCode = 421

  /** 422 Unprocessable Content: Semantic errors in the request (RFC 4918 Section 11.2, RFC 9457).
    */
  val UnprocessableContent: StatusCode = 422

  /** 423 Locked: Resource is locked (RFC 4918 Section 11.3). */
  val Locked: StatusCode = 423

  /** 424 Failed Dependency: Dependent request failed (RFC 4918 Section 11.4). */
  val FailedDependency: StatusCode = 424

  /** 425 Too Early: Server unwilling to process a request that might be replayed (RFC 8470). */
  val TooEarly: StatusCode = 425

  /** 426 Upgrade Required: Client must upgrade to a different protocol. */
  val UpgradeRequired: StatusCode = 426

  /** 428 Precondition Required: Request must be conditional (RFC 6585 Section 3). */
  val PreconditionRequired: StatusCode = 428

  /** 429 Too Many Requests: User has sent too many requests in given time (RFC 6585). */
  val TooManyRequests: StatusCode = 429

  /** 431 Request Header Fields Too Large: Header fields too large to process (RFC 6585 Section 5).
    */
  val RequestHeaderFieldsTooLarge: StatusCode = 431

  /** 451 Unavailable For Legal Reasons: Resource unavailable for legal reasons (RFC 7725). */
  val UnavailableForLegalReasons: StatusCode = 451

  /** 5xx Server Error status codes indicate that the server failed to fulfill a valid request. RFC
    * 9110 Section 15.6
    */

  /** 500 Internal Server Error: Generic server error. */
  val InternalServerError: StatusCode = 500

  /** 501 Not Implemented: Server does not support functionality required to fulfill the request. */
  val NotImplemented: StatusCode = 501

  /** 502 Bad Gateway: Invalid response from upstream server. */
  val BadGateway: StatusCode = 502

  /** 503 Service Unavailable: Server temporarily unable to handle request. */
  val ServiceUnavailable: StatusCode = 503

  /** 504 Gateway Timeout: Upstream server failed to send request in time. */
  val GatewayTimeout: StatusCode = 504

  /** 505 HTTP Version Not Supported: HTTP version not supported by the server. */
  val HttpVersionNotSupported: StatusCode = 505

  /** 506 Variant Also Negotiates: Transparent content negotiation in a loop (RFC 2295 Section 8.1,
    * experimental).
    */
  val VariantAlsoNegotiates: StatusCode = 506

  /** 507 Insufficient Storage: Server cannot store the representation (RFC 4918 Section 11.5). */
  val InsufficientStorage: StatusCode = 507

  /** 508 Loop Detected: Infinite loop while processing a request (RFC 5842 Section 7.2). */
  val LoopDetected: StatusCode = 508

  /** 510 Not Extended: Further extensions required (RFC 2774 Section 4, experimental). */
  val NotExtended: StatusCode = 510

  /** 511 Network Authentication Required: Client must authenticate with the network (RFC 6585
    * Section 6).
    */
  val NetworkAuthenticationRequired: StatusCode = 511

  /** Enables `==` under `-language:strictEquality`. */
  given CanEqual[StatusCode, StatusCode] = CanEqual.derived

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
      * 1xx informational responses never carry bodies; 204, 205, and 304 cannot contain content.
      */
    def allowsResponseBody: Boolean = status match {
      case NoContent | ResetContent | NotModified => false
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
      case UpgradeRequired => Set("Upgrade")
      case ServiceUnavailable => Set.empty
      case _ => Set.empty
    }

    /** Human-readable reason phrase. Note: HTTP/2 and HTTP/3 don't transmit reason phrases.
      */
    def reasonPhrase: String = status match {
      case Continue => "Continue"
      case SwitchingProtocols => "Switching Protocols"
      case Processing => "Processing"
      case EarlyHints => "Early Hints"
      case Ok => "OK"
      case Created => "Created"
      case Accepted => "Accepted"
      case NonAuthoritativeInfo => "Non-Authoritative Information"
      case NoContent => "No Content"
      case ResetContent => "Reset Content"
      case PartialContent => "Partial Content"
      case MultiStatus => "Multi-Status"
      case AlreadyReported => "Already Reported"
      case IMUsed => "IM Used"
      case MultipleChoices => "Multiple Choices"
      case MovedPermanently => "Moved Permanently"
      case Found => "Found"
      case SeeOther => "See Other"
      case NotModified => "Not Modified"
      case UseProxy => "Use Proxy"
      case TemporaryRedirect => "Temporary Redirect"
      case PermanentRedirect => "Permanent Redirect"
      case BadRequest => "Bad Request"
      case Unauthorized => "Unauthorized"
      case PaymentRequired => "Payment Required"
      case Forbidden => "Forbidden"
      case NotFound => "Not Found"
      case MethodNotAllowed => "Method Not Allowed"
      case NotAcceptable => "Not Acceptable"
      case ProxyAuthRequired => "Proxy Authentication Required"
      case RequestTimeout => "Request Timeout"
      case Conflict => "Conflict"
      case Gone => "Gone"
      case LengthRequired => "Length Required"
      case PreconditionFailed => "Precondition Failed"
      case ContentTooLarge => "Content Too Large"
      case URITooLong => "URI Too Long"
      case UnsupportedMediaType => "Unsupported Media Type"
      case RangeNotSatisfiable => "Range Not Satisfiable"
      case ExpectationFailed => "Expectation Failed"
      case ImATeapot => "I'm a teapot"
      case MisdirectedRequest => "Misdirected Request"
      case UnprocessableContent => "Unprocessable Content"
      case Locked => "Locked"
      case FailedDependency => "Failed Dependency"
      case TooEarly => "Too Early"
      case UpgradeRequired => "Upgrade Required"
      case PreconditionRequired => "Precondition Required"
      case TooManyRequests => "Too Many Requests"
      case RequestHeaderFieldsTooLarge => "Request Header Fields Too Large"
      case UnavailableForLegalReasons => "Unavailable For Legal Reasons"
      case InternalServerError => "Internal Server Error"
      case NotImplemented => "Not Implemented"
      case BadGateway => "Bad Gateway"
      case ServiceUnavailable => "Service Unavailable"
      case GatewayTimeout => "Gateway Timeout"
      case HttpVersionNotSupported => "HTTP Version Not Supported"
      case VariantAlsoNegotiates => "Variant Also Negotiates"
      case InsufficientStorage => "Insufficient Storage"
      case LoopDetected => "Loop Detected"
      case NotExtended => "Not Extended"
      case NetworkAuthenticationRequired => "Network Authentication Required"
      case _ => s"Status $status"
    }
  }

  /** Status code classes per RFC 9110 Section 15.
    *
    * Informational covers 1xx, Successful 2xx, Redirection 3xx, ClientError 4xx, and ServerError
    * 5xx.
    */
  enum StatusClass derives CanEqual {
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
