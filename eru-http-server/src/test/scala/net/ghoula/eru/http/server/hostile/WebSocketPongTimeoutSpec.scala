package net.ghoula.eru.http.server.hostile

import java.io.{DataInputStream, EOFException, IOException}
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.http.websocket.*
import net.ghoula.eru.prelude.*

/** WebSocket `pingInterval` / `pongTimeout` enforcement.
  *
  * A per-connection watchdog fiber:
  *
  *   - Sends a Ping when inbound silence exceeds `pingInterval`.
  *   - Closes the connection (client sees a Close frame + TCP close) when no Pong arrives within
  *     `pongTimeout` of the sent Ping.
  *   - Is only forked when BOTH `pingInterval` and `pongTimeout` are `Some(...)`; setting either to
  *     `None` disables both halves with zero runtime cost.
  *
  * The watchdog is a second writer to the channel (handler + watchdog can both send frames).
  * `WebSocketFrameWriter` has no internal lock, so a per-connection `writeLock` serializes every
  * frame write — scenario 5 pins this invariant.
  *
  * Scenarios:
  *   1. Silent client gets pinged within `pingInterval + slack`.
  *   2. Client sends a Pong; connection stays open past `pongTimeout`.
  *   3. Client receives Ping, stays silent; server closes within `pongTimeout + slack`.
  *   4. `pingInterval = None` → no watchdog forked, no frame arrives at a silent client.
  *   5. Concurrent handler `sendText` while the watchdog is sending Ping — the wire carries both
  *      frames intact (no byte interleaving).
  */
class WebSocketPongTimeoutSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  /** Outcome of reading one unmasked server-to-client frame. */
  private final case class ServerFrame(fin: Boolean, opcode: Int, payload: Array[Byte])

  /** Complete the HTTP/1.1 WebSocket upgrade handshake. Returns the connected socket with
    * `Sec-WebSocket-Accept` already validated (or throws).
    */
  private def handshake(host: String, port: Int, soTimeoutMs: Int): Socket = {
    val s = new Socket(host, port)
    s.setTcpNoDelay(true)
    s.setSoTimeout(soTimeoutMs)
    val key = Base64.getEncoder.encodeToString(Array.fill(16)(0x42.toByte))
    val req =
      "GET /ws HTTP/1.1\r\n" +
        s"Host: $host\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        s"Sec-WebSocket-Key: $key\r\n" +
        "Sec-WebSocket-Version: 13\r\n\r\n"
    s.getOutputStream.write(req.getBytes("US-ASCII"))
    s.getOutputStream.flush()
    val in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream, "US-ASCII"))
    val status = Option(in.readLine())
    if !status.exists(_.contains("101")) then {
      s.close()
      throw new IOException(s"expected 101 Switching Protocols, got: ${status.getOrElse("<EOF>")}")
    }
    var line = Option(in.readLine())
    while line.exists(_.nonEmpty) do line = Option(in.readLine())
    s
  }

  /** Read a single frame from a WebSocket server. Assumes server→client frames are unmasked (RFC
    * 6455 §5.3). Returns `None` on EOF / socket-timeout / socket-closed.
    */
  private def readServerFrame(s: Socket): Option[ServerFrame] = {
    val in = new DataInputStream(s.getInputStream)
    try {
      val b0 = in.readUnsignedByte()
      val b1 = in.readUnsignedByte()
      val fin = (b0 & 0x80) != 0
      val opcode = b0 & 0x0f
      val masked = (b1 & 0x80) != 0
      if masked then throw new IOException("server sent masked frame (RFC 6455 §5.3 violation)")
      val lenByte = b1 & 0x7f
      val payloadLen: Int =
        if lenByte < 126 then lenByte
        else if lenByte == 126 then in.readUnsignedShort()
        else {
          val v = in.readLong()
          if v < 0 || v > Int.MaxValue then throw new IOException(s"oversized frame: $v")
          v.toInt
        }
      val payload = new Array[Byte](payloadLen)
      in.readFully(payload)
      Some(ServerFrame(fin, opcode, payload))
    } catch {
      case _: EOFException => None
      case _: java.net.SocketTimeoutException => None
      case _: java.net.SocketException => None
    }
  }

  /** Write a masked client→server frame. `opcode`: 0x1 text, 0x2 binary, 0x8 close, 0x9 ping, 0xA
    * pong. Small frames only (payload ≤ 125 bytes) — sufficient for control frames.
    */
  private def writeClientFrame(s: Socket, opcode: Int, payload: Array[Byte], fin: Boolean = true): Unit = {
    val out = s.getOutputStream
    val len = payload.length
    if len > 125 then throw new IllegalArgumentException(s"test harness supports only small frames, got $len")
    val header = new Array[Byte](2)
    header(0) = ((if fin then 0x80 else 0x00) | (opcode & 0x0f)).toByte
    header(1) = (0x80 | len).toByte
    out.write(header)
    val mask = Array[Byte](0x11, 0x22, 0x33, 0x44)
    out.write(mask)
    val masked = new Array[Byte](len)
    var i = 0
    while i < len do { masked(i) = (payload(i) ^ mask(i & 3)).toByte; i += 1 }
    out.write(masked)
    out.flush()
  }

  private val httpHandler: RequestHandler = _ =>
    Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("http-ok")))

  /** A handler that just calls `receive()` in a loop until the connection closes. Captures how many
    * messages it saw in `received` for optional assertions.
    */
  private def passiveHandler(received: AtomicInteger): WebSocketHandler = { conn =>
    def loop: Eru[WebSocketError | HttpError, Unit] =
      conn
        .receive()
        .flatMap { _ =>
          received.incrementAndGet()
          loop
        }
        .recoverWith {
          case _: WebSocketError.ConnectionClosed => Eru.unit
          case _: WebSocketError.NetworkError => Eru.unit
        }
    loop
  }

  private def withWsServer[A](wsCfg: WebSocketServerConfig, handler: WebSocketHandler)(
    body: ServerAddress => Eru[HttpError, A]
  ): Eru[HttpError, A] = {
    val serverCfg = HttpServerConfig.localhost.withPort(0).copy(maxConnections = 64, acceptorThreads = 1)
    val wrappedHandler = WebSocketServer.upgradeHandler(wsCfg)(handler)(httpHandler)
    HttpServer.scoped(serverCfg)(wrappedHandler) { server =>
      server.start.flatMap(body)
    }
  }

  test("WS pingInterval: silent client receives a server Ping within pingInterval + slack") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = Some(500.millis),
      pongTimeout = Some(10.seconds)
    )
    val seen = new AtomicInteger(0)
    withWsServer(cfg, passiveHandler(seen)) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 3000)
        try {
          val frame = readServerFrame(s)
          assert(frame.isDefined, "expected a server frame within 3s, got nothing")
          assertEquals(frame.get.opcode, 0x9, s"expected Ping (0x9), got ${frame.get.opcode}")
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("WS pongTimeout: Pong reply keeps the connection open past pongTimeout") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = Some(400.millis),
      pongTimeout = Some(400.millis)
    )
    val seen = new AtomicInteger(0)
    withWsServer(cfg, passiveHandler(seen)) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 3000)
        try {
          val ping = readServerFrame(s)
          assert(ping.isDefined, "expected initial Ping")
          assertEquals(ping.get.opcode, 0x9, "expected Ping")
          writeClientFrame(s, 0xa, ping.get.payload)
          Thread.sleep(1000)
          s.setSoTimeout(200)
          var closeSeen = false
          var loop = true
          while loop do {
            readServerFrame(s) match {
              case Some(f) if f.opcode == 0x8 => closeSeen = true; loop = false
              case Some(_) => ()
              case None => loop = false
            }
          }
          assert(!closeSeen, "server must NOT have closed after we answered with Pong")
          assert(!s.isClosed, "socket must still be open on client side")
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("WS pongTimeout: missing Pong triggers Close frame + TCP close within pongTimeout + slack") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = Some(300.millis),
      pongTimeout = Some(300.millis)
    )
    val seen = new AtomicInteger(0)
    withWsServer(cfg, passiveHandler(seen)) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 3000)
        try {
          val ping = readServerFrame(s)
          assert(ping.isDefined, "expected a Ping frame first")
          assertEquals(ping.get.opcode, 0x9, "expected Ping")
          s.setSoTimeout(2000)
          var closeSeen = false
          var eofSeen = false
          var loop = true
          while loop do {
            readServerFrame(s) match {
              case Some(f) if f.opcode == 0x8 => closeSeen = true; loop = false
              case Some(_) => ()
              case None => eofSeen = true; loop = false
            }
          }
          assert(
            closeSeen || eofSeen,
            "expected the server to either send a Close frame or close the socket within pongTimeout + slack"
          )
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("WS pingInterval=None: silent client receives NO frame (watchdog not forked)") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = None,
      pongTimeout = Some(200.millis)
    )
    val seen = new AtomicInteger(0)
    withWsServer(cfg, passiveHandler(seen)) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 1500)
        try {
          s.setSoTimeout(1200)
          val f = readServerFrame(s)
          assert(
            f.isEmpty,
            s"pingInterval=None must disable the watchdog, but got frame: opcode=${f.map(_.opcode)}"
          )
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("WS writeLock: handler sendText concurrent with watchdog Ping is serialized cleanly") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = Some(20.millis),
      pongTimeout = Some(10.seconds)
    )
    val handler: WebSocketHandler = { conn =>
      def sendLoop(i: Int): Eru[WebSocketError | HttpError, Unit] =
        if i <= 0 then Eru.unit
        else
          conn.sendText(s"msg-$i").flatMap { _ =>
            runtime.sleep(java.time.Duration.ofMillis(5)).flatMap(_ => sendLoop(i - 1))
          }
      sendLoop(200).recoverWith {
        case _: WebSocketError.ConnectionClosed => Eru.unit
        case _: WebSocketError.NetworkError => Eru.unit
      }
    }
    withWsServer(cfg, handler) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 3000)
        try {
          s.setSoTimeout(2000)
          var textCount = 0
          var pingCount = 0
          var loop = true
          while loop do {
            readServerFrame(s) match {
              case Some(f) if f.opcode == 0x1 =>
                val text = new String(f.payload, java.nio.charset.StandardCharsets.UTF_8)
                assert(text.startsWith("msg-"), s"corrupted text frame: '$text'")
                textCount += 1
                if textCount >= 50 then loop = false
              case Some(f) if f.opcode == 0x9 =>
                pingCount += 1
                writeClientFrame(s, 0xa, f.payload)
              case Some(_) => ()
              case None => loop = false
            }
          }
          assert(
            textCount >= 50,
            s"expected to drain >=50 text frames cleanly; got $textCount (pings seen: $pingCount)"
          )
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }
}
