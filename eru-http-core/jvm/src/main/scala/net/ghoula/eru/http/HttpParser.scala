package net.ghoula.eru.http

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

import net.ghoula.eru.*

/** HTTP/1.1 Parser for requests and responses.
  *
  * Implements RFC 9112 (HTTP/1.1) parsing using blocking NIO.
  * Designed to work efficiently with Eru's Virtual Threads.
  */
object HttpParser {

  private val SP = " "
  private val COLON = ":"
  private val MAX_LINE_LENGTH = 8192
  private val MAX_HEADERS_SIZE = 64 * 1024 // 64KB

  /** Parse an HTTP request from a socket channel.
    *
    * @param socket The socket channel to read from (must be in blocking mode)
    * @return An Eru effect containing the parsed request
    */
  def parseRequest(socket: SocketChannel): Eru[HttpError, Request[Body]] = for {
    requestLine <- readLine(socket)
    (method, uri, version) <- parseRequestLine(requestLine)
    headers <- readHeaders(socket)
    body <- readBody(socket, headers)
  } yield Request(method, uri, headers, body, version)

  /** Parse an HTTP response from a socket channel.
    *
    * @param socket The socket channel to read from (must be in blocking mode)
    * @return An Eru effect containing the parsed response
    */
  def parseResponse(socket: SocketChannel): Eru[HttpError, Response[Body]] = for {
    statusLine <- readLine(socket)
    (version, status, _) <- parseStatusLine(statusLine)
    headers <- readHeaders(socket)
    body <- readBody(socket, headers)
  } yield Response(status, headers, body, version)

  /** Parse HTTP request line: "GET /path HTTP/1.1"
    *
    * RFC 9112 Section 3: request-line = method SP request-target SP HTTP-version CRLF
    */
  private def parseRequestLine(line: String): Eru[HttpError, (Method, Uri, HttpVersion)] = {
    val parts = line.split(SP, 3)
    if parts.length != 3 then {
      Eru.fail(HttpError.InvalidRequest(InvalidRequest(
        s"Invalid request line: expected 'METHOD URI VERSION', got: $line",
        "RFC 9112 Section 3"
      )))
    } else {
      for {
        method <- Method.parse(parts(0)).mapError(HttpError.InvalidMethod.apply)
        uri <- Uri.parse(parts(1)).mapError(HttpError.InvalidUri.apply)
        version <- parseHttpVersion(parts(2))
      } yield (method, uri, version)
    }
  }

  /** Parse HTTP status line: "HTTP/1.1 200 OK"
    *
    * RFC 9112 Section 4: status-line = HTTP-version SP status-code SP [ reason-phrase ] CRLF
    */
  private def parseStatusLine(line: String): Eru[HttpError, (HttpVersion, StatusCode, String)] = {
    val parts = line.split(SP, 3)
    if parts.length < 2 then {
      Eru.fail(HttpError.InvalidResponse(InvalidResponse(
        s"Invalid status line: expected 'VERSION CODE [REASON]', got: $line",
        "RFC 9112 Section 4"
      )))
    } else {
      for {
        version <- parseHttpVersion(parts(0))
        statusCode <- StatusCode(parts(1).toInt).mapError(HttpError.InvalidStatusCode.apply)
        reason = if parts.length == 3 then parts(2) else ""
      } yield (version, statusCode, reason)
    }
  }

  /** Parse HTTP version string: "HTTP/1.1" or "HTTP/1.0"
    */
  private def parseHttpVersion(versionStr: String): Eru[HttpError, HttpVersion] = {
    versionStr match {
      case "HTTP/1.0" => Eru.succeed(HttpVersion.HTTP_1_0)
      case "HTTP/1.1" => Eru.succeed(HttpVersion.HTTP_1_1)
      case "HTTP/2.0" => Eru.succeed(HttpVersion.HTTP_2_0)
      case other => Eru.fail(HttpError.InvalidRequest(InvalidRequest(
        s"Unsupported HTTP version: $other (expected HTTP/1.0 or HTTP/1.1)",
        "RFC 9112 Section 2.3"
      )))
    }
  }

  /** Read HTTP headers until empty line (\r\n\r\n)
    *
    * RFC 9112 Section 5: header-field = field-name ":" OWS field-value OWS
    */
  private def readHeaders(socket: SocketChannel): Eru[HttpError, Headers] = {
    def loop(headers: Headers, bytesRead: Int): Eru[HttpError, Headers] = {
      if bytesRead > MAX_HEADERS_SIZE then {
        Eru.fail(HttpError.InvalidRequest(InvalidRequest(
          s"Headers too large (max $MAX_HEADERS_SIZE bytes)",
          "RFC 9112 Section 2.3"
        )))
      } else {
        readLine(socket).flatMap { line =>
          if line.isEmpty then {
            // Empty line marks end of headers
            Eru.succeed(headers)
          } else {
            parseHeaderLine(line).flatMap { case (name, value) =>
              headers.add(name, value)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110 Section 5.5")))
                .flatMap(newHeaders => loop(newHeaders, bytesRead + line.length + 2))
            }
          }
        }
      }
    }

    loop(Headers.empty, 0)
  }

  /** Parse a single header line: "Content-Type: application/json"
    */
  private def parseHeaderLine(line: String): Eru[HttpError, (String, String)] = {
    val colonIndex = line.indexOf(COLON)
    if colonIndex <= 0 then {
      Eru.fail(HttpError.InvalidRequest(InvalidRequest(
        s"Invalid header line (missing colon): $line",
        "RFC 9112 Section 5"
      )))
    } else {
      val name = line.substring(0, colonIndex).trim
      val value = line.substring(colonIndex + 1).trim
      Eru.succeed((name, value))
    }
  }

  /** Read message body based on Content-Length or Transfer-Encoding
    *
    * RFC 9112 Section 6: Message body determined by:
    * 1. Transfer-Encoding: chunked
    * 2. Content-Length header
    * 3. Connection close (for responses only)
    */
  private def readBody(socket: SocketChannel, headers: Headers): Eru[HttpError, Body] = {
    // Check Transfer-Encoding first (takes precedence over Content-Length)
    headers.getFirst(HeaderNames.TransferEncoding) match {
      case Some(te) if te.value.toLowerCase.contains("chunked") =>
        readChunkedBody(socket)

      case _ =>
        // Check Content-Length
        headers.getFirst(HeaderNames.ContentLength) match {
          case Some(cl) =>
            Eru.effect {
              val length = cl.value.toLong
              if length == 0 then {
                Body.Empty
              } else if length > Int.MaxValue then {
                throw new IllegalArgumentException(s"Content-Length too large: $length")
              } else {
                readFixedLengthBody(socket, length.toInt)
              }
            }.mapError {
              case _: NumberFormatException =>
                HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: ${cl.value}", "RFC 9110 Section 8.6"))
              case e: Exception =>
                HttpError.NetworkError(s"Error reading body: ${e.getMessage}", Some(e))
            }.flatMap {
              case body: Body => Eru.succeed(body)
              case bodyEru: Eru[HttpError, Body] => bodyEru
            }

          case None =>
            // No body
            Eru.succeed(Body.Empty)
        }
    }
  }

  /** Read fixed-length message body
    */
  private def readFixedLengthBody(socket: SocketChannel, length: Int): Eru[HttpError, Body] =
    Eru.effect {
      val buffer = ByteBuffer.allocate(length)
      var totalRead = 0

      while totalRead < length do {
        val bytesRead = socket.read(buffer)
        if bytesRead == -1 then {
          throw new java.io.EOFException(s"Connection closed before reading $length bytes (read $totalRead)")
        }
        totalRead += bytesRead
      }

      buffer.flip()
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      Body.Binary(Bytes.fromArray(bytes), None)
    }.mapError { case e: Exception =>
      HttpError.NetworkError(s"Error reading body: ${e.getMessage}", Some(e))
    }

  /** Read chunked message body (Transfer-Encoding: chunked)
    *
    * RFC 9112 Section 7.1: Chunked transfer coding
    * Format: chunk-size CRLF chunk-data CRLF ... 0 CRLF CRLF
    */
  private def readChunkedBody(socket: SocketChannel): Eru[HttpError, Body] = {
    def readChunks(accumulator: Array[Byte]): Eru[HttpError, Body] = {
      for {
        chunkSizeLine <- readLine(socket)
        chunkSize <- parseChunkSize(chunkSizeLine)
        result <- if chunkSize == 0 then {
          // Last chunk, read trailing headers (we ignore them for now)
          readLine(socket).flatMap { _ =>
            Eru.succeed(Body.Binary(Bytes.fromArray(accumulator), None))
          }
        } else {
          for {
            chunkData <- readFixedLengthBody(socket, chunkSize)
            _ <- readLine(socket) // Read trailing CRLF after chunk data
            bytes = chunkData match {
              case Body.Binary(b, _) => b.toArray
              case _ => Array.empty[Byte]
            }
            result <- readChunks(accumulator ++ bytes)
          } yield result
        }
      } yield result
    }

    readChunks(Array.empty[Byte])
  }

  /** Parse chunk size from hex string (may include chunk extensions)
    */
  private def parseChunkSize(line: String): Eru[HttpError, Int] = {
    // Chunk size may be followed by chunk extensions: chunk-size [ ";" chunk-ext ]
    val sizeStr = line.split(";", 2)(0).trim
    Eru.effect {
      Integer.parseInt(sizeStr, 16)
    }.mapError { case _: NumberFormatException =>
      HttpError.InvalidRequest(InvalidRequest(
        s"Invalid chunk size: $sizeStr",
        "RFC 9112 Section 7.1"
      ))
    }
  }

  /** Read a single line from socket (up to CRLF)
    *
    * This reads one byte at a time to find CRLF. Not the most efficient,
    * but simple and correct for now. Can be optimized with buffering later.
    */
  private def readLine(socket: SocketChannel): Eru[HttpError, String] =
    Eru.effect {
      def loop(lineBuffer: StringBuilder, foundCR: Boolean, bytesRead: Int): String = {
        if bytesRead >= MAX_LINE_LENGTH then {
          throw new IllegalStateException(s"Line too long (max $MAX_LINE_LENGTH bytes)")
        }

        val byteBuffer = ByteBuffer.allocate(1)
        byteBuffer.clear(): Unit
        val n = socket.read(byteBuffer)

        if n == -1 then {
          throw new java.io.EOFException("Connection closed while reading line")
        }

        if n > 0 then {
          byteBuffer.flip(): Unit
          val byte = byteBuffer.get()
          val char = byte.toChar

          if foundCR && char == '\n' then {
            // Found CRLF - return line without CRLF
            lineBuffer.toString
          } else if foundCR then {
            // CR not followed by LF - add CR to buffer and continue
            lineBuffer.append('\r')
            if char == '\r' then {
              loop(lineBuffer, true, bytesRead + 1)
            } else {
              lineBuffer.append(char)
              loop(lineBuffer, false, bytesRead + 1)
            }
          } else if char == '\r' then {
            loop(lineBuffer, true, bytesRead + 1)
          } else {
            lineBuffer.append(char)
            loop(lineBuffer, false, bytesRead + 1)
          }
        } else {
          loop(lineBuffer, foundCR, bytesRead)
        }
      }

      loop(new StringBuilder, false, 0)
    }.mapError {
      case e: java.io.EOFException =>
        HttpError.NetworkError(s"Connection closed: ${e.getMessage}", Some(e))
      case e: Exception =>
        HttpError.NetworkError(s"Error reading line: ${e.getMessage}", Some(e))
    }
}
