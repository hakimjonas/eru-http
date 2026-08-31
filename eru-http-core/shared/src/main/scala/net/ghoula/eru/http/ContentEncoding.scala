package net.ghoula.eru.http

import net.ghoula.eru.*

/** Content encoding (compression) as defined in RFC 9110 Section 8.4.
  *
  * Content codings are primarily used to allow a representation's data to be compressed or
  * otherwise usefully transformed without losing the identity of its underlying media type.
  */
enum ContentEncoding {

  /** GZIP compression (RFC 1952). Most widely supported compression format.
    */
  case Gzip

  /** DEFLATE compression (RFC 1951). Alternative compression format.
    */
  case Deflate

  /** Brotli compression (RFC 7932). Modern compression with better ratios.
    */
  case Brotli

  /** Identity (no encoding). Explicitly indicates no transformation.
    */
  case Identity

  /** COMPRESS compression (LZW). Legacy format, rarely used.
    */
  case Compress

  /** Custom/unknown encoding with a specific name.
    *
    * @param name
    *   the encoding name (e.g., "x-custom")
    */
  case Custom(name: String)
}

object ContentEncoding {

  /** Parses a single content encoding from a string.
    *
    * @param value
    *   the encoding name (case-insensitive)
    * @return
    *   the parsed ContentEncoding or an error
    */
  def parse(value: String): Eru[ParseError, ContentEncoding] = {
    val trimmed = value.trim.toLowerCase
    if trimmed.isEmpty then {
      Eru.fail(ParseError(value, "Empty encoding name"))
    } else {
      trimmed match {
        case "gzip" | "x-gzip" => Eru.succeed(ContentEncoding.Gzip)
        case "deflate" => Eru.succeed(ContentEncoding.Deflate)
        case "br" => Eru.succeed(ContentEncoding.Brotli)
        case "identity" => Eru.succeed(ContentEncoding.Identity)
        case "compress" | "x-compress" => Eru.succeed(ContentEncoding.Compress)
        case other => Eru.succeed(ContentEncoding.Custom(other))
      }
    }
  }

  /** Parses multiple content encodings from a comma-separated string.
    *
    * Per RFC 9110 Section 8.4.1, multiple encodings are listed in the order in which they were
    * applied. For example, "gzip, deflate" means the content was first gzipped, then deflated.
    *
    * @param value
    *   the comma-separated encoding list
    * @return
    *   the list of parsed ContentEncodings or an error
    */
  def parseMultiple(value: String): Eru[ParseError, List[ContentEncoding]] = {
    val parts = value.split(",").map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then {
      Eru.succeed(List.empty)
    } else {
      parts.toList.foldLeft(Eru.succeed(List.empty[ContentEncoding])) { (accEru, part) =>
        for {
          acc <- accEru
          encoding <- parse(part)
        } yield acc :+ encoding
      }
    }
  }

  /** Parses Accept-Encoding header with quality values (qvalues).
    *
    * Format: encoding[;q=qvalue][, encoding[;q=qvalue]]*
    *
    * Per RFC 9110 Section 12.5.3, the Accept-Encoding header field allows a client to indicate
    * which content codings are acceptable. Quality values range from 0.0 to 1.0, with 1.0 being the
    * default.
    *
    * Examples:
    *   - "gzip" -> List((Gzip, 1.0))
    *   - "gzip, deflate" -> List((Gzip, 1.0), (Deflate, 1.0))
    *   - "gzip;q=0.9, deflate;q=0.8" -> List((Gzip, 0.9), (Deflate, 0.8))
    *   - "br;q=1.0, gzip;q=0.8, *;q=0.1" -> List((Brotli, 1.0), (Gzip, 0.8), (Identity, 0.1))
    *
    * @param value
    *   the Accept-Encoding header value
    * @return
    *   list of (encoding, qvalue) pairs sorted by preference (highest qvalue first)
    */
  def parseAcceptEncoding(value: String): Eru[ParseError, List[(ContentEncoding, Double)]] = {
    val parts = value.split(",").map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then {
      Eru.succeed(List.empty)
    } else {
      val parsed = parts.toList.foldLeft(Eru.succeed(List.empty[(ContentEncoding, Double)])) { (accEru, part) =>
        for {
          acc <- accEru
          pair <- parseEncodingWithQValue(part)
        } yield acc :+ pair
      }
      parsed.map(_.sortBy(-_._2))
    }
  }

  /** Parses a single encoding with optional qvalue.
    *
    * Format: encoding[;q=qvalue]
    */
  private def parseEncodingWithQValue(part: String): Eru[ParseError, (ContentEncoding, Double)] = {
    val segments = part.split(";").map(_.trim)
    segments.toList match {
      case encodingStr :: Nil =>
        for {
          encoding <- parseEncodingOrWildcard(encodingStr)
        } yield (encoding, 1.0)

      case encodingStr :: params =>
        for {
          encoding <- parseEncodingOrWildcard(encodingStr)
          qvalue <- extractQValue(params)
        } yield (encoding, qvalue)

      case Nil =>
        Eru.fail(ParseError(part, "Empty encoding segment"))
    }
  }

  /** Parses an encoding name or the wildcard `*`, which maps to Identity.
    */
  private def parseEncodingOrWildcard(value: String): Eru[ParseError, ContentEncoding] = {
    if value == "*" then {
      Eru.succeed(ContentEncoding.Identity)
    } else {
      parse(value)
    }
  }

  /** Extracts qvalue from parameter list. Defaults to 1.0 if not found.
    */
  private def extractQValue(params: List[String]): Eru[ParseError, Double] = {
    params.find { p =>
      val trimmed = p.trim
      trimmed.startsWith("q=") || trimmed.startsWith("q =")
    }.map { qParam =>
      val trimmed = qParam.trim
      val qStr = if trimmed.contains("=") then {
        trimmed.substring(trimmed.indexOf('=') + 1).trim
      } else {
        ""
      }
      qStr.toDoubleOption match {
        case Some(q) if q >= 0.0 && q <= 1.0 => Eru.succeed(q)
        case Some(q) =>
          Eru.fail(ParseError(qParam, s"qvalue must be between 0.0 and 1.0, got $q"))
        case None => Eru.fail(ParseError(qParam, s"Invalid qvalue: $qStr"))
      }
    }
      .getOrElse(Eru.succeed(1.0))
  }

  extension (encoding: ContentEncoding) {

    /** Returns the standard encoding name for use in HTTP headers.
      *
      * @return
      *   the encoding name as a string (e.g., "gzip", "br", "identity")
      */
    def value: String = encoding match {
      case ContentEncoding.Gzip => "gzip"
      case ContentEncoding.Deflate => "deflate"
      case ContentEncoding.Brotli => "br"
      case ContentEncoding.Identity => "identity"
      case ContentEncoding.Compress => "compress"
      case ContentEncoding.Custom(name) => name
    }

    /** Returns true if this encoding has a codec in the model.
      *
      * The predicate is static: it reflects which encodings the model recognizes, not whether a
      * codec is available on the current platform. Gzip and deflate use java.util.zip; identity is
      * a no-op; brotli requires the brotli4j native library at compression time. Check
      * `Compression.isSupported` for actual runtime availability.
      *
      * @return
      *   true if compression/decompression is supported for this encoding
      */
    def isSupported: Boolean = encoding match {
      case ContentEncoding.Gzip | ContentEncoding.Deflate | ContentEncoding.Identity => true
      case ContentEncoding.Brotli => true
      case _ => false
    }
  }

  /** Error for content encoding parsing failures.
    *
    * @param value
    *   the value that failed to parse
    * @param reason
    *   the reason for the parsing failure
    * @param rfc
    *   the RFC specification violated (defaults to RFC 9110 Section 8.4)
    */
  final case class ParseError(
    value: String,
    reason: String,
    rfc: String = "RFC 9110 Section 8.4"
  ) {
    def message: String = s"Invalid content encoding '$value': $reason ($rfc)"
  }
}
