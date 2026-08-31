package net.ghoula.eru.http.client

import java.net.InetSocketAddress
import java.nio.channels.{ReadableByteChannel, SocketChannel, WritableByteChannel}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import scala.annotation.unused

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.*
import net.ghoula.eru.prelude.*

/** Native WebSocket client implementation using blocking NIO + Virtual Threads.
  *
  * Uses the same architecture as NativeHttpClient:
  *   - Blocking I/O on Virtual Threads
  *   - Reuses BufferedSocketReader for frame parsing
  *   - SSLSocketChannel for transparent TLS support
  */
private[client] final class NativeWebSocketClient(
  config: WebSocketClientConfig,
  sslContext: SSLContext
)(using @unused runtime: EruRuntime)
    extends WebSocketClient {

  override def connect(
    uri: Uri,
    additionalHeaders: Headers
  ): Eru[WebSocketError | HttpError, WebSocketConnection] = {
    for {
      host <- Eru.fromOption(
        uri.host,
        HttpError.InvalidRequest(InvalidRequest("Missing host in URI", "RFC 6455 Section 3"))
      )
      port <- getPort(uri)
      useTls = uri.scheme.exists(s => s == "wss" || s == "https")
      socket <- createSocket(host, port)
      channel <-
        if useTls then wrapWithTls(socket, host, port)
        else Eru.succeed(socket: ReadableByteChannel & WritableByteChannel)
      conn <- performHandshake(channel, uri, host, additionalHeaders).mapError {
        case e: WebSocketError => e
        case e: HttpError => e
      }
    } yield conn
  }

  override def shutdown: Eru[Nothing, Unit] = Eru.unit

  /** Get port from URI, defaulting based on scheme.
    */
  private def getPort(uri: Uri): Eru[HttpError, Int] = {
    uri.port match {
      case Some(p) => Eru.succeed(p.value)
      case None =>
        uri.scheme match {
          case Some("ws") | Some("http") => Eru.succeed(80)
          case Some("wss") | Some("https") => Eru.succeed(443)
          case Some(s) =>
            Eru.fail(HttpError.InvalidUri(Uri.InvalidUri(s, s"Unknown scheme: $s")))
          case None =>
            Eru.fail(HttpError.InvalidUri(Uri.InvalidUri("", "Missing scheme")))
        }
    }
  }

  private def createSocket(host: String, port: Int): Eru[WebSocketError | HttpError, SocketChannel] = {
    Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)
      socket.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
      socket.connect(new InetSocketAddress(host, port))
      socket
    }
      .timeout(java.time.Duration.ofMillis(config.connectTimeout.toMillis))
      .mapError {
        case _: java.util.concurrent.TimeoutException =>
          WebSocketError.Timeout(s"Connect timeout after ${config.connectTimeout}")
        case e: Throwable =>
          WebSocketError.NetworkError(s"Failed to connect to $host:$port: ${e.getMessage}", Some(e))
      }
  }

  /** Wrap socket with TLS.
    *
    * The WebSocket upgrade requires HTTP/1.1, so only HTTP/1.1 is requested via ALPN.
    */
  private def wrapWithTls(
    socket: SocketChannel,
    host: String,
    port: Int
  ): Eru[WebSocketError | HttpError, ReadableByteChannel & WritableByteChannel] = {
    Eru.effect {
      val tlsConfig = config.tlsConfig.getOrElse(TlsConfig.default)
      val sslChannel = SSLSocketChannel.client(
        socket,
        sslContext,
        host,
        port,
        verifyHostname = tlsConfig.verifyHostname,
        alpnProtocols = SSLSocketChannel.Http1Protocols,
        protocols = tlsConfig.protocols,
        cipherSuites = tlsConfig.cipherSuites
      )
      sslChannel.doHandshake()
      sslChannel
    }.mapError { e =>
      WebSocketError.NetworkError(s"TLS handshake failed: ${e.getMessage}", Some(e))
    }
  }

  private def performHandshake(
    channel: ReadableByteChannel & WritableByteChannel,
    uri: Uri,
    host: String,
    additionalHeaders: Headers
  ): Eru[WebSocketError | HttpError, WebSocketConnection] = {
    val handshake: Eru[WebSocketError | HttpError, WebSocketConnection] = for {
      key <- Eru.effectTotal(WebSocketHandshake.generateKey())
      request <- WebSocketHandshake.createUpgradeRequest(uri, key, config.subprotocols, additionalHeaders)
      requestWithHost <-
        if request.headers.contains(HeaderNames.Host) then Eru.succeed(request)
        else
          request.headers
            .add(HeaderNames.Host, host)
            .mapError(e => HttpError.InvalidRequest(InvalidRequest(e.toString, "RFC 6455")))
            .map(h => request.copy(headers = h))
      _ <- HttpWriter.writeRequest(channel, requestWithHost).mapError { err =>
        WebSocketError.NetworkError(s"Failed to send upgrade request: ${err.message}", None)
      }
      reader = new BufferedSocketReader(channel)
      response <- HttpParser.parseResponseWithReader(reader).mapError { e =>
        WebSocketError.HandshakeFailed(s"Failed to parse upgrade response: ${e.message}", "RFC 6455 Section 4.2.2")
      }
      _ <- WebSocketHandshake.validateUpgradeResponse(response, key)
      negotiatedSubprotocol = response.headers
        .getFirst(HeaderNames.SecWebSocketProtocol)
        .map(_.value)
    } yield new NativeWebSocketConnection(
      channel,
      reader,
      config.maxMessageSize,
      config.maxFrameSize,
      negotiatedSubprotocol
    )

    handshake
      .timeout(java.time.Duration.ofMillis(config.handshakeTimeout.toMillis))
      .mapError {
        case e: WebSocketError => e
        case e: HttpError => e
        case _: java.util.concurrent.TimeoutException =>
          WebSocketError.Timeout(s"WebSocket handshake timeout after ${config.handshakeTimeout}")
        case e: Throwable =>
          WebSocketError.NetworkError(s"WebSocket handshake failed: ${e.getMessage}", Some(e))
      }
  }
}

/** Native WebSocket connection implementation.
  */
private[client] final class NativeWebSocketConnection(
  channel: ReadableByteChannel & WritableByteChannel,
  reader: BufferedSocketReader,
  maxMessageSize: Long,
  maxFrameSize: Int,
  val subprotocol: Option[String]
) extends WebSocketConnection {

  private val closed = new AtomicBoolean(false)

  private val writableChannel: WritableByteChannel = channel

  override def sendText(text: String): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else WebSocketFrameWriter.writeText(writableChannel, text, mask = true, maxFrameSize)
  }

  override def sendBinary(data: Bytes): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else WebSocketFrameWriter.writeBinary(writableChannel, data, mask = true, maxFrameSize)
  }

  override def sendPing(data: Bytes): Eru[WebSocketError, Unit] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else WebSocketFrameWriter.writePing(writableChannel, data, mask = true)
  }

  override def receive(): Eru[WebSocketError, WebSocketMessage] = {
    if closed.get() then Eru.fail(WebSocketError.ConnectionClosed(None, Some("Connection is closed"), clean = true))
    else receiveLoop(None)
  }

  /** Reads frames until a complete Text or Binary message is parsed.
    *
    * Control frames are handled inline: Ping frames are answered with a Pong, Pong frames are
    * ignored, and a Close frame completes the close handshake and fails with ConnectionClosed.
    * parseMessageWithState only returns Text or Binary messages, and only Ping, Pong, and Close are
    * valid control frames, so any other value should be unreachable; the corresponding match arms
    * reject it defensively as InvalidFrame.
    */
  private def receiveLoop(
    fragmentState: Option[WebSocketFrameParser.FragmentationState]
  ): Eru[WebSocketError, WebSocketMessage] = {
    WebSocketFrameParser.parseMessageWithState(reader, maxMessageSize, expectMasked = false, fragmentState).flatMap {
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
        WebSocketFrameWriter.writePong(writableChannel, data, mask = true).flatMap(_ => receiveLoop(state))

      case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Pong(_), state) =>
        receiveLoop(state)

      case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Close(code, reason), _) =>
        closed.set(true)
        WebSocketFrameWriter
          .writeClose(writableChannel, code, reason, mask = true)
          .attempt
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

  override def close(code: WebSocketCloseCode, reason: Option[String]): Eru[WebSocketError, Unit] = {
    if closed.compareAndSet(false, true) then {
      WebSocketFrameWriter
        .writeClose(writableChannel, Some(code), reason, mask = true)
        .flatMap { _ =>
          WebSocketFrameParser.parseFrame(reader, 125, expectMasked = false).attempt.map { _ =>
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

private[client] object NativeWebSocketClient {

  /** Create a new WebSocket client.
    */
  def create(config: WebSocketClientConfig)(using runtime: EruRuntime): Eru[HttpError, WebSocketClient] = {
    for {
      sslContext <- config.tlsConfig match {
        case Some(tlsConfig) => createSSLContext(tlsConfig)
        case None => Eru.effectTotal(SSLContext.getDefault)
      }
    } yield new NativeWebSocketClient(config, sslContext)
  }

  /** Create SSL context from TLS configuration.
    */
  private def createSSLContext(tlsConfig: TlsConfig): Eru[HttpError, SSLContext] = {
    Eru.effect {
      SSLContextFactory.createClientContext(tlsConfig)
    }.mapError(e => HttpError.NetworkError(s"Failed to create SSL context: ${e.getMessage}", Some(e)))
  }
}
