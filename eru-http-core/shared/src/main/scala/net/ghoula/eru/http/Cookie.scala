package net.ghoula.eru.http

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** HTTP Cookie as defined in RFC 6265.
  *
  * Represents a cookie with all its attributes as specified in the Set-Cookie header.
  *
  * @param name
  *   Cookie name (case-sensitive)
  * @param value
  *   Cookie value
  * @param domain
  *   Optional domain attribute (RFC 6265 Section 5.2.3)
  * @param path
  *   Optional path attribute (RFC 6265 Section 5.2.4)
  * @param expires
  *   Optional expiration time (RFC 6265 Section 5.2.1)
  * @param maxAge
  *   Optional max-age in seconds (RFC 6265 Section 5.2.2)
  * @param secure
  *   Secure flag - cookie only sent over HTTPS
  * @param httpOnly
  *   HttpOnly flag - cookie not accessible via JavaScript
  * @param sameSite
  *   SameSite attribute for CSRF protection
  */
final case class Cookie(
  name: String,
  value: String,
  domain: Option[String] = None,
  path: Option[String] = None,
  expires: Option[Instant] = None,
  maxAge: Option[Long] = None,
  secure: Boolean = false,
  httpOnly: Boolean = false,
  sameSite: Option[SameSite] = None
) {

  /** Converts this cookie to a Set-Cookie header value per RFC 6265 Section 4.1.
    *
    * Format: name=value; Domain=example.com; Path=/; Expires=...; Max-Age=...; Secure; HttpOnly;
    * SameSite=Lax
    */
  def toSetCookieHeader: String = {
    val parts = scala.collection.mutable.ArrayBuffer[String]()

    // Required: name=value
    parts += s"$name=$value"

    // Optional attributes
    domain.foreach(d => parts += s"Domain=$d")
    path.foreach(p => parts += s"Path=$p")
    expires.foreach { exp =>
      val formatted = DateTimeFormatter.RFC_1123_DATE_TIME
        .withZone(ZoneOffset.UTC)
        .format(exp)
      parts += s"Expires=$formatted"
    }
    maxAge.foreach(ma => parts += s"Max-Age=$ma")
    if secure then parts += "Secure"
    if httpOnly then parts += "HttpOnly"
    sameSite.foreach(ss => parts += s"SameSite=${ss.value}")

    parts.mkString("; ")
  }

  /** Converts this cookie to a Cookie header value per RFC 6265 Section 4.2.
    *
    * Format: name=value (only name and value, no attributes)
    */
  def toCookieHeader: String = s"$name=$value"

  /** Checks if this cookie has expired.
    *
    * @param now
    *   the current time to compare against (defaults to Instant.now())
    * @return
    *   true if the cookie has expired
    */
  def isExpired(now: Instant = Instant.now()): Boolean = {
    expires.exists(exp => now.isAfter(exp)) ||
    maxAge.exists(_ <= 0)
  }

  /** Checks if this cookie matches the given domain per RFC 6265 Section 5.1.3.
    *
    * Domain matching rules:
    *   - Cookie domain must be a suffix of the request domain
    *   - Request domain must domain-match the cookie domain
    *
    * @param requestDomain
    *   the domain to match against
    * @return
    *   true if the cookie matches the request domain
    */
  def domainMatches(requestDomain: String): Boolean = {
    domain match {
      case None => true // No domain restriction
      case Some(cookieDomain) =>
        // Normalize both domains to lowercase for comparison
        val reqDomain = requestDomain.toLowerCase
        val ckDomain = cookieDomain.toLowerCase.stripPrefix(".")

        // Exact match or suffix match
        reqDomain == ckDomain || reqDomain.endsWith(s".$ckDomain")
    }
  }

  /** Checks if this cookie matches the given path per RFC 6265 Section 5.1.4.
    *
    * Path matching rules:
    *   - Cookie path must be a prefix of the request path
    *   - Request path must path-match the cookie path
    *
    * @param requestPath
    *   the path to match against
    * @return
    *   true if the cookie matches the request path
    */
  def pathMatches(requestPath: String): Boolean = {
    path match {
      case None => true // No path restriction
      case Some(cookiePath) =>
        // Request path must start with cookie path
        if requestPath == cookiePath then true
        else if requestPath.startsWith(cookiePath) then
          // Cookie path must end with "/" or next char in request path must be "/"
          cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length) == '/'
        else false
    }
  }
}

object Cookie {

  /** Parses a Set-Cookie header value per RFC 6265 Section 4.1.
    *
    * Format: name=value; attribute1=value1; attribute2; ...
    *
    * @param setCookieHeader
    *   The Set-Cookie header value to parse
    * @return
    *   A parsed Cookie or an InvalidCookie error
    */
  def parseSetCookie(setCookieHeader: String): Eru[InvalidCookie, Cookie] = {
    val trimmed = setCookieHeader.trim
    if trimmed.isEmpty then {
      Eru.fail(InvalidCookie(setCookieHeader, "Set-Cookie header cannot be empty"))
    } else {
      // Split into parts separated by semicolon
      val parts = trimmed.split(';').map(_.trim)
      if parts.isEmpty then {
        Eru.fail(InvalidCookie(setCookieHeader, "Invalid Set-Cookie format"))
      } else {
        // First part is name=value
        val nameValue = parts(0)
        val eqIdx = nameValue.indexOf('=')
        if eqIdx < 0 then {
          Eru.fail(InvalidCookie(setCookieHeader, "Cookie must have name=value format"))
        } else {
          val name = nameValue.substring(0, eqIdx).trim
          val value = nameValue.substring(eqIdx + 1).trim

          // Validate name per RFC 6265 Section 4.1.1
          if !isValidCookieName(name) then {
            Eru.fail(InvalidCookie(name, "Invalid cookie name"))
          } else if !isValidCookieValue(value) then {
            // Validate value per RFC 6265 Section 4.1.1
            Eru.fail(InvalidCookie(value, "Invalid cookie value"))
          } else {
            // Parse attributes using fold to avoid mutable vars
            final case class Attrs(
              domain: Option[String] = None,
              path: Option[String] = None,
              expires: Option[Instant] = None,
              maxAge: Option[Long] = None,
              secure: Boolean = false,
              httpOnly: Boolean = false,
              sameSite: Option[SameSite] = None
            )

            val attrs = parts.tail.foldLeft(Attrs()) { (acc, part) =>
              val eqIdx = part.indexOf('=')
              val (attrName, attrValue) =
                if eqIdx >= 0 then (part.substring(0, eqIdx).trim.toLowerCase, Some(part.substring(eqIdx + 1).trim))
                else (part.toLowerCase, None)

              attrName match {
                case "domain" =>
                  attrValue.map(d => acc.copy(domain = Some(d.stripPrefix(".")))).getOrElse(acc)
                case "path" =>
                  attrValue.map(p => acc.copy(path = Some(p))).getOrElse(acc)
                case "expires" =>
                  attrValue.flatMap { exp =>
                    try {
                      // Try RFC 1123 format first
                      Some(Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(exp)))
                    } catch {
                      case _ =>
                        // Ignore invalid date formats per RFC 6265
                        None
                    }
                  }.map(e => acc.copy(expires = Some(e))).getOrElse(acc)
                case "max-age" =>
                  attrValue.flatMap { ma =>
                    try {
                      Some(ma.toLong)
                    } catch {
                      case _: NumberFormatException =>
                        // Ignore invalid max-age per RFC 6265
                        None
                    }
                  }.map(m => acc.copy(maxAge = Some(m))).getOrElse(acc)
                case "secure" =>
                  acc.copy(secure = true)
                case "httponly" =>
                  acc.copy(httpOnly = true)
                case "samesite" =>
                  attrValue.flatMap { ss =>
                    SameSite.parse(ss).attempt.unsafeRunSync().fold(_ => None, Some(_))
                  }.map(s => acc.copy(sameSite = Some(s))).getOrElse(acc)
                case _ =>
                  // Ignore unknown attributes per RFC 6265
                  acc
              }
            }

            Eru.succeed(Cookie(name, value, attrs.domain, attrs.path, attrs.expires, attrs.maxAge, attrs.secure, attrs.httpOnly, attrs.sameSite))
          }
        }
      }
    }
  }

  /** Parses a Cookie header value per RFC 6265 Section 4.2.
    *
    * Format: name1=value1; name2=value2; ...
    *
    * @param cookieHeader
    *   The Cookie header value to parse
    * @return
    *   A list of parsed Cookies or an InvalidCookie error
    */
  def parseCookie(cookieHeader: String): Eru[InvalidCookie, List[Cookie]] = {
    Eru.effect {
      val trimmed = cookieHeader.trim
      if trimmed.isEmpty then {
        List.empty[Cookie]
      } else {
        // Split by semicolon
        val parts = trimmed.split(';').map(_.trim)
        parts.toList.map { part =>
          val eqIdx = part.indexOf('=')
          if eqIdx < 0 then throw InvalidCookie(part, "Cookie must have name=value format")

          val name = part.substring(0, eqIdx).trim
          val value = part.substring(eqIdx + 1).trim

          // Validate name and value
          if !isValidCookieName(name) then throw InvalidCookie(name, "Invalid cookie name")
          if !isValidCookieValue(value) then throw InvalidCookie(value, "Invalid cookie value")

          Cookie(name, value)
        }
      }
    }.mapError {
      case e: InvalidCookie => e
      case e: Throwable => InvalidCookie(cookieHeader, s"Failed to parse Cookie: ${e.getMessage}")
    }
  }

  /** Validates cookie name per RFC 6265 Section 4.1.1.
    *
    * cookie-name = token (no control chars, separators, etc.)
    */
  private def isValidCookieName(name: String): Boolean = {
    name.nonEmpty && name.forall(isCookieNameChar)
  }

  private def isCookieNameChar(c: Char): Boolean = {
    // RFC 6265: cookie-name uses the token production from RFC 2616
    c match {
      case c if c <= 31 || c >= 127 => false // Control characters
      case '(' | ')' | '<' | '>' | '@' | ',' | ';' | ':' | '\\' | '"' | '/' | '[' | ']' | '?' | '=' | '{' | '}' | ' ' |
          '\t' =>
        false
      case _ => true
    }
  }

  /** Validates cookie value per RFC 6265 Section 4.1.1.
    *
    * cookie-value = *cookie-octet / ( DQUOTE *cookie-octet DQUOTE ) cookie-octet = %x21 / %x23-2B /
    * %x2D-3A / %x3C-5B / %x5D-7E
    */
  private def isValidCookieValue(value: String): Boolean = {
    if value.isEmpty then {
      true // Empty value is allowed
    } else if value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") then {
      // Quoted value
      val inner = value.substring(1, value.length - 1)
      inner.forall(isCookieOctet)
    } else {
      // Unquoted value
      value.forall(isCookieOctet)
    }
  }

  private def isCookieOctet(c: Char): Boolean = {
    c match {
      case c if c == 0x21 => true
      case c if c >= 0x23 && c <= 0x2b => true
      case c if c >= 0x2d && c <= 0x3a => true
      case c if c >= 0x3c && c <= 0x5b => true
      case c if c >= 0x5d && c <= 0x7e => true
      case _ => false
    }
  }

  /** Error for invalid cookies.
    *
    * @param value
    *   the invalid cookie value
    * @param reason
    *   the reason the cookie is invalid
    * @param rfc
    *   the RFC specification violated (defaults to RFC 6265)
    */
  final case class InvalidCookie(
    value: String,
    reason: String,
    rfc: String = "RFC 6265"
  ) extends Exception(s"Invalid cookie '$value': $reason ($rfc)")
}

/** SameSite attribute for cookies as defined in RFC 6265bis.
  *
  * The SameSite attribute controls whether cookies are sent with cross-site requests, providing
  * protection against CSRF attacks.
  */
enum SameSite(val value: String) {

  /** Strict mode: Cookie only sent with same-site requests.
    */
  case Strict extends SameSite("Strict")

  /** Lax mode: Cookie sent with same-site requests and top-level navigations.
    */
  case Lax extends SameSite("Lax")

  /** None mode: Cookie sent with all requests (requires Secure flag).
    */
  case None extends SameSite("None")
}

object SameSite {

  /** Parses a SameSite value from a string (case-insensitive).
    *
    * @param value
    *   the string to parse (accepts "Strict", "Lax", or "None")
    * @return
    *   the parsed SameSite value or an InvalidCookie error
    */
  def parse(value: String): Eru[Cookie.InvalidCookie, SameSite] = {
    value.toLowerCase match {
      case "strict" => Eru.succeed(Strict)
      case "lax" => Eru.succeed(Lax)
      case "none" => Eru.succeed(None)
      case _ => Eru.fail(Cookie.InvalidCookie(value, "Invalid SameSite value (must be Strict, Lax, or None)"))
    }
  }
}
