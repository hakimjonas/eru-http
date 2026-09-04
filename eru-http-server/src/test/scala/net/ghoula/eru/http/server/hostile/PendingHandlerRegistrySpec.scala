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

/** Pending-handler registry hygiene under adversarial upgrades.
  *
  * `WebSocketServer.pendingHandlers` is a process-global, unbounded map bridging the upgrade
  * handler (which inserts) and `NativeHttpServer` (which claims after the handler returns a marked
  * 101). Historically the claim happened AFTER the 101 was written, so a client that aborted
  * between insert and write stranded an entry — pinning the handler, the WS config, and the full
  * request, repeatable without bound.
  *
  * The fix moved ownership: the entry is claimed before the write (a failed write just drops the
  * locally-held claim), the insert happens only after the handshake validates, and `dropPendingFor`
  * reclaims entries whose marked response never reaches the wire (e.g. a middleware discarding the
  * 101).
  *
  * Scenarios:
  *   1. a completed upgrade leaves the registry empty.
  *   2. a burst of clients aborting mid-handshake (RST before the 101 lands) leaves the registry
  *      empty — the pre-fix leak.
  *   3. a middleware that discards the marked 101 still gets its entry reclaimed.
  */
class PendingHandlerRegistrySpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  /** A WebSocket handler that closes immediately — these tests only care about the registry. */
  private val wsHandler: WebSocketHandler = conn => conn.close().attempt.map(_ => ())

  private val httpHandler: RequestHandler = _ =>
    Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("http-ok")))

  private def wsUpgradeRequest(host: String): Array[Byte] = {
    val key = Base64.getEncoder.encodeToString(Array.fill(16)(0x42.toByte))
    val req =
      "GET /ws HTTP/1.1\r\n" +
        s"Host: $host\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        s"Sec-WebSocket-Key: $key\r\n" +
        "Sec-WebSocket-Version: 13\r\n" +
        "\r\n"
    req.getBytes("US-ASCII")
  }

  /** Send an upgrade request, read the status line, and close cleanly. */
  private def upgradeAndReadStatus(host: String, port: Int): Option[Int] = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(2000)
      s.getOutputStream.write(wsUpgradeRequest(host))
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      statusLine.flatMap { line =>
        val parts = line.split(" ", 3)
        if parts.length >= 2 then parts(1).toIntOption else None
      }
    } finally Try(s.close()): Unit
  }

  /** Send an upgrade request and abort the connection with an RST (SO_LINGER 0) before the server
    * can deliver the 101 — the mid-handshake abort.
    */
  private def upgradeAndAbort(host: String, port: Int): Unit = {
    val s = new Socket(host, port)
    try {
      s.getOutputStream.write(wsUpgradeRequest(host))
      s.getOutputStream.flush()
      // RST, not FIN: the server's 101 write must fail.
      s.setSoLinger(true, 0)
    } finally Try(s.close()): Unit
  }

  /** Poll until the registry drains, or fail after the deadline. */
  private def awaitRegistryDrained(timeoutMs: Long = 5000, clue: String): Unit = {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var drained = WebSocketServer.pendingHandlerCount == 0
    while !drained && System.nanoTime() < deadline do {
      Thread.sleep(20)
      drained = WebSocketServer.pendingHandlerCount == 0
    }
    assert(drained, s"$clue: registry did not drain (${WebSocketServer.pendingHandlerCount} entries left)")
  }

  private def withWsServer[A](handler: RequestHandler)(body: ServerAddress => Eru[HttpError, A]): Eru[HttpError, A] = {
    val serverCfg = HttpServerConfig.localhost.withPort(0).copy(maxConnections = 128, acceptorThreads = 1)
    HttpServer.scoped(serverCfg)(handler) { server =>
      server.start.flatMap(body)
    }
  }

  // --------------------------------------------------------------------
  // Scenario 1: a completed upgrade leaves the registry empty
  // --------------------------------------------------------------------

  test("WS registry: completed upgrade claims its entry") {
    requireHostileMode()

    withWsServer(WebSocketServer.upgradeHandler(WebSocketServerConfig.default)(wsHandler)(httpHandler)) { addr =>
      Eru.effect {
        val status = upgradeAndReadStatus(addr.host, addr.port)
        assertEquals(status, Some(101), s"upgrade must succeed, got $status")
        awaitRegistryDrained(clue = "after a completed upgrade")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 2: burst of mid-handshake aborts — the pre-fix leak
  // --------------------------------------------------------------------

  test("WS registry: mid-handshake aborts do not strand entries") {
    requireHostileMode()

    withWsServer(WebSocketServer.upgradeHandler(WebSocketServerConfig.default)(wsHandler)(httpHandler)) { addr =>
      Eru.effect {
        val aborts = 50
        (1 to aborts).foreach { _ => upgradeAndAbort(addr.host, addr.port) }
        awaitRegistryDrained(clue = s"after $aborts mid-handshake aborts")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  // --------------------------------------------------------------------
  // Scenario 3: middleware discards the marked 101 — reclaim by request
  // --------------------------------------------------------------------

  test("WS registry: a discarded marked 101 is reclaimed") {
    requireHostileMode()

    // Wraps the upgrade handler and throws away the marked 101: the registry entry was inserted,
    // but the marker never reaches the wire — only dropPendingFor can reclaim it.
    val sabotaging: RequestHandler = request =>
      WebSocketServer
        .upgradeHandler(WebSocketServerConfig.default)(wsHandler)(httpHandler)(request)
        .flatMap { resp =>
          if resp.status == StatusCode.SwitchingProtocols then
            Eru.succeed(Response(StatusCode.BadRequest, Headers.empty, Body.Text("upgrade denied")))
          else Eru.succeed(resp)
        }

    withWsServer(sabotaging) { addr =>
      Eru.effect {
        val status = upgradeAndReadStatus(addr.host, addr.port)
        assertEquals(status, Some(400), s"sabotaged upgrade must answer 400, got $status")
        awaitRegistryDrained(clue = "after a discarded marked 101")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }
}
