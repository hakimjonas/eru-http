package net.ghoula.eru.http.acme

/** Minimal JSON model for the ACME protocol (RFC 8555 Section 8).
  *
  * ACME needs a small, well-understood slice of JSON: objects, arrays, strings, booleans, null, and
  * integers. Numbers are kept as their raw text (no float round-trips). Strings are always
  * JSON-escaped on encode; the parser accepts the standard escapes plus `\uXXXX`.
  */
enum Json derives CanEqual {
  case Obj(fields: List[(String, Json)])
  case Arr(items: List[Json])
  case Str(value: String)
  case Num(raw: String)
  case Bool(value: Boolean)
  case Null

  /** The string this value carries, if it is a string. */
  def asString: Option[String] = this match {
    case Str(value) => Some(value)
    case _ => None
  }

  /** The field value at `name`, if this is an object carrying it. */
  def field(name: String): Option[Json] = this match {
    case Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _ => None
  }

  /** The elements, if this is an array. */
  def asArray: Option[List[Json]] = this match {
    case Arr(list) => Some(list)
    case _ => None
  }

  /** The field value as a string, if present and a string. */
  def stringField(name: String): Option[String] = field(name).flatMap(_.asString)

  /** Compact JSON encoding. */
  def encode: String = Json.encode(this)
}

object Json {

  /** Builds an object from (name, value) pairs; later duplicates win. */
  def obj(fields: (String, Json)*): Json = Obj(fields.toList)

  def str(value: String): Json = Str(value)
  def num(value: Long): Json = Num(value.toString)
  def bool(value: Boolean): Json = Bool(value)

  /** Parses a JSON document. Returns Left with a reason on malformed input. */
  def parse(input: String): Either[String, Json] = {
    val parser = new Parser(input)
    parser.parseValue().flatMap { value =>
      parser.skipWhitespace()
      if parser.atEnd then Right(value)
      else Left(s"trailing characters after JSON value at offset ${parser.offset}")
    }
  }

  /** Compact encoding. */
  def encode(value: Json): String = {
    val sb = new StringBuilder
    write(value, sb)
    sb.toString
  }

  private def write(value: Json, sb: StringBuilder): Unit = value match {
    case Obj(fields) =>
      sb.append('{')
      fields.zipWithIndex.foreach { case ((name, v), i) =>
        if i > 0 then sb.append(',')
        writeString(name, sb)
        sb.append(':')
        write(v, sb)
      }
      sb.append('}')
    case Arr(items) =>
      sb.append('[')
      items.zipWithIndex.foreach { case (v, i) =>
        if i > 0 then sb.append(',')
        write(v, sb)
      }
      sb.append(']')
    case Str(value) =>
      writeString(value, sb)
    case Num(raw) =>
      sb.append(raw)
    case Bool(value) =>
      sb.append(if value then "true" else "false")
    case Null =>
      sb.append("null")
  }

  private def writeString(value: String, sb: StringBuilder): Unit = {
    sb.append('"')
    value.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case '\b' => sb.append("\\b")
      case '\f' => sb.append("\\f")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c => sb.append(c)
    }
    sb.append('"')
  }

  /** Recursive-descent JSON parser over the raw input. */
  private final class Parser(input: String) {
    var offset: Int = 0

    def atEnd: Boolean = offset >= input.length

    def skipWhitespace(): Unit =
      while offset < input.length && input.charAt(offset).isWhitespace do offset += 1

    def parseValue(): Either[String, Json] = {
      skipWhitespace()
      if atEnd then Left("unexpected end of input")
      else
        input.charAt(offset) match {
          case '{' => parseObject()
          case '[' => parseArray()
          case '"' => parseString().map(Str.apply)
          case 't' => parseLiteral("true", Bool(true))
          case 'f' => parseLiteral("false", Bool(false))
          case 'n' => parseLiteral("null", Null)
          case c if c == '-' || c.isDigit => parseNumber()
          case c => Left(s"unexpected character '$c' at offset $offset")
        }
    }

    private def parseLiteral(literal: String, value: Json): Either[String, Json] =
      if input.startsWith(literal, offset) then {
        offset += literal.length
        Right(value)
      } else Left(s"malformed literal at offset $offset")

    private def parseNumber(): Either[String, Json] = {
      val start = offset
      if offset < input.length && input.charAt(offset) == '-' then offset += 1
      while offset < input.length && (input.charAt(offset).isDigit ||
          input.charAt(offset) == '.' || input.charAt(offset) == 'e' ||
          input.charAt(offset) == 'E' || input.charAt(offset) == '+' ||
          input.charAt(offset) == '-')
      do offset += 1
      val raw = input.substring(start, offset)
      if raw.nonEmpty then Right(Num(raw)) else Left(s"empty number at offset $start")
    }

    private def parseString(): Either[String, String] = {
      // caller ensured input(offset) == '"'
      offset += 1
      val sb = new StringBuilder
      while {
        if atEnd then throw new AcmeJsonException("unterminated string")
        input.charAt(offset) match {
          case '"' =>
            offset += 1
            false
          case '\\' =>
            offset += 1
            if atEnd then throw new AcmeJsonException("unterminated escape")
            input.charAt(offset) match {
              case '"' => sb.append('"'); offset += 1
              case '\\' => sb.append('\\'); offset += 1
              case '/' => sb.append('/'); offset += 1
              case 'b' => sb.append('\b'); offset += 1
              case 'f' => sb.append('\f'); offset += 1
              case 'n' => sb.append('\n'); offset += 1
              case 'r' => sb.append('\r'); offset += 1
              case 't' => sb.append('\t'); offset += 1
              case 'u' =>
                if offset + 4 >= input.length then throw new AcmeJsonException("truncated \\u escape")
                val hex = input.substring(offset + 1, offset + 5)
                val code = scala.util.Try(Integer.parseInt(hex, 16)).toOption
                code match {
                  case Some(c) => sb.append(c.toChar); offset += 5
                  case None => throw new AcmeJsonException(s"bad \\u escape '$hex'")
                }
              case c => throw new AcmeJsonException(s"bad escape '\\$c'")
            }
            true
          case c =>
            sb.append(c)
            offset += 1
            true
        }
      } do ()
      Right(sb.toString)
    }

    private def parseObject(): Either[String, Json] = {
      offset += 1 // '{'
      skipWhitespace()
      if !atEnd && input.charAt(offset) == '}' then {
        offset += 1
        Right(Obj(Nil))
      } else {
        val fields = scala.collection.mutable.ListBuffer.empty[(String, Json)]
        var continueLoop = true
        var failure: Option[String] = None
        while continueLoop && failure.isEmpty do {
          skipWhitespace()
          if atEnd || input.charAt(offset) != '"' then failure = Some(s"expected field name at offset $offset")
          else
            parseString() match {
              case Left(err) => failure = Some(err)
              case Right(name) =>
                skipWhitespace()
                if atEnd || input.charAt(offset) != ':' then failure = Some(s"expected ':' at offset $offset")
                else {
                  offset += 1
                  parseValue() match {
                    case Left(err) => failure = Some(err)
                    case Right(v) =>
                      fields += ((name, v))
                      skipWhitespace()
                      if !atEnd && input.charAt(offset) == ',' then offset += 1
                      else if !atEnd && input.charAt(offset) == '}' then {
                        offset += 1
                        continueLoop = false
                      } else failure = Some(s"expected ',' or '}' at offset $offset")
                  }
                }
            }
        }
        failure.toLeft(Obj(fields.toList))
      }
    }

    private def parseArray(): Either[String, Json] = {
      offset += 1 // '['
      skipWhitespace()
      if !atEnd && input.charAt(offset) == ']' then {
        offset += 1
        Right(Arr(Nil))
      } else {
        val items = scala.collection.mutable.ListBuffer.empty[Json]
        var continueLoop = true
        var failure: Option[String] = None
        while continueLoop && failure.isEmpty do {
          parseValue() match {
            case Left(err) => failure = Some(err)
            case Right(v) =>
              items += v
              skipWhitespace()
              if !atEnd && input.charAt(offset) == ',' then offset += 1
              else if !atEnd && input.charAt(offset) == ']' then {
                offset += 1
                continueLoop = false
              } else failure = Some(s"expected ',' or ']' at offset $offset")
          }
        }
        failure.toLeft(Arr(items.toList))
      }
    }
  }

  /** Signals malformed JSON inside the string parser's imperative loop; converted to Left. */
  private final class AcmeJsonException(message: String) extends RuntimeException(message)
}
