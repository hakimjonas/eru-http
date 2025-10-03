package net.ghoula.eru.http

import scala.collection.immutable.TreeMap

import net.ghoula.eru.*

/** HTTP headers collection that correctly handles case-insensitive names and multi-value headers
  * per RFC 9110 Section 5.
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
      _ <- HeaderName.parse(name) // Validate name
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
      _ <- HeaderName.parse(name) // Validate name
      headerValue <- HeaderValue.parse(value)
    } yield {
      val key = CIString(name)
      Headers(underlying.updated(key, List(headerValue)))
    }
  }

  /** Sets multiple header values with validation, replacing any existing values.
    */
  def setAll(
    name: String,
    values: List[String]
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Headers] = {
    for {
      _ <- HeaderName.parse(name) // Validate name
      headerValues <- values.foldLeft(Eru.succeed(List.empty[HeaderValue])) { (accEru, v) =>
        for {
          acc <- accEru
          parsed <- HeaderValue.parse(v)
        } yield acc :+ parsed
      }
    } yield {
      val key = CIString(name)
      Headers(underlying.updated(key, headerValues))
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

  // Common header accessors with proper types

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

  /** Gets the Content-Length header as a Long.
    */
  def contentLength: Option[Long] =
    getFirst("Content-Length").flatMap(v => v.value.toLongOption)

  /** Gets the Host header.
    */
  def host: Option[String] =
    getFirst("Host").map(_.value)

  /** Gets the User-Agent header.
    */
  def userAgent: Option[String] =
    getFirst("User-Agent").map(_.value)

  /** Gets the raw Accept header values without parsing. Returns a list of comma-separated media
    * type strings.
    */
  def acceptRaw: List[String] =
    get("Accept").toList.flatten.flatMap { value =>
      value.value.split(",").toList.map(_.trim)
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
    val rawValues = acceptRaw
    if rawValues.isEmpty then {
      Eru.succeed(List.empty)
    } else {
      // Parse all media types, collecting into a list
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

  /** Creates headers from pre-validated constants. Only use with known-valid header names and
    * values.
    */
  def unsafeApply(headers: (String, String)*): Headers = {
    headers.foldLeft(empty) { case (acc, (name, value)) =>
      acc.unsafeAdd(name, HeaderValue.unsafeFromString(value))
    }
  }

  /** Creates headers from a map with validation. Note: This loses multi-value headers if the map
    * has String values.
    */
  def fromMap(map: Map[String, String]): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Headers] = {
    map.foldLeft(Eru.succeed(empty)) { case (accEru, (name, value)) =>
      for {
        acc <- accEru
        updated <- acc.set(name, value)
      } yield updated
    }
  }

  /** Creates headers from a multi-map with validation.
    */
  def fromMultiMap(
    map: Map[String, List[String]]
  ): Eru[HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue, Headers] = {
    map.foldLeft(Eru.succeed(empty)) { case (accEru, (name, values)) =>
      for {
        acc <- accEru
        updated <- values.foldLeft(Eru.succeed(acc)) { (hEru, v) =>
          for {
            h <- hEru
            updated <- h.add(name, v)
          } yield updated
        }
      } yield updated
    }
  }
}

/** Case-insensitive string for header names. Preserves the original casing for transmission.
  */
opaque type CIString = (String, String) // (original, lowercase)

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

  /** Parses and validates a header value per RFC 9110 Section 5.5.
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

  /** Validates header value per RFC 9110 Section 5.5. Allows visible ASCII characters, spaces, and
    * tabs. Disallows control characters except HTAB.
    */
  private def isValidHeaderValue(s: String): Boolean = {
    s.forall { c =>
      c >= 0x20 && c <= 0x7e || // Visible ASCII
      c == 0x09 // HTAB
    }
  }

  final case class InvalidHeaderValue(
    value: String,
    reason: String,
    rfc: String = "RFC 9110 Section 5.5"
  ) extends Exception(s"Invalid header value '$value': $reason ($rfc)")
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
  ) extends Exception(s"Invalid header name '$value': $reason ($rfc)")
}

/** Common header names as string constants. These are not validated HeaderName types, just
  * convenient string constants.
  */
object HeaderNames {
  // General headers
  val CacheControl = "Cache-Control"
  val Connection = "Connection"
  val Date = "Date"
  val Pragma = "Pragma"
  val Trailer = "Trailer"
  val TransferEncoding = "Transfer-Encoding"
  val Upgrade = "Upgrade"
  val Via = "Via"
  val Warning = "Warning"

  // Request headers
  val Accept = "Accept"
  val AcceptCharset = "Accept-Charset"
  val AcceptEncoding = "Accept-Encoding"
  val AcceptLanguage = "Accept-Language"
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

  // Response headers
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

  // Entity headers
  val ContentDisposition = "Content-Disposition"

  // WebSocket headers
  val SecWebSocketKey = "Sec-WebSocket-Key"
  val SecWebSocketAccept = "Sec-WebSocket-Accept"
  val SecWebSocketVersion = "Sec-WebSocket-Version"
  val SecWebSocketProtocol = "Sec-WebSocket-Protocol"

  // CORS headers
  val AccessControlAllowOrigin = "Access-Control-Allow-Origin"
  val AccessControlAllowMethods = "Access-Control-Allow-Methods"
  val AccessControlAllowHeaders = "Access-Control-Allow-Headers"
  val AccessControlMaxAge = "Access-Control-Max-Age"
  val AccessControlAllowCredentials = "Access-Control-Allow-Credentials"
  val AccessControlExposeHeaders = "Access-Control-Expose-Headers"
  val AccessControlRequestMethod = "Access-Control-Request-Method"
  val AccessControlRequestHeaders = "Access-Control-Request-Headers"
  val Origin = "Origin"

  // Security headers
  val StrictTransportSecurity = "Strict-Transport-Security"
  val ContentSecurityPolicy = "Content-Security-Policy"
  val XContentTypeOptions = "X-Content-Type-Options"
  val XFrameOptions = "X-Frame-Options"
  val XXSSProtection = "X-XSS-Protection"

  // Cookie headers
  val Cookie = "Cookie"
  val SetCookie = "Set-Cookie"
}
