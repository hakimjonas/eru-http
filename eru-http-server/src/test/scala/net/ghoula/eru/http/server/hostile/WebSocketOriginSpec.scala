package net.ghoula.eru.http.server.hostile

import java.io.{BufferedReader, InputStreamReader}
import java.net.Socket
import java.util.Base64
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Phase D.11: WebSocket Origin allowlist.
  *
  * Prior to D.11 the WebSocket upgrade handshake had no Origin-header check at all. A browser that
  * loaded `evil.com` in a tab could open a WebSocket to `bank.com` using the user's cookies — RFC
  * 6455 §10.2 defines Origin as the browser's CSRF-mitigation signal, and every well-known WS
  * server (nginx, cloudflare, socket.io, etc.) exposes an Origin allowlist.
  *
  * D.11 adds `WebSocketServerConfig.allowedOrigins: Option[List[String]]`:
  *   - `None` (default) — no Origin check, current behavior.
  *   - `Some(list)` — the `Origin` request header MUST exactly match one of the listed values
  *     (case-insensitive for scheme+host; port/path strict). Missing or non-matching Origin → 403
  *     Forbidden with TCP close.
  *
  * Scenarios:
  *   1. allowlist-configured, matching Origin → 101 Switching Protocols.
  *   2. allowlist-configured, non-matching Origin → 403 Forbidden.
  *   3. allowlist-configured, missing Origin → 403.
  *   4. no allowlist (None, default) → no Origin check (backwards compat).
  */
class WebSocketOriginSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  /** A WebSocket handler that does nothing — the test only cares about the handshake status. */
  private val wsHandler: net.ghoula.eru.http.server.WebSocketHandler = conn => conn.close().attempt.map(_ => ())

  private val httpHandler: RequestHandler = _ =>
    Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("http-ok")))

  /** Build a WebSocket upgrade request with an optional Origin header. Returns the raw bytes. */
  private def wsUpgradeRequest(host: String, origin: Option[String]): Array[Byte] = {
    val key = Base64.getEncoder.encodeToString(Array.fill(16)(0x42.toByte))
    val originLine = origin.map(o => s"Origin: $o\r\n").getOrElse("")
    val req =
      "GET /ws HTTP/1.1\r\n" +
        s"Host: $host\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        s"Sec-WebSocket-Key: $key\r\n" +
        "Sec-WebSocket-Version: 13\r\n" +
        originLine +
        "\r\n"
    req.getBytes("US-ASCII")
  }

  private def sendUpgradeAndReadStatus(
    host: String,
    port: Int,
    origin: Option[String]
  ): Option[Int] = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(2000)
      s.getOutputStream.write(wsUpgradeRequest(host, origin))
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      statusLine.flatMap { line =>
        val parts = line.split(" ", 3)
        if parts.length >= 2 then parts(1).toIntOption else None
      }
    } finally Try(s.close()): Unit
  }

  /** Scope: start a fresh HttpServer with the given WebSocket config for the duration of `body`. */
  private def withWsServer[A](wsCfg: WebSocketServerConfig)(
    body: ServerAddress => Eru[HttpError, A]
  ): Eru[HttpError, A] = {
    val serverCfg = HttpServerConfig.localhost.withPort(0).copy(maxConnections = 64, acceptorThreads = 1)
    val wrappedHandler = WebSocketServer.upgradeHandler(wsCfg)(wsHandler)(httpHandler)
    HttpServer.scoped(serverCfg)(wrappedHandler) { server =>
      server.start.flatMap(body)
    }
  }

  // --------------------------------------------------------------------
  // Scenario 1: allowlist configured, Origin matches → 101
  // --------------------------------------------------------------------

  test("WS Origin: allowlisted Origin is accepted with 101") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      allowedOrigins = Some(List("https://app.example.com"))
    )
    withWsServer(cfg) { addr =>
      Eru.effect {
        val status = sendUpgradeAndReadStatus(addr.host, addr.port, Some("https://app.example.com"))
        assertEquals(status, Some(101), s"allowed Origin must get 101, got $status")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 2: allowlist configured, Origin mismatch → 403
  // --------------------------------------------------------------------

  test("WS Origin: mismatched Origin is rejected with 403") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      allowedOrigins = Some(List("https://app.example.com"))
    )
    withWsServer(cfg) { addr =>
      Eru.effect {
        val status = sendUpgradeAndReadStatus(addr.host, addr.port, Some("https://evil.com"))
        assertEquals(status, Some(403), s"mismatched Origin must get 403, got $status")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 3: allowlist configured, missing Origin → 403
  // --------------------------------------------------------------------

  test("WS Origin: missing Origin header is rejected with 403 when allowlist is configured") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      allowedOrigins = Some(List("https://app.example.com"))
    )
    withWsServer(cfg) { addr =>
      Eru.effect {
        val status = sendUpgradeAndReadStatus(addr.host, addr.port, origin = None)
        assertEquals(status, Some(403), s"missing Origin must get 403, got $status")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 4: no allowlist (default) → Origin is ignored (backwards compat)
  // --------------------------------------------------------------------

  test("WS Origin: default config (None) accepts any Origin / missing Origin") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default // allowedOrigins = None
    withWsServer(cfg) { addr =>
      Eru.effect {
        val evilOrigin = sendUpgradeAndReadStatus(addr.host, addr.port, Some("https://evil.com"))
        assertEquals(evilOrigin, Some(101), s"default must accept arbitrary Origin; got $evilOrigin")
        val noOrigin = sendUpgradeAndReadStatus(addr.host, addr.port, origin = None)
        assertEquals(noOrigin, Some(101), s"default must accept missing Origin; got $noOrigin")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 5: multiple allowlist entries — any match succeeds
  // --------------------------------------------------------------------

  test("WS Origin: multi-entry allowlist — any matching entry succeeds") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      allowedOrigins = Some(List("https://a.example.com", "https://b.example.com"))
    )
    withWsServer(cfg) { addr =>
      Eru.effect {
        val a = sendUpgradeAndReadStatus(addr.host, addr.port, Some("https://a.example.com"))
        val b = sendUpgradeAndReadStatus(addr.host, addr.port, Some("https://b.example.com"))
        val c = sendUpgradeAndReadStatus(addr.host, addr.port, Some("https://c.example.com"))
        assertEquals(a, Some(101), s"first allowlist entry must match; got $a")
        assertEquals(b, Some(101), s"second allowlist entry must match; got $b")
        assertEquals(c, Some(403), s"non-listed origin must be rejected; got $c")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }
}
