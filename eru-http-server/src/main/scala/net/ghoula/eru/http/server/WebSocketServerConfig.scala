package net.ghoula.eru.http.server

import scala.concurrent.duration.*

/** Configuration for WebSocket server support.
  *
  * @param maxMessageSize
  *   Maximum size of incoming messages (to prevent memory exhaustion). Default 10 MB.
  * @param maxFrameSize
  *   Maximum size of outgoing frame payloads (0 for no fragmentation)
  * @param pingInterval
  *   Interval of inbound silence after which the server sends a Ping. `None` disables proactive
  *   pings entirely — a silent client parks the receive fiber until TCP teardown. With the default
  *   `Some(30.seconds)`, a connection idle for 30s gets a Ping; failing to answer within
  *   `pongTimeout` triggers a server-initiated close. This mirrors the common production posture
  *   for WebSocket servers: middleboxes (NATs, load balancers) silently drop idle TCP flows, so
  *   without proactive pings a "dead" WS peer is never detected.
  * @param pongTimeout
  *   After a server Ping is sent, how long to wait for the Pong. `None` disables the timeout — if
  *   `pingInterval` is set but `pongTimeout` is `None`, Pings still fire but a missing Pong never
  *   closes the connection. Default `Some(10.seconds)`. Fires a close with
  *   `WebSocketCloseCode.GoingAway` when exceeded. Setting both `pingInterval` and `pongTimeout` to
  *   `None` avoids forking the watchdog fiber entirely (zero runtime cost for callers that manage
  *   liveness themselves).
  * @param closeTimeout
  *   Timeout for completing the close handshake
  * @param allowedSubprotocols
  *   List of subprotocols the server supports (empty to accept any)
  * @param allowedOrigins
  *   Optional allowlist of acceptable `Origin` header values. `None` (default) means no Origin
  *   check — backwards-compatible, appropriate for non-browser WebSocket peers where Origin carries
  *   no trust. `Some(list)` means the upgrade MUST carry an `Origin` header exactly matching one of
  *   the listed values (case-insensitive); otherwise the handshake is rejected with 403 Forbidden.
  *   Origin is the browser's CSRF-mitigation signal (RFC 6455 §10.2): without this check a
  *   malicious page at evil.com can open a WS to bank.com using the user's cookies.
  */
final case class WebSocketServerConfig(
  maxMessageSize: Long = 10 * 1024 * 1024,
  maxFrameSize: Int = 0,
  pingInterval: Option[Duration] = Some(30.seconds),
  pongTimeout: Option[Duration] = Some(10.seconds),
  closeTimeout: Duration = 5.seconds,
  allowedSubprotocols: List[String] = Nil,
  allowedOrigins: Option[List[String]] = None
)

object WebSocketServerConfig {

  /** Default WebSocket server configuration.
    */
  val default: WebSocketServerConfig = WebSocketServerConfig()

  /** Configuration for high-throughput scenarios with large messages.
    *
    * Uses a 100 MB message-size limit and 64 KB frame fragmentation.
    */
  val highThroughput: WebSocketServerConfig = WebSocketServerConfig(
    maxMessageSize = 100 * 1024 * 1024,
    maxFrameSize = 64 * 1024,
    pingInterval = Some(60.seconds),
    pongTimeout = Some(30.seconds)
  )
}
