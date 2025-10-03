package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP Cache-Control directives as defined in RFC 9111.
  *
  * Cache-Control is used to specify directives for caching mechanisms in both requests and
  * responses. Directives control whether a response is cacheable, for how long, and under what
  * conditions.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9111.html RFC 9111 - HTTP Caching]]
  */
enum CacheDirective {

  /** Indicates that the response must not be used to satisfy a subsequent request without
    * successful validation on the origin server.
    */
  case NoCache

  /** Indicates that a cache must not store any part of either this request or any response to it.
    */
  case NoStore

  /** Indicates that caches or proxies must not change media type or modify the body.
    */
  case NoTransform

  /** In requests, indicates that the client only wants a response that is currently cached. */
  case OnlyIfCached

  /** Indicates that any cache may store the response.
    */
  case Public

  /** Indicates that the response is intended for a single user and must not be stored by a shared
    * cache.
    *
    * @param fields
    *   optional list of field names that should be excluded from caching
    */
  case Private(fields: Option[List[String]] = None)

  /** Indicates the maximum amount of time a resource is considered fresh.
    *
    * @param seconds
    *   the number of seconds the resource should be considered fresh
    */
  case MaxAge(seconds: Int)

  /** Like max-age but only for shared caches.
    *
    * @param seconds
    *   the number of seconds the resource should be considered fresh in shared caches
    */
  case SMaxAge(seconds: Int)

  /** In requests, indicates that the client is willing to accept a stale response.
    *
    * @param seconds
    *   optional maximum staleness the client will accept (None means any staleness)
    */
  case MaxStale(seconds: Option[Int] = None)

  /** In requests, indicates that the client wants a response that will still be fresh for at least
    * the specified number of seconds.
    *
    * @param seconds
    *   the minimum freshness required
    */
  case MinFresh(seconds: Int)

  /** Indicates that once the resource becomes stale, a cache must not use the response without
    * successful validation on the origin server.
    */
  case MustRevalidate

  /** Like must-revalidate but only for shared caches.
    */
  case ProxyRevalidate

  /** Indicates that a cache must understand this directive or must not store the response.
    */
  case MustUnderstand

  /** Indicates that the response will not change over time.
    */
  case Immutable

  /** Indicates that the cache can serve a stale response while it revalidates in the background.
    *
    * @param seconds
    *   the number of seconds a stale response can be served during revalidation
    */
  case StaleWhileRevalidate(seconds: Int)

  /** Indicates that the cache can serve a stale response if an error occurs during revalidation.
    *
    * @param seconds
    *   the number of seconds a stale response can be served on error
    */
  case StaleIfError(seconds: Int)

  /** A custom or extension directive not explicitly modeled.
    *
    * @param name
    *   the directive name
    * @param value
    *   optional directive value
    */
  case Custom(name: String, value: Option[String] = None)
}

object CacheDirective {

  /** Parses a single cache directive from a string.
    *
    * @param s
    *   the directive string (e.g., "max-age=3600", "no-cache", "private=\"Set-Cookie\"")
    * @return
    *   the parsed CacheDirective or a ParseError
    */
  def parse(s: String): Eru[ParseError, CacheDirective] = {
    val trimmed = s.trim
    val parts = trimmed.split("=", 2).map(_.trim)
    val name = parts(0).toLowerCase

    name match {
      case "no-cache" => Eru.succeed(CacheDirective.NoCache)
      case "no-store" => Eru.succeed(CacheDirective.NoStore)
      case "no-transform" => Eru.succeed(CacheDirective.NoTransform)
      case "only-if-cached" => Eru.succeed(CacheDirective.OnlyIfCached)
      case "public" => Eru.succeed(CacheDirective.Public)
      case "private" =>
        if parts.length == 2 then {
          val fieldList = parseFieldList(parts(1))
          Eru.succeed(CacheDirective.Private(Some(fieldList)))
        } else {
          Eru.succeed(CacheDirective.Private(None))
        }
      case "max-age" =>
        if parts.length == 2 then {
          parseSeconds(parts(1)).map(CacheDirective.MaxAge.apply)
        } else {
          Eru.fail(ParseError(s, "max-age requires a value"))
        }
      case "s-maxage" =>
        if parts.length == 2 then {
          parseSeconds(parts(1)).map(CacheDirective.SMaxAge.apply)
        } else {
          Eru.fail(ParseError(s, "s-maxage requires a value"))
        }
      case "max-stale" =>
        if parts.length == 2 then {
          parseSeconds(parts(1)).map(sec => CacheDirective.MaxStale(Some(sec)))
        } else {
          Eru.succeed(CacheDirective.MaxStale(None))
        }
      case "min-fresh" =>
        if parts.length == 2 then {
          parseSeconds(parts(1)).map(CacheDirective.MinFresh.apply)
        } else {
          Eru.fail(ParseError(s, "min-fresh requires a value"))
        }
      case "must-revalidate" => Eru.succeed(CacheDirective.MustRevalidate)
      case "proxy-revalidate" => Eru.succeed(CacheDirective.ProxyRevalidate)
      case "must-understand" => Eru.succeed(CacheDirective.MustUnderstand)
      case "immutable" => Eru.succeed(CacheDirective.Immutable)
      case "stale-while-revalidate" =>
        if parts.length == 2 then {
          parseSeconds(parts(1)).map(CacheDirective.StaleWhileRevalidate.apply)
        } else {
          Eru.fail(ParseError(s, "stale-while-revalidate requires a value"))
        }
      case "stale-if-error" =>
        if parts.length == 2 then {
          parseSeconds(parts(1)).map(CacheDirective.StaleIfError.apply)
        } else {
          Eru.fail(ParseError(s, "stale-if-error requires a value"))
        }
      case _ =>
        val value = if parts.length == 2 then Some(parts(1)) else None
        Eru.succeed(CacheDirective.Custom(parts(0), value))
    }
  }

  /** Serializes a cache directive to its string representation.
    *
    * @param directive
    *   the directive to serialize
    * @return
    *   the string representation
    */
  def serialize(directive: CacheDirective): String = directive match {
    case CacheDirective.NoCache => "no-cache"
    case CacheDirective.NoStore => "no-store"
    case CacheDirective.NoTransform => "no-transform"
    case CacheDirective.OnlyIfCached => "only-if-cached"
    case CacheDirective.Public => "public"
    case CacheDirective.Private(None) => "private"
    case CacheDirective.Private(Some(fields)) => s"private=${quoteFieldList(fields)}"
    case CacheDirective.MaxAge(seconds) => s"max-age=$seconds"
    case CacheDirective.SMaxAge(seconds) => s"s-maxage=$seconds"
    case CacheDirective.MaxStale(None) => "max-stale"
    case CacheDirective.MaxStale(Some(seconds)) => s"max-stale=$seconds"
    case CacheDirective.MinFresh(seconds) => s"min-fresh=$seconds"
    case CacheDirective.MustRevalidate => "must-revalidate"
    case CacheDirective.ProxyRevalidate => "proxy-revalidate"
    case CacheDirective.MustUnderstand => "must-understand"
    case CacheDirective.Immutable => "immutable"
    case CacheDirective.StaleWhileRevalidate(seconds) => s"stale-while-revalidate=$seconds"
    case CacheDirective.StaleIfError(seconds) => s"stale-if-error=$seconds"
    case CacheDirective.Custom(name, None) => name
    case CacheDirective.Custom(name, Some(value)) => s"$name=$value"
  }

  private def parseSeconds(s: String): Eru[ParseError, Int] = {
    s.toIntOption match {
      case Some(seconds) if seconds >= 0 => Eru.succeed(seconds)
      case Some(_) => Eru.fail(ParseError(s, "Seconds must be non-negative"))
      case None => Eru.fail(ParseError(s, "Invalid seconds value"))
    }
  }

  private def parseFieldList(s: String): List[String] = {
    val unquoted = s.stripPrefix("\"").stripSuffix("\"")
    unquoted.split(",").map(_.trim).filter(_.nonEmpty).toList
  }

  private def quoteFieldList(fields: List[String]): String = {
    s"\"${fields.mkString(", ")}\""
  }
}

/** Collection of cache control directives.
  *
  * @param directives
  *   the list of cache directives
  */
final case class CacheControl(directives: List[CacheDirective]) {

  /** Serializes the cache control to its header value.
    *
    * @return
    *   the Cache-Control header value
    */
  def value: String = {
    directives.map(CacheDirective.serialize).mkString(", ")
  }

  /** Adds a directive to this cache control.
    *
    * @param directive
    *   the directive to add
    * @return
    *   a new CacheControl with the directive added
    */
  def add(directive: CacheDirective): CacheControl = {
    copy(directives = directives :+ directive)
  }

  /** Checks if this cache control contains a specific directive type.
    *
    * Note: This checks by directive type, ignoring values. For example, has(CacheDirective.MaxAge)
    * will return true if any MaxAge directive exists, regardless of the seconds value.
    *
    * @param directive
    *   the directive to check for
    * @return
    *   true if a directive of this type exists
    */
  def has(directive: CacheDirective): Boolean = {
    directives.exists {
      case CacheDirective.NoCache => directive == CacheDirective.NoCache
      case CacheDirective.NoStore => directive == CacheDirective.NoStore
      case CacheDirective.NoTransform => directive == CacheDirective.NoTransform
      case CacheDirective.OnlyIfCached => directive == CacheDirective.OnlyIfCached
      case CacheDirective.Public => directive == CacheDirective.Public
      case CacheDirective.Private(_) =>
        directive match {
          case CacheDirective.Private(_) => true
          case _ => false
        }
      case CacheDirective.MaxAge(_) =>
        directive match {
          case CacheDirective.MaxAge(_) => true
          case _ => false
        }
      case CacheDirective.SMaxAge(_) =>
        directive match {
          case CacheDirective.SMaxAge(_) => true
          case _ => false
        }
      case CacheDirective.MaxStale(_) =>
        directive match {
          case CacheDirective.MaxStale(_) => true
          case _ => false
        }
      case CacheDirective.MinFresh(_) =>
        directive match {
          case CacheDirective.MinFresh(_) => true
          case _ => false
        }
      case CacheDirective.MustRevalidate => directive == CacheDirective.MustRevalidate
      case CacheDirective.ProxyRevalidate => directive == CacheDirective.ProxyRevalidate
      case CacheDirective.MustUnderstand => directive == CacheDirective.MustUnderstand
      case CacheDirective.Immutable => directive == CacheDirective.Immutable
      case CacheDirective.StaleWhileRevalidate(_) =>
        directive match {
          case CacheDirective.StaleWhileRevalidate(_) => true
          case _ => false
        }
      case CacheDirective.StaleIfError(_) =>
        directive match {
          case CacheDirective.StaleIfError(_) => true
          case _ => false
        }
      case CacheDirective.Custom(name, _) =>
        directive match {
          case CacheDirective.Custom(otherName, _) => name == otherName
          case _ => false
        }
    }
  }

  /** Gets the max-age value if present.
    *
    * @return
    *   the max-age in seconds, or None if not present
    */
  def maxAge: Option[Int] = {
    directives.collectFirst { case CacheDirective.MaxAge(seconds) => seconds }
  }

  /** Gets the s-maxage value if present.
    *
    * @return
    *   the s-maxage in seconds, or None if not present
    */
  def sMaxAge: Option[Int] = {
    directives.collectFirst { case CacheDirective.SMaxAge(seconds) => seconds }
  }

  /** Checks if this cache control indicates the response should not be cached.
    *
    * @return
    *   true if no-store or no-cache is present
    */
  def isNoCache: Boolean = {
    has(CacheDirective.NoCache) || has(CacheDirective.NoStore)
  }

  /** Checks if this cache control indicates the response is public.
    *
    * @return
    *   true if public directive is present
    */
  def isPublic: Boolean = has(CacheDirective.Public)

  /** Checks if this cache control indicates the response is private.
    *
    * @return
    *   true if private directive is present
    */
  def isPrivate: Boolean = directives.exists {
    case CacheDirective.Private(_) => true
    case _ => false
  }
}

object CacheControl {

  /** Parses a Cache-Control header value.
    *
    * Per RFC 9111, multiple Cache-Control headers should be combined with commas, so this parser
    * handles comma-separated directives.
    *
    * @param value
    *   the Cache-Control header value
    * @return
    *   the parsed CacheControl or a ParseError
    */
  def parse(value: String): Eru[ParseError, CacheControl] = {
    // Split by comma, but be careful not to split inside quoted strings
    val parts = splitDirectives(value)
    if parts.isEmpty then {
      Eru.succeed(CacheControl(List.empty))
    } else {
      // Parse all directives, collecting errors
      val parsed = parts.map(CacheDirective.parse)
      // Sequence all results - fails fast on first error
      Eru.foreach(parsed)(identity).map(directives => CacheControl(directives))
    }
  }

  /** Splits directives by comma, respecting quoted strings.
    *
    * @param value
    *   the Cache-Control header value to split
    * @return
    *   the list of directive strings
    */
  private def splitDirectives(value: String): List[String] = {
    val result = scala.collection.mutable.ListBuffer[String]()
    val current = new StringBuilder
    var inQuotes = false
    var i = 0

    while i < value.length do {
      val c = value.charAt(i)
      c match {
        case '"' =>
          inQuotes = !inQuotes
          current.append(c)
        case ',' if !inQuotes =>
          val part = current.toString.trim
          if part.nonEmpty then result += part
          current.clear()
        case _ =>
          current.append(c)
      }
      i += 1
    }

    // Add last part
    val part = current.toString.trim
    if part.nonEmpty then result += part

    result.toList
  }

  // Common presets

  /** No caching at all - the response must not be stored.
    */
  val noStore: CacheControl = CacheControl(List(CacheDirective.NoStore))

  /** Response must be revalidated before use.
    */
  val noCache: CacheControl = CacheControl(List(CacheDirective.NoCache))

  /** Response can be cached by any cache.
    */
  val publicCache: CacheControl = CacheControl(List(CacheDirective.Public))

  /** Response can only be cached by private (browser) caches.
    */
  val privateCache: CacheControl = CacheControl(List(CacheDirective.Private(None)))

  /** Response can be cached for the specified number of seconds.
    *
    * @param seconds
    *   the number of seconds the response is considered fresh
    * @return
    *   a CacheControl with max-age directive
    */
  def maxAge(seconds: Int): CacheControl = {
    CacheControl(List(CacheDirective.MaxAge(seconds)))
  }

  /** Public cache with max-age.
    *
    * @param seconds
    *   the number of seconds the response is considered fresh
    * @return
    *   a CacheControl with public and max-age directives
    */
  def publicMaxAge(seconds: Int): CacheControl = {
    CacheControl(List(CacheDirective.Public, CacheDirective.MaxAge(seconds)))
  }

  /** Private cache with max-age.
    *
    * @param seconds
    *   the number of seconds the response is considered fresh
    * @return
    *   a CacheControl with private and max-age directives
    */
  def privateMaxAge(seconds: Int): CacheControl = {
    CacheControl(List(CacheDirective.Private(None), CacheDirective.MaxAge(seconds)))
  }

  /** Immutable resource with max-age.
    *
    * @param seconds
    *   the number of seconds the response is considered fresh
    * @return
    *   a CacheControl with immutable and max-age directives
    */
  def immutable(seconds: Int): CacheControl = {
    CacheControl(List(CacheDirective.Immutable, CacheDirective.MaxAge(seconds)))
  }

  /** Empty cache control (no directives).
    */
  val empty: CacheControl = CacheControl(List.empty)
}

/** Parse error for cache control directives.
  *
  * @param value
  *   the value that failed to parse
  * @param reason
  *   the reason for the failure
  * @param rfc
  *   the RFC specification violated (defaults to RFC 9111)
  */
final case class ParseError(
  value: String,
  reason: String,
  rfc: String = "RFC 9111"
) extends Exception(s"Failed to parse '$value': $reason ($rfc)")
