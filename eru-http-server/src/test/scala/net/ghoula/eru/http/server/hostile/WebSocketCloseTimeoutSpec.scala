package net.ghoula.eru.http.server.hostile

import java.io.{DataInputStream, EOFException, IOException}
import java.net.Socket
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.websocket.*
import net.ghoula.eru.prelude.*

/** WebSocket `closeTimeout` enforcement.
  *
  * After the server sends a Close frame (here via the pong-timeout watchdog), it waits at most
  * `closeTimeout` for the peer's close echo. A client that ignores the Close must be force-closed
  * at TCP level instead of draining the socket until OS-level timeouts. The field existed with a 5s
  * default but was never enforced; this spec pins the enforcement.
  *
  * Scenarios:
  *   1. Client ignores the server Close → TCP EOF within closeTimeout + slack.
  *   2. Client echoes Close → clean prompt close (no premature force-close).
  */
class WebSocketCloseTimeoutSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private final case class ServerFrame(fin: Boolean, opcode: Int, payload: Array[Byte])

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

  private def writeClientFrame(s: Socket, opcode: Int, payload: Array[Byte], fin: Boolean = true): Unit = {
    val out = s.getOutputStream
    val len = payload.length
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

  private def passiveHandler: WebSocketHandler = { conn =>
    def loop: Eru[WebSocketError | HttpError, Unit] =
      conn
        .receive()
        .flatMap(_ => loop)
        .recoverWith {
          case _: WebSocketError.ConnectionClosed => Eru.unit
          case _: WebSocketError.NetworkError => Eru.unit
        }
    loop
  }

  private def withWsServer[A](
    wsCfg: WebSocketServerConfig
  )(body: ServerAddress => Eru[HttpError, A]): Eru[HttpError, A] = {
    val serverCfg = HttpServerConfig.localhost.withPort(0).copy(maxConnections = 64, acceptorThreads = 1)
    val wrappedHandler = WebSocketServer.upgradeHandler(wsCfg)(passiveHandler)(httpHandler)
    HttpServer.scoped(serverCfg)(wrappedHandler) { server =>
      server.start.flatMap(body)
    }
  }

  test("client ignoring the server Close is force-closed within closeTimeout") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = Some(100.millis),
      pongTimeout = Some(200.millis),
      closeTimeout = 400.millis
    )

    withWsServer(cfg) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 3000)
        try {
          // Ping arrives (inbound silence), client ignores it.
          val ping = readServerFrame(s)
          assert(ping.exists(_.opcode == 0x9), s"expected a server Ping, got: $ping")

          // Pong never comes -> watchdog sends GoingAway Close.
          val close = readServerFrame(s)
          assert(close.exists(_.opcode == 0x8), s"expected a server Close, got: $close")

          // Client keeps ignoring. After closeTimeout (400ms) the server must force-close: the
          // next read observes TCP EOF. Before the closeTimeout wiring this read timed out
          // instead (socket drained, no EOF).
          s.setSoTimeout(1200)
          val sawEof = Try(s.getInputStream.read() == -1).getOrElse(false)
          assert(sawEof, "server did not force-close within closeTimeout; client read timed out")
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }
  }

  test("client echoing Close is closed cleanly (no premature force-close)") {
    requireHostileMode()

    val cfg = WebSocketServerConfig.default.copy(
      pingInterval = Some(100.millis),
      pongTimeout = Some(10.seconds),
      closeTimeout = 400.millis
    )

    withWsServer(cfg) { addr =>
      Eru.effect {
        val s = handshake(addr.host, addr.port, soTimeoutMs = 3000)
        try {
          // Answer the watchdog Ping with a Pong so the connection stays healthy.
          val ping = readServerFrame(s)
          assert(ping.exists(_.opcode == 0x9), s"expected a server Ping, got: $ping")
          writeClientFrame(s, 0xa, ping.get.payload)

          // Client initiates the close handshake.
          writeClientFrame(s, 0x8, Array[Byte](0x03, 0xe8.toByte))

          // Server echoes Close and closes; the EOF must arrive promptly (well within the 400ms
          // closeTimeout being relevant).
          s.setSoTimeout(1500)
          val echo = readServerFrame(s)
          assert(echo.exists(_.opcode == 0x8), s"expected a Close echo, got: $echo")
          val sawEof = Try(s.getInputStream.read() == -1).getOrElse(false)
          assert(sawEof, "connection must close after the close handshake")
        } finally Try(s.close()): Unit
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }
  }
}
