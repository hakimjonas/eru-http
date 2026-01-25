package net.ghoula.eru.http.client

import java.net.InetSocketAddress
import java.nio.channels.{ReadableByteChannel, SocketChannel, WritableByteChannel}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import scala.annotation.unused

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.*

/** Native WebSocket client implementation using blocking NIO + Virtual Threads.
  *
  * Uses the same efficient architecture as NativeHttpClient:
  *   - Blocking I/O on Virtual Threads for simplicity and performance
  *   - Reuses BufferedSocketReader for efficient frame parsing
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
    }.mapError { e =>
      WebSocketError.NetworkError(s"Failed to connect to $host:$port: ${e.getMessage}", Some(e))
    }
  }

  /** Wrap socket with TLS.
    */
  private def wrapWithTls(
    socket: SocketChannel,
    host: String,
    port: Int
  ): Eru[WebSocketError | HttpError, ReadableByteChannel & WritableByteChannel] = {
    Eru.effect {
      // WebSocket upgrade requires HTTP/1.1, so we explicitly request HTTP/1.1 only via ALPN
      val sslChannel = SSLSocketChannel.client(
        socket,
        sslContext,
        host,
        port,
        verifyHostname = true,
        alpnProtocols = SSLSocketChannel.Http1Protocols
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
    for {
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

  private def receiveLoop(
    fragmentState: Option[WebSocketFrameParser.FragmentationState]
  ): Eru[WebSocketError, WebSocketMessage] = {
    WebSocketFrameParser.parseMessageWithState(reader, maxMessageSize, expectMasked = false, fragmentState).flatMap {
      case WebSocketFrameParser.ParseResult.Message(WebSocketFrame.Text(text, _)) =>
        Eru.succeed(WebSocketMessage.Text(text))

      case WebSocketFrameParser.ParseResult.Message(WebSocketFrame.Binary(data, _)) =>
        Eru.succeed(WebSocketMessage.Binary(data))

      case WebSocketFrameParser.ParseResult.Message(_) =>
        // Shouldn't happen - parseMessageWithState only returns Text or Binary messages
        Eru.fail(
          WebSocketError.InvalidFrame(
            "Unexpected message type from parser",
            "Internal error"
          )
        )

      case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Ping(data), state) =>
        // Handle ping and continue with fragmentation state
        WebSocketFrameWriter.writePong(writableChannel, data, mask = true).flatMap(_ => receiveLoop(state))

      case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Pong(_), state) =>
        // Ignore pong and continue with fragmentation state
        receiveLoop(state)

      case WebSocketFrameParser.ParseResult.ControlFrame(WebSocketFrame.Close(code, reason), _) =>
        // Close frame - complete the close handshake
        closed.set(true)
        WebSocketFrameWriter
          .writeClose(writableChannel, code, reason, mask = true)
          .attempt
          .flatMap(_ => Eru.fail(WebSocketError.ConnectionClosed(code, reason, clean = true)))

      case WebSocketFrameParser.ParseResult.ControlFrame(_, _) =>
        // Shouldn't happen - only Ping, Pong, Close are control frames
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

object NativeWebSocketClient {

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
