package net.ghoula.eru.http.client

import java.nio.channels.SocketChannel
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.h2.{H2ClientConnection, H2Error}
import net.ghoula.eru.prelude.*

/** Native HTTP client implementation using blocking NIO + Virtual Threads.
  *
  * This implementation demonstrates the power of Eru's Virtual Thread backend:
  *   - Each request runs on its own Virtual Thread
  *   - Blocking I/O is efficient (~10KB per thread vs ~2MB for OS threads)
  *   - Connection pooling with Eru Ref for structured concurrency
  *   - Simple, readable code with no event loops or callbacks
  *
  * Compare to NettyHttpClient: ~200 lines vs 402 lines (50% reduction)
  */
private[client] final class NativeHttpClient(
  config: HttpClientConfig,
  sslContext: Option[SSLContext],
  pool: ConnectionPool,
  requestInterceptors: List[RequestInterceptor] = List.empty,
  responseInterceptors: List[ResponseInterceptor] = List.empty
)(using runtime: EruRuntime)
    extends HttpClient {

  override def execute[A, B](
    request: Request[A]
  )(using encoder: BodyEncoder[A], decoder: BodyDecoder[B]): Eru[HttpError, Response[B]] =
    for {
      // Validate request
      _ <- request.validate.mapError(HttpError.InvalidRequest.apply)

      // Encode body
      encodedBody <- encoder.encode(request.body).mapError(HttpError.BodyEncodeError.apply)
      encodedRequest = request.copy(body = encodedBody)

      // Apply request interceptors
      interceptedRequest <- requestInterceptors.foldLeft(Eru.succeed(encodedRequest)) { (req, interceptor) =>
        req.flatMap(interceptor)
      }

      // Execute request
      response <- sendInternal(interceptedRequest, redirectCount = 0)

      // Apply response interceptors
      responseAsBody: Response[Body] = response.copy(body = Body.Binary(response.body))
      interceptedResponse <- responseInterceptors.foldLeft(
        Eru.succeed(responseAsBody): Eru[HttpError, Response[Body]]
      ) { (resp, interceptor) =>
        resp.flatMap(interceptor)
      }

      // Decode response body
      decoded <- decoder.decode(interceptedResponse.body).mapError(HttpError.BodyDecodeError.apply)

    } yield interceptedResponse.copy(body = decoded)

  override def send[A](request: Request[A])(using encoder: BodyEncoder[A]): Eru[HttpError, Response[Bytes]] =
    for {
      // Add Host header if missing (required for HTTP/1.1)
      requestWithHost <- addHostHeaderIfNeeded(request)

      // Add Connection: keep-alive for connection pooling (if not already set)
      requestWithConnection <- addConnectionHeaderIfNeeded(requestWithHost)

      // Add Accept-Encoding header if automatic decompression is enabled
      requestWithEncoding <- addAcceptEncodingIfNeeded(requestWithConnection)

      _ <- requestWithEncoding.validate.mapError(HttpError.InvalidRequest.apply)
      encodedBody <- encoder.encode(requestWithEncoding.body).mapError(HttpError.BodyEncodeError.apply)
      encodedRequest = requestWithEncoding.copy(body = encodedBody)

      // Apply request interceptors
      interceptedRequest <- requestInterceptors.foldLeft(Eru.succeed(encodedRequest)) { (req, interceptor) =>
        req.flatMap(interceptor)
      }

      response <- sendInternal(interceptedRequest, redirectCount = 0)

      // Apply response interceptors
      responseAsBody: Response[Body] = response.copy(body = Body.Binary(response.body))
      interceptedResponse <- responseInterceptors.foldLeft(
        Eru.succeed(responseAsBody): Eru[HttpError, Response[Body]]
      ) { (resp, interceptor) =>
        resp.flatMap(interceptor)
      }

      // Convert back to Response[Bytes]
      responseBytes <- convertBodyToBytes(interceptedResponse)
    } yield responseBytes

  /** Internal request execution with redirect handling
    */
  private def sendInternal(request: Request[Body], redirectCount: Int): Eru[HttpError, Response[Bytes]] =
    for {
      // Extract host and port
      host <- Eru.fromOption(
        request.uri.host,
        HttpError.InvalidRequest(InvalidRequest("Missing host in URI", "RFC 9110"))
      )
      port <- getPort(request.uri)

      // Execute request
      response <- executeRequest(host, port, request)

      // Handle cookies
      _ <- config.cookieJar match {
        case Some(jar) =>
          val setCookieHeaders = response.headers.get(HeaderNames.SetCookie).getOrElse(List.empty)
          Eru
            .foreach(setCookieHeaders) { headerValue =>
              Cookie
                .parseSetCookie(headerValue.value)
                .mapError(HttpError.InvalidCookie.apply)
                .flatMap(cookie => jar.add(request.uri, cookie))
            }
            .map(_ => ())
        case None =>
          Eru.succeed(())
      }

      // Handle redirects
      result <-
        if config.followRedirects && response.status.isRedirection && redirectCount < config.maxRedirects then {
          handleRedirect(request, response, redirectCount)
        } else {
          Eru.succeed(response)
        }
    } yield result

  /** Execute a single HTTP request using connection pooling.
    *
    * Connection pooling approach:
    *   1. Acquire connection from pool (may reuse existing)
    *   2. Use connection for request/response
    *   3. Release (for reuse) or remove (on error or close)
    */
  private def executeRequest(
    host: String,
    port: Int,
    request: Request[Body]
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      conn <- pool.acquire(host, port)
      result <- useConnection(conn, request).attempt
      _ <- handleConnectionResult(conn, result)
      response <- fromResult(result)
    } yield response
  }

  private def handleConnectionResult(
    conn: PooledConnection,
    result: Result[HttpError, Response[Bytes]]
  ): Eru[HttpError, Unit] = {
    result match {
      case Result.Success(response) =>
        if shouldReuseConnection(response) then pool.release(conn)
        else pool.remove(conn)

      case Result.Failure(_) =>
        pool.remove(conn)
    }
  }

  private def fromResult[E, A](result: Result[E, A]): Eru[E, A] = {
    result match {
      case Result.Success(a) => Eru.succeed(a)
      case Result.Failure(e) => Eru.fail(e)
    }
  }

  // Type alias for channels that support both reading and writing
  private type RWChannel = java.nio.channels.ReadableByteChannel & java.nio.channels.WritableByteChannel

  /** Use a pooled connection for a single request/response cycle.
    *
    * Routes to HTTP/2 or HTTP/1.1 based on ALPN negotiation result.
    */
  private def useConnection(
    conn: PooledConnection,
    request: Request[Body]
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      // Get or create TLS channel if needed, and detect HTTP/2
      channelAndProtocol <-
        if request.uri.scheme.contains("https") then {
          sslContext match {
            case Some(ctx) =>
              // Check if we already have an SSL channel for this connection (reuse!)
              pool.getSSLChannel(conn).flatMap {
                case Some(existingChannel) =>
                  // Reuse existing TLS session - check if it negotiated HTTP/2
                  Eru.succeed((existingChannel: RWChannel, existingChannel.isHttp2))
                case None =>
                  // First HTTPS request on this connection - create and store SSL channel
                  wrapWithTLS(conn.socket, request.uri.host.getOrElse(""), conn.port, ctx).flatMap { newChannel =>
                    pool.setSSLChannel(conn, newChannel).map(_ => (newChannel: RWChannel, newChannel.isHttp2))
                  }
              }
            case None => Eru.fail(HttpError.NetworkError("HTTPS requested but no SSL context configured", None))
          }
        } else {
          // Plain HTTP - always HTTP/1.1
          Eru.succeed((conn.socket: RWChannel, false))
        }

      (channel, isHttp2) = channelAndProtocol

      // Route to appropriate protocol handler based on ALPN result
      response <- channel match {
        case ssl: SSLSocketChannel if isHttp2 =>
          useH2Connection(conn, request, ssl)
        case _ =>
          useHttp1Connection(conn, request, channel)
      }
    } yield response
  }

  /** Use HTTP/2 for the request/response cycle.
    *
    * Handles connection preface exchange on first use, then sends request and receives response.
    */
  private def useH2Connection(
    conn: PooledConnection,
    request: Request[Body],
    sslChannel: SSLSocketChannel
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      // Get or create H2ClientConnection
      h2conn <- pool.getH2Connection(conn).flatMap {
        case Some(existingH2) =>
          Eru.succeed(existingH2)
        case None =>
          // First HTTP/2 request - create connection and exchange preface
          H2ClientConnection
            .connect(sslChannel)
            .mapError(h2ErrorToHttpError)
            .flatMap { newH2 =>
              pool.setH2Connection(conn, newH2).map(_ => newH2)
            }
      }

      // Add cookies from jar
      requestWithCookies <- addCookiesIfNeeded(request)

      // Build request parameters
      method = request.method.value
      host = request.uri.host.getOrElse("localhost")
      port = request.uri.port.map(_.value).getOrElse(if request.uri.scheme.contains("https") then 443 else 80)
      authority = if port == 443 || port == 80 then host else s"$host:$port"
      scheme = request.uri.scheme.getOrElse("https")
      path = {
        val p = request.uri.path
        val pathStr = if p.isEmpty then "/" else p
        request.uri.query.map(q => s"$pathStr?$q").getOrElse(pathStr)
      }

      // Convert headers for HTTP/2:
      // - Filter out Host (replaced by :authority pseudo-header)
      // - Filter out connection-specific headers (forbidden in HTTP/2 per RFC 9113 Section 8.2.2)
      headers = requestWithCookies.headers.toList.filter { case (name, _) =>
        val nameLower = name.toLowerCase
        nameLower != "host" &&
        nameLower != "connection" &&
        nameLower != "keep-alive" &&
        nameLower != "proxy-connection" &&
        nameLower != "transfer-encoding" &&
        nameLower != "upgrade"
      }.map { case (name, value) => (name.toLowerCase, value) }

      // Convert body to bytes
      bodyBytes <- requestWithCookies.body match {
        case Body.Empty => Eru.succeed(Option.empty[Array[Byte]])
        case Body.Text(text, _, charset) => Eru.succeed(Some(text.getBytes(charset.toJavaCharset)))
        case Body.Binary(bytes, _) => Eru.succeed(Some(bytes.toArray))
        case Body.Stream(_, _, _) =>
          // TODO: Support streaming bodies for HTTP/2
          Eru.fail(
            HttpError.InvalidRequest(InvalidRequest("Streaming bodies not yet supported for HTTP/2", "RFC 9113"))
          )
      }

      // Get expected stream ID BEFORE sending - needed for concurrent receive
      expectedStreamId <- h2conn.connection.nextStreamId

      // Send request - fork to prevent flow control deadlock
      // For large request bodies (>65KB), sendRequest() may block waiting for
      // WINDOW_UPDATE frames. By forking the send and immediately starting to
      // receive, we ensure WINDOW_UPDATE frames are processed (in receiveResponse's
      // frame reading loop), which unblocks the send.
      sendFiber <- runtime.fork(
        h2conn
          .sendRequest(method, path, authority, scheme, headers, bodyBytes)
          .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
          .mapError {
            case _: TimeoutException => HttpError.TimeoutError(s"H2 request timeout after ${config.requestTimeout}")
            case e: H2Error => h2ErrorToHttpError(e)
            case e: Throwable => HttpError.NetworkError(s"H2 request error: ${e.getMessage}", Some(e))
          }
      )

      // Receive response concurrently with send - this reads frames including
      // WINDOW_UPDATE which unblocks the forked send fiber if it's waiting on flow control
      (responseHeaders, responseBody) <- h2conn
        .receiveResponse(expectedStreamId)
        .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
        .mapError {
          case _: TimeoutException => HttpError.TimeoutError(s"H2 response timeout after ${config.requestTimeout}")
          case e: H2Error => h2ErrorToHttpError(e)
          case e: Throwable => HttpError.NetworkError(s"H2 response error: ${e.getMessage}", Some(e))
        }

      // Wait for send to complete (should have already completed by now, but ensure no errors)
      sendExit <- sendFiber.await
      actualStreamId <- sendExit match {
        case Exit.Success(id) => Eru.succeed(id)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(throwable) =>
          Eru.fail(HttpError.NetworkError(s"Send fiber died: ${throwable.getMessage}", Some(throwable)))
        case Exit.Interrupt(_, _) => Eru.fail(HttpError.NetworkError("Send fiber was interrupted", None))
      }
      _ <-
        if actualStreamId != expectedStreamId then
          Eru.fail(
            HttpError.ProtocolError(s"Stream ID mismatch: expected $expectedStreamId, got $actualStreamId", "RFC 9113")
          )
        else Eru.unit

      // Parse :status pseudo-header
      statusValue <- Eru.fromOption(
        responseHeaders.find(_._1 == ":status").map(_._2),
        HttpError.ProtocolError("Missing :status pseudo-header in HTTP/2 response", "RFC 9113 Section 8.3.2")
      )
      statusInt <- Eru.fromOption(
        statusValue.toIntOption,
        HttpError.ProtocolError(s"Invalid :status value: $statusValue", "RFC 9113 Section 8.3.2")
      )
      statusCode <- StatusCode(statusInt).mapError(e =>
        HttpError.ProtocolError(s"Invalid status code $statusInt: ${e.reason}", "RFC 9113 Section 8.3.2")
      )

      // Convert headers (filter out pseudo-headers)
      httpHeaders <- responseHeaders.filter { case (name, _) => !name.startsWith(":") }
        .foldLeft(Eru.succeed(Headers.empty): Eru[HttpError, Headers]) { case (acc, (name, value)) =>
          acc.flatMap(_.add(name, value).mapError(e => HttpError.ProtocolError(s"Invalid header: $e", "RFC 9113")))
        }

      // Build response
      bodyBytes = responseBody.getOrElse(Array.empty[Byte])
      response = Response(
        status = statusCode,
        headers = httpHeaders,
        body = Body.Binary(Bytes.fromArray(bodyBytes), None),
        version = HttpVersion.HTTP_2_0
      )

      // Automatically decompress response if enabled
      decompressedResponse <-
        if config.automaticDecompression then decompressResponse(response)
        else Eru.succeed(response)

      // Convert body to Bytes
      responseBytes <- convertBodyToBytes(decompressedResponse)

    } yield responseBytes
  }

  /** Use HTTP/1.1 for the request/response cycle. */
  private def useHttp1Connection(
    conn: PooledConnection,
    request: Request[Body],
    channel: RWChannel
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      // Add cookies from jar
      requestWithCookies <- addCookiesIfNeeded(request)

      // Add Content-Length header if not present and body is not empty
      requestWithContentLength <-
        if !requestWithCookies.headers.contains(HeaderNames.ContentLength) then {
          requestWithCookies.body match {
            case Body.Empty => Eru.succeed(requestWithCookies)
            case Body.Text(text, _, charset) =>
              val contentLength = text.getBytes(charset.toJavaCharset).length
              requestWithCookies.headers
                .add(HeaderNames.ContentLength, contentLength.toString)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: $e", "RFC 9110")))
                .map(newHeaders => requestWithCookies.copy(headers = newHeaders))
            case Body.Binary(bytes, _) =>
              requestWithCookies.headers
                .add(HeaderNames.ContentLength, bytes.length.toString)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: $e", "RFC 9110")))
                .map(newHeaders => requestWithCookies.copy(headers = newHeaders))
            case Body.Stream(_, _, _) =>
              // Don't set Content-Length for streams (would need Transfer-Encoding: chunked)
              Eru.succeed(requestWithCookies)
          }
        } else {
          Eru.succeed(requestWithCookies)
        }

      // Determine if this is an HTTPS request
      isHttps = request.uri.scheme.contains("https")

      // Get buffer for this connection (managed separately for type safety)
      buffer <- pool.getBuffer(conn)

      // Write request with timeout using connection's buffer
      _ <- HttpWriter
        .writeRequestWithBuffer(channel, requestWithContentLength, buffer)
        .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
        .mapError {
          case _: TimeoutException => HttpError.TimeoutError(s"Write timeout after ${config.requestTimeout}")
          case e: HttpError => e
          case e: Throwable => HttpError.NetworkError(s"Write error: ${e.getMessage}", Some(e))
        }

      // Get reader: for HTTPS use pooled SSL reader, for HTTP use pooled reader
      reader <-
        if isHttps then {
          // For HTTPS, use pooled SSL reader (wraps SSLSocketChannel for decrypted data)
          pool.getSSLReader(conn).flatMap {
            case Some(existingReader) =>
              Eru.succeed(existingReader)
            case None =>
              // First HTTPS request - create and store SSL reader
              val newReader = new BufferedSocketReader(channel)
              pool.setSSLReader(conn, newReader).map(_ => newReader)
          }
        } else {
          // For HTTP, use pooled reader (zero-allocation benefit)
          pool.getReader(conn)
        }

      // Read response with timeout using pooled reader
      response <- HttpParser
        .parseResponseWithReader(reader)
        .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
        .mapError {
          case _: TimeoutException => HttpError.TimeoutError(s"Read timeout after ${config.requestTimeout}")
          case e: HttpError => e
          case e: Throwable => HttpError.NetworkError(s"Read error: ${e.getMessage}", Some(e))
        }

      // Automatically decompress response if enabled
      decompressedResponse <-
        if config.automaticDecompression then decompressResponse(response)
        else Eru.succeed(response)

      // Convert body to Bytes (handles streaming/chunked bodies)
      responseBytes <- convertBodyToBytes(decompressedResponse)

    } yield responseBytes
  }

  /** Add cookies from cookie jar if configured. */
  private def addCookiesIfNeeded(request: Request[Body]): Eru[HttpError, Request[Body]] = {
    config.cookieJar match {
      case Some(jar) =>
        jar.getCookies(request.uri).flatMap { cookies =>
          if cookies.nonEmpty then {
            val cookieHeader = cookies.map(_.toCookieHeader).mkString("; ")
            request.headers
              .add(HeaderNames.Cookie, cookieHeader)
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid cookie: $e", "RFC 6265")))
              .map(newHeaders => request.copy(headers = newHeaders))
          } else {
            Eru.succeed(request)
          }
        }
      case None => Eru.succeed(request)
    }
  }

  /** Convert H2Error to HttpError. */
  private def h2ErrorToHttpError(e: H2Error): HttpError = e match {
    case H2Error.ConnectionError(code, msg) =>
      HttpError.ProtocolError(s"HTTP/2 connection error ($code): ${msg.getOrElse("")}", "RFC 9113")
    case H2Error.StreamError(streamId, code, msg) =>
      HttpError.ProtocolError(s"HTTP/2 stream $streamId error ($code): ${msg.getOrElse("")}", "RFC 9113")
    case H2Error.InvalidFrame(msg, _) =>
      HttpError.ProtocolError(s"HTTP/2 invalid frame: $msg", "RFC 9113")
    case H2Error.InvalidPreface(msg) =>
      HttpError.ProtocolError(s"HTTP/2 invalid preface: $msg", "RFC 9113 Section 3.4")
    case H2Error.FlowControlViolation(_, msg) =>
      HttpError.ProtocolError(s"HTTP/2 flow control error: $msg", "RFC 9113 Section 5.2")
    case H2Error.StreamStateViolation(streamId, msg) =>
      HttpError.ProtocolError(s"HTTP/2 stream $streamId state error: $msg", "RFC 9113 Section 5.1")
    case H2Error.SettingsError(msg) =>
      HttpError.ProtocolError(s"HTTP/2 settings error: $msg", "RFC 9113 Section 6.5")
    case H2Error.CompressionError(msg) =>
      HttpError.ProtocolError(s"HTTP/2 compression error: $msg", "RFC 7541")
    case H2Error.ProtocolViolation(msg, code) =>
      HttpError.ProtocolError(s"HTTP/2 protocol violation ($code): $msg", "RFC 9113")
    case H2Error.NetworkError(msg, cause) =>
      HttpError.NetworkError(s"HTTP/2 network error: $msg", cause)
  }

  /** Automatically decompress response body based on Content-Encoding header.
    *
    * If Content-Encoding header is present and matches a supported encoding (gzip, deflate, br),
    * decompress the body and remove the Content-Encoding header.
    */
  private def decompressResponse(response: Response[Body]): Eru[HttpError, Response[Body]] = {
    response.headers.getFirst(HeaderNames.ContentEncoding) match {
      case None =>
        // No Content-Encoding header, return as-is
        Eru.succeed(response)

      case Some(encodingHeader) =>
        val encodingStr = encodingHeader.value.toLowerCase.trim
        val encoding = encodingStr match {
          case "gzip" => Some(ContentEncoding.Gzip)
          case "deflate" => Some(ContentEncoding.Deflate)
          case "br" => Some(ContentEncoding.Brotli)
          case "identity" => None // identity means no encoding
          case _ => None // unsupported encoding
        }

        encoding match {
          case Some(enc) =>
            // Decompress the body based on its type
            response.body match {
              case Body.Empty => Eru.succeed(response)

              case Body.Text(text, mediaType, charset) =>
                val bytes = Bytes.fromString(text, charset)
                Compression
                  .decompress(bytes, enc)
                  .flatMap { decompressed =>
                    val decompressedText = decompressed.asString(charset)
                    val headersWithoutEncoding = response.headers.remove(HeaderNames.ContentEncoding)
                    Eru.succeed(
                      response.copy(
                        headers = headersWithoutEncoding,
                        body = Body.Text(decompressedText, mediaType, charset)
                      )
                    )
                  }
                  .mapError(e => HttpError.NetworkError(s"Decompression failed: ${e.message}", None))

              case Body.Binary(bytes, mediaType) =>
                Compression
                  .decompress(bytes, enc)
                  .flatMap { decompressed =>
                    val headersWithoutEncoding = response.headers.remove(HeaderNames.ContentEncoding)
                    // Update Content-Length if present
                    val updatedHeaders = response.headers.getFirst(HeaderNames.ContentLength) match {
                      case Some(_) =>
                        headersWithoutEncoding
                          .add(HeaderNames.ContentLength, decompressed.length.toString)
                          .attempt
                          .unsafeRunSync() match {
                          case Result.Success(h) => h
                          case Result.Failure(_) => headersWithoutEncoding
                        }
                      case None => headersWithoutEncoding
                    }
                    Eru.succeed(
                      response.copy(
                        headers = updatedHeaders,
                        body = Body.Binary(decompressed, mediaType)
                      )
                    )
                  }
                  .mapError(e => HttpError.NetworkError(s"Decompression failed: ${e.message}", None))

              case Body.Stream(chunks, _, mediaType) =>
                // Decompress streaming body chunk-by-chunk
                chunks.flatMap { stream =>
                  Compression
                    .decompressStream(stream, enc)
                    .flatMap { decompressedStream =>
                      val headersWithoutEncoding = response.headers.remove(HeaderNames.ContentEncoding)
                      // Remove Content-Length since it's now unknown
                      val updatedHeaders = headersWithoutEncoding.remove(HeaderNames.ContentLength)
                      Eru.succeed(
                        response.copy(
                          headers = updatedHeaders,
                          body = Body.Stream(Eru.succeed(decompressedStream), None, mediaType)
                        )
                      )
                    }
                    .mapError(e => HttpError.NetworkError(s"Decompression failed: ${e.message}", None))
                }.mapError(e => HttpError.NetworkError(e.toString, None))
            }

          case None =>
            // identity encoding or unsupported, return as-is
            Eru.succeed(response)
        }
    }
  }

  /** Determine if a connection should be reused based on HTTP/1.1 keep-alive semantics.
    *
    * Connection is reused if:
    *   - Response doesn't have Connection: close
    *   - HTTP/1.1 (keep-alive is default) OR has Connection: keep-alive
    */
  private def shouldReuseConnection(response: Response[Bytes]): Boolean = {
    val connHeader = response.headers
      .getFirst(HeaderNames.Connection)
      .map(_.value.toLowerCase)

    val hasClose = connHeader.contains("close")
    val isHttp11 = response.version == HttpVersion.HTTP_1_1
    val hasKeepAlive = connHeader.contains("keep-alive")

    !hasClose && (isHttp11 || hasKeepAlive)
  }

  /** Wrap socket with TLS/SSL
    *
    * Creates an SSLSocketChannel that encrypts/decrypts data transparently. The handshake is
    * performed synchronously (fine on Virtual Threads).
    */
  private def wrapWithTLS(
    socket: SocketChannel,
    host: String,
    port: Int,
    ctx: SSLContext
  ): Eru[HttpError, SSLSocketChannel] =
    Eru.effect {
      val sslChannel = SSLSocketChannel.client(
        socket,
        ctx,
        host,
        port,
        verifyHostname = config.tlsConfig.verifyHostname
      )
      sslChannel.doHandshake()
      sslChannel
    }.mapError(e => HttpError.NetworkError(s"TLS handshake failed: ${e.getMessage}", Some(e)))

  /** Convert response body to Bytes.
    *
    * Handles all body types including streams (chunked transfer encoding). For streams, this
    * eagerly reads all chunks into memory - use with caution for very large responses.
    */
  private def convertBodyToBytes(response: Response[Body]): Eru[HttpError, Response[Bytes]] = {
    response.body match {
      case Body.Empty =>
        Eru.succeed(response.copy(body = Bytes.empty))
      case Body.Text(text, _, charset) =>
        Eru.succeed(response.copy(body = Bytes.fromArray(text.getBytes(charset.toJavaCharset))))
      case Body.Binary(b, _) =>
        Eru.succeed(response.copy(body = b))
      case Body.Stream(chunks, _, _) =>
        // Read stream chunks into memory
        chunks
          .flatMap(_.toBytes)
          .map(bytes => response.copy(body = bytes))
          .mapError(e => HttpError.NetworkError(s"Error reading stream body: $e", None))
    }
  }

  /** Add Host header to request if not already present (required for HTTP/1.1)
    */
  private def addHostHeaderIfNeeded[A](request: Request[A]): Eru[HttpError, Request[A]] = {
    if request.headers.contains(HeaderNames.Host) then {
      Eru.succeed(request)
    } else {
      for {
        host <- Eru.fromOption(
          request.uri.host,
          HttpError.InvalidRequest(InvalidRequest("Missing host in URI", "RFC 9110"))
        )
        port <- getPort(request.uri)
        hostValue = if port == 80 || port == 443 then host else s"$host:$port"
        newHeaders <- request.headers
          .add(HeaderNames.Host, hostValue)
          .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Host header: $e", "RFC 9110")))
      } yield request.copy(headers = newHeaders)
    }
  }

  /** Add Connection: keep-alive header if not already present (for connection pooling)
    */
  private def addConnectionHeaderIfNeeded[A](request: Request[A]): Eru[HttpError, Request[A]] = {
    if request.headers.contains(HeaderNames.Connection) then {
      Eru.succeed(request)
    } else {
      request.headers
        .add(HeaderNames.Connection, "keep-alive")
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Connection header: $e", "RFC 9110")))
        .map(newHeaders => request.copy(headers = newHeaders))
    }
  }

  /** Add Accept-Encoding header if automatic decompression is enabled and header not already
    * present
    */
  private def addAcceptEncodingIfNeeded[A](request: Request[A]): Eru[HttpError, Request[A]] = {
    if !config.automaticDecompression || request.headers.contains(HeaderNames.AcceptEncoding) then {
      Eru.succeed(request)
    } else {
      val encodings = config.acceptEncoding.map(_.value).mkString(", ")
      if encodings.nonEmpty then {
        request.headers
          .add(HeaderNames.AcceptEncoding, encodings)
          .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Accept-Encoding header: $e", "RFC 9110")))
          .map(newHeaders => request.copy(headers = newHeaders))
      } else {
        Eru.succeed(request)
      }
    }
  }

  /** Get port from URI
    */
  private def getPort(uri: Uri): Eru[HttpError, Int] =
    Eru.succeed {
      uri.port.map(_.value).getOrElse {
        uri.scheme match {
          case Some("https") => 443
          case Some("http") => 80
          case _ => 80
        }
      }
    }

  /** Handle HTTP redirect
    */
  private def handleRedirect(
    originalRequest: Request[Body],
    response: Response[Bytes],
    redirectCount: Int
  ): Eru[HttpError, Response[Bytes]] =
    for {
      locationHeader <- Eru.fromOption(
        response.headers.getFirst(HeaderNames.Location).map(_.value),
        HttpError.ProtocolError("Redirect response missing Location header", "RFC 9110 Section 15.4")
      )
      newUri <- Uri.parse(locationHeader).mapError(HttpError.InvalidUri.apply)
      newRequest = originalRequest.copy(uri = newUri)
      result <- sendInternal(newRequest, redirectCount + 1)
    } yield result

  def shutdown: Eru[Nothing, Unit] =
    pool.shutdown.attempt.map(_ => ())

  def withRequestInterceptor(interceptor: RequestInterceptor): HttpClient =
    new NativeHttpClient(
      config,
      sslContext,
      pool,
      requestInterceptors :+ interceptor,
      responseInterceptors
    )

  def withResponseInterceptor(interceptor: ResponseInterceptor): HttpClient =
    new NativeHttpClient(
      config,
      sslContext,
      pool,
      requestInterceptors,
      responseInterceptors :+ interceptor
    )
}

private[client] object NativeHttpClient {

  /** Create a native HTTP client.
    *
    * This is dramatically simpler than NettyHttpClient.create:
    *   - No EventLoopGroup to manage
    *   - No Bootstrap configuration
    *   - No ChannelInitializer setup
    *   - Just pure Eru effects + blocking NIO + connection pooling
    */
  def create(config: HttpClientConfig)(using runtime: EruRuntime): Eru[HttpError, NativeHttpClient] =
    for {
      sslContext <-
        if config.tlsConfig.enabled then {
          createSSLContext(config.tlsConfig).map(Some(_))
        } else {
          Eru.succeed(None)
        }
      pool <- ConnectionPool.create(config)
    } yield new NativeHttpClient(config, sslContext, pool)

  /** Create SSL context from TLS configuration
    *
    * Configures trust managers and protocol settings based on TlsConfig.
    */
  private def createSSLContext(tlsConfig: TlsConfig): Eru[HttpError, SSLContext] =
    Eru.effect {
      SSLContextFactory.createClientContext(tlsConfig)
    }.mapError(e => HttpError.NetworkError(s"Failed to create SSL context: ${e.getMessage}", Some(e)))
}
