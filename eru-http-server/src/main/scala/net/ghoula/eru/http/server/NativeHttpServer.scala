package net.ghoula.eru.http.server

import jdk.net.ExtendedSocketOptions

import java.net.InetSocketAddress
import java.nio.channels.{ReadableByteChannel, ServerSocketChannel, SocketChannel, WritableByteChannel}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.h2.{H2Error, H2ServerConnection}
import net.ghoula.eru.prelude.*

/** Native HTTP server implementation using blocking NIO + Virtual Threads.
  *
  * This implementation demonstrates the power of Eru's Virtual Thread backend:
  *   - Each connection runs on its own Virtual Thread via .fork
  *   - Blocking I/O is efficient (~10KB per thread vs ~2MB for OS threads)
  *   - Structured concurrency ensures automatic cleanup
  *   - Simple, readable code with no event loops or callbacks
  *
  * SO_REUSEPORT Support (Linux 3.9+):
  *   - Multiple acceptor threads for multi-core scaling
  *   - Kernel-level load balancing across acceptors
  *   - Each acceptor has its own blocking ServerSocketChannel on a Virtual Thread
  *   - Enabled automatically when acceptorThreads > 1
  */
private[server] final class NativeHttpServer(
  config: HttpServerConfig,
  handler: RequestHandler,
  serverSockets: List[ServerSocketChannel],
  sslContext: Option[SSLContext]
)(using runtime: EruRuntime)
    extends HttpServer {

  private val running = new AtomicBoolean(true)
  private val activeClients = new java.util.concurrent.ConcurrentHashMap[SocketChannel, java.lang.Boolean]()

  def start: Eru[HttpError, ServerAddress] = for {
    // Bind all server sockets
    _ <- Eru.effect {
      serverSockets.foreach { socket =>
        socket.configureBlocking(true) // Blocking accept - efficient on Virtual Threads, no pinning
        socket.bind(new InetSocketAddress(config.host, config.port), config.backlog)
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to bind server: ${e.getMessage}", Some(e)))

    // Get address from first socket (all bound to same port)
    address <- Eru.effect {
      serverSockets.head.getLocalAddress match {
        case addr: InetSocketAddress =>
          ServerAddress(addr.getHostString, addr.getPort)
        case other =>
          throw new IllegalStateException(s"Unexpected address type: ${other.getClass}")
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to get address: ${e.getMessage}", Some(e)))

    // Start one accept loop per server socket (SO_REUSEPORT multi-threading)
    // Each loop runs as a daemon fiber on its own Virtual Thread
    _ <- serverSockets.foldLeft[Eru[Nothing, Unit]](Eru.unit) { (acc, socket) =>
      acc.flatMap(_ => runtime.forkDaemon(acceptLoop(socket)).map(_ => ()))
    }

  } yield address

  /** Accept loop - blocking accept on Virtual Thread.
    *
    * Uses blocking ServerSocketChannel.accept() which is efficient on Virtual Threads (no pinning).
    * Each accepted connection is dispatched to its own daemon fiber for handling.
    *
    * With SO_REUSEPORT (acceptorThreads > 1), multiple instances of this loop run concurrently,
    * each with its own ServerSocketChannel bound to the same port. The kernel distributes incoming
    * connections across acceptors for multi-core scaling.
    *
    * Shutdown: serverSocket.close() causes accept() to throw ClosedChannelException, cleanly
    * terminating the loop.
    *
    * @param serverSocket
    *   The server socket for this accept loop (unique per acceptor thread)
    */
  private def acceptLoop(serverSocket: ServerSocketChannel): Eru[HttpError, Nothing] = {
    val acceptAndHandle = for {
      clientSocket <- Eru
        .effect(serverSocket.accept())
        .mapError(e => HttpError.NetworkError(s"Accept error: ${e.getMessage}", Some(e)))
      _ <- Eru.effect {
        // Configure client socket for blocking I/O on Virtual Thread
        clientSocket.configureBlocking(true)

        // Enable TCP_NODELAY to disable Nagle's algorithm (avoid 40ms delay)
        clientSocket.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)

        // Enable TCP_QUICKACK on Linux to avoid delayed ACK (avoid 40ms delay)
        try {
          clientSocket.setOption(ExtendedSocketOptions.TCP_QUICKACK, true)
        } catch {
          case _: Exception => () // TCP_QUICKACK not available on this platform
        }

        // Track active client for graceful shutdown
        activeClients.put(clientSocket, java.lang.Boolean.TRUE)
      }.mapError(e => HttpError.NetworkError(s"Socket config error: ${e.getMessage}", Some(e)))
      // Dispatch to daemon fiber - prevents memory accumulation from fiber tracking
      _ <- runtime.forkDaemon(handleClient(clientSocket)).map(_ => ())
    } yield ()
    Eru.forever(acceptAndHandle)
  }

  /** Handle a single client connection.
    *
    * This runs on its own Virtual Thread, so blocking I/O is efficient. The entire request-response
    * cycle is a simple for-comprehension!
    */
  private def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = {
    val clientEffect = for {
      // Wrap with TLS if configured, and check for HTTP/2 via ALPN
      channelAndProtocol <- sslContext match {
        case Some(ctx) =>
          wrapWithTLS(socket, ctx).map { sslChannel =>
            (sslChannel: ReadableByteChannel & WritableByteChannel, sslChannel.isHttp2, Some(sslChannel))
          }
        case None =>
          Eru.succeed((socket: ReadableByteChannel & WritableByteChannel, false, Option.empty[SSLSocketChannel]))
      }

      (channel, isHttp2, maybeSslChannel) = channelAndProtocol

      // Route to appropriate protocol handler
      _ <-
        if isHttp2 then {
          maybeSslChannel match {
            case Some(ssl) => handleHttp2Connection(ssl)
            case None => handleRequestLoop(channel) // Should not happen, HTTP/2 requires TLS
          }
        } else {
          handleRequestLoop(channel)
        }

    } yield ()

    // Ensure socket is closed and unregistered even if errors occur
    val cleanup = Eru.effect {
      activeClients.remove(socket)
      socket.close()
    }.attempt.map(_ => ())

    clientEffect.attempt.flatMap { _ => cleanup }
  }

  /** Handle an HTTP/2 connection.
    *
    * Uses H2ServerConnection to process HTTP/2 frames and handle requests.
    *
    * CRITICAL: Response handlers are forked to prevent flow control deadlock. Without forking,
    * large responses (>65KB initial window) would block on sendData() waiting for WINDOW_UPDATE
    * frames, but no fiber would be reading frames from the socket to receive those WINDOW_UPDATE
    * frames.
    *
    * By forking the response handler, the main loop continues reading frames (which processes
    * WINDOW_UPDATE via handleWindowUpdate), allowing blocked response fibers to wake up and
    * continue sending data.
    */
  private def handleHttp2Connection(channel: SSLSocketChannel): Eru[HttpError, Unit] = {
    // Accept the HTTP/2 connection (exchange preface)
    H2ServerConnection.accept(channel).mapError(h2ErrorToHttpError).flatMap { h2conn =>
      // Handle requests in a loop
      def loop(): Eru[HttpError, Unit] = {
        h2conn
          .receiveRequest()
          .mapError(h2ErrorToHttpError)
          .flatMap { case (streamId, h2Headers, body) =>
            // Convert HTTP/2 request to eru-http Request
            convertH2RequestToRequest(h2Headers, body).flatMap { request =>
              // Fork the handler - main loop continues reading frames (processes WINDOW_UPDATE)
              val handlerEffect = handler(request).attempt.flatMap { handlerResult =>
                val responseEffect = handlerResult match {
                  case Result.Success(resp) => Eru.succeed(resp)
                  case Result.Failure(httpError: HttpError) => errorToResponse(httpError)
                }

                responseEffect.flatMap { response =>
                  // Convert response to HTTP/2 and send
                  val statusCode = response.status.value
                  val responseHeaders = response.headers.toList.map { case (name, value) => (name.toLowerCase, value) }

                  // Get response body bytes
                  val bodyBytes = response.body match {
                    case Body.Empty => None
                    case Body.Text(text, _, charset) => Some(text.getBytes(charset.toJavaCharset))
                    case Body.Binary(bytes, _) => Some(bytes.toArray)
                    case Body.Stream(_, _, _) => None // TODO: Support streaming in HTTP/2
                  }

                  h2conn.sendResponse(streamId, statusCode, responseHeaders, bodyBytes).mapError(h2ErrorToHttpError)
                }
              }

              // Fork and continue - don't wait for response to complete
              // The forked fiber handles the response while main loop reads more frames
              runtime.fork(handlerEffect).map(_ => ())
            }
          }
          .attempt
          .flatMap {
            case Result.Success(_) =>
              // Request received and handler forked, continue if connection is still usable
              h2conn.connection.isGoingAway.flatMap { goingAway =>
                if goingAway then Eru.unit
                else loop()
              }
            case Result.Failure(_) =>
              // Connection closed or error, exit cleanly
              Eru.unit
          }
      }

      loop()
    }
  }

  /** Convert HTTP/2 headers to eru-http Request. */
  private def convertH2RequestToRequest(
    h2Headers: List[(String, String)],
    body: Option[Array[Byte]]
  ): Eru[HttpError, Request[Body]] = {
    val headerMap = h2Headers.toMap

    for {
      // Extract pseudo-headers (required)
      methodStr <- Eru.fromOption(
        headerMap.get(":method"),
        HttpError.ProtocolError("Missing :method pseudo-header", "RFC 9113 Section 8.3.1")
      )
      pathStr <- Eru.fromOption(
        headerMap.get(":path"),
        HttpError.ProtocolError("Missing :path pseudo-header", "RFC 9113 Section 8.3.1")
      )
      schemeStr <- Eru.fromOption(
        headerMap.get(":scheme"),
        HttpError.ProtocolError("Missing :scheme pseudo-header", "RFC 9113 Section 8.3.1")
      )
      authority = headerMap.get(":authority")

      // Parse method
      method <- Method.parse(methodStr).mapError(e => HttpError.InvalidMethod(e))

      // Build URI from pseudo-headers
      uriStr = authority match {
        case Some(host) => s"$schemeStr://$host$pathStr"
        case None => pathStr // Relative URI
      }
      uri <- Uri.parse(uriStr).mapError(e => HttpError.InvalidUri(e))

      // Build headers (filter out pseudo-headers)
      regularHeaders = h2Headers.filter { case (name, _) => !name.startsWith(":") }
      headers <- regularHeaders.foldLeft(Eru.succeed(Headers.empty): Eru[HttpError, Headers]) {
        case (acc, (name, value)) =>
          acc.flatMap(_.add(name, value).mapError(e => HttpError.ProtocolError(s"Invalid header: $e", "RFC 9113")))
      }

      // Build body
      requestBody: Body = body match {
        case Some(bytes) if bytes.nonEmpty => Body.Binary(Bytes.fromArray(bytes), None)
        case _ => Body.Empty
      }

    } yield Request(method, uri, headers, requestBody, HttpVersion.HTTP_2_0)
  }

  /** Convert H2Error to HttpError. */
  private def h2ErrorToHttpError(error: H2Error): HttpError = error match {
    case H2Error.ConnectionError(code, msg) =>
      HttpError.ProtocolError(s"HTTP/2 connection error ($code): ${msg.getOrElse("unknown")}", "RFC 9113")
    case H2Error.StreamError(streamId, code, msg) =>
      HttpError.ProtocolError(s"HTTP/2 stream $streamId error ($code): ${msg.getOrElse("unknown")}", "RFC 9113")
    case H2Error.FlowControlViolation(streamId, msg) =>
      HttpError.ProtocolError(s"HTTP/2 flow control violation on stream $streamId: $msg", "RFC 9113 Section 5.2")
    case H2Error.StreamStateViolation(streamId, msg) =>
      HttpError.ProtocolError(s"HTTP/2 stream state violation on stream $streamId: $msg", "RFC 9113 Section 5.1")
    case H2Error.ProtocolViolation(msg, _) =>
      HttpError.ProtocolError(s"HTTP/2 protocol violation: $msg", "RFC 9113")
    case H2Error.InvalidPreface(msg) =>
      HttpError.ProtocolError(s"Invalid HTTP/2 connection preface: $msg", "RFC 9113 Section 3.4")
    case H2Error.InvalidFrame(msg, rfc) =>
      HttpError.ProtocolError(s"HTTP/2 frame error: $msg", rfc)
    case H2Error.CompressionError(msg) =>
      HttpError.ProtocolError(s"HTTP/2 compression error: $msg", "RFC 7541")
    case H2Error.SettingsError(msg) =>
      HttpError.ProtocolError(s"HTTP/2 settings error: $msg", "RFC 9113 Section 6.5")
    case H2Error.NetworkError(msg, cause) =>
      HttpError.NetworkError(s"HTTP/2 network error: $msg", cause)
  }

  /** Handle requests in a loop for HTTP keep-alive support.
    *
    * Continues handling requests on the same channel until:
    *   - Connection: close header is received
    *   - An error occurs
    *   - Channel is closed by client
    */
  private def handleRequestLoop(channel: ReadableByteChannel & WritableByteChannel): Eru[HttpError, Unit] = {
    // Create BufferedSocketReader ONCE per connection and reuse for all requests
    // This is critical for performance - avoids allocating 8KB direct ByteBuffer per request
    val reader = new net.ghoula.eru.http.BufferedSocketReader(channel)

    // Create write buffer ONCE per connection for zero-allocation response writing
    // 8KB buffer is sufficient for most HTTP response headers
    val writeBuffer = java.nio.ByteBuffer.allocate(8192)

    def loop(isFirstRequest: Boolean = true): Eru[HttpError, Boolean] = {
      val requestEffect = for {
        // Reset reader state before parsing (except first request)
        _ <- if !isFirstRequest then Eru.effect(reader.reset()) else Eru.unit

        // Parse request without timeout - socket blocking handles idle connections naturally
        // BufferedSocketReader will throw EOFException if connection is closed
        requestResult <- HttpParser
          .parseRequest(reader) // Use reader instead of socket
          .attempt

        // If parsing fails (socket closed, timeout, etc.), exit loop
        request <- requestResult match {
          case Result.Success(req) => Eru.succeed(req)
          case Result.Failure(_) => Eru.fail(HttpError.NetworkError("Connection closed", None))
        }

        // Execute handler without timeout (fast handlers don't need it, slow handlers are user's responsibility)
        // The parse timeout above is sufficient to handle idle connections
        handlerResult <- handler(request).attempt

        // Convert handler result to response (either success or error response)
        // Add Connection and Content-Length headers
        response <- handlerResult match {
          case Result.Success(resp) => addConnectionHeader(request, resp)
          case Result.Failure(httpError: HttpError) =>
            errorToResponse(httpError).flatMap(addConnectionHeader(request, _))
        }

        // Check if this is a WebSocket upgrade response
        isWebSocketUpgrade = WebSocketServer.isUpgradeResponse(response)

        // Remove the internal handler ID header before sending to client
        responseToSend =
          if isWebSocketUpgrade then response.copy(headers = response.headers.remove("X-WebSocket-Handler-Id"))
          else response

        // Write response with reusable buffer (zero-allocation, blocking write - efficient on VT!)
        _ <- HttpWriter.writeResponseWithBuffer(channel, responseToSend, writeBuffer)

        // Handle WebSocket upgrade if detected
        result <-
          if isWebSocketUpgrade then {
            // Get the handler and switch to WebSocket mode
            WebSocketServer.getHandlerId(response) match {
              case Some(handlerId) =>
                WebSocketServer.retrieveHandler(handlerId) match {
                  case Some(pending) =>
                    // Create WebSocket connection and run the handler
                    val wsConn = NativeServerWebSocketConnection.create(
                      channel,
                      reader,
                      pending.config,
                      pending.subprotocol,
                      pending.request
                    )
                    // Run the WebSocket handler - this blocks until the connection closes
                    pending.handler(wsConn).attempt.map { _ =>
                      // WebSocket session ended, don't continue HTTP loop
                      false
                    }
                  case None =>
                    // Handler not found (shouldn't happen), continue as HTTP
                    Eru.succeed(shouldKeepAlive(request, responseToSend))
                }
              case None =>
                // No handler ID (shouldn't happen), continue as HTTP
                Eru.succeed(shouldKeepAlive(request, responseToSend))
            }
          } else {
            // Regular HTTP - check if we should continue the loop
            Eru.succeed(shouldKeepAlive(request, responseToSend))
          }

      } yield result

      requestEffect.attempt.flatMap {
        case Result.Success(true) => loop(false) // Continue for next request
        case Result.Success(false) => Eru.succeed(false) // Connection: close or WebSocket, exit cleanly
        case Result.Failure(_) => Eru.succeed(false) // Error, exit cleanly
      }
    }

    loop().map(_ => ())
  }

  /** Check if connection should be kept alive based on request/response headers.
    */
  private def shouldKeepAlive(request: Request[Body], response: Response[Body]): Boolean = {
    // Check response Connection header first
    val responseConnection = response.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)

    if responseConnection.contains("close") then false
    else {
      // Check request Connection header
      val requestConnection = request.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)

      if requestConnection.contains("close") then false
      else {
        // Default for HTTP/1.1 is keep-alive
        request.version == HttpVersion.HTTP_1_1 || responseConnection.contains("keep-alive")
      }
    }
  }

  /** Add Connection and Content-Length/Transfer-Encoding headers to response if not present.
    *
    * HTTP/1.1 requires either Content-Length or Transfer-Encoding for message framing. This ensures
    * we send proper headers for keep-alive support.
    *
    * If the client requests Connection: close, we echo it back in the response.
    */
  private def addConnectionHeader(request: Request[Body], response: Response[Body]): Eru[Nothing, Response[Body]] = {
    // First, ensure Content-Length or Transfer-Encoding is set
    val withContentLength: Eru[Nothing, Response[Body]] =
      if response.headers.contains(HeaderNames.ContentLength) ||
        response.headers.contains(HeaderNames.TransferEncoding)
      then {
        Eru.succeed(response)
      } else {
        // Calculate content length based on body type
        val headerEffect = response.body match {
          case Body.Empty =>
            response.headers.add(HeaderNames.ContentLength, "0")

          case Body.Text(text, _, charset) =>
            val length = text.getBytes(charset.toJavaCharset).length
            response.headers.add(HeaderNames.ContentLength, length.toString)

          case Body.Binary(bytes, _) =>
            response.headers.add(HeaderNames.ContentLength, bytes.length.toString)

          case Body.Stream(_, contentLength, _) =>
            contentLength match {
              case Some(length) =>
                response.headers.add(HeaderNames.ContentLength, length.toString)
              case None =>
                response.headers.add(HeaderNames.TransferEncoding, "chunked")
            }
        }
        headerEffect
          .map(newHeaders => response.copy(headers = newHeaders))
          .attempt
          .map {
            case Result.Success(r) => r
            case Result.Failure(_) => response
          }
      }

    // Then add Connection header if not present
    withContentLength.flatMap { resp =>
      if resp.headers.contains(HeaderNames.Connection) then {
        Eru.succeed(resp)
      } else {
        val requestConnection = request.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
        val connectionValue = if requestConnection.contains("close") then "close" else "keep-alive"

        resp.headers
          .add(HeaderNames.Connection, connectionValue)
          .map(newHeaders => resp.copy(headers = newHeaders))
          .attempt
          .map {
            case Result.Success(r) => r
            case Result.Failure(_) => resp
          }
      }
    }
  }

  /** Wrap socket with TLS/SSL.
    *
    * Uses SSLEngine with blocking mode. The handshake blocks the Virtual Thread, which is efficient
    * since VTs are cheap. ALPN is used to negotiate HTTP/2 or HTTP/1.1.
    */
  private def wrapWithTLS(
    socket: SocketChannel,
    ctx: SSLContext
  ): Eru[HttpError, SSLSocketChannel] =
    Eru.effect {
      // Enable HTTP/2 negotiation via ALPN
      val sslChannel = SSLSocketChannel.server(socket, ctx, SSLSocketChannel.Http2Protocols)
      sslChannel.doHandshake()
      sslChannel
    }.mapError(e => HttpError.NetworkError(s"TLS handshake failed: ${e.getMessage}", Some(e)))

  /** Convert HTTP error to error response
    */
  private def errorToResponse(error: HttpError): Eru[Nothing, Response[Body]] = {
    val (status, message) = error match {
      case HttpError.InvalidMethod(_) =>
        (StatusCode.BadRequest, "Bad Request: Invalid HTTP method")
      case HttpError.InvalidUri(_) =>
        (StatusCode.BadRequest, "Bad Request: Invalid URI")
      case HttpError.InvalidRequest(_) =>
        (StatusCode.BadRequest, "Bad Request")
      case HttpError.InvalidResponse(_) =>
        (StatusCode.InternalServerError, "Internal Server Error")
      case HttpError.TimeoutError(_) =>
        (StatusCode.RequestTimeout, "Request Timeout")
      case _ =>
        (StatusCode.InternalServerError, "Internal Server Error")
    }

    val body = Body.Text(message, None, Charset.UTF8)
    val contentType = "text/plain; charset=utf-8"

    Headers.empty
      .add(HeaderNames.ContentType, contentType)
      .flatMap(_.add(HeaderNames.ContentLength, message.getBytes.length.toString))
      .attempt
      .map {
        case Result.Success(h) => h
        case Result.Failure(_) => Headers.empty
      }
      .map(headers => Response(status = status, headers = headers, body = body))
  }

  def shutdown: Eru[HttpError, Unit] = {
    if running.compareAndSet(true, false) then {
      Eru.effect {
        // Close all server sockets first to stop accepting new connections
        serverSockets.foreach(_.close())

        // Close all active client connections
        val clients = activeClients.keySet().iterator()
        while clients.hasNext() do {
          val socket = clients.next()
          try {
            socket.close()
          } catch {
            case _: Exception => () // Ignore errors closing individual sockets
          }
        }
        activeClients.clear()
        ()
      }.mapError(e => HttpError.NetworkError(s"Error during shutdown: ${e.getMessage}", Some(e)))
    } else {
      Eru.succeed(())
    }
  }

  def isRunning: Boolean = running.get()
}

private[server] object NativeHttpServer {

  /** Try to enable SO_REUSEPORT for multi-threaded accept.
    *
    * Returns Some(sockets) if successful, None if SO_REUSEPORT is not supported on this platform.
    * SO_REUSEPORT is available in StandardSocketOptions since Java 9.
    */
  private def tryEnableReusePort(numAcceptors: Int): Option[List[ServerSocketChannel]] = {
    try {
      // Create N sockets with SO_REUSEPORT enabled
      // SO_REUSEPORT is in StandardSocketOptions (not ExtendedSocketOptions!)
      val sockets = (0 until numAcceptors).map { _ =>
        val socket = ServerSocketChannel.open()
        socket.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
        socket.setOption(java.net.StandardSocketOptions.SO_REUSEPORT, java.lang.Boolean.TRUE)
        socket
      }.toList

      Some(sockets)
    } catch {
      case _: UnsupportedOperationException => None // Platform doesn't support SO_REUSEPORT
      case _: Exception => None // Any other error
    }
  }

  /** Create a native HTTP server.
    *
    * With acceptorThreads > 1, creates multiple ServerSocketChannels with SO_REUSEPORT for
    * kernel-level load balancing (Linux 3.9+). Each acceptor runs its own accept loop.
    */
  def create(
    config: HttpServerConfig,
    handler: RequestHandler
  )(using runtime: EruRuntime): Eru[HttpError, HttpServer] = {
    for {
      // Create N server sockets (one per acceptor thread)
      // With SO_REUSEPORT, multiple sockets can bind to the same port
      serverSockets <- Eru.effect {
        val requestedAcceptors = config.acceptorThreads.max(1)

        // Try to enable SO_REUSEPORT if multiple acceptors requested
        val (actualAcceptors, sockets) = if requestedAcceptors > 1 then {
          tryEnableReusePort(requestedAcceptors) match {
            case Some(socketsWithReusePort) =>
              (requestedAcceptors, socketsWithReusePort)
            case None =>
              // SO_REUSEPORT not available, fall back to single acceptor
              System.err.println(
                "[WARN] SO_REUSEPORT not available on this JDK/platform. " +
                  s"Falling back to single-threaded accept (acceptorThreads=${requestedAcceptors} -> 1). " +
                  "For best performance on Linux, use a JDK that supports jdk.net.ExtendedSocketOptions.SO_REUSEPORT"
              )
              (1, List(ServerSocketChannel.open()))
          }
        } else {
          (1, List(ServerSocketChannel.open()))
        }

        if actualAcceptors == 1 && requestedAcceptors > 1 then {
          System.err.println("[INFO] Using single-threaded accept loop. CPU scaling will be limited.")
        } else if actualAcceptors > 1 then {
          System.err.println(
            s"[INFO] Using SO_REUSEPORT with ${actualAcceptors} acceptor threads for multi-core scaling"
          )
        }

        sockets
      }.mapError(e => HttpError.NetworkError(s"Failed to create server sockets: ${e.getMessage}", Some(e)))

      sslContext <- config.tlsConfig match {
        case Some(tlsConfig) => createSSLContext(tlsConfig).map(Some(_))
        case None => Eru.succeed(None)
      }

      server = new NativeHttpServer(config, handler, serverSockets, sslContext)

    } yield server
  }

  /** Create SSL context from TLS configuration.
    *
    * Loads server certificate and key from the configured keystore.
    */
  private def createSSLContext(tlsConfig: TlsConfig): Eru[HttpError, SSLContext] =
    Eru.effect {
      SSLContextFactory.createServerContext(tlsConfig)
    }.mapError(e => HttpError.NetworkError(s"Failed to create SSL context: ${e.getMessage}", Some(e)))
}
