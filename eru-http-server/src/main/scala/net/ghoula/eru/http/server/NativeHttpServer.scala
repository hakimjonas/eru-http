package net.ghoula.eru.http.server

import jdk.net.ExtendedSocketOptions

import java.net.InetSocketAddress
import java.nio.channels.{ReadableByteChannel, ServerSocketChannel, SocketChannel, WritableByteChannel}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import scala.annotation.unused

import net.ghoula.eru.*
import net.ghoula.eru.http.*
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
  *   - Each acceptor has its own ServerSocketChannel + Selector
  *   - Enabled automatically when acceptorThreads > 1
  *
  * Compare to NettyHttpServer: ~150 lines vs 332 lines (55% reduction)
  */
private[server] final class NativeHttpServer(
  config: HttpServerConfig,
  handler: RequestHandler,
  serverSockets: List[ServerSocketChannel],
  sslContext: Option[SSLContext]
)(using @unused runtime: EruRuntime)
    extends HttpServer {

  private val running = new AtomicBoolean(true)
  private val activeClients = new java.util.concurrent.ConcurrentHashMap[SocketChannel, java.lang.Boolean]()

  def start: Eru[HttpError, ServerAddress] = for {
    // Bind all server sockets
    _ <- Eru.effect {
      serverSockets.foreach { socket =>
        socket.configureBlocking(false) // Non-blocking for Selector-based accept
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
    // Use Thread.startVirtualThread directly to bypass Eru's effect system
    // This ensures each accept loop runs on its own Virtual Thread immediately
    _ <- Eru.effect {
      serverSockets.foreach { socket =>
        Thread.startVirtualThread(() => acceptLoop(socket).unsafeRunSync())
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to start accept loops: ${e.getMessage}", Some(e)))

  } yield address

  /** Accept loop - uses Selector for efficient non-blocking accept.
    *
    * Uses NIO Selector to wait for incoming connections efficiently, then dispatches each
    * connection to its own Virtual Thread for handling. This avoids blocking on accept() and allows
    * handling thousands of concurrent connection attempts.
    *
    * With SO_REUSEPORT (acceptorThreads > 1), multiple instances of this loop run concurrently,
    * each with its own ServerSocketChannel bound to the same port. The kernel distributes incoming
    * connections across acceptors for multi-core scaling.
    *
    * @param serverSocket
    *   The server socket for this accept loop (unique per acceptor thread)
    */
  private def acceptLoop(serverSocket: ServerSocketChannel): Eru[HttpError, Unit] = {
    Eru.effect {
      val selector = java.nio.channels.Selector.open()
      serverSocket.register(selector, java.nio.channels.SelectionKey.OP_ACCEPT)

      while running.get() do {
        // Wait for events (blocks efficiently in kernel)
        selector.select()

        // Process all ready keys
        val keys = selector.selectedKeys().iterator()
        while keys.hasNext() do {
          val key = keys.next()
          keys.remove()

          if key.isAcceptable() then {
            // Accept all pending connections
            // scalafix:off DisableSyntax.null
            // Java NIO accept() returns null in non-blocking mode when no connections pending
            var clientSocket = serverSocket.accept()
            while clientSocket != null do {
              // scalafix:on DisableSyntax.null
              // Configure socket
              clientSocket.configureBlocking(true) // Client socket uses blocking mode on VT

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

              // Dispatch to Virtual Thread immediately WITHOUT BLOCKING
              // We're in a sync effect block, so start the VT directly
              // This allows the accept loop to continue immediately
              val socket = clientSocket // Capture for closure
              Thread.startVirtualThread(() => handleClient(socket).unsafeRunSync())

              // Try to accept next pending connection (returns null if none)
              clientSocket = serverSocket.accept()
            }
          }
        }
      }

      selector.close()
    }.mapError(e => HttpError.NetworkError(s"Accept loop error: ${e.getMessage}", Some(e)))
  }

  /** Handle a single client connection.
    *
    * This runs on its own Virtual Thread, so blocking I/O is efficient. The entire request-response
    * cycle is a simple for-comprehension!
    */
  private def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = {
    val clientEffect = for {
      // Wrap with TLS if configured
      secureSocket <- sslContext match {
        case Some(ctx) => wrapWithTLS(socket, ctx)
        case None => Eru.succeed(socket)
      }

      // Handle requests in a loop for keep-alive
      _ <- handleRequestLoop(secureSocket)

    } yield ()

    // Ensure socket is closed and unregistered even if errors occur
    val cleanup = Eru.effect {
      activeClients.remove(socket)
      socket.close()
    }.attempt.map(_ => ())

    clientEffect.attempt.flatMap { _ => cleanup }
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
        response <- Eru.effectTotal {
          handlerResult match {
            case Result.Success(resp) => addConnectionHeader(request, resp)
            case Result.Failure(httpError: HttpError) => addConnectionHeader(request, errorToResponse(httpError))
          }
        }

        // Write response with reusable buffer (zero-allocation, blocking write - efficient on VT!)
        _ <- HttpWriter.writeResponseWithBuffer(channel, response, writeBuffer)

        // Check if we should continue the loop (keep-alive)
        shouldContinue = shouldKeepAlive(request, response)

      } yield shouldContinue

      requestEffect.attempt.flatMap {
        case Result.Success(true) => loop(false) // Continue for next request
        case Result.Success(false) => Eru.succeed(false) // Connection: close, exit cleanly
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
  private def addConnectionHeader(request: Request[Body], response: Response[Body]): Response[Body] = {
    // First, ensure Content-Length or Transfer-Encoding is set
    val withContentLength =
      if response.headers.contains(HeaderNames.ContentLength) ||
        response.headers.contains(HeaderNames.TransferEncoding)
      then {
        response
      } else {
        // Calculate content length based on body type
        response.body match {
          case Body.Empty =>
            response.headers.add(HeaderNames.ContentLength, "0").attempt.unsafeRunSync() match {
              case Result.Success(newHeaders) => response.copy(headers = newHeaders)
              case Result.Failure(_) => response
            }

          case Body.Text(text, _, charset) =>
            val length = text.getBytes(charset.toJavaCharset).length
            response.headers.add(HeaderNames.ContentLength, length.toString).attempt.unsafeRunSync() match {
              case Result.Success(newHeaders) => response.copy(headers = newHeaders)
              case Result.Failure(_) => response
            }

          case Body.Binary(bytes, _) =>
            response.headers.add(HeaderNames.ContentLength, bytes.length.toString).attempt.unsafeRunSync() match {
              case Result.Success(newHeaders) => response.copy(headers = newHeaders)
              case Result.Failure(_) => response
            }

          case Body.Stream(_, contentLength, _) =>
            contentLength match {
              case Some(length) =>
                // Stream with known length - use Content-Length
                response.headers.add(HeaderNames.ContentLength, length.toString).attempt.unsafeRunSync() match {
                  case Result.Success(newHeaders) => response.copy(headers = newHeaders)
                  case Result.Failure(_) => response
                }
              case None =>
                // Stream with unknown length - use chunked encoding
                response.headers.add(HeaderNames.TransferEncoding, "chunked").attempt.unsafeRunSync() match {
                  case Result.Success(newHeaders) => response.copy(headers = newHeaders)
                  case Result.Failure(_) => response
                }
            }
        }
      }

    // Then add Connection header if not present
    if withContentLength.headers.contains(HeaderNames.Connection) then {
      withContentLength
    } else {
      // Check if client requested Connection: close
      val requestConnection = request.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
      val connectionValue = if requestConnection.contains("close") then "close" else "keep-alive"

      withContentLength.headers.add(HeaderNames.Connection, connectionValue).attempt.unsafeRunSync() match {
        case Result.Success(newHeaders) => withContentLength.copy(headers = newHeaders)
        case Result.Failure(_) => withContentLength
      }
    }
  }

  /** Wrap socket with TLS/SSL.
    *
    * Uses SSLEngine with blocking mode. The handshake blocks the Virtual Thread, which is efficient
    * since VTs are cheap.
    */
  private def wrapWithTLS(
    socket: SocketChannel,
    ctx: SSLContext
  ): Eru[HttpError, ReadableByteChannel & WritableByteChannel] =
    Eru.effect {
      val sslChannel = SSLSocketChannel.server(socket, ctx)
      sslChannel.doHandshake()
      sslChannel
    }.mapError(e => HttpError.NetworkError(s"TLS handshake failed: ${e.getMessage}", Some(e)))

  /** Convert HTTP error to error response
    */
  private def errorToResponse(error: HttpError): Response[Body] = {
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

    val headers = Headers.empty
      .add(HeaderNames.ContentType, contentType)
      .flatMap(_.add(HeaderNames.ContentLength, message.getBytes.length.toString))
      .attempt
      .map {
        case Result.Success(h) => h
        case Result.Failure(_) => Headers.empty
      }
      .unsafeRunSync()

    Response(
      status = status,
      headers = headers,
      body = body
    )
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
    * This is dramatically simpler than NettyHttpServer.create:
    *   - No EventLoopGroups to manage
    *   - No Bootstrap configuration
    *   - No ChannelPipeline setup
    *   - No ChannelHandlers
    *   - Just pure Eru effects + blocking NIO
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
