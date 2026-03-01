package net.ghoula.eru.http

import net.ghoula.eru.*

/** Media type (MIME type) as defined in RFC 9110 Section 8.3.1.
  *
  * Represents types like "text/html", "application/json", etc.
  */
final case class MediaType(
  mainType: String,
  subType: String,
  parameters: Map[String, String] = Map.empty
) {

  /** The full media type string including parameters.
    *
    * @return
    *   the media type as a string (e.g., "text/html; charset=utf-8")
    */
  def value: String = {
    val base = s"$mainType/$subType"
    if parameters.isEmpty then {
      base
    } else {
      val params = parameters.map { case (k, v) =>
        if needsQuoting(v) then {
          s"""$k="${escapeQuotedString(v)}""""
        } else {
          s"$k=$v"
        }
      }.mkString("; ")
      s"$base; $params"
    }
  }

  /** Checks if a parameter value needs to be quoted. Values that are not valid tokens must be
    * quoted.
    */
  private def needsQuoting(value: String): Boolean = {
    !MediaType.isValidToken(value)
  }

  /** Escapes a string for use in a quoted-string. Escapes backslash and double-quote characters.
    */
  private def escapeQuotedString(value: String): String = {
    val result = new StringBuilder
    value.foreach {
      case '"' => result.append("\\\"")
      case '\\' => result.append("\\\\")
      case c => result.append(c)
    }
    result.toString
  }

  /** The media type without parameters.
    *
    * @return
    *   the base media type without parameters (e.g., "text/html")
    */
  def withoutParameters: String = s"$mainType/$subType"

  /** Checks if this media type matches a pattern. Supports wildcards: star/star matches anything,
    * type/star matches any subtype.
    *
    * @param pattern
    *   the pattern to match against
    * @return
    *   true if this media type matches the pattern
    */
  def matches(pattern: MediaType): Boolean = {
    val mainMatches = pattern.mainType == "*" || pattern.mainType == mainType
    val subMatches = pattern.subType == "*" || pattern.subType == subType
    mainMatches && subMatches
  }

  /** Common predicates for media type categories.
    */
  def isText: Boolean = mainType == "text"
  def isImage: Boolean = mainType == "image"
  def isAudio: Boolean = mainType == "audio"
  def isVideo: Boolean = mainType == "video"
  def isApplication: Boolean = mainType == "application"
  def isMultipart: Boolean = mainType == "multipart"
  def isMessage: Boolean = mainType == "message"

  /** Checks for specific common types.
    */
  def isJson: Boolean = {
    subType == "json" || subType.endsWith("+json")
  }

  def isXml: Boolean = {
    subType == "xml" || subType.endsWith("+xml")
  }

  def isHtml: Boolean = {
    mainType == "text" && subType == "html"
  }

  def isPlainText: Boolean = {
    mainType == "text" && subType == "plain"
  }

  def isFormUrlEncoded: Boolean = {
    mainType == "application" && subType == "x-www-form-urlencoded"
  }

  def isMultipartFormData: Boolean = {
    mainType == "multipart" && subType == "form-data"
  }

  /** Gets the charset parameter if present.
    *
    * @return
    *   the charset parameter value, or None if not present
    */
  def charset: Option[String] = parameters.get("charset")

  /** Gets the boundary parameter for multipart types.
    *
    * @return
    *   the boundary parameter value, or None if not present
    */
  def boundary: Option[String] = parameters.get("boundary")

  /** Creates a copy with an added or updated parameter.
    *
    * @param key
    *   the parameter name
    * @param value
    *   the parameter value
    * @return
    *   a new MediaType with the parameter added or updated
    */
  def withParameter(key: String, value: String): MediaType =
    copy(parameters = parameters + (key -> value))

  /** Creates a copy with charset parameter.
    *
    * @param charset
    *   the charset value (e.g., "utf-8")
    * @return
    *   a new MediaType with the charset parameter set
    */
  def withCharset(charset: String): MediaType =
    withParameter("charset", charset)

  override def toString: String = value
}

object MediaType {
  // Common media types
  val any: MediaType = MediaType("*", "*")

  // Text types
  val textPlain: MediaType = MediaType("text", "plain")
  val textHtml: MediaType = MediaType("text", "html")
  val textXml: MediaType = MediaType("text", "xml")
  val textCss: MediaType = MediaType("text", "css")
  val textJavascript: MediaType = MediaType("text", "javascript")
  val textCsv: MediaType = MediaType("text", "csv")
  val textEventStream: MediaType = MediaType("text", "event-stream")

  // Application types
  val applicationJson: MediaType = MediaType("application", "json")
  val applicationXml: MediaType = MediaType("application", "xml")
  val applicationPdf: MediaType = MediaType("application", "pdf")
  val applicationZip: MediaType = MediaType("application", "zip")
  val applicationOctetStream: MediaType = MediaType("application", "octet-stream")
  val applicationFormUrlencoded: MediaType = MediaType("application", "x-www-form-urlencoded")
  val applicationJavascript: MediaType = MediaType("application", "javascript")
  val applicationXhtmlXml: MediaType = MediaType("application", "xhtml+xml")

  // Image types
  val imageJpeg: MediaType = MediaType("image", "jpeg")
  val imagePng: MediaType = MediaType("image", "png")
  val imageGif: MediaType = MediaType("image", "gif")
  val imageSvgXml: MediaType = MediaType("image", "svg+xml")
  val imageWebp: MediaType = MediaType("image", "webp")
  val imageIcon: MediaType = MediaType("image", "x-icon")

  // Audio types
  val audioMpeg: MediaType = MediaType("audio", "mpeg")
  val audioOgg: MediaType = MediaType("audio", "ogg")
  val audioWav: MediaType = MediaType("audio", "wav")
  val audioWebm: MediaType = MediaType("audio", "webm")

  // Video types
  val videoMp4: MediaType = MediaType("video", "mp4")
  val videoMpeg: MediaType = MediaType("video", "mpeg")
  val videoOgg: MediaType = MediaType("video", "ogg")
  val videoWebm: MediaType = MediaType("video", "webm")

  // Multipart types
  val multipartFormData: MediaType = MediaType("multipart", "form-data")
  val multipartByteranges: MediaType = MediaType("multipart", "byteranges")

  // Shortcuts
  val json: MediaType = applicationJson
  val xml: MediaType = applicationXml
  val html: MediaType = textHtml
  val text: MediaType = textPlain
  val binary: MediaType = applicationOctetStream

  /** Parses a media type string.
    *
    * Format: type/subtype[; parameter=value]*
    *
    * @param value
    *   the media type string to parse
    * @return
    *   a parsed MediaType or an InvalidMediaType error
    */
  def parse(value: String): Eru[InvalidMediaType, MediaType] = {
    val trimmed = value.trim
    if trimmed.isEmpty then {
      Eru.fail(InvalidMediaType(value, "Empty media type"))
    } else {
      // Split on semicolons, but respect quoted-strings
      val parts = splitParameters(trimmed)
      parts match {
        case Nil => Eru.fail(InvalidMediaType(value, "Empty media type"))
        case typeStr :: paramStrs =>
          for {
            (mainType, subType) <- parseType(typeStr.trim)
            params <- parseParameters(paramStrs)
          } yield MediaType(mainType, subType, params)
      }
    }
  }

  /** Splits a media type string on semicolons, respecting quoted-strings. Semicolons inside
    * quoted-strings are not treated as delimiters.
    */
  private def splitParameters(s: String): List[String] = {
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

    if current.nonEmpty then {
      parts += current.toString
    }

    parts.toList
  }

  private def parseType(typeStr: String): Eru[InvalidMediaType, (String, String)] = {
    typeStr.split("/") match {
      case Array(main, sub) if main.nonEmpty && sub.nonEmpty =>
        // Validate token characters per RFC 9110
        if isValidToken(main) && isValidToken(sub) then {
          Eru.succeed((main.toLowerCase, sub.toLowerCase))
        } else {
          Eru.fail(InvalidMediaType(typeStr, "Type and subtype must be valid tokens"))
        }
      case _ =>
        Eru.fail(InvalidMediaType(typeStr, "Invalid type/subtype format"))
    }
  }

  private def parseParameters(paramStrs: List[String]): Eru[InvalidMediaType, Map[String, String]] = {
    paramStrs.foldLeft(Eru.succeed(Map.empty[String, String])) { (accEru, paramStr) =>
      for {
        params <- accEru
        (key, value) <- parseParameter(paramStr.trim)
      } yield params + (key.toLowerCase -> value)
    }
  }

  private def parseParameter(paramStr: String): Eru[InvalidMediaType, (String, String)] = {
    paramStr.split("=", 2) match {
      case Array(key, value) if key.trim.nonEmpty =>
        val trimmedKey = key.trim
        val trimmedValue = value.trim

        // Validate parameter name is a token
        if !isValidToken(trimmedKey) then {
          Eru.fail(InvalidMediaType(paramStr, s"Invalid parameter name: $trimmedKey"))
        } else if trimmedValue.startsWith("\"") then {
          // Parse quoted-string per RFC 9110
          parseQuotedString(trimmedValue).map(unquoted => (trimmedKey, unquoted))
        } else {
          // Unquoted value must be a valid token
          if !isValidToken(trimmedValue) then {
            Eru.fail(InvalidMediaType(paramStr, s"Unquoted parameter value must be a valid token: $trimmedValue"))
          } else {
            Eru.succeed((trimmedKey, trimmedValue))
          }
        }
      case _ =>
        Eru.fail(InvalidMediaType(paramStr, "Invalid parameter format"))
    }
  }

  /** Parses a quoted-string per RFC 9110 Section 5.6.4.
    *
    * quoted-string = DQUOTE *( qdtext / quoted-pair ) DQUOTE qdtext = HTAB / SP / %x21 / %x23-5B /
    * %x5D-7E / obs-text quoted-pair = "\" ( HTAB / SP / VCHAR / obs-text )
    */
  private def parseQuotedString(s: String): Eru[InvalidMediaType, String] = {
    if !s.startsWith("\"") then {
      Eru.fail(InvalidMediaType(s, "Quoted string must start with '\"'"))
    } else {
      val result = new StringBuilder
      var i = 1 // Skip opening quote
      var closed = false
      var error: Option[InvalidMediaType] = None

      while i < s.length && !closed && error.isEmpty do {
        s.charAt(i) match {
          case '"' =>
            // End of quoted string
            if i == s.length - 1 then {
              closed = true
            } else {
              error = Some(InvalidMediaType(s, "Characters after closing quote"))
            }

          case '\\' =>
            // Quoted pair - next character is escaped
            if i + 1 >= s.length then {
              error = Some(InvalidMediaType(s, "Incomplete escape sequence at end of quoted string"))
            } else {
              i += 1
              val escaped = s.charAt(i)
              // Validate escaped character is HTAB / SP / VCHAR
              if escaped == '\t' || escaped == ' ' || (escaped >= 0x21 && escaped <= 0x7e) then {
                result.append(escaped)
              } else {
                error = Some(InvalidMediaType(s, s"Invalid escaped character: ${escaped.toInt.toHexString}"))
              }
            }

          case c =>
            // Regular qdtext character
            // Valid: HTAB / SP / %x21 / %x23-5B / %x5D-7E
            if c == '\t' || c == ' ' || c == 0x21 || (c >= 0x23 && c <= 0x5b) || (c >= 0x5d && c <= 0x7e) then {
              result.append(c)
            } else {
              error = Some(InvalidMediaType(s, s"Invalid character in quoted string: ${c.toInt.toHexString}"))
            }
        }
        i += 1
      }

      error match {
        case Some(e) => Eru.fail(e)
        case None =>
          if !closed then {
            Eru.fail(InvalidMediaType(s, "Unclosed quoted string"))
          } else {
            Eru.succeed(result.toString)
          }
      }
    }
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

  /** Error for invalid media types.
    */
  final case class InvalidMediaType(
    value: String,
    reason: String,
    rfc: String = "RFC 9110 Section 8.3.1"
  ) {
    def message: String = s"Invalid media type '$value': $reason ($rfc)"
  }
}
