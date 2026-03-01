package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP request method as defined in RFC 9110 Section 9.
  *
  * This is an opaque type ensuring only valid HTTP methods can be constructed. Methods have
  * semantic properties (safe, idempotent, cacheable) that affect how they can be used.
  */
opaque type Method = String

object Method {

  /** Standard HTTP methods with their semantic properties per RFC 9110. */

  /** GET method: Retrieves a resource. Safe, idempotent, and cacheable. */
  val GET: Method = "GET"

  /** HEAD method: Like GET but returns only headers. Safe, idempotent, and cacheable. */
  val HEAD: Method = "HEAD"

  /** POST method: Submits data to be processed. Not safe, not idempotent. */
  val POST: Method = "POST"

  /** PUT method: Replaces or creates a resource. Not safe but idempotent. */
  val PUT: Method = "PUT"

  /** DELETE method: Removes a resource. Not safe but idempotent. */
  val DELETE: Method = "DELETE"

  /** CONNECT method: Establishes a tunnel to the server. Not safe, not idempotent. */
  val CONNECT: Method = "CONNECT"

  /** OPTIONS method: Describes communication options. Safe and idempotent. */
  val OPTIONS: Method = "OPTIONS"

  /** TRACE method: Performs a message loop-back test. Safe and idempotent. */
  val TRACE: Method = "TRACE"

  /** PATCH method: Applies partial modifications. Not safe, not idempotent. */
  val PATCH: Method = "PATCH"

  /** Parser for custom HTTP methods.
    *
    * Per RFC 9110 Section 9.1, method names are case-sensitive and must consist of token characters
    * only.
    *
    * @param value
    *   the method string to parse
    * @return
    *   a validated Method or an InvalidMethod error
    */
  def parse(value: String): Eru[InvalidMethod, Method] = {
    if isValidToken(value) then {
      Eru.succeed(value)
    } else {
      Eru.fail(InvalidMethod(value, "RFC 9110 Section 9.1: Method must be a valid token"))
    }
  }

  /** Unsafe constructor for internal use only.
    */
  private[http] def unsafeFromString(value: String): Method = value

  extension (method: Method) {

    /** The string representation of this method.
      *
      * @return
      *   the method name as a string
      */
    def value: String = method

    /** A safe method does not change server state. RFC 9110 Section 9.2.1
      *
      * @return
      *   true if the method is safe (GET, HEAD, OPTIONS, TRACE)
      */
    def isSafe: Boolean = method match {
      case GET | HEAD | OPTIONS | TRACE => true
      case _ => false
    }

    /** An idempotent method can be called multiple times with the same effect. RFC 9110 Section
      * 9.2.2
      *
      * @return
      *   true if the method is idempotent (GET, HEAD, PUT, DELETE, OPTIONS, TRACE)
      */
    def isIdempotent: Boolean = method match {
      case GET | HEAD | PUT | DELETE | OPTIONS | TRACE => true
      case _ => false
    }

    /** Whether responses to this method are cacheable by default. RFC 9111 Section 3
      *
      * @return
      *   true if the method allows caching by default (GET, HEAD, POST with explicit headers)
      */
    def isCacheable: Boolean = method match {
      case GET | HEAD => true
      case POST => true // POST can be cacheable with explicit headers
      case _ => false
    }

    /** Whether this method allows a request body. RFC 9110 Section 9
      *
      * @return
      *   true if the method allows a request body
      */
    def allowsRequestBody: Boolean = method match {
      case GET | HEAD | DELETE | TRACE => false
      case _ => true
    }

    /** Whether this method requires a request body.
      *
      * @return
      *   true if the method requires a body (POST, PUT, PATCH)
      */
    def requiresRequestBody: Boolean = method match {
      case POST | PUT | PATCH => true
      case _ => false
    }

    /** Whether this method expects a response body.
      *
      * @return
      *   true if the method expects a response body (all except HEAD)
      */
    def expectsResponseBody: Boolean = method match {
      case HEAD => false
      case _ => true
    }
  }

  /** Validates that a string is a valid HTTP token per RFC 9110.
    *
    * token = 1*tchar tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." / "^" / "_"
    * / "`" / "|" / "~" / DIGIT / ALPHA
    */
  private def isValidToken(s: String): Boolean = {
    s.nonEmpty && s.forall(isTokenChar)
  }

  private def isTokenChar(c: Char): Boolean = {
    c match {
      case '!' | '#' | '$' | '%' | '&' | '\'' | '*' | '+' | '-' | '.' | '^' | '_' | '`' | '|' | '~' => true
      case c if c.isLetterOrDigit => true
      case _ => false
    }
  }

  /** Error returned when parsing an invalid method.
    */
  final case class InvalidMethod(
    value: String,
    reason: String,
    rfc: String = "RFC 9110 Section 9"
  ) {
    def message: String = s"Invalid HTTP method '$value': $reason ($rfc)"
  }
}
