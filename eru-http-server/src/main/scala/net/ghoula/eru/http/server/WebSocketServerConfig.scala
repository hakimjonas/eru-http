package net.ghoula.eru.http.server

import scala.concurrent.duration.*

/** Configuration for WebSocket server support.
  *
  * @param maxMessageSize
  *   Maximum size of incoming messages (to prevent memory exhaustion)
  * @param maxFrameSize
  *   Maximum size of outgoing frame payloads (0 for no fragmentation)
  * @param pingInterval
  *   Interval for sending Ping frames to clients (None to disable)
  * @param pongTimeout
  *   Timeout for receiving Pong after sending Ping (None to disable)
  * @param closeTimeout
  *   Timeout for completing the close handshake
  * @param allowedSubprotocols
  *   List of subprotocols the server supports (empty to accept any)
  */
final case class WebSocketServerConfig(
  maxMessageSize: Long = 10 * 1024 * 1024, // 10MB
  maxFrameSize: Int = 0, // 0 = no fragmentation
  pingInterval: Option[Duration] = Some(30.seconds),
  pongTimeout: Option[Duration] = Some(10.seconds),
  closeTimeout: Duration = 5.seconds,
  allowedSubprotocols: List[String] = Nil
)

object WebSocketServerConfig {

  /** Default WebSocket server configuration.
    */
  val default: WebSocketServerConfig = WebSocketServerConfig()

  /** Configuration for low-latency scenarios.
    */
  val lowLatency: WebSocketServerConfig = WebSocketServerConfig(
    maxMessageSize = 1 * 1024 * 1024, // 1MB
    pingInterval = Some(10.seconds),
    pongTimeout = Some(5.seconds),
    closeTimeout = 2.seconds
  )

  /** Configuration for high-throughput scenarios with large messages.
    */
  val highThroughput: WebSocketServerConfig = WebSocketServerConfig(
    maxMessageSize = 100 * 1024 * 1024, // 100MB
    maxFrameSize = 64 * 1024, // Fragment at 64KB
    pingInterval = Some(60.seconds),
    pongTimeout = Some(30.seconds)
  )
}
