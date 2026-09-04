package net.ghoula.eru.http.server

import java.nio.channels.{ReadableByteChannel, WritableByteChannel}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.concurrent.duration.Duration as SDuration

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.*
import net.ghoula.eru.prelude.*

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
  private[server] def isWebSocketUpgrade(request: Request[Body]): Boolean =
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

  /** In-flight upgrade handoffs, keyed by the marker the upgrade response carries.
    *
    * Lifetime contract: an entry is inserted only after the handshake validates, and it leaves the
    * map when `NativeHttpServer` claims it (immediately on handler return, before the 101 is
    * written) or when `dropPendingFor` reclaims it (handler failure, or a non-101 answer for the
    * same request). Entries therefore live for the microseconds between insert and claim; the map
    * is process-global, so a wrapping middleware that delegates to the upgrade handler and then
    * never returns could still orphan an entry — `MaxPendingHandlers` is the circuit breaker that
    * keeps even that pathological case bounded.
    */
  private val pendingHandlers = new ConcurrentHashMap[String, PendingWebSocket]()
  private val handlerIdCounter = new AtomicLong(0)

  /** Circuit breaker for the process-global registry. Reaching this many simultaneous in-flight
    * handoffs means the reclaim paths have failed; the registry is dropped wholesale (live entries
    * would be re-requested by their owners within microseconds anyway).
    */
  private val MaxPendingHandlers = 10_000

  /** Current registry size. Test hook for the leak assertions.
    */
  private[server] def pendingHandlerCount: Int = pendingHandlers.size()

  /** Handle a WebSocket upgrade request.
    *
    * This creates the upgrade response and registers the handler for later execution. The
    * NativeHttpServer detects 101 responses, claims the registered handler, and completes the
    * upgrade.
    *
    * If `config.allowedOrigins` is `Some(list)`, the request's `Origin` header MUST exactly match
    * one of the listed values (case-insensitive). Mismatch / missing Origin → 403 Forbidden,
    * short-circuiting the upgrade. The pending-handler registry is NOT touched on rejection so no
    * state leaks.
    */
  private def handleWebSocketUpgrade(
    request: Request[Body],
    config: WebSocketServerConfig,
    wsHandler: WebSocketHandler
  ): Eru[HttpError, Response[Body]] =
    checkOrigin(request, config) match {
      case Some(rejection) => Eru.succeed(rejection)
      case None =>
        val requestedSubprotocols = WebSocketHandshake.extractSubprotocols(request)
        val selectedSubprotocol = selectSubprotocol(requestedSubprotocols, config.allowedSubprotocols)
        val handlerId = handlerIdCounter.incrementAndGet().toString

        for {
          key <- WebSocketHandshake.extractKey(request).mapError { wsError =>
            HttpError.InvalidRequest(InvalidRequest(wsError.errorMessage, "RFC 6455"))
          }
          response <- WebSocketHandshake.createUpgradeResponse(key, selectedSubprotocol)
          // Register only after the handshake validates: the insert is the last step before the
          // marked response is returned, and everything between insert and return is pure — no
          // effect boundary where an interruption could land and strand the entry.
          _ = {
            if pendingHandlers.size() >= MaxPendingHandlers then pendingHandlers.clear()
            pendingHandlers.put(handlerId, PendingWebSocket(wsHandler, config, request, selectedSubprotocol))
          }
        } yield response.copy(
          headers = response.headers
            .unsafeAdd("X-WebSocket-Handler-Id", HeaderValue.unsafeFromString(handlerId))
        )
    }

  /** Check the `Origin` header against `config.allowedOrigins`. Returns `None` if the check passes
    * (or is disabled), `Some(Response)` with a 403 if it fails.
    *
    * Match is case-insensitive on the whole header value — scheme and host are case-insensitive per
    * RFC 3986 §3.1 / §3.2, and per-port precision is preserved by comparing the full value
    * lowercased. A browser Origin has the shape `scheme://host[:port]`, so this comparison covers
    * the normal case.
    */
  private def checkOrigin(request: Request[Body], config: WebSocketServerConfig): Option[Response[Body]] =
    config.allowedOrigins match {
      case None => None
      case Some(allowed) =>
        val origin = request.headers.getFirst(HeaderNames.Origin).map(_.value.trim.toLowerCase)
        val allowedLower = allowed.map(_.trim.toLowerCase).toSet
        origin match {
          case Some(o) if allowedLower.contains(o) => None
          case _ =>
            val body = Body.Text("Forbidden: WebSocket Origin not allowed", None, Charset.UTF8)
            Some(Response(StatusCode.Forbidden, Headers.empty, body))
        }
    }

  /** Retrieve and remove a pending WebSocket handler by ID.
    *
    * Called by NativeHttpServer as soon as the handler returns a marked 101 — before the response
    * is written — so a failed or interrupted write cannot strand the entry in the registry.
    */
  private[server] def retrieveHandler(handlerId: String): Option[PendingWebSocket] = {
    Option(pendingHandlers.remove(handlerId))
  }

  /** Reclaim any entry registered while handling `request`, comparing by reference identity.
    *
    * Covers composition paths the id-based claim cannot see: a middleware wrapping `upgradeHandler`
    * can discard a marked 101 after the registry insert (the id then never reaches the wire), and a
    * failing handler run may have inserted before failing. Entries live in the registry for
    * microseconds, so this sweep only ever visits in-flight or orphaned entries.
    */
  private[server] def dropPendingFor(request: Request[Body]): Unit = {
    val it = pendingHandlers.values().iterator()
    while it.hasNext do {
      if it.next().request eq request then it.remove()
    }
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
  *
  * `pingInterval` / `pongTimeout` enforcement. When both are `Some(...)`, the connection forks a
  * per-instance watchdog fiber that:
  *   - On inbound silence ≥ `pingInterval` with no ping outstanding, sends a Ping.
  *   - On ping outstanding ≥ `pongTimeout`, sets `closed`, calls `channel.close()` (which unparks
  *     the blocked receive loop), and exits.
  *   - On `closed == true`, exits.
  *
  * The watchdog creates a second writer. To prevent byte interleaving on concurrent frame writes
  * (handler `sendX` + watchdog `writePing` + receive-loop auto-Pong), every frame write in this
  * class goes through `locked(...)` which holds `writeLock` across the channel write. The lock is
  * NOT held across reads — doing so would deadlock the watchdog behind a blocked read.
  *
  * When the watchdog fires a pong-timeout it closes the channel; the blocked receive read then
  * surfaces as EOF or a socket-closed exception and is translated by `receiveLoop`'s `mapError`
  * into `ConnectionClosed(GoingAway, "Pong timeout")` so user code observes the timeout naturally.
  */
private[server] final class NativeServerWebSocketConnection(
  channel: ReadableByteChannel & WritableByteChannel,
  reader: BufferedSocketReader,
  maxMessageSize: Long,
  maxFrameSize: Int,
  pingInterval: Option[SDuration],
  pongTimeout: Option[SDuration],
  closeTimeout: SDuration,
  val subprotocol: Option[String],
  val upgradeRequest: Request[Body]
)(using runtime: EruRuntime)
    extends ServerWebSocketConnection {

  private val closed = new AtomicBoolean(false)
  private val writableChannel: WritableByteChannel = channel

  /** Serializes every frame write to the channel. Without it, the watchdog's Ping can interleave
    * bytes with a handler's `writeText` mid-frame and corrupt the wire.
    */
  private val writeLock: AnyRef = new Object

  /** Monotonic nanos of the most recent inbound frame parse (any opcode). Updated by `receiveLoop`
    * on each successful parse.
    */
  private val lastInboundNanos = new AtomicLong(System.nanoTime())

  /** Monotonic nanos of the currently-outstanding watchdog Ping (0L = no ping in flight). Cleared
    * on any inbound Pong.
    */
  private val pingInFlightNanos = new AtomicLong(0L)

  /** Handle to the watchdog fiber (None when pingInterval or pongTimeout is None, or before the
    * fiber has been registered via `attachWatchdog`). Interrupted on `close()`.
    */
  private val watchdogFiber: AtomicReference[Option[Fiber[?, ?]]] = new AtomicReference(None)

  /** Serialize a writer effect behind `writeLock`. Safe for the handler, the receive-loop
    * auto-Pong, the watchdog Ping-send, and `close`'s writeClose. The inner effect is reduced to a
    * `Result` inside the lock (so the synchronized block never sees an interpreter-thrown exception
    * escape), and rehydrated outside.
    */
  private def locked[A](effect: Eru[WebSocketError, A]): Eru[WebSocketError, A] =
    Eru.effect {
      writeLock.synchronized {
        effect.attempt.unsafeRunSync()
      }
    }.mapError { t =>
      WebSocketError.NetworkError(s"Error writing to socket: ${t.getMessage}", Some(t))
    }.flatMap {
      case Result.Success(a) => Eru.succeed(a)
      case Result.Failure(e) => Eru.fail(e)
    }

  override def sendText(text: String): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else locked(WebSocketFrameWriter.writeText(writableChannel, text, mask = false, maxFrameSize))
  }

  override def sendBinary(data: Bytes): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else locked(WebSocketFrameWriter.writeBinary(writableChannel, data, mask = false, maxFrameSize))
  }

  override def sendPing(data: Bytes): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else locked(WebSocketFrameWriter.writePing(writableChannel, data, mask = false))
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
      .flatMap { result =>
        lastInboundNanos.set(System.nanoTime())
        result match {
          case WebSocketFrameParser.ParseResult.Message(WebSocketFrame.Text(text, _)) =>
            Eru.succeed(WebSocketMessage.Text(text))

          case WebSocketFrameParser.ParseResult.Message(WebSocketFrame.Binary(data, _)) =>
            Eru.succeed(WebSocketMessage.Binary(data))

          case WebSocketFrameParser.ParseResult.Message(_) =>
            Eru.fail(
              WebSocketError.InvalidFrame(
                "Unexpected message type from parser",
                "Internal error"
              )
            )

          case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Ping(data), state) =>
            locked(WebSocketFrameWriter.writePong(writableChannel, data, mask = false))
              .flatMap(_ => receiveLoop(state))

          case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Pong(_), state) =>
            pingInFlightNanos.set(0L)
            receiveLoop(state)

          case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Close(code, reason), _) =>
            closed.set(true)
            interruptWatchdog()
            locked(WebSocketFrameWriter.writeClose(writableChannel, code, reason, mask = false)).attempt
              .flatMap(_ => Eru.fail(WebSocketError.ConnectionClosed(code, reason, clean = true)))

          case WebSocketFrameParser.ParseResult.ControlFrame(_, _) =>
            Eru.fail(
              WebSocketError.InvalidFrame(
                "Unexpected control frame type",
                "Internal error"
              )
            )
        }
      }
      .mapError {
        case e: WebSocketError.ProtocolViolation =>
          closed.set(true)
          interruptWatchdog()
          locked(
            WebSocketFrameWriter.writeClose(writableChannel, Some(e.closeCode), Some(e.message), mask = false)
          ).attempt
            .unsafeRunSync()
          WebSocketError.ConnectionClosed(Some(e.closeCode), Some(e.message), clean = true)

        case _: WebSocketError.ConnectionClosed if closed.get() =>
          WebSocketError.ConnectionClosed(Some(WebSocketCloseCode.GoingAway), Some("Pong timeout"), clean = false)
        case _: WebSocketError.NetworkError if closed.get() =>
          WebSocketError.ConnectionClosed(Some(WebSocketCloseCode.GoingAway), Some("Pong timeout"), clean = false)

        case e => e
      }
  }

  override def close(code: WebSocketCloseCode, reason: Option[String]): Eru[WebSocketError, Unit] = {
    if closed.compareAndSet(false, true) then {
      interruptWatchdog()
      locked(WebSocketFrameWriter.writeClose(writableChannel, Some(code), reason, mask = false)).flatMap { _ =>
        // Bounded wait for the peer's close echo (RFC 6455 closing handshake). A client that
        // ignores our Close must not drain the socket past closeTimeout — the deadline interrupts
        // the read (closing the channel), and the explicit close below is idempotent.
        WebSocketFrameParser
          .parseFrame(reader, 125, expectMasked = true)
          .attempt
          .timeout(java.time.Duration.ofMillis(closeTimeout.toMillis))
          .attempt
          .map { _ =>
            try channel.close()
            catch { case _: Exception => () }
          }
      }
    } else {
      Eru.unit
    }
  }

  override def isOpen: Boolean = !closed.get() && channel.isOpen

  private def interruptWatchdog(): Unit = {
    val prev = watchdogFiber.getAndSet(None)
    prev.foreach { fiber =>
      val cause = InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(()))
      fiber.interrupt(cause).attempt.unsafeRunSync(): Unit
    }
  }

  /** Returns the watchdog effect if `pingInterval` and `pongTimeout` are both `Some`, else `None`.
    * Package-private so the `create` factory can fork it once the instance exists.
    */
  private[server] def watchdogEffect: Option[Eru[Nothing, Unit]] =
    (pingInterval, pongTimeout) match {
      case (Some(pi), Some(pt)) if pi.isFinite && pt.isFinite && pi.toMillis > 0 && pt.toMillis > 0 =>
        Some(runWatchdog(pi.toMillis, pt.toMillis))
      case _ => None
    }

  private[server] def registerWatchdog(fiber: Fiber[?, ?]): Unit =
    watchdogFiber.set(Some(fiber))

  /** The periodic watchdog. Wake ~coarsely every `min(pingInterval, pongTimeout) / 4` (floor 50ms,
    * ceiling 1s) and decide among: do-nothing, send-ping, fire-timeout-close, exit. Tail-recursive;
    * no `return`.
    *
    * On pong-timeout, `closed` is set BEFORE the channel is closed so the blocked read unparks,
    * propagates up through `parseMessageWithState`, and is mapped in `receiveLoop`'s `mapError`
    * into `ConnectionClosed(GoingAway, "Pong timeout")`. A ping write that fails mid-flight is
    * treated as fatal for this connection: the channel is closed.
    */
  private def runWatchdog(pingIntervalMs: Long, pongTimeoutMs: Long): Eru[Nothing, Unit] = {
    val tickMs: Long = {
      val raw = Math.min(pingIntervalMs, pongTimeoutMs) / 4
      Math.max(50L, Math.min(1000L, raw))
    }
    val pingIntervalNanos = pingIntervalMs * 1_000_000L
    val pongTimeoutNanos = pongTimeoutMs * 1_000_000L

    def step(): Eru[Nothing, Unit] =
      runtime.sleep(java.time.Duration.ofMillis(tickMs)).flatMap { _ =>
        if closed.get() then Eru.unit
        else {
          val now = System.nanoTime()
          val lastIn = lastInboundNanos.get()
          val pingAt = pingInFlightNanos.get()

          val decision: WatchdogDecision =
            if pingAt != 0L && (now - pingAt) >= pongTimeoutNanos then WatchdogDecision.FireTimeout
            else if pingAt == 0L && (now - lastIn) >= pingIntervalNanos then WatchdogDecision.SendPing
            else WatchdogDecision.Idle

          decision match {
            case WatchdogDecision.FireTimeout =>
              closed.set(true)
              Eru.effect {
                try channel.close()
                catch { case _: Exception => () }
              }.attempt.flatMap(_ => Eru.unit)
            case WatchdogDecision.SendPing =>
              pingInFlightNanos.set(System.nanoTime())
              locked(WebSocketFrameWriter.writePing(writableChannel, Bytes.empty, mask = false)).attempt.flatMap {
                case Result.Success(_) => step()
                case Result.Failure(_) =>
                  closed.set(true)
                  Eru.effect {
                    try channel.close()
                    catch { case _: Exception => () }
                  }.attempt.flatMap(_ => Eru.unit)
              }
            case WatchdogDecision.Idle => step()
          }
        }
      }

    step()
  }

  private enum WatchdogDecision {
    case Idle, SendPing, FireTimeout
  }
}

/** Factory for creating server WebSocket connections.
  *
  * Used internally by NativeHttpServer when handling WebSocket upgrades.
  */
private[server] object NativeServerWebSocketConnection {

  /** Create a server WebSocket connection from an upgraded HTTP connection.
    *
    * If the config has BOTH `pingInterval` and `pongTimeout` set to finite positive durations, a
    * per-connection watchdog fiber is forked here. It is interrupted when the connection closes
    * (either via `close()`, peer Close frame, or the watchdog itself firing a pong-timeout close).
    */
  def create(
    channel: ReadableByteChannel & WritableByteChannel,
    reader: BufferedSocketReader,
    config: WebSocketServerConfig,
    subprotocol: Option[String],
    upgradeRequest: Request[Body]
  )(using runtime: EruRuntime): ServerWebSocketConnection = {
    val conn = new NativeServerWebSocketConnection(
      channel,
      reader,
      config.maxMessageSize,
      config.maxFrameSize,
      config.pingInterval,
      config.pongTimeout,
      config.closeTimeout,
      subprotocol,
      upgradeRequest
    )
    conn.watchdogEffect.foreach { effect =>
      val fiber = runtime.forkDaemon(effect).unsafeRunSync()
      conn.registerWatchdog(fiber)
    }
    conn
  }
}
