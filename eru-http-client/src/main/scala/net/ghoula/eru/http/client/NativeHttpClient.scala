package net.ghoula.eru.http.client

import java.nio.channels.SocketChannel
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext

import net.ghoula.eru.*
import net.ghoula.eru.http.*
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
      responseBytes = convertBodyToBytes(interceptedResponse)
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

  /** Use a pooled connection for a single request/response cycle.
    */
  private def useConnection(
    conn: PooledConnection,
    request: Request[Body]
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      // Get or create TLS channel if needed
      secureSocket <-
        if request.uri.scheme.contains("https") then {
          sslContext match {
            case Some(ctx) =>
              // Check if we already have an SSL channel for this connection (reuse!)
              pool.getSSLChannel(conn).flatMap {
                case Some(existingChannel) =>
                  // Reuse existing TLS session
                  Eru.succeed(existingChannel)
                case None =>
                  // First HTTPS request on this connection - create and store SSL channel
                  wrapWithTLS(conn.socket, request.uri.host.getOrElse(""), conn.port, ctx).flatMap { newChannel =>
                    pool.setSSLChannel(conn, newChannel).map(_ => newChannel)
                  }
              }
            case None => Eru.fail(HttpError.NetworkError("HTTPS requested but no SSL context configured", None))
          }
        } else {
          Eru.succeed(conn.socket)
        }

      // Add cookies from jar
      requestWithCookies <- config.cookieJar match {
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
        .writeRequestWithBuffer(secureSocket, requestWithContentLength, buffer)
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
              val newReader = new BufferedSocketReader(secureSocket)
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

      // Convert body to Bytes
      responseBytes = convertBodyToBytes(decompressedResponse)

    } yield responseBytes
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

  /** Convert response body to Bytes
    */
  private def convertBodyToBytes(response: Response[Body]): Response[Bytes] = {
    val bytes = response.body match {
      case Body.Empty => Bytes.empty
      case Body.Text(text, _, charset) => Bytes.fromArray(text.getBytes(charset.toJavaCharset))
      case Body.Binary(b, _) => b
      case Body.Stream(_, _, _) =>
        // TODO: Read stream to bytes
        Bytes.empty
    }
    response.copy(body = bytes)
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
