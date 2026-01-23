package net.ghoula.eru.http.server

import java.nio.channels.{ReadableByteChannel, WritableByteChannel}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.*

/** Server-side WebSocket connection for bidirectional communication.
  *
  * Similar to the client WebSocketConnection but:
  *   - Server frames are NOT masked (per RFC 6455)
  *   - Expects client frames TO BE masked
  */
trait ServerWebSocketConnection {

  /** Send a text message to the client.
    */
  def sendText(text: String): Eru[WebSocketError, Unit]

  /** Send a binary message to the client.
    */
  def sendBinary(data: Bytes): Eru[WebSocketError, Unit]

  /** Send a ping frame to the client.
    */
  def sendPing(data: Bytes = Bytes.empty): Eru[WebSocketError, Unit]

  /** Receive the next message from the client.
    *
    * Handles control frames automatically:
    *   - Ping: Sends Pong automatically
    *   - Pong: Ignored
    *   - Close: Completes the close handshake and returns ConnectionClosed error
    */
  def receive(): Eru[WebSocketError, WebSocketMessage]

  /** Close the WebSocket connection.
    */
  def close(
    code: WebSocketCloseCode = WebSocketCloseCode.NormalClosure,
    reason: Option[String] = None
  ): Eru[WebSocketError, Unit]

  /** Check if the connection is still open.
    */
  def isOpen: Boolean

  /** Get the negotiated subprotocol, if any.
    */
  def subprotocol: Option[String]

  /** Get the original HTTP upgrade request.
    */
  def upgradeRequest: Request[Body]
}

/** WebSocket handler function type.
  *
  * Takes a server-side WebSocket connection and handles the WebSocket session. The handler should
  * receive and send messages until the connection is closed.
  */
type WebSocketHandler = ServerWebSocketConnection => Eru[WebSocketError | HttpError, Unit]

/** WebSocket server utilities for handling WebSocket upgrades.
  *
  * Use `upgradeHandler` to wrap an HTTP handler with WebSocket support.
  */
object WebSocketServer {

  /** Check if an HTTP request is a WebSocket upgrade request.
    */
  def isWebSocketUpgrade(request: Request[Body]): Boolean =
    WebSocketHandshake.isUpgradeRequest(request)

  /** Create a request handler that supports WebSocket upgrades.
    *
    * When a WebSocket upgrade request is received, the wsHandler is called with the upgraded
    * connection. For all other requests, the httpHandler is called normally.
    *
    * @param config
    *   WebSocket configuration
    * @param wsHandler
    *   Handler for WebSocket connections
    * @param httpHandler
    *   Handler for regular HTTP requests
    * @return
    *   A combined request handler
    */
  def upgradeHandler(
    config: WebSocketServerConfig = WebSocketServerConfig.default
  )(
    wsHandler: WebSocketHandler
  )(
    httpHandler: RequestHandler
  ): RequestHandler = { request =>
    if isWebSocketUpgrade(request) then handleWebSocketUpgrade(request, config, wsHandler)
    else httpHandler(request)
  }

  /** Pending WebSocket upgrade information.
    */
  private[server] final case class PendingWebSocket(
    handler: WebSocketHandler,
    config: WebSocketServerConfig,
    request: Request[Body],
    subprotocol: Option[String]
  )

  private val pendingHandlers = new ConcurrentHashMap[String, PendingWebSocket]()
  private val handlerIdCounter = new AtomicLong(0)

  /** Handle a WebSocket upgrade request.
    *
    * This creates the upgrade response and registers the handler for later execution. The
    * NativeHttpServer detects 101 responses and retrieves the handler to complete the upgrade.
    */
  private def handleWebSocketUpgrade(
    request: Request[Body],
    config: WebSocketServerConfig,
    wsHandler: WebSocketHandler
  ): Eru[HttpError, Response[Body]] = {
    val requestedSubprotocols = WebSocketHandshake.extractSubprotocols(request)
    val selectedSubprotocol = selectSubprotocol(requestedSubprotocols, config.allowedSubprotocols)
    val handlerId = handlerIdCounter.incrementAndGet().toString
    pendingHandlers.put(handlerId, PendingWebSocket(wsHandler, config, request, selectedSubprotocol))

    for {
      key <- WebSocketHandshake.extractKey(request).mapError { wsError =>
        HttpError.InvalidRequest(InvalidRequest(wsError.errorMessage, "RFC 6455"))
      }
      response <- WebSocketHandshake.createUpgradeResponse(key, selectedSubprotocol)
      responseWithMarker = response.copy(
        headers = response.headers
          .unsafeAdd("X-WebSocket-Handler-Id", HeaderValue.unsafeFromString(handlerId))
      )
    } yield responseWithMarker
  }

  /** Retrieve and remove a pending WebSocket handler by ID.
    *
    * Called by NativeHttpServer after detecting a 101 response.
    */
  private[server] def retrieveHandler(handlerId: String): Option[PendingWebSocket] = {
    Option(pendingHandlers.remove(handlerId))
  }

  /** Check if a response is a WebSocket upgrade response.
    */
  private[server] def isUpgradeResponse(response: Response[Body]): Boolean = {
    response.status == StatusCode.SwitchingProtocols &&
    response.headers.contains("X-WebSocket-Handler-Id")
  }

  /** Get the handler ID from an upgrade response.
    */
  private[server] def getHandlerId(response: Response[Body]): Option[String] = {
    response.headers.getFirst("X-WebSocket-Handler-Id").map(_.value)
  }

  private def selectSubprotocol(
    requested: List[String],
    allowed: List[String]
  ): Option[String] = {
    if allowed.isEmpty then requested.headOption
    else requested.find(allowed.contains)
  }

  /** Create a complete WebSocket server that handles both HTTP and WebSocket.
    *
    * This is a convenience method that sets up the full WebSocket-capable server.
    *
    * @param httpConfig
    *   HTTP server configuration
    * @param wsConfig
    *   WebSocket configuration
    * @param wsHandler
    *   Handler for WebSocket connections
    * @param httpHandler
    *   Handler for regular HTTP requests
    * @return
    *   An HTTP server that supports WebSocket upgrades
    */
  def create(
    httpConfig: HttpServerConfig,
    wsConfig: WebSocketServerConfig = WebSocketServerConfig.default
  )(wsHandler: WebSocketHandler)(httpHandler: RequestHandler)(using
    runtime: EruRuntime
  ): Eru[HttpError, HttpServer] = {
    val combinedHandler = upgradeHandler(wsConfig)(wsHandler)(httpHandler)
    HttpServer.create(httpConfig, combinedHandler)
  }
}

/** Native server-side WebSocket connection implementation.
  */
private[server] final class NativeServerWebSocketConnection(
  channel: ReadableByteChannel & WritableByteChannel,
  reader: BufferedSocketReader,
  maxMessageSize: Long,
  maxFrameSize: Int,
  val subprotocol: Option[String],
  val upgradeRequest: Request[Body]
) extends ServerWebSocketConnection {

  private val closed = new AtomicBoolean(false)
  private val writableChannel: WritableByteChannel = channel

  override def sendText(text: String): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else WebSocketFrameWriter.writeText(writableChannel, text, mask = false, maxFrameSize)
  }

  override def sendBinary(data: Bytes): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else WebSocketFrameWriter.writeBinary(writableChannel, data, mask = false, maxFrameSize)
  }

  override def sendPing(data: Bytes): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else WebSocketFrameWriter.writePing(writableChannel, data, mask = false)
  }

  override def receive(): Eru[WebSocketError, WebSocketMessage] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else receiveLoop(None)
  }

  private def receiveLoop(
    fragmentState: Option[WebSocketFrameParser.FragmentationState]
  ): Eru[WebSocketError, WebSocketMessage] = {
    WebSocketFrameParser
      .parseMessageWithState(reader, maxMessageSize, expectMasked = true, fragmentState)
      .flatMap {
        case WebSocketFrameParser.ParseResult.Message(WebSocketFrame.Text(text, _)) =>
          Eru.succeed(WebSocketMessage.Text(text))

        case WebSocketFrameParser.ParseResult.Message(WebSocketFrame.Binary(data, _)) =>
          Eru.succeed(WebSocketMessage.Binary(data))

        case WebSocketFrameParser.ParseResult.Message(_) =>
          // Shouldn't happen - parseMessageWithState only returns Text or Binary messages
          Eru.fail(
            WebSocketError.InvalidFrame(
              "Unexpected message type from parser",
              "Internal error"
            )
          )

        case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Ping(data), state) =>
          // Handle ping and continue with fragmentation state
          WebSocketFrameWriter.writePong(writableChannel, data, mask = false).flatMap(_ => receiveLoop(state))

        case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Pong(_), state) =>
          // Ignore pong and continue with fragmentation state
          receiveLoop(state)

        case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Close(code, reason), _) =>
          // Close frame - complete the close handshake
          closed.set(true)
          WebSocketFrameWriter
            .writeClose(writableChannel, code, reason, mask = false)
            .attempt
            .flatMap(_ => Eru.fail(WebSocketError.ConnectionClosed(code, reason, clean = true)))

        case WebSocketFrameParser.ParseResult.ControlFrame(_, _) =>
          // Shouldn't happen - only Ping, Pong, Close are control frames
          Eru.fail(
            WebSocketError.InvalidFrame(
              "Unexpected control frame type",
              "Internal error"
            )
          )
      }
      .mapError {
        // Handle protocol violations by sending 1002 Protocol Error
        case e: WebSocketError.ProtocolViolation =>
          closed.set(true)
          WebSocketFrameWriter
            .writeClose(writableChannel, Some(e.closeCode), Some(e.message), mask = false)
            .attempt
            .unsafeRunSync()
          WebSocketError.ConnectionClosed(Some(e.closeCode), Some(e.message), clean = true)
        case e => e
      }
  }

  override def close(code: WebSocketCloseCode, reason: Option[String]): Eru[WebSocketError, Unit] = {
    if closed.compareAndSet(false, true) then {
      WebSocketFrameWriter
        .writeClose(writableChannel, Some(code), reason, mask = false)
        .flatMap { _ =>
          WebSocketFrameParser.parseFrame(reader, 125, expectMasked = true).attempt.map { _ =>
            try channel.close()
            catch { case _: Exception => () }
          }
        }
    } else {
      Eru.unit
    }
  }

  override def isOpen: Boolean = !closed.get() && channel.isOpen
}

/** Factory for creating server WebSocket connections.
  *
  * Used internally by NativeHttpServer when handling WebSocket upgrades.
  */
private[server] object NativeServerWebSocketConnection {

  /** Create a server WebSocket connection from an upgraded HTTP connection.
    */
  def create(
    channel: ReadableByteChannel & WritableByteChannel,
    reader: BufferedSocketReader,
    config: WebSocketServerConfig,
    subprotocol: Option[String],
    upgradeRequest: Request[Body]
  ): ServerWebSocketConnection = {
    new NativeServerWebSocketConnection(
      channel,
      reader,
      config.maxMessageSize,
      config.maxFrameSize,
      subprotocol,
      upgradeRequest
    )
  }
}
