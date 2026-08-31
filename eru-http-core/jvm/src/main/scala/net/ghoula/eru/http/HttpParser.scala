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
  private val MAX_HEADERS_SIZE = 64 * 1024

  /** Pre-parsed header names for the most common HTTP headers, interned once so the header-name
    * lookup avoids per-request allocation.
    */
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
    * connection (HTTP keep-alive), which avoids allocating a new 8KB direct ByteBuffer per request.
    *
    * Per RFC 9112 Section 3.2.2, an absolute-form request-target overrides the received Host header
    * with the target's authority so every downstream consumer sees one consistent authority; a
    * missing Host is left missing and rejected by request validation.
    *
    * @param reader
    *   The buffered socket reader to read from
    * @param maxBodySize
    *   Maximum allowed body size in bytes. Requests with Content-Length exceeding this fail with
    *   HttpError.PayloadTooLarge *before* allocating any body buffer. Default Int.MaxValue (no
    *   limit) preserves backwards compatibility for callers that don't opt in.
    * @return
    *   An Eru effect containing the parsed request
    */
  def parseRequest(
    reader: BufferedSocketReader,
    maxBodySize: Int = Int.MaxValue,
    onHeaders: Headers => Eru[HttpError, Unit] = _ => Eru.unit
  ): Eru[HttpError, Request[Body]] = {
    for {
      requestLine <- readLineBuffered(reader)
      (method, uri, version, isAbsoluteForm) <- parseRequestLine(requestLine)
      headers <- readHeadersBuffered(reader)
      _ <- validateRequestFramingHeaders(headers)
      // Interim hook: the server writes 100 Continue here when the request asks for it, BEFORE
      // the body read blocks waiting for content the client is withholding until it sees 100.
      _ <- onHeaders(headers)
      // The header-phase read deadline stays active through the body read as well (a Content-Length
      // body is read eagerly here), so a client that stalls mid-body hits the same deadline. The
      // caller lowers it to the body-phase deadline (or 0) once this effect returns.
      effectiveHeaders <-
        if isAbsoluteForm && headers.contains(HeaderNames.Host) then overrideHostFromTarget(headers, uri)
        else Eru.succeed(headers)
      body <- readBodyBuffered(reader, effectiveHeaders, maxBodySize)
      // Body acquisition done: the request is fully framed. Lazy chunked pulls happen after this
      // effect returns, driven by the handler — the caller sets the body-phase deadline for them.
      _ <- Eru.effect(reader.readTimeoutMillis = 0).mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield Request(method, uri, effectiveHeaders, body, version)
  }

  /** Replace the Host header with the request-target's authority (RFC 9112 Section 3.2.2).
    *
    * Host = uri-host [ ":" port ], with default ports omitted. The presence requirement (and the
    * duplicate-Host rejection) is enforced earlier by `validateRequestFramingHeaders`; this only
    * overrides the value.
    */
  private def overrideHostFromTarget(headers: Headers, uri: Uri): Eru[HttpError, Headers] = {
    val authority = uri.authority match {
      case Some(a) =>
        val hostValue = a.port match {
          case Some(p) =>
            val defaultPort = uri.scheme match {
              case Some("https") => 443
              case Some("http") => 80
              case _ => -1
            }
            if p.value == defaultPort then a.host else s"${a.host}:${p.value}"
          case None => a.host
        }
        Some(hostValue)
      case None => None
    }

    authority match {
      case Some(hostValue) =>
        headers
          .set(HeaderNames.Host, hostValue)
          .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Host header: $e", "RFC 9110")))
      case None =>
        Eru.succeed(headers)
    }
  }

  /** Reject smuggling-class header combinations before reading the body.
    *
    *   - `Content-Length` + `Transfer-Encoding` present together → 400 (RFC 9112 §6.1).
    *   - `Content-Length` or `Transfer-Encoding` or `Host` appears more than once → 400 (RFC 9112
    *     §6.2, §6.1, RFC 9110 §7.2). Even equal-value duplicates are rejected — different parsers
    *     disagree on how to collapse them, which is precisely the shape smugglers exploit.
    *   - A single `Content-Length` header whose value contains a comma (a joined list like `10, 10`
    *     or `10, 20`) → 400. Rejected regardless of whether the joined values agree, for the same
    *     parser-divergence reason.
    */
  private def validateRequestFramingHeaders(headers: Headers): Eru[HttpError, Unit] = {
    def reject(msg: String, rfc: String): Eru[HttpError, Unit] =
      Eru.fail(HttpError.InvalidRequest(InvalidRequest(msg, rfc)))

    val cl = headers.get(HeaderNames.ContentLength).getOrElse(Nil)
    val te = headers.get(HeaderNames.TransferEncoding).getOrElse(Nil)
    val host = headers.get(HeaderNames.Host).getOrElse(Nil)

    (cl, te, host) match {
      case (_ :: _, _ :: _, _) =>
        reject("Content-Length and Transfer-Encoding are mutually exclusive", "RFC 9112 Section 6.1")
      case (_ :: _ :: _, _, _) =>
        reject("Duplicate Content-Length header", "RFC 9112 Section 6.2")
      case (_, _ :: _ :: _, _) =>
        reject("Duplicate Transfer-Encoding header", "RFC 9112 Section 6.1")
      case (_, _, _ :: _ :: _) =>
        reject("Duplicate Host header", "RFC 9110 Section 7.2")
      case (only :: Nil, Nil, _) if only.value.contains(',') =>
        reject("Comma-separated Content-Length values are not accepted", "RFC 9112 Section 6.2")
      case _ =>
        Eru.unit
    }
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
  def parseResponseWithReader(
    reader: BufferedSocketReader,
    maxBodySize: Int = Int.MaxValue
  ): Eru[HttpError, Response[Body]] = {
    for {
      statusLine <- readLineBuffered(reader)
      (version, status, _) <- parseStatusLine(statusLine)
      headers <- readHeadersBuffered(reader)
      body <- readBodyBuffered(reader, headers, maxBodySize)
    } yield Response(status, headers, body, version)
  }

  /** Parse HTTP request line: "GET /path HTTP/1.1"
    *
    * RFC 9112 Section 3: request-line = method SP request-target SP HTTP-version CRLF
    */
  private def parseRequestLine(line: String): Eru[HttpError, (Method, Uri, HttpVersion, Boolean)] = {
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
      } yield (method, uri, version, uri.scheme.isDefined)
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
      // The reader's socket-level read deadline (readHeaderTimeout / idleTimeout) fired. Typed
      // distinctly so the request loop can answer 408 while the channel is still open.
      case e: java.net.SocketTimeoutException =>
        HttpError.TimeoutError(s"Request timeout: ${e.getMessage}")
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
            Eru.succeed(pairs.reverse)
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
                      loop((name, hv) :: pairs, bytesRead + line.length + 2)
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
  private def readBodyBuffered(
    reader: BufferedSocketReader,
    headers: Headers,
    maxBodySize: Int
  ): Eru[HttpError, Body] = {
    headers.getFirst(HeaderNames.TransferEncoding) match {
      case Some(te) if te.value.toLowerCase.contains("chunked") =>
        readChunkedBodyBuffered(reader, maxBodySize)

      case _ =>
        headers.getFirst(HeaderNames.ContentLength) match {
          case Some(cl) =>
            parseContentLength(cl.value).flatMap { length =>
              if length == 0 then {
                Eru.succeed(Body.Empty)
              } else if length > maxBodySize.toLong then {
                Eru.fail(HttpError.PayloadTooLarge(length, maxBodySize))
              } else {
                Eru.effect {
                  val bytes = reader.readBytes(length.toInt)
                  Body.Binary(Bytes.fromArray(bytes), None): Body
                }.mapError {
                  // The reader's socket-level deadline fired mid-body: typed so the request loop
                  // can answer 408 while the channel is still open.
                  case e: java.net.SocketTimeoutException =>
                    HttpError.TimeoutError(s"Request body timeout: ${e.getMessage}")
                  case e: Exception =>
                    HttpError.NetworkError(s"Error reading body: ${e.getMessage}", Some(e))
                }
              }
            }

          case None =>
            Eru.succeed(Body.Empty)
        }
    }
  }

  /** Parse Content-Length value, rejecting invalid or negative values. */
  private def parseContentLength(value: String): Eru[HttpError, Long] =
    Eru
      .effect(value.toLong)
      .mapError { case _: NumberFormatException =>
        HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: $value", "RFC 9110 Section 8.6"))
      }
      .flatMap { length =>
        if length < 0 then
          Eru.fail(
            HttpError.InvalidRequest(InvalidRequest(s"Negative Content-Length: $length", "RFC 9110 Section 8.6"))
          )
        else Eru.succeed(length)
      }

  /** Read chunked message body (Transfer-Encoding: chunked) - streaming version
    *
    * RFC 9112 Section 7.1: Chunked transfer coding Format: chunk-size CRLF chunk-data CRLF ... 0
    * CRLF CRLF
    *
    * Returns Body.Stream to avoid buffering the entire body in memory. Enforces maxBodySize across
    * the sum of chunk sizes — a malicious client cannot bypass the size limit by splitting a large
    * payload across many chunks. A cumulative byte counter is shared across chunk pulls, which may
    * span fibers.
    */
  private def readChunkedBodyBuffered(reader: BufferedSocketReader, maxBodySize: Int): Eru[HttpError, Body] = {
    val bytesRead = new java.util.concurrent.atomic.AtomicLong(0)
    val chunkStream = createChunkStreamFromReader(reader, maxBodySize, bytesRead)
    Eru.succeed(Body.Stream(chunks = Eru.succeed(chunkStream), contentLength = None))
  }

  /** Creates a ChunkStream that lazily reads chunks from a BufferedSocketReader.
    *
    * This implements pull-based streaming - chunks are only read when pulled from the stream. This
    * avoids buffering the entire request body in memory.
    *
    * On any chunk read error (including PayloadTooLarge) the stream terminates silently, because
    * Body.Stream cannot surface typed errors; the caller closes the connection when it observes the
    * truncation.
    */
  private def createChunkStreamFromReader(
    reader: BufferedSocketReader,
    maxBodySize: Int,
    bytesRead: java.util.concurrent.atomic.AtomicLong
  ): ChunkStream = {
    ChunkStream.eval {
      readNextChunkFromReader(reader, maxBodySize, bytesRead).attempt.map {
        case Result.Success(Some(chunk)) =>
          ChunkStream.single(chunk) ++ createChunkStreamFromReader(reader, maxBodySize, bytesRead)

        case Result.Success(None) =>
          ChunkStream.Empty

        // Framing failures (malformed chunk size, forbidden trailer, cumulative-size cap) end the
        // stream in ChunkStream.Fail so consumers observe them. A handler that never consumes the
        // body never sees the failure; the next parse attempt rejects the unconsumed bytes, so the
        // pipeline stays aligned either way.
        case Result.Failure(e) =>
          ChunkStream.fail(e)
      }
    }
  }

  /** Read the next chunk from the reader.
    *
    * Returns None when the final 0-sized chunk is encountered (end of stream). Throws HttpError on
    * parse errors or I/O errors. Rejects chunks that would push cumulative body size over
    * maxBodySize.
    *
    * On the final 0-sized chunk the full trailer section is consumed up to its terminating empty
    * CRLF; consuming only a single line would desync keep-alive connections whenever any trailer
    * field line is present.
    */
  private def readNextChunkFromReader(
    reader: BufferedSocketReader,
    maxBodySize: Int,
    bytesRead: java.util.concurrent.atomic.AtomicLong
  ): Eru[HttpError, Option[Chunk]] = {
    for {
      chunkSizeLine <- readLineBuffered(reader)
      chunkSize <- parseChunkSize(chunkSizeLine)
      result <-
        if chunkSize == 0 then consumeChunkedTrailers(reader).map(_ => None)
        else if bytesRead.get() + chunkSize.toLong > maxBodySize.toLong then
          Eru.fail(HttpError.PayloadTooLarge(bytesRead.get() + chunkSize.toLong, maxBodySize))
        else
          for {
            bytes <- Eru.effect(reader.readBytes(chunkSize)).mapError { case e: Exception =>
              HttpError.NetworkError(s"Error reading chunk: ${e.getMessage}", Some(e))
            }
            _ <- readLineBuffered(reader)
            _ = bytesRead.addAndGet(chunkSize.toLong): Unit
          } yield Some(Chunk.fromBytes(Bytes.fromArray(bytes)))
    } yield result
  }

  /** Consume the chunked trailer section per RFC 9112 §7.1.2.
    *
    * After the `0`-sized last-chunk line, the grammar is:
    * {{{
    *   trailer-section = *( field-line CRLF )
    *   CRLF   ; terminating blank line
    * }}}
    *
    * We read lines until an empty line is seen. Trailer field values are currently discarded
    * (eru-http does not yet surface trailers to handlers), but forbidden framing headers are
    * rejected outright per RFC 9112 §7.1.3 — a trailer-placed Content-Length / Transfer-Encoding /
    * Host is a classic smuggling vector if an upstream proxy disagrees about its effect.
    *
    * Bound: at most 64 non-empty trailer lines and at most the BufferedSocketReader's maxLineLength
    * per line. This keeps the trailer section O(1) even under a malicious trailer flood.
    */
  private def consumeChunkedTrailers(reader: BufferedSocketReader): Eru[HttpError, Unit] = {
    val MaxTrailerLines = 64

    def rejectFlood: Eru[HttpError, Unit] =
      Eru.fail(
        HttpError.InvalidRequest(
          InvalidRequest(s"Chunked trailer section exceeds $MaxTrailerLines fields", "RFC 9112 Section 7.1.2")
        )
      )

    def loop(remaining: Int): Eru[HttpError, Unit] =
      readLineBuffered(reader).flatMap {
        case "" => Eru.unit
        case _ if remaining <= 0 => rejectFlood
        case fieldLine => validateTrailerField(fieldLine).flatMap(_ => loop(remaining - 1))
      }

    loop(MaxTrailerLines)
  }

  /** Reject framing-critical fields in a chunked trailer (RFC 9112 §7.1.3). */
  private def validateTrailerField(line: String): Eru[HttpError, Unit] = {
    def fail(msg: String, rfc: String): Eru[HttpError, Unit] =
      Eru.fail(HttpError.InvalidRequest(InvalidRequest(msg, rfc)))

    line.indexOf(':') match {
      case idx if idx <= 0 =>
        fail(s"Malformed trailer line: $line", "RFC 9112 Section 7.1.2")
      case idx =>
        val name = line.substring(0, idx).trim.toLowerCase
        if ForbiddenTrailerFields.contains(name) then
          fail(s"Trailer field '$name' is forbidden in the trailer section", "RFC 9112 Section 7.1.3")
        else Eru.unit
    }
  }

  /** Header names that MUST NOT appear in a chunked trailer section. Lower-cased for the
    * case-insensitive lookup in `validateTrailerField`.
    */
  private val ForbiddenTrailerFields: Set[String] = Set(
    "content-length",
    "transfer-encoding",
    "host",
    "cache-control",
    "expect",
    "max-forwards",
    "pragma",
    "range",
    "te",
    "trailer",
    "www-authenticate",
    "authorization",
    "set-cookie",
    "cookie",
    "age",
    "expires",
    "date",
    "location",
    "retry-after",
    "vary",
    "warning",
    "content-encoding",
    "content-type",
    "content-range"
  )

  /** Parse chunk size from hex string (may include chunk extensions)
    */
  private def parseChunkSize(line: String): Eru[HttpError, Int] = {
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
