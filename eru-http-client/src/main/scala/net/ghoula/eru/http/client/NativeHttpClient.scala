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
  *   - Each request runs on its own Virtual Thread
  *   - Blocking I/O parks the virtual thread cheaply; thread stacks grow on demand instead of
  *     reserving a fixed large stack per request (JEP 444)
  *   - Connection pooling with Eru Ref for structured concurrency
  *   - Simple blocking code with no event loops or callbacks
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
      requestWithHeaders <- addStandardHeaders(request)

      _ <- requestWithHeaders.validate.mapError(HttpError.InvalidRequest.apply)

      encodedBody <- encoder.encode(requestWithHeaders.body).mapError(HttpError.BodyEncodeError.apply)
      encodedRequest = requestWithHeaders.copy(body = encodedBody)

      interceptedRequest <- requestInterceptors.foldLeft(Eru.succeed(encodedRequest)) { (req, interceptor) =>
        req.flatMap(interceptor)
      }

      response <- sendInternal(interceptedRequest, redirectCount = 0)

      responseAsBody: Response[Body] = response.copy(body = Body.Binary(response.body))
      interceptedResponse <- responseInterceptors.foldLeft(
        Eru.succeed(responseAsBody): Eru[HttpError, Response[Body]]
      ) { (resp, interceptor) =>
        resp.flatMap(interceptor)
      }

      decoded <- decoder.decode(interceptedResponse.body).mapError(HttpError.BodyDecodeError.apply)

    } yield interceptedResponse.copy(body = decoded)

  override def queryFormats(uri: Uri): Eru[HttpError, Option[AcceptQuery]] =
    for {
      requestWithHeaders <- addStandardHeaders(Request(Method.OPTIONS, uri, Headers.empty, Body.Empty))
      _ <- requestWithHeaders.validate.mapError(HttpError.InvalidRequest.apply)
      response <- sendInternal(requestWithHeaders, redirectCount = 0)
      result <- response.headers.getFirst(HeaderNames.AcceptQuery) match {
        case None => Eru.succeed(None)
        case Some(value) =>
          AcceptQuery
            .parse(value.value)
            .map(Some(_))
            .mapError(e => HttpError.ProtocolError(e.message, "RFC 10008 Section 3"))
      }
    } yield result

  override def send[A](request: Request[A])(using encoder: BodyEncoder[A]): Eru[HttpError, Response[Bytes]] =
    for {
      requestWithHeaders <- addStandardHeaders(request)

      _ <- requestWithHeaders.validate.mapError(HttpError.InvalidRequest.apply)
      encodedBody <- encoder.encode(requestWithHeaders.body).mapError(HttpError.BodyEncodeError.apply)
      encodedRequest = requestWithHeaders.copy(body = encodedBody)

      interceptedRequest <- requestInterceptors.foldLeft(Eru.succeed(encodedRequest)) { (req, interceptor) =>
        req.flatMap(interceptor)
      }

      response <- sendInternal(interceptedRequest, redirectCount = 0)

      responseAsBody: Response[Body] = response.copy(body = Body.Binary(response.body))
      interceptedResponse <- responseInterceptors.foldLeft(
        Eru.succeed(responseAsBody): Eru[HttpError, Response[Body]]
      ) { (resp, interceptor) =>
        resp.flatMap(interceptor)
      }

      responseBytes <- convertBodyToBytes(interceptedResponse)
    } yield responseBytes

  /** Internal request execution with redirect handling. */
  private def sendInternal(request: Request[Body], redirectCount: Int): Eru[HttpError, Response[Bytes]] =
    for {
      host <- Eru.fromOption(
        request.uri.host,
        HttpError.InvalidRequest(InvalidRequest("Missing host in URI", "RFC 9110"))
      )
      port <- getPort(request.uri)

      requestWithDefaults <-
        config.userAgent match {
          case Some(agent) if !request.headers.contains(HeaderNames.UserAgent) =>
            request.addHeader(HeaderNames.UserAgent, agent).mapError {
              case e: HeaderName.InvalidHeaderName =>
                HttpError.InvalidRequest(InvalidRequest(s"Invalid header name: ${e.message}", "RFC 9110"))
              case e: HeaderValue.InvalidHeaderValue =>
                HttpError.InvalidRequest(InvalidRequest(s"Invalid header value: ${e.message}", "RFC 9110"))
            }
          case _ => Eru.succeed(request)
        }

      response <- executeRequest(host, port, requestWithDefaults)

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

  /** Type alias for channels that support both reading and writing. */
  private type RWChannel = java.nio.channels.ReadableByteChannel & java.nio.channels.WritableByteChannel

  /** Use a pooled connection for a single request/response cycle.
    *
    * Routes to HTTP/2 or HTTP/1.1 based on ALPN negotiation result. The TLS channel and its ALPN
    * result are cached per connection: the first HTTPS request creates and stores the
    * `SSLSocketChannel`, later requests reuse it. Plain HTTP is always HTTP/1.1.
    */
  private def useConnection(
    conn: PooledConnection,
    request: Request[Body]
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      channelAndProtocol <-
        if request.uri.scheme.contains("https") then {
          sslContext match {
            case Some(ctx) =>
              pool.getSSLChannel(conn).flatMap {
                case Some(existingChannel) =>
                  Eru.succeed((existingChannel: RWChannel, existingChannel.isHttp2))
                case None =>
                  wrapWithTLS(conn.socket, request.uri.host.getOrElse(""), conn.port, ctx).flatMap { newChannel =>
                    pool.setSSLChannel(conn, newChannel).map(_ => (newChannel: RWChannel, newChannel.isHttp2))
                  }
              }
            case None => Eru.fail(HttpError.NetworkError("HTTPS requested but no SSL context configured", None))
          }
        } else {
          Eru.succeed((conn.socket: RWChannel, false))
        }

      (channel, isHttp2) = channelAndProtocol

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
    * Request headers are converted for HTTP/2: Host is replaced by the `:authority` pseudo-header
    * and connection-specific headers are filtered out (forbidden in HTTP/2 per RFC 9113 Section
    * 8.2.2). The body is converted to bytes via the zero-copy `unsafeArray`; the H2 client only
    * reads these bytes to serialize DATA frames. Streaming bodies over HTTP/2 are not implemented
    * and are rejected.
    *
    * The send is forked and the response is received concurrently: for large request bodies (over
    * ~65KB) `sendRequest` may block waiting for WINDOW_UPDATE frames; receiving concurrently
    * ensures WINDOW_UPDATE frames are processed (in the receive loop), which unblocks the send —
    * preventing a flow-control deadlock. The expected stream ID is captured before sending, and the
    * send fiber's reported stream ID is checked against it.
    */
  private def useH2Connection(
    conn: PooledConnection,
    request: Request[Body],
    sslChannel: SSLSocketChannel
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      h2conn <- pool.getH2Connection(conn).flatMap {
        case Some(existingH2) =>
          Eru.succeed(existingH2)
        case None =>
          H2ClientConnection
            .connect(sslChannel)
            .mapError(h2ErrorToHttpError)
            .flatMap { newH2 =>
              pool.setH2Connection(conn, newH2).map(_ => newH2)
            }
      }

      requestWithCookies <- addCookiesIfNeeded(request)

      requestWithType <- addContentTypeIfNeeded(requestWithCookies)

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

      headers = requestWithType.headers.toList.filter { case (name, _) =>
        val nameLower = name.toLowerCase
        nameLower != "host" &&
        nameLower != "connection" &&
        nameLower != "keep-alive" &&
        nameLower != "proxy-connection" &&
        nameLower != "transfer-encoding" &&
        nameLower != "upgrade"
      }.map { case (name, value) => (name.toLowerCase, value) }

      bodyBytes <- requestWithType.body match {
        case Body.Empty => Eru.succeed(Option.empty[Array[Byte]])
        case t: Body.Text => Eru.succeed(Some(t.bytes.unsafeArray))
        case Body.Binary(bytes, _) => Eru.succeed(Some(bytes.unsafeArray))
        case Body.Stream(_, _, _) =>
          Eru.fail(
            HttpError.InvalidRequest(InvalidRequest("Streaming bodies not yet supported for HTTP/2", "RFC 9113"))
          )
      }

      expectedStreamId <- h2conn.connection.nextStreamId

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

      (responseHeaders, responseBody) <- h2conn
        .receiveResponse(expectedStreamId)
        .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
        .mapError {
          case _: TimeoutException => HttpError.TimeoutError(s"H2 response timeout after ${config.requestTimeout}")
          case e: H2Error => h2ErrorToHttpError(e)
          case e: Throwable => HttpError.NetworkError(s"H2 response error: ${e.getMessage}", Some(e))
        }

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

      httpHeaders <- responseHeaders.filter { case (name, _) => !name.startsWith(":") }
        .foldLeft(Eru.succeed(List.empty[(String, HeaderValue)]): Eru[HttpError, List[(String, HeaderValue)]]) {
          case (accEru, (name, value)) =>
            accEru.flatMap { acc =>
              HeaderName
                .parse(name)
                .flatMap(_ => HeaderValue.parse(value))
                .mapError(e => HttpError.ProtocolError(s"Invalid header: $e", "RFC 9113"))
                .map(hv => acc :+ (name, hv))
            }
        }
        .map(Headers.fromValidatedPairs)

      bodyBytes = responseBody.getOrElse(Array.empty[Byte])
      response = Response(
        status = statusCode,
        headers = httpHeaders,
        body = Body.Binary(Bytes.fromArray(bodyBytes), None),
        version = HttpVersion.HTTP_2_0
      )

      decompressedResponse <-
        if config.automaticDecompression then decompressResponse(response)
        else Eru.succeed(response)

      responseBytes <- convertBodyToBytes(decompressedResponse)

    } yield responseBytes
  }

  /** Use HTTP/1.1 for the request/response cycle.
    *
    * Streamed bodies without a known length are sent with chunked transfer coding: the wire writer
    * emits chunked framing, so the request must declare `Transfer-Encoding: chunked` (RFC 9112
    * Section 6.3) — without it a compliant server treats the request as bodyless. Reads and writes
    * go through pooled per-connection buffers and readers.
    */
  private def useHttp1Connection(
    conn: PooledConnection,
    request: Request[Body],
    channel: RWChannel
  ): Eru[HttpError, Response[Bytes]] = {
    for {
      requestWithCookies <- addCookiesIfNeeded(request)

      requestWithType <- addContentTypeIfNeeded(requestWithCookies)

      requestWithContentLength <-
        if !requestWithType.headers.contains(HeaderNames.ContentLength) then {
          requestWithType.body match {
            case Body.Empty => Eru.succeed(requestWithType)
            case t: Body.Text =>
              requestWithType.headers
                .add(HeaderNames.ContentLength, t.bytes.length.toString)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: $e", "RFC 9110")))
                .map(newHeaders => requestWithType.copy(headers = newHeaders))
            case Body.Binary(bytes, _) =>
              requestWithType.headers
                .add(HeaderNames.ContentLength, bytes.length.toString)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: $e", "RFC 9110")))
                .map(newHeaders => requestWithType.copy(headers = newHeaders))
            case Body.Stream(_, contentLength, _) =>
              contentLength match {
                case Some(length) =>
                  requestWithType.headers
                    .add(HeaderNames.ContentLength, length.toString)
                    .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Length: $e", "RFC 9110")))
                    .map(newHeaders => requestWithType.copy(headers = newHeaders))
                case None =>
                  Eru.succeed(requestWithType)
              }
          }
        } else {
          Eru.succeed(requestWithType)
        }

      requestWithFraming <-
        requestWithContentLength.body match {
          case Body.Stream(_, None, _) if !requestWithContentLength.headers.contains(HeaderNames.TransferEncoding) =>
            requestWithContentLength.headers
              .add(HeaderNames.TransferEncoding, "chunked")
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Transfer-Encoding: $e", "RFC 9110")))
              .map(newHeaders => requestWithContentLength.copy(headers = newHeaders))
          case _ => Eru.succeed(requestWithContentLength)
        }

      isHttps = request.uri.scheme.contains("https")

      buffer <- pool.getBuffer(conn)

      _ <- HttpWriter
        .writeRequestWithBuffer(channel, requestWithFraming, buffer)
        .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
        .mapError {
          case _: TimeoutException => HttpError.TimeoutError(s"Write timeout after ${config.requestTimeout}")
          case e: HttpError => e
          case e: Throwable => HttpError.NetworkError(s"Write error: ${e.getMessage}", Some(e))
        }

      reader <-
        if isHttps then {
          pool.getSSLReader(conn).flatMap {
            case Some(existingReader) =>
              Eru.succeed(existingReader)
            case None =>
              val newReader = new BufferedSocketReader(channel)
              pool.setSSLReader(conn, newReader).map(_ => newReader)
          }
        } else {
          pool.getReader(conn)
        }

      response <- HttpParser
        .parseResponseWithReader(reader)
        .timeout(java.time.Duration.ofMillis(config.requestTimeout.toMillis))
        .mapError {
          case _: TimeoutException => HttpError.TimeoutError(s"Read timeout after ${config.requestTimeout}")
          case e: HttpError => e
          case e: Throwable => HttpError.NetworkError(s"Read error: ${e.getMessage}", Some(e))
        }

      decompressedResponse <-
        if config.automaticDecompression then decompressResponse(response)
        else Eru.succeed(response)

      responseBytes <- convertBodyToBytes(decompressedResponse)

    } yield responseBytes
  }

  /** Add Content-Type from the body's declared media type if the header is not already set.
    *
    * Body variants carry an optional MediaType; without this header servers cannot know how to
    * parse the payload (RFC 9110 Section 8.3). An explicitly set Content-Type header always wins.
    */
  private def addContentTypeIfNeeded(request: Request[Body]): Eru[HttpError, Request[Body]] = {
    request.body.mediaType match {
      case Some(mediaType) if !request.headers.contains(HeaderNames.ContentType) =>
        request.headers
          .add(HeaderNames.ContentType, mediaType.value)
          .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid Content-Type: $e", "RFC 9110")))
          .map(newHeaders => request.copy(headers = newHeaders))
      case _ => Eru.succeed(request)
    }
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
    * decompress the body and remove the Content-Encoding header. For binary bodies the
    * Content-Length header is updated to the decompressed length; for streams it is removed because
    * the length becomes unknown. `identity` and unsupported encodings return the body unchanged.
    */
  private def decompressResponse(response: Response[Body]): Eru[HttpError, Response[Body]] = {
    response.headers.getFirst(HeaderNames.ContentEncoding) match {
      case None =>
        Eru.succeed(response)

      case Some(encodingHeader) =>
        val encodingStr = encodingHeader.value.toLowerCase.trim
        val encoding = encodingStr match {
          case "gzip" => Some(ContentEncoding.Gzip)
          case "deflate" => Some(ContentEncoding.Deflate)
          case "br" => Some(ContentEncoding.Brotli)
          case "identity" => None
          case _ => None
        }

        encoding match {
          case Some(enc) =>
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
                chunks.flatMap { stream =>
                  Compression
                    .decompressStream(stream, enc)
                    .flatMap { decompressedStream =>
                      val headersWithoutEncoding = response.headers.remove(HeaderNames.ContentEncoding)
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
      val alpnProtocols =
        if config.enableHttp2 then SSLSocketChannel.Http2Protocols else SSLSocketChannel.Http1Protocols
      val sslChannel = SSLSocketChannel.client(
        socket,
        ctx,
        host,
        port,
        verifyHostname = config.tlsConfig.verifyHostname,
        alpnProtocols = alpnProtocols,
        protocols = config.tlsConfig.protocols,
        cipherSuites = config.tlsConfig.cipherSuites
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
      case t: Body.Text =>
        Eru.succeed(response.copy(body = t.bytes))
      case Body.Binary(b, _) =>
        Eru.succeed(response.copy(body = b))
      case Body.Stream(chunks, _, _) =>
        chunks
          .flatMap(_.toBytes)
          .map(bytes => response.copy(body = bytes))
          .mapError(e => HttpError.NetworkError(s"Error reading stream body: $e", None))
    }
  }

  /** Add standard request headers (Host, Connection, Accept-Encoding) in a single TreeMap update.
    *
    * Collects all headers to add, validates them, then builds one updated Headers via
    * fromValidatedPairs. Host is always added for HTTP/1.1 when the request does not set it (RFC
    * 9110 §7.2: a client MUST send Host in every HTTP/1.1 request). Content-Type is added from the
    * body's declared media type (RFC 9110 Section 8.3); an explicitly set Content-Type header
    * always wins. Content-Type is applied before validation so a QUERY with a typed body satisfies
    * its Content-Type requirement (RFC 10008 Section 2).
    */
  private def addStandardHeaders[A](request: Request[A]): Eru[HttpError, Request[A]] = {
    for {
      hostPair <-
        if request.headers.contains(HeaderNames.Host) then Eru.succeed(List.empty[(String, String)])
        else {
          for {
            host <- Eru.fromOption(
              request.uri.host,
              HttpError.InvalidRequest(InvalidRequest("Missing host in URI", "RFC 9110"))
            )
            port <- getPort(request.uri)
            hostValue = if port == 80 || port == 443 then host else s"$host:$port"
          } yield List((HeaderNames.Host, hostValue))
        }

      connectionPair =
        if request.headers.contains(HeaderNames.Connection) then List.empty
        else List((HeaderNames.Connection, "keep-alive"))

      encodingPair =
        if !config.automaticDecompression || request.headers.contains(HeaderNames.AcceptEncoding) then List.empty
        else {
          val encodings = config.acceptEncoding.map(_.value).mkString(", ")
          if encodings.nonEmpty then List((HeaderNames.AcceptEncoding, encodings)) else List.empty
        }

      contentTypePair =
        if request.headers.contains(HeaderNames.ContentType) then List.empty
        else {
          @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
          val mediaType: Option[MediaType] = request.body match {
            case b: Body => b.mediaType
            case _ => None
          }
          mediaType.map(mt => (HeaderNames.ContentType, mt.value)).toList
        }

      allPairs = hostPair ++ connectionPair ++ encodingPair ++ contentTypePair

      result <-
        if allPairs.isEmpty then Eru.succeed(request)
        else {
          allPairs
            .foldLeft(Eru.succeed(List.empty[(String, HeaderValue)]): Eru[HttpError, List[(String, HeaderValue)]]) {
              case (accEru, (name, value)) =>
                accEru.flatMap { acc =>
                  HeaderName
                    .parse(name)
                    .flatMap(_ => HeaderValue.parse(value))
                    .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC 9110")))
                    .map(hv => acc :+ (name, hv))
                }
            }
            .map { validatedPairs =>
              val extra = Headers.fromValidatedPairs(validatedPairs)
              request.copy(headers = request.headers ++ extra)
            }
        }
    } yield result
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

  /** Handle HTTP redirect.
    *
    * Location carries a URI reference; it is resolved against the effective request URI (RFC 9110
    * Section 10.2.2, RFC 3986 Section 5.2). Per RFC 10008 Section 2.5, a 303 redirect converts a
    * QUERY to a GET on the new target URI without the query content; all other redirect statuses
    * repeat the QUERY with the same content (the POST-to-GET exceptions of RFC 9110 Section 15.4.2
    * do not apply to QUERY). On a cross-origin redirect, origin-scoped credentials (Authorization,
    * Proxy-Authorization, and an explicitly set Cookie) are dropped, following the RFC 9110 Section
    * 11.5 protection-space reasoning (a protection space cannot extend outside its server);
    * jar-managed cookies are re-evaluated against the target URI with RFC 6265 domain matching, so
    * a cookie whose domain spans both origins (for example Domain=example.com set from
    * www.example.com) may still be attached after a redirect to a sibling subdomain. The copied
    * request carries the old Host header, so standard headers are regenerated from the new target
    * URI (RFC 9110 Section 7.2).
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
      newUri <- originalRequest.uri.resolve(locationHeader).mapError(HttpError.InvalidUri.apply)

      newRequest =
        if originalRequest.method == Method.QUERY && response.status == StatusCode.SeeOther then Request.get(newUri)
        else originalRequest.copy(uri = newUri)

      requestWithoutCredentials =
        if sameOrigin(originalRequest.uri, newUri) then newRequest
        else {
          newRequest.copy(
            headers = newRequest.headers
              .remove(HeaderNames.Authorization)
              .remove(HeaderNames.ProxyAuthorization)
              .remove(HeaderNames.Cookie)
          )
        }

      stripped = requestWithoutCredentials.copy(headers = requestWithoutCredentials.headers.remove(HeaderNames.Host))
      requestWithHeaders <- addStandardHeaders(stripped)
      _ <- requestWithHeaders.validate.mapError(HttpError.InvalidRequest.apply)

      result <- sendInternal(requestWithHeaders, redirectCount + 1)
    } yield result

  /** RFC 6454 origin comparison: scheme, host, and port, with default ports normalized to the
    * scheme defaults.
    */
  private def sameOrigin(a: Uri, b: Uri): Boolean = {
    def effectivePort(uri: Uri): Option[Int] =
      uri.port.map(_.value).orElse {
        uri.scheme match {
          case Some("https") => Some(443)
          case Some("http") => Some(80)
          case _ => None
        }
      }

    a.scheme == b.scheme && a.host == b.host && effectivePort(a) == effectivePort(b)
  }

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

  /** Create a native HTTP client. */
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
