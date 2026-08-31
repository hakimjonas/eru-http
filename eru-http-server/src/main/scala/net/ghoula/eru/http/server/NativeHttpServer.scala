package net.ghoula.eru.http.server

import jdk.net.ExtendedSocketOptions

import java.net.InetSocketAddress
import java.nio.channels.{ReadableByteChannel, ServerSocketChannel, SocketChannel, WritableByteChannel}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.h2.{H2Error, H2ServerConnection}
import net.ghoula.eru.prelude.*

/** Native HTTP server implementation using blocking NIO + Virtual Threads.
  *
  *   - Each connection runs on its own Virtual Thread via .fork
  *   - Blocking I/O parks the virtual thread cheaply; thread stacks grow on demand instead of
  *     reserving a fixed large stack per connection (JEP 444)
  *   - Structured concurrency ensures automatic cleanup
  *   - Simple blocking code with no event loops or callbacks
  *
  * SO_REUSEPORT Support (Linux 3.9+):
  *   - Multiple acceptor threads for multi-core scaling
  *   - Kernel-level load balancing across acceptors
  *   - Each acceptor has its own blocking ServerSocketChannel on a Virtual Thread
  *   - Enabled automatically when acceptorThreads > 1
  */
private[server] final class NativeHttpServer(
  config: HttpServerConfig,
  handler: RequestHandler,
  serverSockets: List[ServerSocketChannel],
  sslContext: Option[SSLContext],
  connectionSemaphore: Semaphore,
  writeBufferPool: ObjectPool[java.nio.ByteBuffer],
  perIpGovernor: Option[PerIpGovernor]
)(using runtime: EruRuntime)
    extends HttpServer {

  /** Track the resolved client IP for each in-flight connection. Used to:
    *   1. Call releaseConnection on handler exit (so the per-IP counter decrements).
    *   2. Enforce request-rate limits in runRequestLoop.
    * Cleared in handleClient's cleanup finalizer.
    */
  private val clientIps = new java.util.concurrent.ConcurrentHashMap[SocketChannel, IpKey]()

  /** Replay buffer: bytes the PROXY-detection path peeked off the wire that belong to the HTTP
    * stream (i.e. when the preamble signature didn't match in Optional mode). The request loop's
    * BufferedSocketReader is seeded with these bytes so HTTP parsing sees them. Cleared on read.
    */
  private val replayBytes = new java.util.concurrent.ConcurrentHashMap[SocketChannel, Array[Byte]]()

  private val running = new AtomicBoolean(true)
  private val activeClients = new java.util.concurrent.ConcurrentHashMap[SocketChannel, java.lang.Boolean]()

  /** Per-server FiberTracker for handler fibers. `shutdown` drains this queue, interrupting each
    * live handler and awaiting its cleanup so that resources (sockets, write-buffer permits,
    * semaphore permits) are actually released before shutdown returns — rather than lingering as
    * in-flight daemon VTs.
    */
  private val handlerTracker = FiberTracker()

  /** Bind all server sockets and start the server's accept loops.
    *
    * One accept loop runs per server socket (SO_REUSEPORT multi-threading); each loop runs as a
    * daemon fiber on its own Virtual Thread. If `watchdogInterval` is configured, the systemd
    * watchdog fiber is started here, after `Watchdog.ready()` has been signaled (see
    * `watchdogLoop`). The address is taken from the first socket (all are bound to the same port).
    */
  def start: Eru[HttpError, ServerAddress] = for {
    _ <- Eru.effect {
      serverSockets.foreach { socket =>
        socket.configureBlocking(true)
        socket.bind(new InetSocketAddress(config.host, config.port), config.backlog)
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to bind server: ${e.getMessage}", Some(e)))

    address <- Eru.effect {
      serverSockets.head.getLocalAddress match {
        case addr: InetSocketAddress =>
          ServerAddress(addr.getHostString, addr.getPort)
        case other =>
          throw new IllegalStateException(s"Unexpected address type: ${other.getClass}")
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to get address: ${e.getMessage}", Some(e)))

    _ <- serverSockets.foldLeft[Eru[Nothing, Unit]](Eru.unit) { (acc, socket) =>
      val forkAccept = config.serverObserver match {
        case Some(obs) => runtime.forkWithObserver(acceptLoop(socket), obs)
        case None => runtime.forkDaemon(acceptLoop(socket))
      }
      acc.flatMap(_ => forkAccept.map(_ => ()))
    }

    _ <- config.watchdogInterval match {
      case Some(interval) =>
        if !Watchdog.isAvailable then
          Eru.fail(
            HttpError.NetworkError(
              "watchdog requested (watchdogInterval is set) but the JDK does not " +
                "support Unix datagram channels. Install a JDK that includes the " +
                "standard channel provider for java.net.UnixDomainSocketAddress " +
                "(JDK 16+). When not running under systemd, do not set watchdogInterval.",
              None
            )
          )
        else
          for {
            _ <- Watchdog
              .ready()
              .mapError(t => HttpError.NetworkError(s"watchdog ready failed: ${t.getMessage}", Some(t)))
            _ <- config.serverObserver match {
              case Some(obs) => runtime.forkWithObserver(watchdogLoop(interval), obs)
              case None => runtime.forkDaemon(watchdogLoop(interval))
            }
            _ <- Eru.succeed(())
          } yield ()
      case None =>
        Eru.unit
    }

  } yield address

  /** Accept loop - blocking accept on Virtual Thread.
    *
    * Uses blocking ServerSocketChannel.accept() which is efficient on Virtual Threads (no pinning).
    * Each accepted connection is dispatched to its own daemon fiber for handling.
    *
    * With SO_REUSEPORT (acceptorThreads > 1), multiple instances of this loop run concurrently,
    * each with its own ServerSocketChannel bound to the same port. The kernel distributes incoming
    * connections across acceptors for multi-core scaling.
    *
    * Shutdown: serverSocket.close() causes accept() to throw ClosedChannelException, cleanly
    * terminating the loop.
    *
    * @param serverSocket
    *   The server socket for this accept loop (unique per acceptor thread)
    */
  private def acceptLoop(serverSocket: ServerSocketChannel): Eru[HttpError, Nothing] = {
    // One accept-loop iteration, with every transient failure contained: a failed accept (EMFILE
    // under flood), a failed socket config, or a failed fork must never kill the acceptor — the
    // server is under the most pressure exactly when these fire. Eru.forever stops on failure, so
    // the iteration catches transient errors itself, closes any socket it owns, releases what it
    // acquired, and backs off briefly before accepting again. One failure stays terminal: when
    // shutdown closes the server socket, accept() failing IS the shutdown signal, and the loop
    // must end so the fiber completes and observers see it.
    val iteration: Eru[HttpError, Unit] = (for {
      accepted <- Eru.effect(serverSocket.accept()).attempt
      _ <- accepted match {
        case Result.Failure(acceptError) if !serverSocket.isOpen =>
          Eru.fail(
            HttpError.NetworkError(s"accept: server socket closed (shutdown): ${describeError(acceptError)}", None)
          )
        case Result.Failure(acceptError) =>
          acceptFailureBackoff(s"accept failed: ${describeError(acceptError)}")
        case Result.Success(clientSocket) =>
          handleAccepted(clientSocket).attempt.flatMap {
            case Result.Success(_) => Eru.succeed(())
            case Result.Failure(e) =>
              // Whatever failed after accept (socket config on a peer that vanished, fork
              // failure), the socket is ours to close and the loop must go on. Permits and
              // governor counters are released by handleAccepted's own error path.
              acceptFailureBackoff(s"connection setup failed: ${describeError(e)}").flatMap { _ =>
                closeQuietly(clientSocket)
              }
          }
      }
    } yield ())
    Eru.forever(iteration)
  }

  /** Gate + permit + dispatch for one accepted socket. On any failure the caller closes the socket;
    * this method owns releasing everything it acquired before that point.
    */
  private def handleAccepted(clientSocket: SocketChannel): Eru[HttpError, Unit] =
    for {
      gateResult <- gateWithBound(clientSocket)
      _ <- gateResult match {
        case ConnectionGate.Rejected =>
          closeQuietly(clientSocket)
        case ConnectionGate.Accepted(clientIp) =>
          for {
            _ <- connectionSemaphore.acquire.eru
              .mapError(e => HttpError.NetworkError(s"Failed to acquire connection permit: $e", None))
            _ <- (for {
              _ <- Eru.effect {
                clientSocket.configureBlocking(true)
                clientSocket.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                try {
                  clientSocket.setOption(ExtendedSocketOptions.TCP_QUICKACK, true)
                } catch {
                  case _: Exception => ()
                }
                activeClients.put(clientSocket, java.lang.Boolean.TRUE)
                clientIp.foreach(clientIps.put(clientSocket, _))
              }.mapError(e => HttpError.NetworkError(s"Socket config error: ${e.getMessage}", Some(e)))
              _ <- runtime
                .forkTracked(
                  handleClient(clientSocket).ensure(
                    connectionSemaphore.release.eru.attempt.map { _ =>
                      clientIp.foreach { ip => perIpGovernor.foreach(_.releaseConnection(ip)) }
                    }
                  ),
                  handlerTracker
                )
                .map(_ => ())
            } yield ()).tapError { _ =>
              // The forked handler owns the permit and governor counter only once forkTracked
              // succeeds; if anything before that failed, this effect releases them.
              connectionSemaphore.release.eru.attempt.flatMap { _ =>
                clientIp.foreach { ip => perIpGovernor.foreach(_.releaseConnection(ip)) }
                Eru.unit
              }
            }
          } yield ()
      }
    } yield ()

  /** Brief pause after a failed accept-loop iteration so a persistently failing resource (fd
    * exhaustion) does not busy-spin the acceptor.
    */
  private def acceptFailureBackoff(message: String): Eru[Nothing, Unit] =
    Eru.effect {
      System.err.println(s"[eru-http] $message")
      Thread.sleep(100)
    }.attempt.map(_ => ())

  private def closeQuietly(socket: SocketChannel): Eru[Nothing, Unit] =
    Eru.effect {
      try socket.close()
      catch { case _: Exception => () }
    }.attempt.map(_ => ())

  private def describeError(e: HttpError | Throwable): String = e match {
    case h: HttpError => h.message
    case t: Throwable => Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
  }

  /** Watchdog heartbeat loop for systemd `WatchdogSec=` integration.
    *
    * Sends `WATCHDOG=1` at half the configured interval. Runs as a daemon fiber independent of
    * request handlers — a stuck handler cannot block the heartbeat. Sends `READY=1` once at startup
    * (via `Watchdog.ready()` in `start`). Stops when the `running` flag is set to false.
    */
  private def watchdogLoop(interval: scala.concurrent.duration.Duration): Eru[HttpError, Nothing] = {
    val sleepMs = interval.toMillis / 2
    val step = for {
      _ <- Watchdog.heartbeat().mapError(t => HttpError.NetworkError(s"watchdog heartbeat: ${t.getMessage}", Some(t)))
      _ <- runtime.sleep(java.time.Duration.ofMillis(sleepMs))
    } yield ()
    Eru.forever(step)
  }

  /** Decision from the connection gate (PROXY parse + per-IP governance).
    *
    *   - `Accepted(Some(ip))`: connection allowed and we tracked `ip`'s connection counter (must be
    *     released on handler exit).
    *   - `Accepted(None)`: governance disabled; no counter to decrement.
    *   - `Rejected`: caller closes the socket, no further work: the connection is closed with no
    *     HTTP response (writing a "429 too many" byte stream takes longer than the attacker spends
    *     opening the next socket) and no diagnostic info about which cap was hit.
    */
  private sealed trait ConnectionGate
  private object ConnectionGate {
    final case class Accepted(ip: Option[IpKey]) extends ConnectionGate
    case object Rejected extends ConnectionGate
  }

  /** Apply PROXY protocol parsing + per-IP governance to a freshly-accepted socket.
    *
    * Always runs on the accept-loop VT, BEFORE any connection-semaphore permit is acquired, so a
    * rejected connection holds no resource beyond the TCP socket, which the caller closes
    * immediately. Returns synchronously; the caller decides whether to dispatch a handler or close.
    *
    * PROXY v2 parsing runs on the RAW socket for both plain and TLS connections: a PROXY-emitting
    * LB sends the preamble before any TLS handshake bytes, so at gate time the raw stream starts
    * with the preamble. After the preamble is consumed the TLS handshake proceeds normally (the
    * client does not wait for the server). With TLS + Optional mode, a non-PROXY peer's first 12
    * bytes are the start of the ClientHello — they are stashed in `replayBytes` and handed to
    * `wrapWithTLS` as pre-read seed bytes so the handshake sees the stream untouched. With TLS +
    * Required mode, a peer whose bytes are not a PROXY preamble is rejected.
    *
    * In Optional mode (plain HTTP), a non-PROXY first-12-bytes peek means the bytes are HTTP — they
    * are stashed in `replayBytes` so the downstream BufferedSocketReader can seed its buffer and
    * HTTP parsing sees the complete request.
    *
    * A `proxyRejected` flag force-rejects the connection independent of per-IP governance —
    * otherwise a mode=Required deployment with governance off would silently accept plain HTTP,
    * which is exactly what Required is supposed to forbid.
    */
  private def gateConnection(socket: SocketChannel): Eru[HttpError, ConnectionGate] =
    Eru.effect {
      var proxyRejected = false
      val clientAddr: Option[java.net.InetAddress] =
        if config.proxyProtocolMode == ProxyProtocolMode.Off then tcpPeerAddress(socket)
        else {
          val in = socket.socket().getInputStream
          val result = ProxyProtocol.parse(Array.empty, in)
          result match {
            case ProxyProtocol.ParseResult.Parsed(header, _) =>
              header.clientAddr.orElse(tcpPeerAddress(socket))
            case ProxyProtocol.ParseResult.NotProxyProtocol(peeked) =>
              if config.proxyProtocolMode == ProxyProtocolMode.Required then {
                proxyRejected = true
                None
              } else {
                replayBytes.put(socket, peeked): Unit
                tcpPeerAddress(socket)
              }
            case ProxyProtocol.ParseResult.Invalid(_) =>
              proxyRejected = true
              None
          }
        }

      val gateDecision: ConnectionGate =
        if proxyRejected then ConnectionGate.Rejected
        else if !config.perIpGovernanceEnabled then ConnectionGate.Accepted(None)
        else
          perIpGovernor match {
            case None => ConnectionGate.Accepted(None)
            case Some(governor) =>
              clientAddr match {
                case None =>
                  ConnectionGate.Rejected
                case Some(addr) =>
                  val ip = IpKey.fromInetAddress(addr)
                  governor.tryAcquireConnection(ip) match {
                    case PerIpGovernor.AcquireResult.Ok =>
                      ConnectionGate.Accepted(Some(ip))
                    case _ =>
                      ConnectionGate.Rejected
                  }
              }
          }

      gateDecision
    }.mapError(e => HttpError.NetworkError(s"Connection gate error: ${e.getMessage}", Some(e)))

  /** Runs the connection gate with the PROXY preamble read bounded by `proxyHandshakeTimeout`.
    *
    * The preamble read is the one blocking read on the accept-loop virtual thread; without a bound
    * a peer that stalls mid-preamble parks the acceptor forever (with one acceptor that is the
    * whole server). The gate runs under Eru `.timeout` — the same fork-and-interrupt mechanism as
    * the TLS handshake — and any failure (timeout, malformed preamble, or the interrupt closing the
    * socket) becomes `Rejected`, so the accept loop closes the socket and moves on. `Off` mode
    * skips the bound: no preamble bytes are read at all.
    */
  private def gateWithBound(socket: SocketChannel): Eru[HttpError, ConnectionGate] = {
    val gated = gateConnection(socket)
    if config.proxyProtocolMode == ProxyProtocolMode.Off then gated
    else
      gated
        .timeout(java.time.Duration.ofMillis(config.proxyHandshakeTimeout.toMillis))
        .attempt
        .flatMap {
          case Result.Success(gate) => Eru.succeed(gate)
          case Result.Failure(_) =>
            Eru.effect {
              try socket.close()
              catch { case _: Exception => () }
            }.map(_ => ConnectionGate.Rejected: ConnectionGate)
        }
        .mapError(e => HttpError.NetworkError(s"Connection gate error: ${e.getMessage}", None))
  }

  /** Interim `100 Continue` hook (RFC 9110 Section 10.1.1): when the client declares
    * `Expect: 100-continue` on a request that declares a body, the interim response is written
    * before the body read blocks — clients like curl withhold the body until they see it. Other
    * Expect values are ignored and the request proceeds normally (RFC 9110 permits that in place of
    * 417).
    */
  private def continueIfExpected(
    channel: ReadableByteChannel & WritableByteChannel,
    writeBuffer: java.nio.ByteBuffer
  ): Headers => Eru[HttpError, Unit] = headers => {
    val expectsContinue = headers
      .getFirst(HeaderNames.Expect)
      .exists(_.value.trim.equalsIgnoreCase("100-continue"))
    val declaresBody = headers.getFirst(HeaderNames.TransferEncoding).isDefined ||
      headers.getFirst(HeaderNames.ContentLength).exists(h => h.value.trim.toLongOption.exists(_ > 0))
    if expectsContinue && declaresBody then
      HttpWriter.writeResponseWithBuffer(
        channel,
        Response(StatusCode.Continue, Headers.empty, Body.Empty),
        writeBuffer
      )
    else Eru.unit
  }

  private def tcpPeerAddress(socket: SocketChannel): Option[java.net.InetAddress] = {
    try
      socket.getRemoteAddress match {
        case isa: InetSocketAddress => Option(isa.getAddress)
        case _ => None
      }
    catch { case _: Exception => None }
  }

  /** Handle a single client connection.
    *
    * This runs on its own Virtual Thread, so blocking I/O is efficient. The entire request-response
    * cycle is a simple for-comprehension!
    *
    * `.ensure` runs cleanup on success, failure, AND interruption: the Eru interpreter's Effect
    * case catches InterruptedException and re-throws as InterruptedWithFinalizers preserving the
    * finalizer list, so socket cleanup is guaranteed even if the fiber is interrupted during a
    * blocking read.
    *
    * HTTP/2 is only reachable via TLS + ALPN negotiation; with no SSL context the channel is always
    * handled as HTTP/1.1.
    */
  private def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = {
    val cleanup: Eru[Nothing, Unit] = Eru.effect {
      activeClients.remove(socket)
      clientIps.remove(socket): Unit
      replayBytes.remove(socket): Unit
      socket.close()
    }.attempt.map(_ => ())

    val clientEffect = for {
      channelAndProtocol <- sslContext match {
        case Some(ctx) =>
          // TLS + Optional PROXY mode: the gate peeked 12 bytes that were not a preamble (they are
          // the start of the ClientHello) and stashed them; seed them into the TLS stream.
          val preRead = Option(replayBytes.remove(socket)).getOrElse(Array.emptyByteArray)
          wrapWithTLS(socket, ctx, preRead).map { sslChannel =>
            (sslChannel: ReadableByteChannel & WritableByteChannel, sslChannel.isHttp2, Some(sslChannel))
          }
        case None =>
          Eru.succeed((socket: ReadableByteChannel & WritableByteChannel, false, Option.empty[SSLSocketChannel]))
      }

      (channel, isHttp2, maybeSslChannel) = channelAndProtocol

      _ <-
        if isHttp2 then {
          maybeSslChannel match {
            case Some(ssl) => handleHttp2Connection(ssl)
            case None => handleRequestLoop(channel, socket)
          }
        } else {
          handleRequestLoop(channel, socket)
        }

    } yield ()

    clientEffect.ensure(cleanup)
  }

  /** Handle an HTTP/2 connection.
    *
    * Uses H2ServerConnection to process HTTP/2 frames and handle requests.
    *
    * Response handlers are forked to prevent a flow-control deadlock. Without forking, large
    * responses (>65KB initial window) would block on sendData() waiting for WINDOW_UPDATE frames,
    * but no fiber would be reading frames from the socket to receive those WINDOW_UPDATE frames.
    *
    * By forking the response handler, the main loop continues reading frames (which processes
    * WINDOW_UPDATE via handleWindowUpdate), allowing blocked response fibers to wake up and
    * continue sending data.
    *
    * Requests are converted and validated the same way the HTTP/1.1 path does: the Host rule keys
    * on HTTP_1_1 and Content-Length/TE cannot co-occur in HTTP/2, so the shared validation is safe.
    * Conversion failures (missing pseudo-headers, invalid :scheme, control characters in
    * :path/:authority) are malformed requests per RFC 9113 Section 8.1.1 and answer 400 on the
    * stream, as do validation failures; neither tears the connection.
    *
    * Streaming bodies are not implemented over HTTP/2 (a Body.Stream maps to no DATA bytes).
    * Response body bytes are read from `unsafeArray` — zero-copy, since `sendResponse` only reads
    * the array when serializing DATA frames.
    */
  private def handleHttp2Connection(channel: SSLSocketChannel): Eru[HttpError, Unit] = {
    H2ServerConnection.accept(channel).mapError(h2ErrorToHttpError).flatMap { h2conn =>
      def loop(): Eru[HttpError, Unit] = {
        h2conn
          .receiveRequest()
          .mapError(h2ErrorToHttpError)
          .flatMap { case (streamId, h2Headers, body) =>
            val requestEffect: Eru[HttpError, Response[Body]] =
              convertH2RequestToRequest(h2Headers, body)
                .mapError(e => HttpError.InvalidRequest(InvalidRequest(e.message, "RFC 9113")))
                .flatMap { request =>
                  request.validate.mapError(HttpError.InvalidRequest.apply).flatMap { validRequest =>
                    checkStrictPath(validRequest) match {
                      case Some(err) => errorToResponse(err)
                      case None => handler(validRequest)
                    }
                  }
                }
            val handlerEffect = requestEffect.attempt.flatMap { handlerResult =>
              val responseEffect = handlerResult match {
                case Result.Success(resp) => Eru.succeed(resp)
                case Result.Failure(httpError: HttpError) => errorToResponse(httpError)
              }

              responseEffect.flatMap { response =>
                val statusCode = response.status.value
                val responseHeaders = response.headers.toList.map { case (name, value) => (name.toLowerCase, value) }

                val bodyBytes = response.body match {
                  case Body.Empty => None
                  case t: Body.Text => Some(t.bytes.unsafeArray)
                  case Body.Binary(bytes, _) => Some(bytes.unsafeArray)
                  case Body.Stream(_, _, _) => None
                }

                h2conn.sendResponse(streamId, statusCode, responseHeaders, bodyBytes).mapError(h2ErrorToHttpError)
              }
            }

            runtime.fork(handlerEffect).map(_ => ())
          }
          .attempt
          .flatMap {
            case Result.Success(_) =>
              h2conn.connection.isGoingAway.flatMap { goingAway =>
                if goingAway then Eru.unit
                else loop()
              }
            case Result.Failure(_) =>
              Eru.unit
          }
      }

      loop()
    }
  }

  private def hasControlChars(s: String): Boolean =
    s.exists(c => c < 0x20 || c == 0x7f)

  /** Convert HTTP/2 headers to an eru-http Request.
    *
    * :scheme is restricted to "http" and "https" by server policy; RFC 9113 Section 8.3.1 states
    * that ":scheme" is not restricted to "http" and "https" schemed URIs, so this is a stricter
    * choice than the RFC requires. Pseudo-header values must not carry control characters. Unlike
    * the byte-faithful HTTP/1.1 path posture (documented in SECURITY.md, where strictPathValidation
    * is opt-in), RFC 9113 mandates validation here, so CR/LF and NUL never reach the URI parser or
    * the handler from an H2 request.
    */
  private def convertH2RequestToRequest(
    h2Headers: List[(String, String)],
    body: Option[Array[Byte]]
  ): Eru[HttpError, Request[Body]] = {
    val headerMap = h2Headers.toMap

    for {
      methodStr <- Eru.fromOption(
        headerMap.get(":method"),
        HttpError.ProtocolError("Missing :method pseudo-header", "RFC 9113 Section 8.3.1")
      )
      pathStr <- Eru.fromOption(
        headerMap.get(":path"),
        HttpError.ProtocolError("Missing :path pseudo-header", "RFC 9113 Section 8.3.1")
      )
      schemeStr <- Eru.fromOption(
        headerMap.get(":scheme"),
        HttpError.ProtocolError("Missing :scheme pseudo-header", "RFC 9113 Section 8.3.1")
      )
      authority = headerMap.get(":authority")

      _ <-
        if schemeStr == "http" || schemeStr == "https" then Eru.unit
        else
          Eru.fail(
            HttpError.ProtocolError(s"Invalid :scheme pseudo-header value: $schemeStr", "RFC 9113 Section 8.3.1")
          )
      _ <-
        if hasControlChars(pathStr) then
          Eru.fail(HttpError.ProtocolError("Control character in :path pseudo-header", "RFC 9113 Section 8.3.1"))
        else Eru.unit
      _ <- authority match {
        case Some(a) if hasControlChars(a) =>
          Eru.fail(HttpError.ProtocolError("Control character in :authority pseudo-header", "RFC 9113 Section 8.3.1"))
        case _ => Eru.unit
      }

      method <- Method.parse(methodStr).mapError(e => HttpError.InvalidMethod(e))

      uriStr = authority match {
        case Some(host) => s"$schemeStr://$host$pathStr"
        case None => pathStr
      }
      uri <- Uri.parse(uriStr).mapError(e => HttpError.InvalidUri(e))

      regularHeaders = h2Headers.filter { case (name, _) => !name.startsWith(":") }
      headers <- regularHeaders.foldLeft(Eru.succeed(Headers.empty): Eru[HttpError, Headers]) {
        case (acc, (name, value)) =>
          acc.flatMap(_.add(name, value).mapError(e => HttpError.ProtocolError(s"Invalid header: $e", "RFC 9113")))
      }

      requestBody: Body = body match {
        case Some(bytes) if bytes.nonEmpty => Body.Binary(Bytes.fromArray(bytes), None)
        case _ => Body.Empty
      }

    } yield Request(method, uri, headers, requestBody, HttpVersion.HTTP_2_0)
  }

  /** Convert H2Error to HttpError. */
  private def h2ErrorToHttpError(error: H2Error): HttpError = error match {
    case H2Error.ConnectionError(code, msg) =>
      HttpError.ProtocolError(s"HTTP/2 connection error ($code): ${msg.getOrElse("unknown")}", "RFC 9113")
    case H2Error.StreamError(streamId, code, msg) =>
      HttpError.ProtocolError(s"HTTP/2 stream $streamId error ($code): ${msg.getOrElse("unknown")}", "RFC 9113")
    case H2Error.FlowControlViolation(streamId, msg) =>
      HttpError.ProtocolError(s"HTTP/2 flow control violation on stream $streamId: $msg", "RFC 9113 Section 5.2")
    case H2Error.StreamStateViolation(streamId, msg) =>
      HttpError.ProtocolError(s"HTTP/2 stream state violation on stream $streamId: $msg", "RFC 9113 Section 5.1")
    case H2Error.ProtocolViolation(msg, _) =>
      HttpError.ProtocolError(s"HTTP/2 protocol violation: $msg", "RFC 9113")
    case H2Error.InvalidPreface(msg) =>
      HttpError.ProtocolError(s"Invalid HTTP/2 connection preface: $msg", "RFC 9113 Section 3.4")
    case H2Error.InvalidFrame(msg, rfc) =>
      HttpError.ProtocolError(s"HTTP/2 frame error: $msg", rfc)
    case H2Error.CompressionError(msg) =>
      HttpError.ProtocolError(s"HTTP/2 compression error: $msg", "RFC 7541")
    case H2Error.SettingsError(msg) =>
      HttpError.ProtocolError(s"HTTP/2 settings error: $msg", "RFC 9113 Section 6.5")
    case H2Error.NetworkError(msg, cause) =>
      HttpError.NetworkError(s"HTTP/2 network error: $msg", cause)
  }

  /** Handle requests in a loop for HTTP keep-alive support.
    *
    * Continues handling requests on the same channel until:
    *   - Connection: close header is received
    *   - An error occurs
    *   - Channel is closed by client
    *
    * A direct write buffer is borrowed from the pool for this connection's lifetime. Direct buffers
    * avoid the JVM's hidden heap→direct copy on blocking channel.write(); the pool is bounded at
    * config.maxConnections, so direct memory is capped regardless of allocation/free churn.
    * withResource handles acquire/release via bracket, so the permit is released on success,
    * failure, and interruption.
    *
    * The request loop reads keep-alive requests in two phases (mirrors nginx client_header_timeout
    * + keepalive_timeout): `idleTimeout` bounds the silent gap between the previous response and
    * the client's first byte of the next request, and `readHeaderTimeout` bounds the header phase
    * of every request — first or keep-alive — so a slow-drip client cannot stretch a header phase
    * past the Slowloris deadline.
    *
    * maxRequestSize rejects oversize bodies via HttpError.PayloadTooLarge BEFORE allocating,
    * preventing remote OOM from attacker-controlled Content-Length.
    *
    * Parse/validation failures always write a response before closing (413 with the detail message
    * for PayloadTooLarge; 408 for timeout, 400 otherwise, via errorToResponse) so well-formed
    * clients know what happened. The connection closes after any parse error because the byte
    * stream is in an undefined state — partial body bytes may still be queued, partial header
    * buffers may have been consumed, and the reader's preserved-bytes invariant for pipelining
    * assumes a clean request boundary. The RFC 9110 validators the Request type defines
    * (bodyless-method+body, HTTP/1.1 missing Host, redundant CL+TE check) are answered the same
    * way: 400 written, then close.
    */
  private def handleRequestLoop(
    channel: ReadableByteChannel & WritableByteChannel,
    socket: SocketChannel
  ): Eru[HttpError, Unit] = {
    writeBufferPool.withResource(buf => runRequestLoop(channel, socket, buf)).eru
  }

  private def runRequestLoop(
    channel: ReadableByteChannel & WritableByteChannel,
    socket: SocketChannel,
    writeBuffer: java.nio.ByteBuffer
  ): Eru[HttpError, Unit] = {
    val seed = Option(replayBytes.remove(socket)).getOrElse(Array.emptyByteArray)
    val reader = new net.ghoula.eru.http.BufferedSocketReader(channel, seedBytes = seed)

    def loop(isFirstRequest: Boolean = true): Eru[HttpError, Boolean] = {
      val requestEffect = for {
        _ <- if !isFirstRequest then Eru.effect(reader.reset()) else Eru.unit

        // Two-phase keep-alive read (nginx client_header_timeout + keepalive_timeout semantics):
        // idleTimeout bounds the SILENT gap between the previous response and the client's first
        // byte of the next request; once bytes flow, readHeaderTimeout bounds the header phase of
        // EVERY request. The deadlines are socket-level (SO_TIMEOUT through the socket's
        // InputStream / SSLSocketChannel raw reads), so a header-phase timeout leaves the channel
        // OPEN and a 408 can still be written — the Eru .timeout wrappers below are backstops set
        // two seconds past the socket deadline and only fire if the deadline mechanism fails.
        _ <-
          if isFirstRequest || reader.hasBufferedData then {
            reader.readTimeoutMillis = config.readHeaderTimeout.toMillis.toInt
            Eru.unit
          } else {
            reader.readTimeoutMillis = config.idleTimeout.toMillis.toInt
            Eru
              .effect(reader.awaitData())
              .flatMap {
                case true => Eru.unit
                case false =>
                  Eru.fail(HttpError.NetworkError("Connection closed while keep-alive idle", None))
              }
              .mapError {
                case _: java.net.SocketTimeoutException =>
                  HttpError.TimeoutError(s"Keep-alive idle timeout after ${config.idleTimeout}")
                case _: java.util.concurrent.TimeoutException =>
                  HttpError.TimeoutError(s"Keep-alive idle timeout after ${config.idleTimeout}")
                case e: HttpError => e
                case e: Throwable => HttpError.NetworkError(e.getMessage, Some(e))
              }
          }

        requestResult <- {
          reader.readTimeoutMillis = config.readHeaderTimeout.toMillis.toInt
          // No Eru .timeout backstop here anymore: every read in the parse goes through the
          // reader's socket-level deadline (header phase) or the body deadline set below for lazy
          // pulls, and a total-duration backstop would kill legitimately slow large uploads.
          HttpParser
            .parseRequest(
              reader,
              config.maxRequestSize,
              onHeaders = continueIfExpected(channel, writeBuffer)
            )
            .mapError {
              // The parser types its own read deadlines as TimeoutError; other failures are
              // HttpErrors already.
              case e: HttpError => e
            }
            .attempt
        }

        parsedRequest <- requestResult match {
          case Result.Success(req) =>
            // Request fully framed: switch the reader to the body-phase deadline for the lazy
            // chunked pulls the handler drives (Content-Length bodies were already read).
            reader.readTimeoutMillis = config.bodyReadTimeout.toMillis.toInt
            Eru.succeed(req)
          case Result.Failure(tooLarge: HttpError.PayloadTooLarge) =>
            sendPayloadTooLargeResponse(channel, writeBuffer, tooLarge).flatMap(_ =>
              Eru.fail(HttpError.NetworkError("Connection closed after 413", None))
            )
          case Result.Failure(err) =>
            sendParseErrorResponse(channel, writeBuffer, err).flatMap(_ =>
              Eru.fail(HttpError.NetworkError("Connection closed after parse error", None))
            )
        }

        validateResult <- parsedRequest.validate.attempt
        validated <- validateResult match {
          case Result.Success(req) => Eru.succeed(req)
          case Result.Failure(invalid) =>
            sendParseErrorResponse(channel, writeBuffer, HttpError.InvalidRequest(invalid)).flatMap(_ =>
              Eru.fail(HttpError.NetworkError("Connection closed after validation failure", None))
            )
        }

        request <- checkStrictPath(validated) match {
          case None => Eru.succeed(validated)
          case Some(err) =>
            sendParseErrorResponse(channel, writeBuffer, err).flatMap(_ =>
              Eru.fail(HttpError.NetworkError("Connection closed after strict-path rejection", None))
            )
        }

        rateCheck <- checkRequestRate(request, socket)
        result <- rateCheck match {
          case Some(retryAfter) =>
            send429Response(channel, writeBuffer, retryAfter)
              .map(_ => shouldKeepAlive(request, empty429Response))
          case None =>
            runHandlerAndWrite(request, channel, writeBuffer, reader)
        }
      } yield result

      requestEffect.attempt.flatMap {
        case Result.Success(true) => loop(false)
        case Result.Success(false) => Eru.succeed(false)
        case Result.Failure(_) => Eru.succeed(false)
      }
    }

    loop().map(_ => ())
  }

  /** A canonical 429 response shape used as the `response` argument to `shouldKeepAlive` after
    * we've already written the real 429 to the wire. It carries no `Connection` header, so
    * `shouldKeepAlive` falls through to the request's connection preference — i.e. HTTP/1.1
    * keep-alive remains keep-alive, HTTP/1.0 or explicit `Connection: close` remains closing. Rate
    * limiting does NOT force-close a client's connection.
    */
  private val empty429Response: Response[Body] =
    Response(StatusCode.TooManyRequests, Headers.empty, Body.Empty)

  /** If `config.strictPathValidation` is enabled, check the request path for control characters
    * (0x00–0x1F and 0x7F). Returns `Some(HttpError.InvalidRequest)` on rejection so the caller can
    * surface a 400 via `sendParseErrorResponse`. Otherwise `None`.
    *
    * This is a pure check — no decoding, no normalization. Percent-encoded bytes (`%00`, `%2f`,
    * etc.) are untouched: `%`, `0`, `0` are all printable ASCII and pass.
    */
  private def checkStrictPath(request: Request[Body]): Option[HttpError] =
    if !config.strictPathValidation then None
    else {
      val path = request.uri.path
      path.find(c => c < 0x20 || c == 0x7f).map { c =>
        HttpError.InvalidRequest(
          InvalidRequest(
            s"Request path contains control character (U+${"%04X".format(c.toInt)})",
            "RFC 9110 Section 4 / RFC 3986 Section 3.3"
          )
        )
      }
    }

  /** Run the request-rate check. Returns:
    *   - `None` — governance disabled or request allowed. Caller proceeds with the handler.
    *   - `Some(retryAfterSeconds)` — over-budget. Caller MUST write a 429 response.
    */
  private def checkRequestRate(request: Request[Body], socket: SocketChannel): Eru[Nothing, Option[Int]] =
    Eru.succeed {
      if !config.perIpGovernanceEnabled then None
      else
        perIpGovernor.flatMap { governor =>
          Option(clientIps.get(socket)).flatMap { peerIp =>
            val subjectIp = resolveRateLimitIp(request, peerIp)
            governor.tryAcquireRequest(subjectIp) match {
              case PerIpGovernor.AcquireResult.Ok => None
              case _ => Some(governor.requestRateRetryAfterSeconds(subjectIp))
            }
          }
        }
    }

  /** Run the user handler for `request`, convert its result to a response, handle WebSocket
    * upgrade, write the response to the wire, and decide whether to keep the connection alive.
    *
    * Extracted from the for-comprehension inside `runRequestLoop` so the rate-limit path can
    * short-circuit before ever reaching the handler.
    */
  private def runHandlerAndWrite(
    request: Request[Body],
    channel: ReadableByteChannel & WritableByteChannel,
    writeBuffer: java.nio.ByteBuffer,
    reader: net.ghoula.eru.http.BufferedSocketReader
  ): Eru[HttpError, Boolean] =
    for {
      handlerResult <- handler(request).attempt

      response <- handlerResult match {
        case Result.Success(resp) => addConnectionHeader(request, resp)
        case Result.Failure(httpError: HttpError) =>
          errorToResponse(httpError).flatMap(addConnectionHeader(request, _))
      }

      isWebSocketUpgrade = WebSocketServer.isUpgradeResponse(response)

      responseToSend =
        if isWebSocketUpgrade then response.copy(headers = response.headers.remove("X-WebSocket-Handler-Id"))
        else response

      _ <- HttpWriter.writeResponseWithBuffer(channel, responseToSend, writeBuffer)

      keepAlive <-
        if isWebSocketUpgrade then {
          WebSocketServer.getHandlerId(response) match {
            case Some(handlerId) =>
              WebSocketServer.retrieveHandler(handlerId) match {
                case Some(pending) =>
                  val wsConn = NativeServerWebSocketConnection.create(
                    channel,
                    reader,
                    pending.config,
                    pending.subprotocol,
                    pending.request
                  )
                  pending.handler(wsConn).attempt.map(_ => false)
                case None =>
                  Eru.succeed(shouldKeepAlive(request, responseToSend))
              }
            case None =>
              Eru.succeed(shouldKeepAlive(request, responseToSend))
          }
        } else Eru.succeed(shouldKeepAlive(request, responseToSend))
    } yield keepAlive

  /** Check if connection should be kept alive based on request/response headers.
    *
    * A response or request `Connection: close` closes the connection. Default for HTTP/1.1 is
    * keep-alive; HTTP/1.0 stays open only when the response explicitly says `keep-alive`.
    */
  private def shouldKeepAlive(request: Request[Body], response: Response[Body]): Boolean = {
    val responseConnection = response.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)

    if responseConnection.contains("close") then false
    else {
      val requestConnection = request.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)

      if requestConnection.contains("close") then false
      else {
        request.version == HttpVersion.HTTP_1_1 || responseConnection.contains("keep-alive")
      }
    }
  }

  /** Add Connection and Content-Length/Transfer-Encoding headers to response if not present.
    *
    * HTTP/1.1 requires either Content-Length or Transfer-Encoding for message framing. This ensures
    * we send proper headers for keep-alive support.
    *
    * If the client requests Connection: close, we echo it back in the response.
    */
  private def addConnectionHeader(request: Request[Body], response: Response[Body]): Eru[Nothing, Response[Body]] = {
    val withContentLength: Eru[Nothing, Response[Body]] =
      if response.headers.contains(HeaderNames.ContentLength) ||
        response.headers.contains(HeaderNames.TransferEncoding)
      then {
        Eru.succeed(response)
      } else {
        val headerEffect = response.body match {
          case Body.Empty =>
            response.headers.add(HeaderNames.ContentLength, "0")

          case Body.Text(_, _, _) | Body.Binary(_, _) =>
            response.body.contentLength match {
              case Some(length) =>
                response.headers.add(HeaderNames.ContentLength, length.toString)
              case None =>
                response.headers.add(HeaderNames.ContentLength, "0")
            }

          case Body.Stream(_, contentLength, _) =>
            contentLength match {
              case Some(length) =>
                response.headers.add(HeaderNames.ContentLength, length.toString)
              case None =>
                response.headers.add(HeaderNames.TransferEncoding, "chunked")
            }
        }
        headerEffect
          .map(newHeaders => response.copy(headers = newHeaders))
          .attempt
          .map {
            case Result.Success(r) => r
            case Result.Failure(_) => response
          }
      }

    withContentLength.flatMap { resp =>
      if resp.headers.contains(HeaderNames.Connection) then {
        Eru.succeed(resp)
      } else {
        val requestConnection = request.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
        val connectionValue = if requestConnection.contains("close") then "close" else "keep-alive"

        resp.headers
          .add(HeaderNames.Connection, connectionValue)
          .map(newHeaders => resp.copy(headers = newHeaders))
          .attempt
          .map {
            case Result.Success(r) => r
            case Result.Failure(_) => resp
          }
      }
    }
  }

  /** Wrap socket with TLS/SSL.
    *
    * Uses SSLEngine with blocking mode. The handshake blocks the Virtual Thread, which is efficient
    * since VTs are cheap. ALPN is used to negotiate HTTP/2 or HTTP/1.1.
    *
    * The handshake is wrapped in `.timeout(config.tlsHandshakeTimeout)` so that a
    * Slowloris-over-TLS client (opens TCP, starts ClientHello, stalls) cannot park a handler VT
    * forever. `readHeaderTimeout` only covers `parseRequest`, which runs AFTER handshake.
    *
    * Why `.timeout` and not `setSoTimeout`: `SocketChannel.read(ByteBuffer)` in blocking mode does
    * NOT honor `SO_TIMEOUT` set on the underlying `java.net.Socket` — that's a documented JDK
    * behavior. A SO_TIMEOUT-based fix would not actually trip. Eru's `.timeout` interrupts the
    * fiber via Thread.interrupt, which makes the blocking `read` throw `ClosedByInterruptException`
    * (also closing the channel), unblocking the VT immediately. The caller's cleanup chain then
    * proceeds normally.
    *
    * `tlsConfig` is guaranteed present here: this method only runs when `sslContext` is `Some`,
    * which is only built from `Some(tlsConfig)` in `NativeHttpServer.create`.
    */
  private def wrapWithTLS(
    socket: SocketChannel,
    ctx: SSLContext,
    preRead: Array[Byte]
  ): Eru[HttpError, SSLSocketChannel] =
    Eru.effect {
      val tlsConfig = config.tlsConfig.getOrElse(TlsConfig.default)
      val alpnProtocols =
        if config.enableHttp2 then SSLSocketChannel.Http2Protocols else SSLSocketChannel.Http1Protocols
      val sslChannel = SSLSocketChannel.server(
        socket,
        ctx,
        alpnProtocols,
        protocols = tlsConfig.protocols,
        cipherSuites = tlsConfig.cipherSuites,
        preRead = preRead
      )
      sslChannel.doHandshake()
      sslChannel
    }.timeout(java.time.Duration.ofMillis(config.tlsHandshakeTimeout.toMillis)).mapError {
      case _: java.util.concurrent.TimeoutException =>
        HttpError.TimeoutError(s"TLS handshake timeout after ${config.tlsHandshakeTimeout}")
      case e: Throwable =>
        HttpError.NetworkError(s"TLS handshake failed: ${e.getMessage}", Some(e))
    }

  /** Send a 429 Too Many Requests response with `Retry-After` + `X-RateLimit-*` headers.
    *
    * Called when the per-IP request rate bucket is empty. Unlike 413, the connection stays open —
    * we are rate-limiting THIS request, not terminating the client. Keep-alive continues to work;
    * subsequent requests that pass the bucket succeed normally.
    */
  private def send429Response(
    channel: ReadableByteChannel & WritableByteChannel,
    writeBuffer: java.nio.ByteBuffer,
    retryAfterSeconds: Int
  ): Eru[HttpError, Unit] = {
    val message = s"Too Many Requests - retry after ${retryAfterSeconds}s.\n"
    val body = Body.Text(message, None, Charset.UTF8)
    val contentLength = message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    val resetEpoch = (System.currentTimeMillis() / 1000L) + retryAfterSeconds
    val headersEffect = Headers.empty
      .add(HeaderNames.ContentType, "text/plain; charset=utf-8")
      .flatMap(_.add(HeaderNames.ContentLength, contentLength.toString))
      .flatMap(_.add(HeaderNames.RetryAfter, retryAfterSeconds.toString))
      .flatMap(_.add("X-RateLimit-Limit", config.requestsPerSecondPerIp.toString))
      .flatMap(_.add("X-RateLimit-Remaining", "0"))
      .flatMap(_.add("X-RateLimit-Reset", resetEpoch.toString))
      .attempt
      .map {
        case Result.Success(h) => h
        case Result.Failure(_) => Headers.empty
      }

    headersEffect.flatMap { headers =>
      val response = Response(status = StatusCode.TooManyRequests, headers = headers, body = body)
      HttpWriter.writeResponseWithBuffer(channel, response, writeBuffer).attempt.map(_ => ())
    }
  }

  /** Resolve the rate-limit-subject IP for a request.
    *
    * Default is the TCP peer IP we captured at accept time. If `config.trustedProxies` is non-empty
    * AND the TCP peer falls inside one of those CIDRs AND the request carries `X-Forwarded-For`,
    * parse the header and use its leftmost untrusted IP as the real client.
    *
    * Malformed XFF values silently fall back to the TCP peer — we never let a bogus header bypass
    * rate limiting.
    */
  private def resolveRateLimitIp(request: Request[Body], tcpPeerIp: IpKey): IpKey = {
    val peerTrusted = config.trustedProxies.exists(_.contains(tcpPeerIp))
    if !peerTrusted then tcpPeerIp
    else {
      request.headers.getFirst("X-Forwarded-For") match {
        case None => tcpPeerIp
        case Some(headerValue) =>
          leftmostUntrustedIp(headerValue.value).getOrElse(tcpPeerIp)
      }
    }
  }

  /** Walk an `X-Forwarded-For` chain left-to-right, returning the leftmost IP that is NOT inside
    * any configured trusted-proxies CIDR.
    *
    * The header format is a comma-separated list: `client, proxy1, proxy2`. The leftmost entry is
    * the original client as reported by the first proxy. If every entry is a trusted proxy (e.g. a
    * pure proxy chain with no real client), return None — caller falls back to TCP peer.
    */
  private def leftmostUntrustedIp(xff: String): Option[IpKey] = {
    val parts = xff.split(',').iterator.map(_.trim).filter(_.nonEmpty)
    parts
      .flatMap(p => IpKey.parse(p))
      .find(ip => !config.trustedProxies.exists(_.contains(ip)))
  }

  /** Send a 413 Content Too Large response and mark the connection to close.
    *
    * Called when the parser rejects a request because Content-Length or cumulative chunked body
    * size exceeds `config.maxRequestSize`. We build the response manually (bypassing the normal
    * handler path) because we haven't successfully parsed a Request and must not call the user's
    * handler with invalid input.
    */
  private def sendPayloadTooLargeResponse(
    channel: ReadableByteChannel & WritableByteChannel,
    writeBuffer: java.nio.ByteBuffer,
    error: HttpError.PayloadTooLarge
  ): Eru[HttpError, Unit] = {
    val body = Body.Text(error.message, None, Charset.UTF8)
    val contentLength = error.message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    val headersEffect = Headers.empty
      .add(HeaderNames.ContentType, "text/plain; charset=utf-8")
      .flatMap(_.add(HeaderNames.ContentLength, contentLength.toString))
      .flatMap(_.add(HeaderNames.Connection, "close"))
      .attempt
      .map {
        case Result.Success(h) => h
        case Result.Failure(_) => Headers.empty
      }

    headersEffect.flatMap { headers =>
      val response = Response(status = StatusCode.ContentTooLarge, headers = headers, body = body)
      HttpWriter.writeResponseWithBuffer(channel, response, writeBuffer).attempt.map(_ => ())
    }
  }

  /** Write an HTTP response for a request-parse failure, then close.
    *
    * `errorToResponse` already maps every HttpError to (status, message) — we just tack on a
    * `Connection: close` header because the byte stream is in an undefined state after a parse
    * failure and we cannot safely keep-alive. Body write failures are swallowed: at this point the
    * TCP peer may already be gone, and we're about to close the socket regardless.
    */
  private def sendParseErrorResponse(
    channel: ReadableByteChannel & WritableByteChannel,
    writeBuffer: java.nio.ByteBuffer,
    error: HttpError
  ): Eru[HttpError, Unit] =
    errorToResponse(error).flatMap { baseResponse =>
      baseResponse.headers
        .add(HeaderNames.Connection, "close")
        .attempt
        .map {
          case Result.Success(h) => baseResponse.copy(headers = h)
          case Result.Failure(_) => baseResponse
        }
        .flatMap { response =>
          HttpWriter.writeResponseWithBuffer(channel, response, writeBuffer).attempt.map(_ => ())
        }
    }

  /** Convert HTTP error to error response
    */
  private def errorToResponse(error: HttpError): Eru[Nothing, Response[Body]] = {
    val (status, message) = error match {
      case HttpError.InvalidMethod(_) =>
        (StatusCode.BadRequest, "Bad Request: Invalid HTTP method")
      case HttpError.InvalidUri(_) =>
        (StatusCode.BadRequest, "Bad Request: Invalid URI")
      case HttpError.InvalidRequest(_) =>
        (StatusCode.BadRequest, "Bad Request")
      case HttpError.InvalidResponse(_) =>
        (StatusCode.InternalServerError, "Internal Server Error")
      case HttpError.TimeoutError(_) =>
        (StatusCode.RequestTimeout, "Request Timeout")
      case e: HttpError.PayloadTooLarge =>
        (StatusCode.ContentTooLarge, e.message)
      case HttpError.BodyDecodeError(e) =>
        // A request body that cannot be decoded (including a chunked body that failed framing
        // mid-stream) is the client's error, not a server fault: answer 400, carrying a bounded
        // version of the decode message.
        (StatusCode.BadRequest, s"Bad Request: ${e.message.take(200)}")
      case _ =>
        (StatusCode.InternalServerError, "Internal Server Error")
    }

    val body = Body.Text(message, None, Charset.UTF8)
    val contentType = "text/plain; charset=utf-8"

    Headers.empty
      .add(HeaderNames.ContentType, contentType)
      .flatMap(_.add(HeaderNames.ContentLength, message.getBytes.length.toString))
      .attempt
      .map {
        case Result.Success(h) => h
        case Result.Failure(_) => Headers.empty
      }
      .map(headers => Response(status = status, headers = headers, body = body))
  }

  /** Stop the server.
    *
    * Signals `STOPPING=1` to systemd if a watchdog is configured, then:
    *   1. Closes every ServerSocketChannel, causing the blocking accept() in each accept loop to
    *      throw so those loops exit cleanly. The accept loops are not in handlerTracker — they are
    *      long-lived daemon-like fibers forked at startup that die when their server socket closes.
    *   2. Closes all client sockets, breaking any in-flight socket.read() calls so handler fibers
    *      unblock promptly instead of waiting for readHeaderTimeout/idleTimeout — otherwise a
    *      handler parked on a long-lived keep-alive connection could keep shutdown running for up
    *      to idleTimeout.
    *   3. Drains the handler tracker: each tracked handler fiber is interrupted (if still active)
    *      and awaited. This runs the `.ensure(cleanup)` finalizer registered in handleClient —
    *      socket close, permit release, write-buffer pool return — SYNCHRONOUSLY before shutdown
    *      returns. Each await is bounded by gracefulShutdownTimeout so a stuck handler cannot stall
    *      shutdown indefinitely.
    *
    * A second call is a no-op (`running` is only flipped true→false once).
    */
  def shutdown: Eru[HttpError, Unit] = {
    if running.compareAndSet(true, false) then {
      Watchdog
        .stopping()
        .attempt
        .flatMap(_ =>
          Eru.effect {
            serverSockets.foreach { socket =>
              try socket.close()
              catch { case _: Exception => () }
            }

            val clients = activeClients.keySet().iterator()
            while clients.hasNext() do {
              val socket = clients.next()
              try socket.close()
              catch { case _: Exception => () }
            }
            activeClients.clear()

            drainHandlerTracker(config.gracefulShutdownTimeout)
            ()
          }.mapError(e => HttpError.NetworkError(s"Error during shutdown: ${e.getMessage}", Some(e)))
        )
    } else {
      Eru.succeed(())
    }
  }

  /** Drain the handlerTracker: interrupt any active fibers, await all to completion.
    *
    * After this returns, every handler fiber has finished and its finalizers have run — sockets are
    * closed, permits are released, buffers returned to the pool.
    *
    * The queue is snapshotted before iteration — new fibers can't be added once accept loops have
    * exited, but a stable list avoids mutation-during-iteration. Every fiber is awaited — even
    * already-completed fibers may have pending finalizers — and each await is bounded by
    * gracefulShutdownTimeout so a handler that ignores interruption cannot stall shutdown forever.
    * Per-fiber errors are swallowed; shutdown must complete.
    *
    * Inspired by RuntimeBackendAdapter.shutdownRootFibers but operating on our per-server tracker
    * rather than the runtime-global queue. Also blocks on InterruptCause.ParentTerminated to signal
    * why we're cancelling.
    */
  private def drainHandlerTracker(gracefulShutdownTimeout: scala.concurrent.duration.Duration): Unit = {
    val parentId = FiberId.fresh()
    val cause = InterruptCause.ParentTerminated(parentId, Exit.Success(()))
    val queue = handlerTracker.queue

    val fibers = scala.collection.mutable.ListBuffer.empty[UnifiedFiber[?, ?]]
    var f = Option(queue.poll())
    while f.nonEmpty do {
      fibers += f.get
      f = Option(queue.poll())
    }

    fibers.foreach { fiber =>
      try {
        val active = fiber.currentState match {
          case _: UnifiedFiberState.Active[?, ?] => true
          case _: UnifiedFiberState.Completed[?, ?] => false
        }
        if active then {
          fiber.interrupt(cause).attempt.unsafeRunSync(): Unit
        }
        fiber.await
          .timeout(java.time.Duration.ofMillis(gracefulShutdownTimeout.toMillis))
          .attempt
          .unsafeRunSync(): Unit
      } catch {
        case _: Exception => ()
      }
    }
  }

  def isRunning: Boolean = running.get()
}

private[server] object NativeHttpServer {

  /** Try to enable SO_REUSEPORT for multi-threaded accept.
    *
    * Returns Some(sockets) if successful, None if SO_REUSEPORT is not supported on this platform.
    * SO_REUSEPORT is available in StandardSocketOptions since Java 9.
    */
  private def tryEnableReusePort(numAcceptors: Int): Option[List[ServerSocketChannel]] = {
    try {
      val sockets = (0 until numAcceptors).map { _ =>
        val socket = ServerSocketChannel.open()
        socket.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
        socket.setOption(java.net.StandardSocketOptions.SO_REUSEPORT, java.lang.Boolean.TRUE)
        socket
      }.toList

      Some(sockets)
    } catch {
      case _: UnsupportedOperationException => None
      case _: Exception => None
    }
  }

  /** Create a native HTTP server.
    *
    * With acceptorThreads > 1, creates multiple ServerSocketChannels with SO_REUSEPORT for
    * kernel-level load balancing (Linux 3.9+). Each acceptor runs its own accept loop.
    *
    * A pool of direct write buffers (one per max-concurrent-connection, 8KB each) bounds direct
    * memory to maxConnections * 8KB regardless of connection churn and avoids the JVM's hidden
    * heap→direct buffer copy on every channel.write() call.
    *
    * The per-IP governor is built only when per-IP governance is enabled and lives for the lifetime
    * of the server; its bounded Caffeine cache self-evicts idle entries.
    */
  def create(
    config: HttpServerConfig,
    handler: RequestHandler
  )(using runtime: EruRuntime): Eru[HttpError, HttpServer] = {
    for {
      serverSockets <- Eru.effect {
        val requestedAcceptors = config.acceptorThreads.max(1)

        val (actualAcceptors, sockets) = if requestedAcceptors > 1 then {
          tryEnableReusePort(requestedAcceptors) match {
            case Some(socketsWithReusePort) =>
              (requestedAcceptors, socketsWithReusePort)
            case None =>
              System.err.println(
                "[WARN] SO_REUSEPORT not available on this JDK/platform. " +
                  s"Falling back to single-threaded accept (acceptorThreads=${requestedAcceptors} -> 1). " +
                  "For best performance on Linux, use a JDK that supports jdk.net.ExtendedSocketOptions.SO_REUSEPORT"
              )
              (1, List(ServerSocketChannel.open()))
          }
        } else {
          (1, List(ServerSocketChannel.open()))
        }

        if actualAcceptors == 1 && requestedAcceptors > 1 then {
          System.err.println("[INFO] Using single-threaded accept loop. CPU scaling will be limited.")
        } else if actualAcceptors > 1 then {
          System.err.println(
            s"[INFO] Using SO_REUSEPORT with ${actualAcceptors} acceptor threads for multi-core scaling"
          )
        }

        sockets
      }.mapError(e => HttpError.NetworkError(s"Failed to create server sockets: ${e.getMessage}", Some(e)))

      sslContext <- config.tlsConfig match {
        case Some(tlsConfig) => createSSLContext(tlsConfig).map(Some(_))
        case None => Eru.succeed(None)
      }

      connSem <- Semaphore
        .make(config.maxConnections.toLong)
        .mapError(e => HttpError.NetworkError(s"Failed to create connection semaphore: $e", None))

      writeBufferPool <- Pools
        .directByteBuffers(capacity = config.maxConnections, bufferSize = 8192)
        .mapError(e => HttpError.NetworkError(s"Failed to create write buffer pool: $e", None))

      governor =
        if config.perIpGovernanceEnabled then
          Some(
            new PerIpGovernor(
              trackedIpCap = config.trackedIpCap,
              maxConnectionsPerIp = config.maxConnectionsPerIp,
              acceptRatePerIp = config.acceptRatePerIp.toDouble,
              acceptBurstPerIp = config.acceptBurstPerIp.toDouble,
              requestsPerSecondPerIp = config.requestsPerSecondPerIp.toDouble,
              burstSizePerIp = config.burstSizePerIp.toDouble
            )
          )
        else None

      server = new NativeHttpServer(config, handler, serverSockets, sslContext, connSem, writeBufferPool, governor)

    } yield server
  }

  /** Create SSL context from TLS configuration.
    *
    * Loads server certificate and key from the configured keystore.
    */
  private def createSSLContext(tlsConfig: TlsConfig): Eru[HttpError, SSLContext] =
    Eru.effect {
      SSLContextFactory.createServerContext(tlsConfig)
    }.mapError(e => HttpError.NetworkError(s"Failed to create SSL context: ${e.getMessage}", Some(e)))
}
