package net.ghoula.eru.http

import java.nio.channels.SocketChannel

import net.ghoula.eru.*

/** HTTP/1.1 Parser for requests and responses.
  *
  * Implements RFC 9112 (HTTP/1.1) parsing using blocking NIO. Designed to work efficiently with
  * Eru's Virtual Threads.
  */
object HttpParser {

  private val SP = " "
  private val COLON = ":"
  private val MAX_HEADERS_SIZE = 64 * 1024 // 64KB

  // Pre-parsed header names for zero-allocation header parsing
  // These are the most common HTTP headers - we intern them once
  private val commonHeaderNames = Set(
    "content-length",
    "content-type",
    "connection",
    "host",
    "user-agent",
    "accept",
    "accept-encoding",
    "transfer-encoding",
    "date",
    "server",
    "cache-control",
    "expires",
    "last-modified",
    "etag",
    "location",
    "set-cookie",
    "cookie",
    "authorization"
  ).map(h => h.toLowerCase -> h.split("-").map(_.capitalize).mkString("-")).toMap

  /** Parse an HTTP request from a socket channel.
    *
    * @param socket
    *   The socket channel to read from (must be in blocking mode)
    * @return
    *   An Eru effect containing the parsed request
    */
  def parseRequest(socket: SocketChannel): Eru[HttpError, Request[Body]] = {
    val reader = new BufferedSocketReader(socket)
    parseRequest(reader)
  }

  /** Parse an HTTP request from a BufferedSocketReader.
    *
    * This overload allows reusing a BufferedSocketReader across multiple requests on the same
    * connection (HTTP keep-alive), which is critical for performance as it avoids allocating a new
    * 8KB direct ByteBuffer per request.
    *
    * @param reader
    *   The buffered socket reader to read from
    * @return
    *   An Eru effect containing the parsed request
    */
  def parseRequest(reader: BufferedSocketReader): Eru[HttpError, Request[Body]] = {
    for {
      requestLine <- readLineBuffered(reader)
      (method, uri, version) <- parseRequestLine(requestLine)
      headers <- readHeadersBuffered(reader)
      body <- readBodyBuffered(reader, headers)
    } yield Request(method, uri, headers, body, version)
  }

  /** Parse an HTTP response from a socket channel.
    *
    * @param socket
    *   The socket channel to read from (must be in blocking mode)
    * @return
    *   An Eru effect containing the parsed response
    */
  def parseResponse(socket: SocketChannel): Eru[HttpError, Response[Body]] = {
    val reader = new BufferedSocketReader(socket)
    parseResponseWithReader(reader)
  }

  /** Parse an HTTP response using an existing reader (for connection pooling).
    *
    * This allows reusing BufferedSocketReader across requests to avoid allocation overhead. The
    * caller should reset() the reader before calling this method.
    *
    * @param reader
    *   The buffered socket reader to use
    * @return
    *   An Eru effect containing the parsed response
    */
  def parseResponseWithReader(reader: BufferedSocketReader): Eru[HttpError, Response[Body]] = {
    for {
      statusLine <- readLineBuffered(reader)
      (version, status, _) <- parseStatusLine(statusLine)
      headers <- readHeadersBuffered(reader)
      body <- readBodyBuffered(reader, headers)
    } yield Response(status, headers, body, version)
  }

  /** Parse HTTP request line: "GET /path HTTP/1.1"
    *
    * RFC 9112 Section 3: request-line = method SP request-target SP HTTP-version CRLF
    */
  private def parseRequestLine(line: String): Eru[HttpError, (Method, Uri, HttpVersion)] = {
    val parts = line.split(SP, 3)
    if parts.length != 3 then {
      Eru.fail(
        HttpError.InvalidRequest(
          InvalidRequest(
            s"Invalid request line: expected 'METHOD URI VERSION', got: $line",
            "RFC 9112 Section 3"
          )
        )
      )
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
      Eru.fail(
        HttpError.InvalidResponse(
          InvalidResponse(
            s"Invalid status line: expected 'VERSION CODE [REASON]', got: $line",
            "RFC 9112 Section 4"
          )
        )
      )
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
      case other =>
        Eru.fail(
          HttpError.InvalidRequest(
            InvalidRequest(
              s"Unsupported HTTP version: $other (expected HTTP/1.0 or HTTP/1.1)",
              "RFC 9112 Section 2.3"
            )
          )
        )
    }
  }

  /** Read a line using buffered reader (high performance version)
    */
  private def readLineBuffered(reader: BufferedSocketReader): Eru[HttpError, String] =
    Eru.effect {
      reader.readLine()
    }.mapError {
      case e: java.io.EOFException =>
        HttpError.NetworkError(s"Connection closed: ${e.getMessage}", Some(e))
      case e: Exception =>
        HttpError.NetworkError(s"Error reading line: ${e.getMessage}", Some(e))
    }

  /** Read HTTP headers until empty line (\r\n\r\n) - buffered version
    *
    * RFC 9112 Section 5: header-field = field-name ":" OWS field-value OWS
    */
  private def readHeadersBuffered(reader: BufferedSocketReader): Eru[HttpError, Headers] = {
    def loop(
      pairs: List[(String, HeaderValue)],
      bytesRead: Int
    ): Eru[HttpError, List[(String, HeaderValue)]] = {
      if bytesRead > MAX_HEADERS_SIZE then {
        Eru.fail(
          HttpError.InvalidRequest(
            InvalidRequest(
              s"Headers too large (max $MAX_HEADERS_SIZE bytes)",
              "RFC 9112 Section 2.3"
            )
          )
        )
      } else {
        readLineBuffered(reader).flatMap { line =>
          if line.isEmpty then {
            // Empty line marks end of headers
            Eru.succeed(pairs)
          } else {
            parseHeaderLine(line).flatMap { case (name, value) =>
              HeaderName
                .parse(name)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110 Section 5.5")))
                .flatMap { _ =>
                  HeaderValue
                    .parse(value)
                    .mapError(e =>
                      HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110 Section 5.5"))
                    )
                    .flatMap { hv =>
                      loop(pairs :+ (name, hv), bytesRead + line.length + 2)
                    }
                }
            }
          }
        }
      }
    }

    loop(List.empty, 0).map(Headers.fromValidatedPairs)
  }

  /** Parse a single header line: "Content-Type: application/json"
    *
    * Uses pre-parsed header names for common headers to avoid allocations.
    */
  private def parseHeaderLine(line: String): Eru[HttpError, (String, String)] = {
    val colonIndex = line.indexOf(COLON)
    if colonIndex <= 0 then {
      Eru.fail(
        HttpError.InvalidRequest(
          InvalidRequest(
            s"Invalid header line (missing colon): $line",
            "RFC 9112 Section 5"
          )
        )
      )
    } else {
      val rawName = line.substring(0, colonIndex).trim
      val value = line.substring(colonIndex + 1).trim

      // Use pre-parsed header name if it's a common one (zero allocation)
      val name = commonHeaderNames.getOrElse(rawName.toLowerCase, rawName)

      Eru.succeed((name, value))
    }
  }

  /** Read message body based on Content-Length or Transfer-Encoding - buffered version
    *
    * RFC 9112 Section 6: Message body determined by:
    *   1. Transfer-Encoding: chunked
    *   2. Content-Length header
    *   3. Connection close (for responses only)
    */
  private def readBodyBuffered(reader: BufferedSocketReader, headers: Headers): Eru[HttpError, Body] = {
    // Check Transfer-Encoding first (takes precedence over Content-Length)
    headers.getFirst(HeaderNames.TransferEncoding) match {
      case Some(te) if te.value.toLowerCase.contains("chunked") =>
        readChunkedBodyBuffered(reader)

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
                val bytes = reader.readBytes(length.toInt)
                Body.Binary(Bytes.fromArray(bytes), None)
              }
            }.mapError {
              case _: NumberFormatException =>
                HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: ${cl.value}", "RFC 9110 Section 8.6"))
              case e: Exception =>
                HttpError.NetworkError(s"Error reading body: ${e.getMessage}", Some(e))
            }

          case None =>
            // No body
            Eru.succeed(Body.Empty)
        }
    }
  }

  /** Read message body based on Content-Length or Transfer-Encoding - legacy unbuffered version
    *
    * RFC 9112 Section 6: Message body determined by:
    *   1. Transfer-Encoding: chunked
    *   2. Content-Length header
    *   3. Connection close (for responses only)
    */

  /** Read chunked message body (Transfer-Encoding: chunked) - streaming version
    *
    * RFC 9112 Section 7.1: Chunked transfer coding Format: chunk-size CRLF chunk-data CRLF ... 0
    * CRLF CRLF
    *
    * Returns Body.Stream to avoid buffering the entire body in memory.
    */
  private def readChunkedBodyBuffered(reader: BufferedSocketReader): Eru[HttpError, Body] = {
    // Create a streaming ChunkStream that lazily reads chunks from the socket
    val chunkStream = createChunkStreamFromReader(reader)
    Eru.succeed(Body.Stream(chunks = Eru.succeed(chunkStream), contentLength = None))
  }

  /** Creates a ChunkStream that lazily reads chunks from a BufferedSocketReader.
    *
    * This implements pull-based streaming - chunks are only read when pulled from the stream. This
    * avoids buffering the entire request body in memory.
    */
  private def createChunkStreamFromReader(reader: BufferedSocketReader): ChunkStream = {
    ChunkStream.eval {
      readNextChunkFromReader(reader).attempt.map {
        case Result.Success(Some(chunk)) =>
          // More chunks available - prepend chunk and continue reading
          ChunkStream.single(chunk) ++ createChunkStreamFromReader(reader)

        case Result.Success(None) =>
          // End of chunked stream (got 0-sized chunk)
          ChunkStream.Empty

        case Result.Failure(_) =>
          // Error reading chunk - terminate stream
          ChunkStream.Empty
      }
    }
  }

  /** Read the next chunk from the reader.
    *
    * Returns None when the final 0-sized chunk is encountered (end of stream). Throws HttpError on
    * parse errors or I/O errors.
    */
  private def readNextChunkFromReader(reader: BufferedSocketReader): Eru[HttpError, Option[Chunk]] = {
    for {
      chunkSizeLine <- readLineBuffered(reader)
      chunkSize <- parseChunkSize(chunkSizeLine)
      result <-
        if chunkSize == 0 then {
          // Final chunk - read trailing CRLF and return None to signal end
          readLineBuffered(reader).map(_ => None)
        } else {
          // Read chunk data and trailing CRLF
          for {
            bytes <- Eru.effect(reader.readBytes(chunkSize)).mapError { case e: Exception =>
              HttpError.NetworkError(s"Error reading chunk: ${e.getMessage}", Some(e))
            }
            _ <- readLineBuffered(reader) // Read trailing CRLF after chunk data
          } yield Some(Chunk.fromBytes(Bytes.fromArray(bytes)))
        }
    } yield result
  }

  /** Parse chunk size from hex string (may include chunk extensions)
    */
  private def parseChunkSize(line: String): Eru[HttpError, Int] = {
    // Chunk size may be followed by chunk extensions: chunk-size [ ";" chunk-ext ]
    val sizeStr = line.split(";", 2)(0).trim
    Eru.effect {
      Integer.parseInt(sizeStr, 16)
    }.mapError { case _: NumberFormatException =>
      HttpError.InvalidRequest(
        InvalidRequest(
          s"Invalid chunk size: $sizeStr",
          "RFC 9112 Section 7.1"
        )
      )
    }
  }
}
