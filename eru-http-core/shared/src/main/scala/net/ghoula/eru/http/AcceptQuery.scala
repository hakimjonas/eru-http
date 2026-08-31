package net.ghoula.eru.http

import net.ghoula.eru.*

/** One media range in an `Accept-Query` value: the range itself plus its parameters.
  *
  * RFC 10008 Section 3: media ranges are represented by a Structured Fields List item of either a
  * Token or a String, with media type parameters mapped to Structured Field parameters.
  */
final case class QueryMediaRange(
  range: String,
  parameters: Map[String, String] = Map.empty
) {
  override def toString: String = AcceptQuery.serializeRange(this)
}

/** The `Accept-Query` response field (RFC 10008 Section 3).
  *
  * A Structured Fields List (RFC 9651) of media ranges: the query media types a resource accepts
  * for QUERY requests. Supported wildcards are the any-type wildcard and type wildcards (any
  * subtype of a type). The order of ranges in the list carries no meaning.
  */
final case class AcceptQuery(ranges: List[QueryMediaRange]) {

  def isEmpty: Boolean = ranges.isEmpty
  def nonEmpty: Boolean = ranges.nonEmpty

  /** The serialized field value per RFC 9651 List syntax. */
  def value: String = ranges.map(AcceptQuery.serializeRange).mkString(", ")

  /** Whether this value advertises support for the given media range.
    *
    * Matches the all-types wildcard, a type wildcard (any subtype of the given main type), and
    * exact type/subtype ranges. Unlike `MediaType.matches`, the only wildcard form is the all-types
    * range; a wildcard main type combined with an exact subtype is not matched.
    */
  def accepts(mediaType: MediaType): Boolean =
    ranges.exists { r =>
      r.range == "*/*" ||
      r.range == s"${mediaType.mainType}/*" ||
      r.range == s"${mediaType.mainType}/${mediaType.subType}"
    }

  override def toString: String = value
}

object AcceptQuery {

  /** An empty Accept-Query (no advertised query media types). */
  val empty: AcceptQuery = AcceptQuery(Nil)

  /** Build from media types. Media type parameters map to Structured Field parameters.
    */
  def fromMediaTypes(mediaTypes: List[MediaType]): AcceptQuery =
    AcceptQuery(
      mediaTypes.map(mt => QueryMediaRange(s"${mt.mainType}/${mt.subType}", mt.parameters))
    )

  /** Parse a field value per RFC 9651 List syntax as restricted by RFC 10008 Section 3.
    *
    * @param value
    *   the raw field value
    * @return
    *   the parsed AcceptQuery or an InvalidAcceptQuery error
    */
  def parse(value: String): Eru[InvalidAcceptQuery, AcceptQuery] = {
    val trimmed = value.trim
    if trimmed.isEmpty then {
      Eru.fail(InvalidAcceptQuery(value, "Empty field value"))
    } else {
      parseList(trimmed).flatMap(items => validate(items, value))
    }
  }

  private[http] def serializeRange(range: QueryMediaRange): String = {
    val base =
      if isSfToken(range.range) then range.range
      else serializeString(range.range)
    val params = range.parameters.map { case (k, v) =>
      val valueStr =
        if isSfToken(v) then v
        else serializeString(v)
      s";$k=$valueStr"
    }.mkString
    base + params
  }

  private def serializeString(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case c => sb.append(c)
    }
    sb.append('"')
    sb.toString
  }

  private def parseList(s: String): Eru[InvalidAcceptQuery, List[QueryMediaRange]] = {
    val items = splitOnTopLevelCommas(s)
    items.foldLeft(Eru.succeed(List.empty[QueryMediaRange]): Eru[InvalidAcceptQuery, List[QueryMediaRange]]) {
      (accEru, item) =>
        for {
          acc <- accEru
          range <- parseItem(item.trim)
        } yield acc :+ range
    }
  }

  private def parseItem(item: String): Eru[InvalidAcceptQuery, QueryMediaRange] = {
    val segments = splitOnSemicolons(item)
    val bare = segments.head.trim
    for {
      bareValue <- parseBareItem(bare, item)
      params <- segments.tail.foldLeft(
        Eru.succeed(Map.empty[String, String]): Eru[InvalidAcceptQuery, Map[String, String]]
      ) { (accEru, segment) =>
        for {
          acc <- accEru
          (key, value) <- parseParameter(segment.trim, item)
        } yield acc + (key -> value)
      }
    } yield QueryMediaRange(bareValue, params)
  }

  private def parseBareItem(bare: String, original: String): Eru[InvalidAcceptQuery, String] = {
    if bare.isEmpty then Eru.fail(InvalidAcceptQuery(original, "Empty list item"))
    else if bare.startsWith("\"") then parseQuotedString(bare, original)
    else if isSfToken(bare) then Eru.succeed(bare)
    else Eru.fail(InvalidAcceptQuery(original, s"Invalid media range: $bare"))
  }

  private def parseQuotedString(s: String, original: String): Eru[InvalidAcceptQuery, String] = {
    if !s.startsWith("\"") then Eru.fail(InvalidAcceptQuery(original, "Quoted string must start with '\"'"))
    else {
      val result = new StringBuilder
      var i = 1
      var closed = false
      var error: Option[InvalidAcceptQuery] = None

      while i < s.length && !closed && error.isEmpty do {
        s.charAt(i) match {
          case '"' =>
            if i == s.length - 1 then closed = true
            else error = Some(InvalidAcceptQuery(original, "Characters after closing quote"))

          case '\\' =>
            if i + 1 >= s.length then {
              error = Some(InvalidAcceptQuery(original, "Incomplete escape sequence at end of string"))
            } else {
              i += 1
              result.append(s.charAt(i))
            }

          case c if c < 0x20 || c == 0x7f =>
            error = Some(InvalidAcceptQuery(original, s"Invalid character in string: ${c.toInt.toHexString}"))

          case c => result.append(c)
        }
        i += 1
      }

      error match {
        case Some(e) => Eru.fail(e)
        case None =>
          if !closed then Eru.fail(InvalidAcceptQuery(original, "Unclosed quoted string"))
          else Eru.succeed(result.toString)
      }
    }
  }

  /** Parses one parameter segment into a (key, value) pair.
    *
    * The caller has already consumed the leading `';'`; a segment with no `'='` is a bare
    * parameter, which per Structured Fields means a boolean with value `true`.
    */
  private def parseParameter(segment: String, original: String): Eru[InvalidAcceptQuery, (String, String)] = {
    val body = segment.trim
    if body.isEmpty then {
      Eru.fail(InvalidAcceptQuery(original, "Empty parameter segment"))
    } else {
      val eq = body.indexOf('=')
      if eq < 0 then {
        if isValidKey(body) then Eru.succeed((body, "true"))
        else Eru.fail(InvalidAcceptQuery(original, s"Invalid parameter key: $body"))
      } else {
        val key = body.substring(0, eq)
        val valueStr = body.substring(eq + 1)
        if !isValidKey(key) then {
          Eru.fail(InvalidAcceptQuery(original, s"Invalid parameter key: $key"))
        } else if valueStr.startsWith("\"") then {
          parseQuotedString(valueStr, original).map(v => (key, v))
        } else if isSfToken(valueStr) then {
          Eru.succeed((key, valueStr))
        } else {
          Eru.fail(InvalidAcceptQuery(original, s"Invalid parameter value: $valueStr"))
        }
      }
    }
  }

  private def validate(
    items: List[QueryMediaRange],
    original: String
  ): Eru[InvalidAcceptQuery, AcceptQuery] = {
    items
      .foldLeft(Eru.succeed(List.empty[QueryMediaRange]): Eru[InvalidAcceptQuery, List[QueryMediaRange]]) {
        (accEru, item) =>
          for {
            acc <- accEru
            _ <- validateMediaRange(item.range, original)
          } yield acc :+ item
      }
      .map(AcceptQuery.apply)
  }

  private def validateMediaRange(range: String, original: String): Eru[InvalidAcceptQuery, Unit] = {
    if range == "*/*" then Eru.unit
    else {
      range.split("/", -1) match {
        case Array(t, "*") if isValidHttpToken(t) => Eru.unit
        case Array(t, s) if isValidHttpToken(t) && isValidHttpToken(s) => Eru.unit
        case _ => Eru.fail(InvalidAcceptQuery(original, s"Invalid media range: $range"))
      }
    }
  }

  /** RFC 9651 Section 4.2.6: the first character is ALPHA or "*"; subsequent characters are tchar,
    * ":", or "/".
    */
  private def isSfToken(s: String): Boolean =
    s.nonEmpty &&
      (s.head.isLetter || s.head == '*') &&
      s.forall(isSfTokenChar)

  private def isSfTokenChar(c: Char): Boolean = {
    c match {
      case '!' | '#' | '$' | '%' | '&' | '\'' | '*' | '+' | '-' | '.' | '^' | '_' | '`' | '|' | '~' | ':' | '/' =>
        true
      case c if c.isLetterOrDigit => true
      case _ => false
    }
  }

  /** RFC 9110 token (no ':' or '/'). */
  private def isValidHttpToken(s: String): Boolean =
    s.nonEmpty && s.forall(isValidHttpTokenChar)

  private def isValidHttpTokenChar(c: Char): Boolean = {
    c match {
      case '!' | '#' | '$' | '%' | '&' | '\'' | '*' | '+' | '-' | '.' | '^' | '_' | '`' | '|' | '~' => true
      case c if c.isLetterOrDigit => true
      case _ => false
    }
  }

  /** Parameter keys, simplified from RFC 9651: any letter or digit, plus "_" and "-". RFC 9651
    * restricts keys to lowercase lcalpha, which this validator does not enforce.
    */
  private def isValidKey(s: String): Boolean =
    s.nonEmpty && s.forall(c => c.isLetterOrDigit || c == '_' || c == '-')

  private def splitOnTopLevelCommas(s: String): List[String] = {
    val parts = scala.collection.mutable.ListBuffer[String]()
    val current = new StringBuilder
    var inQuotes = false
    var escaped = false
    var i = 0

    while i < s.length do {
      val c = s.charAt(i)
      if escaped then {
        current.append(c)
        escaped = false
      } else if c == '\\' && inQuotes then {
        current.append(c)
        escaped = true
      } else if c == '"' then {
        current.append(c)
        inQuotes = !inQuotes
      } else if c == ',' && !inQuotes then {
        parts += current.toString
        current.clear()
      } else {
        current.append(c)
      }
      i += 1
    }

    if current.nonEmpty then parts += current.toString
    parts.toList
  }

  private def splitOnSemicolons(s: String): List[String] = {
    val parts = scala.collection.mutable.ListBuffer[String]()
    val current = new StringBuilder
    var inQuotes = false
    var escaped = false
    var i = 0

    while i < s.length do {
      val c = s.charAt(i)
      if escaped then {
        current.append(c)
        escaped = false
      } else if c == '\\' && inQuotes then {
        current.append(c)
        escaped = true
      } else if c == '"' then {
        current.append(c)
        inQuotes = !inQuotes
      } else if c == ';' && !inQuotes then {
        parts += current.toString
        current.clear()
      } else {
        current.append(c)
      }
      i += 1
    }

    if current.nonEmpty then parts += current.toString
    parts.toList
  }

  /** Error for invalid Accept-Query field values.
    */
  final case class InvalidAcceptQuery(
    value: String,
    reason: String,
    rfc: String = "RFC 10008 Section 3"
  ) {
    def message: String = s"Invalid Accept-Query value '$value': $reason ($rfc)"
  }
}
