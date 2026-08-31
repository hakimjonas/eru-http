package net.ghoula.eru.http

import scala.collection.immutable.TreeMap

import net.ghoula.eru.*

/** HTTP headers collection with case-insensitive names and multi-value support per RFC 9110 Section 5.
  *
  * Headers are stored internally with case-insensitive keys but preserve the original casing for
  * transmission.
  */
final case class Headers(private val underlying: TreeMap[CIString, List[HeaderValue]]) {

  /** Gets all values for a header name. Header names are case-insensitive per RFC 9110 Section 5.1.
    *
    * @param name
    *   the header name to retrieve
    * @return
    *   a list of all values for the header, or None if the header does not exist
    */
  def get(name: String): Option[List[HeaderValue]] =
    underlying.get(CIString(name))

  /** Gets the first value for a header name.
    *
    * @param name
    *   the header name to retrieve
    * @return
    *   the first value for the header, or None if the header does not exist
    */
  def getFirst(name: String): Option[HeaderValue] =
    get(name).flatMap(_.headOption)

  /** Adds a header value with validation. If the header already exists, the value is appended to
    * the list.
    *
    * @param name
    *   the header name
    * @param value
    *   the header value to add
    * @return
    *   updated Headers or a validation error
    */
  def add(name: String, value: String): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Headers] = {
    for {
      _ <- HeaderName.parse(name)
      headerValue <- HeaderValue.parse(value)
    } yield {
      val key = CIString(name)
      val updated = underlying.get(key) match {
        case Some(existing) => underlying.updated(key, existing :+ headerValue)
        case None => underlying.updated(key, List(headerValue))
      }
      Headers(updated)
    }
  }

  /** Internal unsafe add for pre-validated values.
    */
  private[http] def unsafeAdd(name: String, value: HeaderValue): Headers = {
    val key = CIString(name)
    val updated = underlying.get(key) match {
      case Some(existing) => underlying.updated(key, existing :+ value)
      case None => underlying.updated(key, List(value))
    }
    Headers(updated)
  }

  /** Sets a header value with validation, replacing any existing values.
    *
    * @param name
    *   the header name
    * @param value
    *   the header value to set
    * @return
    *   updated Headers or a validation error
    */
  def set(name: String, value: String): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Headers] = {
    for {
      _ <- HeaderName.parse(name)
      headerValue <- HeaderValue.parse(value)
    } yield {
      val key = CIString(name)
      Headers(underlying.updated(key, List(headerValue)))
    }
  }

  /** Removes all values for a header name.
    *
    * @param name
    *   the header name to remove
    * @return
    *   updated Headers without the specified header
    */
  def remove(name: String): Headers = {
    Headers(underlying.removed(CIString(name)))
  }

  /** Checks if a header exists.
    *
    * @param name
    *   the header name to check
    * @return
    *   true if the header exists, false otherwise
    */
  def contains(name: String): Boolean =
    underlying.contains(CIString(name))

  /** Returns all headers as a list of name-value pairs. Each value in a multi-value header becomes
    * a separate pair.
    *
    * @return
    *   list of (name, value) pairs for all headers
    */
  def toList: List[(String, String)] =
    underlying.flatMap { case (name, values) =>
      values.map(v => (name.original, v.value))
    }.toList

  /** Iterates all header name-value pairs without allocating an intermediate collection.
    */
  def foreach(f: (String, String) => Unit): Unit =
    underlying.foreachEntry { (name, values) =>
      values.foreach(v => f(name.original, v.value))
    }

  /** Combines two Headers collections. Values from the other Headers are appended to this one.
    */
  def ++(other: Headers): Headers = {
    val combined = other.underlying.foldLeft(underlying) { case (acc, (name, values)) =>
      acc.get(name) match {
        case Some(existing) => acc.updated(name, existing ++ values)
        case None => acc.updated(name, values)
      }
    }
    Headers(combined)
  }

  /** Returns the number of distinct header names.
    */
  def size: Int = underlying.size

  /** Returns true if there are no headers.
    */
  def isEmpty: Boolean = underlying.isEmpty

  /** Returns true if there are headers.
    */
  def nonEmpty: Boolean = underlying.nonEmpty

  /** Gets the raw Content-Type header value without parsing. Use this when you just need the string
    * value.
    */
  def contentTypeRaw: Option[String] =
    getFirst("Content-Type").map(_.value)

  /** Gets the Content-Type header parsed as MediaType with proper error handling.
    *
    * @return
    *   Eru[InvalidMediaType, Option[MediaType]] - None if header missing, Some if present and valid
    */
  def contentType: Eru[MediaType.InvalidMediaType, Option[MediaType]] =
    getFirst("Content-Type") match {
      case None => Eru.succeed(None)
      case Some(value) => MediaType.parse(value.value).map(Some(_))
    }

  /** Gets the Accept header parsed as a list of MediaTypes with proper error handling.
    *
    * This method parses all media types in the Accept header. If any media type fails to parse, the
    * entire operation fails with that error (fail-fast semantics).
    *
    * @return
    *   Eru[InvalidMediaType, List[MediaType]] - Empty list if header missing, parsed list otherwise
    */
  def accept: Eru[MediaType.InvalidMediaType, List[MediaType]] = {
    val rawValues: List[String] =
      get("Accept").toList.flatten.flatMap { value =>
        value.value.split(",").toList.map(_.trim)
      }
    if rawValues.isEmpty then {
      Eru.succeed(List.empty)
    } else {
      Eru.foreach(rawValues)(MediaType.parse)
    }
  }

  override def toString: String =
    toList.map { case (name, value) => s"$name: $value" }.mkString("\n")
}

object Headers {

  /** Empty headers collection.
    */
  val empty: Headers = Headers(TreeMap.empty[CIString, List[HeaderValue]](using CIString.ordering))

  /** Creates headers from a list of name-value pairs with validation.
    */
  def apply(headers: (String, String)*): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Headers] = {
    headers.foldLeft(Eru.succeed(empty)) { case (accEru, (name, value)) =>
      for {
        acc <- accEru
        updated <- acc.add(name, value)
      } yield updated
    }
  }

  /** Builds Headers from pre-validated name/value pairs in a single TreeMap pass. Used by parsers
    * that validate each header individually then build the collection once, avoiding N intermediate
    * TreeMap copies.
    */
  private[http] def fromValidatedPairs(
    pairs: List[(String, HeaderValue)]
  ): Headers = {
    val tree = pairs.foldLeft(TreeMap.empty[CIString, List[HeaderValue]](using CIString.ordering)) {
      case (acc, (name, hv)) =>
        val key = CIString(name)
        acc.get(key) match {
          case Some(existing) => acc.updated(key, existing :+ hv)
          case None => acc.updated(key, List(hv))
        }
    }
    Headers(tree)
  }

  /** Creates headers from pre-validated constants, bypassing CR/LF validation.
    *
    * Package-private because bypassing `HeaderValue.parse` is a header-injection vector when any
    * input is user-controlled — a value containing CR/LF could inject a fake header or even a
    * premature response boundary. External callers MUST use `Headers.apply` or `.add`, both of
    * which validate. Internal library code calls this with compile-time-constant values (e.g.
    * standard header names the library defines).
    */
  private[http] def unsafeApply(headers: (String, String)*): Headers = {
    headers.foldLeft(empty) { case (acc, (name, value)) =>
      acc.unsafeAdd(name, HeaderValue.unsafeFromString(value))
    }
  }
}

/** Case-insensitive string for header names. Preserves the original casing for transmission.
  *
  * Implemented as a tuple of `(original, lowercase)`.
  */
opaque type CIString = (String, String)

object CIString {
  def apply(s: String): CIString = (s, s.toLowerCase)

  extension (ci: CIString) {
    def original: String = ci._1
    def lowercased: String = ci._2
  }

  /** Ordering for case-insensitive strings.
    */
  given ordering: Ordering[CIString] = Ordering.by(_.lowercased)
}

/** HTTP header value validated per RFC 9110 Section 5.5.
  *
  * field-value = *field-content field-content = field-vchar [ 1*( SP / HTAB / field-vchar )
  * field-vchar ] field-vchar = VCHAR / obs-text
  */
opaque type HeaderValue = String

object HeaderValue {

  /** Parses and validates a header value per RFC 9110 Section 5.5: VCHAR, SP, HTAB, and obs-text
    * (bytes 0x80-0xFF). CR/LF and other control characters are rejected.
    */
  def parse(value: String): Eru[InvalidHeaderValue, HeaderValue] = {
    val trimmed = value.trim
    if isValidHeaderValue(trimmed) then {
      Eru.succeed(trimmed)
    } else {
      Eru.fail(InvalidHeaderValue(value, "Invalid header value characters"))
    }
  }

  /** Unsafe constructor for pre-validated constants.
    */
  private[http] def unsafeFromString(value: String): HeaderValue = value

  extension (value: HeaderValue) {
    def value: String = value
  }

  /** Validates header value per RFC 9110 Section 5.5: visible ASCII, SP, HTAB, and obs-text (bytes
    * 0x80-0xFF). CR/LF and other control characters are rejected — they are the header-injection
    * vector; obs-text carries no framing risk.
    */
  private def isValidHeaderValue(s: String): Boolean = {
    s.forall { c =>
      (c >= 0x20 && c <= 0x7e) ||
      c == 0x09 ||
      (c >= 0x80 && c <= 0xff) // obs-text
    }
  }

  final case class InvalidHeaderValue(
    value: String,
    reason: String,
    rfc: String = "RFC 9110 Section 5.5"
  ) {
    def message: String = s"Invalid header value '$value': $reason ($rfc)"
  }
}

/** HTTP header name validated per RFC 9110 Section 5.1.
  *
  * field-name = token
  */
opaque type HeaderName = String

object HeaderName {

  /** Parses and validates a header name per RFC 9110 Section 5.1.
    */
  def parse(name: String): Eru[InvalidHeaderName, HeaderName] = {
    if isValidToken(name) then {
      Eru.succeed(name)
    } else {
      Eru.fail(InvalidHeaderName(name, "Header name must be a valid token"))
    }
  }

  /** Unsafe constructor for pre-validated constants.
    */
  private[http] def unsafeFromString(name: String): HeaderName = name

  extension (name: HeaderName) {
    def value: String = name
  }

  /** Validates that a string is a valid HTTP token per RFC 9110.
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

  final case class InvalidHeaderName(
    value: String,
    reason: String,
    rfc: String = "RFC 9110 Section 5.1"
  ) {
    def message: String = s"Invalid header name '$value': $reason ($rfc)"
  }
}

/** Common header names as string constants. These are not validated HeaderName types, just
  * convenient string constants.
  */
object HeaderNames {
  val CacheControl = "Cache-Control"
  val Connection = "Connection"
  val Date = "Date"
  val Pragma = "Pragma"
  val Trailer = "Trailer"
  val TransferEncoding = "Transfer-Encoding"
  val Upgrade = "Upgrade"
  val Via = "Via"
  val Warning = "Warning"

  val Accept = "Accept"
  val AcceptCharset = "Accept-Charset"
  val AcceptEncoding = "Accept-Encoding"
  val AcceptLanguage = "Accept-Language"
  val AcceptQuery = "Accept-Query"
  val Authorization = "Authorization"
  val Expect = "Expect"
  val From = "From"
  val Host = "Host"
  val IfMatch = "If-Match"
  val IfModifiedSince = "If-Modified-Since"
  val IfNoneMatch = "If-None-Match"
  val IfRange = "If-Range"
  val IfUnmodifiedSince = "If-Unmodified-Since"
  val MaxForwards = "Max-Forwards"
  val ProxyAuthorization = "Proxy-Authorization"
  val Range = "Range"
  val Referer = "Referer"
  val TE = "TE"
  val UserAgent = "User-Agent"

  val AcceptRanges = "Accept-Ranges"
  val Age = "Age"
  val Allow = "Allow"
  val ContentEncoding = "Content-Encoding"
  val ContentLanguage = "Content-Language"
  val ContentLength = "Content-Length"
  val ContentLocation = "Content-Location"
  val ContentMD5 = "Content-MD5"
  val ContentRange = "Content-Range"
  val ContentType = "Content-Type"
  val ETag = "ETag"
  val Expires = "Expires"
  val LastModified = "Last-Modified"
  val Location = "Location"
  val ProxyAuthenticate = "Proxy-Authenticate"
  val RetryAfter = "Retry-After"
  val Server = "Server"
  val Vary = "Vary"
  val WWWAuthenticate = "WWW-Authenticate"

  val ContentDisposition = "Content-Disposition"

  val SecWebSocketKey = "Sec-WebSocket-Key"
  val SecWebSocketAccept = "Sec-WebSocket-Accept"
  val SecWebSocketVersion = "Sec-WebSocket-Version"
  val SecWebSocketProtocol = "Sec-WebSocket-Protocol"

  val AccessControlAllowOrigin = "Access-Control-Allow-Origin"
  val AccessControlAllowMethods = "Access-Control-Allow-Methods"
  val AccessControlAllowHeaders = "Access-Control-Allow-Headers"
  val AccessControlMaxAge = "Access-Control-Max-Age"
  val AccessControlAllowCredentials = "Access-Control-Allow-Credentials"
  val AccessControlExposeHeaders = "Access-Control-Expose-Headers"
  val AccessControlRequestMethod = "Access-Control-Request-Method"
  val AccessControlRequestHeaders = "Access-Control-Request-Headers"
  val Origin = "Origin"

  val StrictTransportSecurity = "Strict-Transport-Security"
  val ContentSecurityPolicy = "Content-Security-Policy"
  val XContentTypeOptions = "X-Content-Type-Options"
  val XFrameOptions = "X-Frame-Options"
  val XXSSProtection = "X-XSS-Protection"

  val Cookie = "Cookie"
  val SetCookie = "Set-Cookie"
}
