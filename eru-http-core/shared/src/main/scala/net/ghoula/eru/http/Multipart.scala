package net.ghoula.eru.http

import net.ghoula.eru.*

/** Multipart form data support per RFC 7578.
  *
  * Provides parsing and encoding of multipart/form-data content, commonly used for file uploads in
  * HTTP forms.
  *
  * Key features:
  *   - RFC 7578 compliant boundary generation and formatting
  *   - Support for form fields and file uploads
  *   - Proper CRLF line ending handling
  *   - Content-Disposition header parsing
  *   - Binary-safe encoding and decoding
  *
  * Example usage:
  * {{{
  * val parts = List(
  *   Part.formField("username", "john"),
  *   Part.fileFromBytes("avatar", "photo.jpg", MediaType.imageJpeg, imageBytes)
  * )
  * val multipart = Multipart.formData(parts).runTest
  * val body = multipart.toBody.runTest
  * }}}
  */

/** A single part in a multipart message.
  *
  * Each part has a name (from Content-Disposition), optional headers, an optional filename (for
  * file uploads), and a body containing the part's data.
  *
  * @param name
  *   the name of this part from Content-Disposition
  * @param headers
  *   additional headers for this part (excludes Content-Disposition which is generated)
  * @param filename
  *   optional filename for file uploads
  * @param body
  *   the content of this part
  */
final case class Part(
  name: String,
  headers: Headers = Headers.empty,
  filename: Option[String] = None,
  body: Body
) {

  /** Returns the Content-Type for this part, if any.
    *
    * @return
    *   the media type of this part's body, or None if not specified
    */
  def contentType: Option[MediaType] = body.mediaType

  /** Generates the Content-Disposition header value for this part.
    *
    * Format: form-data; name="fieldname"[; filename="filename.txt"]
    *
    * @return
    *   the Content-Disposition header value for this part
    */
  private[http] def contentDispositionValue: String = {
    val nameParam = s"""name="${Part.escapeQuotedString(name)}""""
    filename match {
      case Some(fn) =>
        val filenameParam = s"""filename="${Part.escapeQuotedString(fn)}""""
        s"form-data; $nameParam; $filenameParam"
      case None =>
        s"form-data; $nameParam"
    }
  }
}

object Part {

  /** Creates a form field part (simple text field).
    *
    * @param name
    *   the field name
    * @param value
    *   the field value
    * @return
    *   a Part representing a text form field
    */
  def formField(name: String, value: String): Part = {
    Part(
      name = name,
      headers = Headers.empty,
      filename = None,
      body = Body.text(value, MediaType.textPlain.withCharset("utf-8"))
    )
  }

  /** Creates a file upload part from a Body.
    *
    * @param name
    *   the field name
    * @param filename
    *   the filename to report
    * @param contentType
    *   the media type of the file
    * @param body
    *   the file content
    * @return
    *   an Eru effect producing a Part representing a file upload
    */
  def file(name: String, filename: String, contentType: MediaType, body: Body): Eru[HttpError, Part] = {
    Eru.succeed(
      Part(
        name = name,
        headers = Headers.empty,
        filename = Some(filename),
        body = body match {
          case Body.Empty => Body.Binary(Bytes.empty, Some(contentType))
          case Body.Text(value, _, charset) =>
            Body.Binary(Bytes.fromString(value, charset), Some(contentType))
          case Body.Binary(value, _) => Body.Binary(value, Some(contentType))
          case stream: Body.Stream => stream.copy(mediaType = Some(contentType))
        }
      )
    )
  }

  /** Creates a file upload part from bytes.
    *
    * @param name
    *   the field name
    * @param filename
    *   the filename to report
    * @param contentType
    *   the media type of the file
    * @param bytes
    *   the file content as bytes
    * @return
    *   an Eru effect producing a Part representing a file upload
    */
  def fileFromBytes(
    name: String,
    filename: String,
    contentType: MediaType,
    bytes: Bytes
  ): Eru[HttpError, Part] = {
    Eru.succeed(
      Part(
        name = name,
        headers = Headers.empty,
        filename = Some(filename),
        body = Body.Binary(bytes, Some(contentType))
      )
    )
  }

  /** Escapes a string for use in a quoted-string per RFC 9110.
    *
    * Escapes backslash and double-quote characters.
    *
    * @param value
    *   the string to escape
    * @return
    *   the escaped string safe for use in quoted-string contexts
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

  /** Parses Content-Disposition header to extract name and filename parameters.
    *
    * Format: form-data; name="fieldname"[; filename="filename.txt"]
    *
    * @param headerValue
    *   the Content-Disposition header value
    * @return
    *   (name, optionalFilename) or error
    */
  private[http] def parseContentDisposition(
    headerValue: String
  ): Eru[HttpError, (String, Option[String])] = {
    // Split by semicolon but respect quoted strings
    val parts = splitParameters(headerValue)

    if parts.isEmpty then {
      Eru.fail(HttpError.ProtocolError("Empty Content-Disposition header", "RFC 7578"))
    } else {
      val disposition = parts.head.trim.toLowerCase
      if disposition != "form-data" then {
        Eru.fail(
          HttpError.ProtocolError(
            s"Content-Disposition must be 'form-data', got '$disposition'",
            "RFC 7578"
          )
        )
      } else {
        val params = parts.tail
        var name: Option[String] = None
        var filename: Option[String] = None

        params.foreach { param =>
          parseParameter(param.trim) match {
            case ("name", value) => name = Some(value)
            case ("filename", value) => filename = Some(value)
            case _ => // ignore other parameters
          }
        }

        name match {
          case Some(n) => Eru.succeed((n, filename))
          case None =>
            Eru.fail(
              HttpError.ProtocolError("Content-Disposition must have 'name' parameter", "RFC 7578")
            )
        }
      }
    }
  }

  /** Splits a header value on semicolons, respecting quoted strings.
    *
    * @param s
    *   the string to split
    * @return
    *   the list of parameter strings
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

  /** Parses a single parameter like name="value" or name=value.
    *
    * Returns (key, value) with quotes removed from value.
    *
    * @param param
    *   the parameter string to parse
    * @return
    *   a tuple of (key, value) with quotes removed from value
    */
  private def parseParameter(param: String): (String, String) = {
    param.split("=", 2) match {
      case Array(key, value) =>
        val trimmedKey = key.trim
        val trimmedValue = value.trim
        val unquotedValue = if trimmedValue.startsWith("\"") && trimmedValue.endsWith("\"") then {
          unescapeQuotedString(trimmedValue.substring(1, trimmedValue.length - 1))
        } else {
          trimmedValue
        }
        (trimmedKey.toLowerCase, unquotedValue)
      case _ => ("", "")
    }
  }

  /** Unescapes a quoted-string per RFC 9110.
    *
    * @param s
    *   the escaped string
    * @return
    *   the unescaped string
    */
  private def unescapeQuotedString(s: String): String = {
    val result = new StringBuilder
    var i = 0
    var escaped = false

    while i < s.length do {
      val c = s.charAt(i)
      if escaped then {
        result.append(c)
        escaped = false
      } else if c == '\\' then {
        escaped = true
      } else {
        result.append(c)
      }
      i += 1
    }

    result.toString
  }
}

/** Multipart form data per RFC 7578.
  *
  * Represents a collection of parts separated by a boundary string. Each part can be a form field
  * or a file upload.
  *
  * @param parts
  *   the list of parts in this multipart message
  * @param boundary
  *   the boundary string separating parts
  */
final case class Multipart(
  parts: List[Part],
  boundary: String
) {

  /** Converts this multipart message to a Body.
    *
    * Encodes all parts using the multipart/form-data format with CRLF line endings.
    *
    * @return
    *   an Eru effect producing the encoded Body
    */
  def toBody: Eru[HttpError, Body] = {
    if parts.isEmpty then {
      Eru.fail(HttpError.ProtocolError("Multipart must have at least one part", "RFC 7578"))
    } else {
      Eru.effect {
        val builder = new StringBuilder

        // Encode each part
        parts.foreach { part =>
          // Boundary line: --{boundary}CRLF
          builder.append("--")
          builder.append(boundary)
          builder.append("\r\n")

          // Content-Disposition header
          builder.append("Content-Disposition: ")
          builder.append(part.contentDispositionValue)
          builder.append("\r\n")

          // Content-Type header (if present)
          part.contentType.foreach { ct =>
            builder.append("Content-Type: ")
            builder.append(ct.value)
            builder.append("\r\n")
          }

          // Empty line separating headers from body
          builder.append("\r\n")

          // Body content (converted to bytes, then back to string for now)
          // Note: This is simplified - real implementation should handle binary properly
          val bodyBytes = part.body match {
            case Body.Empty => Bytes.empty
            case Body.Text(value, _, charset) => Bytes.fromString(value, charset)
            case Body.Binary(bytes, _) => bytes
            case _: Body.Stream =>
              // For now, consume stream into memory
              // TODO: Support streaming multipart in the future
              throw new UnsupportedOperationException("Streaming parts not yet supported in multipart encoding")
          }

          // Convert bytes to ISO-8859-1 to preserve binary data in string
          // This is necessary because we're building the multipart body as a string
          // but need to preserve binary data
          builder.append(bodyBytes.asString(Charset.ISO_8859_1))
          builder.append("\r\n")
        }

        // Final boundary: --{boundary}--CRLF
        builder.append("--")
        builder.append(boundary)
        builder.append("--")
        builder.append("\r\n")

        // Convert entire multipart body to bytes using ISO-8859-1 to preserve binary
        val fullBytes = Bytes.fromString(builder.toString, Charset.ISO_8859_1)
        Body.Binary(fullBytes, Some(contentType))
      }.mapError { e =>
        HttpError.BodyEncodeError(EncodeError(s"Failed to encode multipart: ${e.getMessage}", Some(e)))
      }
    }
  }

  /** Returns the Content-Type for this multipart message.
    *
    * Format: multipart/form-data; boundary={boundary}
    *
    * @return
    *   the media type with boundary parameter
    */
  def contentType: MediaType = {
    MediaType.multipartFormData.withParameter("boundary", boundary)
  }
}

object Multipart {

  /** Creates a multipart/form-data message from parts.
    *
    * Generates a random boundary string automatically.
    *
    * @param parts
    *   the parts to include
    * @return
    *   an Eru effect producing the Multipart
    */
  def formData(parts: List[Part]): Eru[HttpError, Multipart] = {
    if parts.isEmpty then {
      Eru.fail(HttpError.ProtocolError("Multipart must have at least one part", "RFC 7578"))
    } else {
      Eru.succeed(Multipart(parts, generateBoundary))
    }
  }

  /** Parses a multipart/form-data body.
    *
    * @param body
    *   the body to parse
    * @param boundary
    *   the boundary string from Content-Type header
    * @return
    *   an Eru effect producing the parsed Multipart
    */
  def parse(body: Body, boundary: String): Eru[HttpError, Multipart] = {
    if boundary.isEmpty then {
      Eru.fail(HttpError.ProtocolError("Boundary cannot be empty", "RFC 7578"))
    } else {
      // Convert body to bytes
      val bytesEru: Eru[HttpError, Bytes] = body match {
        case Body.Empty => Eru.succeed(Bytes.empty)
        case Body.Text(value, _, charset) => Eru.succeed(Bytes.fromString(value, charset))
        case Body.Binary(bytes, _) => Eru.succeed(bytes)
        case stream: Body.Stream =>
          stream.toBytes.mapError { _ =>
            HttpError.BodyDecodeError(DecodeError("Failed to read stream for multipart parsing"))
          }
      }

      bytesEru.flatMap { bytes =>
        parseBytes(bytes, boundary)
      }
    }
  }

  /** Parses multipart bytes with a given boundary.
    *
    * @param bytes
    *   the bytes to parse
    * @param boundary
    *   the boundary string
    * @return
    *   the parsed Multipart or an error
    */
  private def parseBytes(bytes: Bytes, boundary: String): Eru[HttpError, Multipart] = {
    Eru.effect {
      // Convert to string using ISO-8859-1 to preserve binary data
      val content = bytes.asString(Charset.ISO_8859_1)

      // Boundary markers
      val boundaryMarker = s"--$boundary"
      val finalBoundaryMarker = s"--$boundary--"

      // Split content by boundary markers
      val rawParts = content.split(s"(?=--$boundary)")

      // Parse each part
      val parts = scala.collection.mutable.ListBuffer[Part]()

      rawParts.foreach { rawPart =>
        // Don't trim - we need to preserve the structure
        if rawPart.startsWith(boundaryMarker) && !rawPart.startsWith(finalBoundaryMarker) then {
          // Remove boundary marker and leading CRLF
          val withoutBoundary = rawPart.substring(boundaryMarker.length).stripPrefix("\r\n").stripPrefix("\n")

          // Find the empty line that separates headers from body
          val headerBodySplit = withoutBoundary.indexOf("\r\n\r\n") match {
            case -1 => withoutBoundary.indexOf("\n\n")
            case i => i
          }

          if headerBodySplit >= 0 then {
            val headerSection = withoutBoundary.substring(0, headerBodySplit)
            val separatorLen = if withoutBoundary.substring(headerBodySplit).startsWith("\r\n\r\n") then 4 else 2
            val bodySection = withoutBoundary.substring(headerBodySplit + separatorLen)

            // Parse headers
            val headerLines = headerSection.split("\r?\n")
            var name: Option[String] = None
            var filename: Option[String] = None
            var contentType: Option[MediaType] = None
            val partHeaders = scala.collection.mutable.ListBuffer[(String, String)]()

            headerLines.foreach { line =>
              val colonIndex = line.indexOf(':')
              if colonIndex > 0 then {
                val headerName = line.substring(0, colonIndex).trim
                val headerValue = line.substring(colonIndex + 1).trim

                headerName.toLowerCase match {
                  case "content-disposition" =>
                    Part.parseContentDisposition(headerValue).unsafeRunSync() match {
                      case (n, fn) =>
                        name = Some(n)
                        filename = fn
                    }
                  case "content-type" =>
                    MediaType.parse(headerValue).attempt.unsafeRunSync() match {
                      case Result.Success(mt) => contentType = Some(mt)
                      case Result.Failure(_) => // ignore invalid media types
                    }
                  case _ =>
                    partHeaders += ((headerName, headerValue))
                }
              }
            }

            // Create part body
            // The body section may end with CRLF before the next boundary
            // We need to strip trailing CRLF but preserve empty bodies
            var cleanBodySection = bodySection
            // Check if it ends with CRLF (which separates from next boundary)
            if cleanBodySection.endsWith("\r\n") then {
              cleanBodySection = cleanBodySection.substring(0, cleanBodySection.length - 2)
            } else if cleanBodySection.endsWith("\n") then {
              cleanBodySection = cleanBodySection.substring(0, cleanBodySection.length - 1)
            }

            val bodyBytes = Bytes.fromString(cleanBodySection, Charset.ISO_8859_1)

            name match {
              case Some(n) =>
                val partBody = contentType match {
                  case Some(ct) => Body.Binary(bodyBytes, Some(ct))
                  case None => Body.Binary(bodyBytes, Some(MediaType.applicationOctetStream))
                }

                val headers = partHeaders.foldLeft(Headers.empty) { case (h, (name, value)) =>
                  h.add(name, value).attempt.unsafeRunSync() match {
                    case Result.Success(updated) => updated
                    case Result.Failure(_) => h // skip invalid headers
                  }
                }

                parts += Part(n, headers, filename, partBody)
              case None =>
              // Skip parts without name
            }
          }
        }
      }

      if parts.isEmpty then {
        throw new IllegalArgumentException("No valid parts found in multipart body")
      }

      Multipart(parts.toList, boundary)
    }.mapError { e =>
      HttpError.BodyDecodeError(
        DecodeError(s"Failed to parse multipart body: ${e.getMessage}", Some(e))
      )
    }
  }

  /** Generates a random boundary string per RFC 7578.
    *
    * The boundary must not appear in any part content. We use a random UUID-based string with a
    * prefix to make collisions extremely unlikely.
    *
    * @return
    *   a boundary string safe for use in multipart messages
    */
  def generateBoundary: String = {
    val random = new java.security.SecureRandom()
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    s"----EruHttpFormBoundary${bytes.map("%02x".format(_)).mkString}"
  }
}

/** BodyEncoder for Multipart messages.
  *
  * Encodes a Multipart into a Body with appropriate Content-Type header. This encoder automatically
  * includes the boundary parameter in the Content-Type.
  */
given multipartEncoder: BodyEncoder[Multipart] = new BodyEncoder[Multipart] {
  def encode(mp: Multipart, mediaType: Option[MediaType]): Eru[EncodeError, Body] =
    mp.toBody.mapError(e => EncodeError(e.message))

  def defaultMediaType: MediaType = MediaType.multipartFormData
}

/** Creates a BodyDecoder for Multipart messages.
  *
  * The decoder requires a boundary parameter extracted from the Content-Type header. Extract the
  * boundary from the Content-Type header's parameters before creating the decoder.
  *
  * @param boundary
  *   the boundary string from the Content-Type header
  * @return
  *   a BodyDecoder that can parse multipart/form-data with the given boundary
  */
def multipartDecoder(boundary: String): BodyDecoder[Multipart] = new BodyDecoder[Multipart] {
  def decode(body: Body): Eru[DecodeError, Multipart] =
    Multipart.parse(body, boundary).mapError(e => DecodeError(e.message))

  def supportedMediaTypes: List[MediaType] = List(MediaType.multipartFormData)
}
