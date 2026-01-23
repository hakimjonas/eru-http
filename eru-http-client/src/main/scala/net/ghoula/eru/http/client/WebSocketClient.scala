package net.ghoula.eru.http.client

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.*

/** WebSocket client for establishing WebSocket connections.
  *
  * Implements RFC 6455 WebSocket protocol with support for:
  *   - ws:// (plain) and wss:// (TLS) connections
  *   - Text and binary messages
  *   - Automatic ping/pong handling
  *   - Message fragmentation
  *   - Subprotocol negotiation
  */
trait WebSocketClient {

  /** Connect to a WebSocket server.
    *
    * Performs the WebSocket opening handshake (HTTP Upgrade) and returns a connection for
    * bidirectional communication.
    *
    * @param uri
    *   The WebSocket URI (ws:// or wss://)
    * @param additionalHeaders
    *   Additional headers to include in the upgrade request
    * @return
    *   A WebSocket connection or an error
    */
  def connect(
    uri: Uri,
    additionalHeaders: Headers = Headers.empty
  ): Eru[WebSocketError | HttpError, WebSocketConnection]

  /** Shuts down the client gracefully.
    */
  def shutdown: Eru[Nothing, Unit]
}

/** Active WebSocket connection for bidirectional communication.
  *
  * Provides methods for sending and receiving messages. The connection automatically handles:
  *   - Ping/pong frames (responds to pings automatically)
  *   - Message fragmentation (reassembles fragmented messages)
  *   - UTF-8 validation for text messages
  *
  * The connection is NOT thread-safe. Concurrent sends or concurrent receives may result in
  * interleaved frames. For concurrent access, use external synchronization or a message queue.
  */
trait WebSocketConnection {

  /** Send a text message.
    *
    * @param text
    *   The text to send (will be encoded as UTF-8)
    * @return
    *   Success or an error
    */
  def sendText(text: String): Eru[WebSocketError, Unit]

  /** Send a binary message.
    *
    * @param data
    *   The binary data to send
    * @return
    *   Success or an error
    */
  def sendBinary(data: Bytes): Eru[WebSocketError, Unit]

  /** Send a ping frame.
    *
    * @param data
    *   Optional application data (max 125 bytes)
    * @return
    *   Success or an error
    */
  def sendPing(data: Bytes = Bytes.empty): Eru[WebSocketError, Unit]

  /** Receive the next message.
    *
    * Blocks until a complete message is received, handling control frames automatically:
    *   - Ping frames: Sends Pong response automatically
    *   - Pong frames: Ignored (or passed to pong handler if configured)
    *   - Close frames: Returns ConnectionClosed error
    *
    * @return
    *   The received message or an error
    */
  def receive(): Eru[WebSocketError, WebSocketMessage]

  /** Close the WebSocket connection gracefully.
    *
    * Initiates the WebSocket closing handshake by sending a Close frame and waiting for the
    * server's Close frame response.
    *
    * @param code
    *   The close status code (default: NormalClosure)
    * @param reason
    *   Optional reason text (max 123 bytes UTF-8)
    * @return
    *   Success or an error
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
}

object WebSocketClient {

  /** Create a new WebSocket client with default configuration.
    */
  def create(using runtime: EruRuntime): Eru[HttpError, WebSocketClient] =
    create(WebSocketClientConfig.default)

  /** Create a new WebSocket client with the specified configuration.
    */
  def create(config: WebSocketClientConfig)(using runtime: EruRuntime): Eru[HttpError, WebSocketClient] =
    NativeWebSocketClient.create(config)

  /** Connect to a WebSocket server using a scoped connection.
    *
    * The connection is automatically closed when the handler completes or fails.
    *
    * @param uri
    *   The WebSocket URI (ws:// or wss://)
    * @param handler
    *   A function that uses the connection
    * @return
    *   The result of the handler
    */
  def scoped[A](uri: Uri)(
    handler: WebSocketConnection => Eru[WebSocketError | HttpError, A]
  )(using runtime: EruRuntime): Eru[WebSocketError | HttpError, A] =
    scoped(uri, WebSocketClientConfig.default, Headers.empty)(handler)

  /** Connect to a WebSocket server using a scoped connection with custom configuration.
    *
    * @param uri
    *   The WebSocket URI (ws:// or wss://)
    * @param config
    *   The client configuration
    * @param additionalHeaders
    *   Additional headers to include in the upgrade request
    * @param handler
    *   A function that uses the connection
    * @return
    *   The result of the handler
    */
  def scoped[A](uri: Uri, config: WebSocketClientConfig, additionalHeaders: Headers)(
    handler: WebSocketConnection => Eru[WebSocketError | HttpError, A]
  )(using runtime: EruRuntime): Eru[WebSocketError | HttpError, A] = {
    for {
      client <- create(config).mapError(e => e: WebSocketError | HttpError)
      conn <- client.connect(uri, additionalHeaders)
      result <- handler(conn).attempt
      _ <- conn.close().attempt
      _ <- client.shutdown
      finalResult <- result match {
        case Result.Success(a) => Eru.succeed(a)
        case Result.Failure(err) => Eru.fail(err)
      }
    } yield finalResult
  }
}
