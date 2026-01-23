package net.ghoula.eru.http.client

import scala.concurrent.duration.*

import net.ghoula.eru.http.TlsConfig

/** Configuration for WebSocket client connections.
  *
  * @param connectTimeout
  *   Timeout for establishing the TCP connection
  * @param handshakeTimeout
  *   Timeout for completing the WebSocket handshake (upgrade request/response)
  * @param maxMessageSize
  *   Maximum size of incoming messages (to prevent memory exhaustion)
  * @param maxFrameSize
  *   Maximum size of outgoing frame payloads (0 for no fragmentation)
  * @param pingInterval
  *   Interval for sending Ping frames (None to disable automatic pings)
  * @param pongTimeout
  *   Timeout for receiving Pong after sending Ping (None to disable timeout)
  * @param tlsConfig
  *   TLS configuration for wss:// connections (uses system default if None)
  * @param subprotocols
  *   List of subprotocols to request (in order of preference)
  */
final case class WebSocketClientConfig(
  connectTimeout: Duration = 30.seconds,
  handshakeTimeout: Duration = 30.seconds,
  maxMessageSize: Long = 10 * 1024 * 1024, // 10MB
  maxFrameSize: Int = 0, // 0 = no fragmentation
  pingInterval: Option[Duration] = Some(30.seconds),
  pongTimeout: Option[Duration] = Some(10.seconds),
  tlsConfig: Option[TlsConfig] = None,
  subprotocols: List[String] = Nil
)

object WebSocketClientConfig {

  /** Default WebSocket client configuration.
    */
  val default: WebSocketClientConfig = WebSocketClientConfig()

  /** Configuration optimized for low latency.
    */
  val lowLatency: WebSocketClientConfig = WebSocketClientConfig(
    connectTimeout = 5.seconds,
    handshakeTimeout = 5.seconds,
    maxMessageSize = 1 * 1024 * 1024, // 1MB
    pingInterval = Some(10.seconds),
    pongTimeout = Some(5.seconds)
  )

  /** Configuration for high-throughput scenarios with large messages.
    */
  val highThroughput: WebSocketClientConfig = WebSocketClientConfig(
    maxMessageSize = 100 * 1024 * 1024, // 100MB
    maxFrameSize = 64 * 1024, // Fragment at 64KB
    pingInterval = Some(60.seconds),
    pongTimeout = Some(30.seconds)
  )
}
