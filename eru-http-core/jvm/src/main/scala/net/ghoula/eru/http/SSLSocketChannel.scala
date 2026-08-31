package net.ghoula.eru.http

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, SocketChannel, WritableByteChannel}
import javax.net.ssl.SSLEngineResult.{HandshakeStatus, Status}
import javax.net.ssl.{SSLContext, SSLEngine}

/** TLS wrapper for SocketChannel implementing channel interfaces.
  *
  * Wraps a plain SocketChannel with TLS encryption/decryption using Java's SSLEngine. Implements
  * both ReadableByteChannel and WritableByteChannel for transparent integration with existing HTTP
  * infrastructure.
  *
  * Uses blocking I/O which is efficient with Virtual Threads. The handshake is performed
  * synchronously via `doHandshake()`.
  *
  * ==Thread safety==
  *
  * Read operations are NOT thread-safe and should only be called from a single thread. Write
  * operations are serialized by an internal lock, so concurrent writes from multiple threads are
  * safe (useful for HTTP/2 where multiple streams may send responses concurrently).
  *
  * SSLEngine is not designed for concurrent access, so a read() and a write() must not run at the
  * same time. The connection pool ensures exclusive access by tracking connections in an "inUse"
  * set - a connection is only returned to the available pool after the request completes.
  *
  * For connection reuse across sequential requests (HTTP keep-alive), the same SSLSocketChannel
  * instance is reused, which is safe because each request completes before the next begins.
  *
  * @param socket
  *   The underlying TCP socket channel
  * @param engine
  *   Configured SSLEngine for encryption/decryption
  */
final class SSLSocketChannel private (
  socket: SocketChannel,
  engine: SSLEngine,
  preRead: Array[Byte]
) extends ReadableByteChannel
    with WritableByteChannel {

  /** Bytes consumed from the raw socket BEFORE the TLS stream (e.g. a PROXY protocol preamble was
    * already parsed, or protocol-detection peeked bytes that turned out not to be a preamble).
    * Drained before any network read so the TLS handshake sees the stream untouched.
    */
  private val preReadBuffer: ByteBuffer = ByteBuffer.wrap(preRead)

  /** Buffer sizes come from the SSLEngine session (typically ~16KB each). */
  private val session = engine.getSession
  private val appBufferSize = session.getApplicationBufferSize
  private val netBufferSize = session.getPacketBufferSize

  /** Network buffer for encrypted data, initialized empty in read mode (position=0, limit=0). */
  private val netInBuffer = {
    val buf = ByteBuffer.allocateDirect(netBufferSize)
    buf.flip()
    buf
  }
  private val netOutBuffer = ByteBuffer.allocateDirect(netBufferSize)

  /** Application buffer for decrypted incoming data, initialized empty in read mode. */
  private val appInBuffer = {
    val buf = ByteBuffer.allocateDirect(appBufferSize)
    buf.flip()
    buf
  }

  private var handshakeComplete = false

  private val writeLock = new java.util.concurrent.locks.ReentrantLock()

  /** Debug logging, enabled with `-Dssl.debug=true`. */
  private val debug = java.lang.Boolean.getBoolean("ssl.debug")

  private def log(msg: String): Unit =
    if debug then println(s"[SSL ${if engine.getUseClientMode then "CLIENT" else "SERVER"}] $msg")

  /** Perform TLS handshake.
    *
    * This is a blocking operation that completes the full TLS handshake. Must be called before
    * reading or writing data. Safe to use with Virtual Threads.
    *
    * After handshake, appInBuffer is reset to empty read mode: handshake unwraps produce no
    * application data, but `clear()` leaves the buffer in write mode.
    *
    * @throws Exception
    *   if handshake fails
    */
  def doHandshake(): Unit = {
    log("Starting handshake")
    engine.beginHandshake()
    runHandshake()

    appInBuffer.clear()
    appInBuffer.flip(): Unit

    log(
      s"Handshake complete. netInBuffer: pos=${netInBuffer.position()}, lim=${netInBuffer.limit()}, rem=${netInBuffer.remaining()}"
    )
    handshakeComplete = true
  }

  /** Get the negotiated application protocol from ALPN.
    *
    * Must be called after `doHandshake()` completes. Java's SSLEngine.getApplicationProtocol
    * returns null when no ALPN was negotiated, which is mapped to "".
    *
    * @return
    *   the negotiated protocol ("h2", "http/1.1", or "" if no ALPN)
    */
  def getApplicationProtocol: String = {
    Option(engine.getApplicationProtocol).getOrElse("")
  }

  /** Check if HTTP/2 was negotiated via ALPN.
    *
    * @return
    *   true if "h2" was negotiated
    */
  def isHttp2: Boolean = getApplicationProtocol == "h2"

  /** Run the handshake state machine until complete.
    */
  private def runHandshake(): Unit = {
    var status = engine.getHandshakeStatus

    while status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING do {
      status = status match {
        case HandshakeStatus.NEED_UNWRAP => doUnwrap()
        case HandshakeStatus.NEED_WRAP => doWrap()
        case HandshakeStatus.NEED_TASK => runDelegatedTasks()
        case _ => status
      }
    }
  }

  /** Unwrap incoming TLS data during handshake or renegotiation.
    */
  private def doUnwrap(): HandshakeStatus = {
    if !netInBuffer.hasRemaining then {
      netInBuffer.clear()
      val bytesRead = rawRead(netInBuffer)
      if bytesRead < 0 then {
        throw new java.io.EOFException("Connection closed during TLS handshake")
      }
      netInBuffer.flip(): Unit
    }

    appInBuffer.clear()
    val result = engine.unwrap(netInBuffer, appInBuffer)

    result.getStatus match {
      case Status.OK => ()
      case Status.BUFFER_UNDERFLOW =>
        netInBuffer.compact()
        val bytesRead = rawRead(netInBuffer)
        if bytesRead < 0 then {
          throw new java.io.EOFException("Connection closed during TLS handshake")
        }
        netInBuffer.flip(): Unit
      case Status.BUFFER_OVERFLOW =>
        throw new IllegalStateException("Application buffer overflow during unwrap")
      case Status.CLOSED =>
        throw new java.io.IOException("SSLEngine closed during handshake")
    }

    result.getHandshakeStatus
  }

  /** Wrap outgoing TLS data during handshake or renegotiation.
    */
  private def doWrap(): HandshakeStatus = {
    netOutBuffer.clear()
    val result = engine.wrap(ByteBuffer.allocate(0), netOutBuffer)

    result.getStatus match {
      case Status.OK =>
        netOutBuffer.flip()
        writeAll(netOutBuffer)
      case Status.BUFFER_OVERFLOW =>
        throw new IllegalStateException("Network buffer overflow during wrap")
      case Status.BUFFER_UNDERFLOW =>
        throw new IllegalStateException("Unexpected buffer underflow during wrap")
      case Status.CLOSED =>
        throw new java.io.IOException("SSLEngine closed during handshake")
    }

    result.getHandshakeStatus
  }

  /** Run any delegated tasks (certificate validation, etc.).
    *
    * The SSLEngine API returns null when no more delegated tasks remain, so null handling is
    * required here.
    */
  private def runDelegatedTasks(): HandshakeStatus = {
    // scalafix:off DisableSyntax.null
    var task = engine.getDelegatedTask
    while task != null do {
      task.run()
      task = engine.getDelegatedTask
    }
    // scalafix:on DisableSyntax.null
    engine.getHandshakeStatus
  }

  /** Read decrypted data from the TLS channel.
    *
    * Reads encrypted data from the socket, decrypts it, and stores in the destination buffer.
    *
    * Loops until application data is available or EOF is reached; TLS control messages (such as
    * NewSessionTicket) consume network data without producing application data, so several network
    * reads may be needed. Leftover decrypted data is served first. The slice is created with
    * `slice()` then `limit()` applied separately to avoid a Buffer/ByteBuffer return-type issue. A
    * partial TLS record at EOF returns what was decrypted so far. The trailing `-1` is unreachable
    * but required by the compiler.
    *
    * Returns are used for early exit from the loop, which is idiomatic for this pattern.
    *
    * @param dst
    *   Destination buffer for decrypted data
    * @return
    *   Number of bytes read, or -1 if end of stream
    */
  // scalafix:off DisableSyntax.return
  override def read(dst: ByteBuffer): Int = {
    if !handshakeComplete then {
      throw new IllegalStateException("TLS handshake not complete")
    }

    log(s"read() called. dst.remaining=${dst.remaining()}")

    while true do {
      log(s"  appInBuffer: pos=${appInBuffer.position()}, lim=${appInBuffer.limit()}, rem=${appInBuffer.remaining()}")
      log(s"  netInBuffer: pos=${netInBuffer.position()}, lim=${netInBuffer.limit()}, rem=${netInBuffer.remaining()}")

      if appInBuffer.hasRemaining then {
        val count = math.min(dst.remaining(), appInBuffer.remaining())
        val slice = appInBuffer.slice()
        slice.limit(count)
        dst.put(slice)
        appInBuffer.position(appInBuffer.position() + count): Unit
        log(s"  Returned $count bytes from leftover appInBuffer")
        return count
      }

      if !netInBuffer.hasRemaining then {
        netInBuffer.clear()
        val bytesRead = rawRead(netInBuffer)
        log(s"  Read $bytesRead bytes from network")
        if bytesRead < 0 then return -1
        if bytesRead == 0 then return 0
        netInBuffer.flip(): Unit
      } else {
        log(s"  Using leftover netInBuffer data: ${netInBuffer.remaining()} bytes")
      }

      appInBuffer.clear()

      var done = false

      while !done && netInBuffer.hasRemaining do {
        val result = engine.unwrap(netInBuffer, appInBuffer)
        log(
          s"  unwrap: status=${result.getStatus}, produced=${result.bytesProduced()}, consumed=${result.bytesConsumed()}"
        )

        result.getStatus match {
          case Status.OK =>
            ()
          case Status.BUFFER_UNDERFLOW =>
            netInBuffer.compact()
            val extraBytes = rawRead(netInBuffer)
            if extraBytes < 0 then {
              done = true
            } else {
              netInBuffer.flip(): Unit
            }
          case Status.BUFFER_OVERFLOW =>
            done = true
          case Status.CLOSED =>
            return -1
        }

        if result.getHandshakeStatus != HandshakeStatus.NOT_HANDSHAKING &&
          result.getHandshakeStatus != HandshakeStatus.FINISHED
        then {
          runHandshake()
        }
      }

      appInBuffer.flip()

      if !appInBuffer.hasRemaining then {
        log("  No app data produced, reading more...")
        appInBuffer.clear()
        appInBuffer.flip(): Unit
      }
    }

    -1
  }
  // scalafix:on DisableSyntax.return

  /** Write data to the TLS channel.
    *
    * Encrypts the source data and writes to the socket.
    *
    * @param src
    *   Source buffer containing plaintext data
    * @return
    *   Number of bytes consumed from source
    */
  override def write(src: ByteBuffer): Int = {
    if !handshakeComplete then {
      throw new IllegalStateException("TLS handshake not complete")
    }

    log(s"write() called. src.remaining=${src.remaining()}")

    writeLock.lock()
    try {
      var totalWritten = 0

      while src.hasRemaining do {
        netOutBuffer.clear()
        val result = engine.wrap(src, netOutBuffer)

        result.getStatus match {
          case Status.OK =>
            netOutBuffer.flip()
            writeAll(netOutBuffer)
            totalWritten += result.bytesConsumed()
          case Status.BUFFER_OVERFLOW =>
            throw new IllegalStateException("Network buffer overflow during write")
          case Status.BUFFER_UNDERFLOW =>
            throw new IllegalStateException("Unexpected buffer underflow during write")
          case Status.CLOSED =>
            throw new java.io.IOException("SSLEngine closed")
        }

        if result.getHandshakeStatus != HandshakeStatus.NOT_HANDSHAKING &&
          result.getHandshakeStatus != HandshakeStatus.FINISHED
        then {
          runHandshake()
        }
      }

      totalWritten
    } finally {
      writeLock.unlock()
    }
  }

  /** Write all bytes from buffer to socket.
    */
  private def writeAll(buffer: ByteBuffer): Unit = {
    while buffer.hasRemaining do {
      val written = socket.write(buffer)
      if written == 0 then Thread.`yield`()
    }
  }

  /** Check if the channel is open.
    */
  override def isOpen: Boolean = socket.isOpen && !engine.isOutboundDone

  /** Get the underlying socket for timeout control.
    */
  def underlyingSocket: java.net.Socket = socket.socket()

  /** Per-read timeout in milliseconds for network reads, 0 (default) for no timeout.
    *
    * SocketChannel reads ignore SO_TIMEOUT, so the raw reads route through the socket's InputStream
    * when a timeout is set. A timeout surfaces as java.net.SocketTimeoutException and leaves the
    * channel OPEN (unlike an interrupt, which closes it) — the server uses this to answer 408 on
    * header-read deadlines.
    */
  @volatile var readTimeoutMillis: Int = 0

  private lazy val socketInputStream = socket.socket().getInputStream
  private val rawReadScratch = new Array[Byte](netBufferSize)

  /** Raw network read honoring [[readTimeoutMillis]]. Pre-read bytes drain first. */
  private def rawRead(dst: ByteBuffer): Int = {
    val fromPreRead =
      if preReadBuffer.hasRemaining then {
        val n = math.min(preReadBuffer.remaining(), dst.remaining())
        val slice = preReadBuffer.slice()
        slice.limit(n)
        dst.put(slice)
        preReadBuffer.position(preReadBuffer.position() + n)
        n
      } else 0
    if fromPreRead > 0 then fromPreRead
    else if readTimeoutMillis > 0 then {
      val s = socket.socket()
      if s.getSoTimeout != readTimeoutMillis then s.setSoTimeout(readTimeoutMillis)
      val n = socketInputStream.read(rawReadScratch, 0, math.min(rawReadScratch.length, dst.remaining()))
      if n > 0 then dst.put(rawReadScratch, 0, n): Unit
      n
    } else {
      socket.read(dst)
    }
  }

  /** Check if there is buffered data available to read without blocking.
    *
    * This checks the application input buffer that holds decrypted data.
    */
  def hasBufferedData: Boolean = appInBuffer.hasRemaining

  /** Close the TLS channel.
    *
    * Sends TLS close_notify alert and closes the underlying socket.
    */
  override def close(): Unit = {
    log("close() called")
    if socket.isOpen then {
      try {
        engine.closeOutbound()
        netOutBuffer.clear()
        engine.wrap(ByteBuffer.allocate(0), netOutBuffer)
        netOutBuffer.flip()
        writeAll(netOutBuffer)
      } catch {
        case _: Exception => ()
      } finally {
        socket.close()
      }
    }
  }
}

object SSLSocketChannel {

  /** ALPN protocols for HTTP/2 with HTTP/1.1 fallback. */
  val Http2Protocols: Array[String] = Array("h2", "http/1.1")

  /** ALPN protocols for HTTP/1.1 only. */
  val Http1Protocols: Array[String] = Array("http/1.1")

  /** Create a client-mode SSLSocketChannel with SNI and hostname verification.
    *
    * @param socket
    *   Connected TCP socket
    * @param context
    *   SSL context configured with trust managers
    * @param host
    *   Target hostname for SNI and certificate verification
    * @param port
    *   Target port
    * @param verifyHostname
    *   Whether to verify the server's certificate hostname
    * @param alpnProtocols
    *   ALPN protocols to offer; an empty array disables ALPN
    * @param protocols
    *   TLS protocol versions to enable, in preference order
    * @param cipherSuites
    *   Explicit cipher suite allowlist; None applies the TlsConfig default list
    * @return
    *   SSLSocketChannel ready for handshake
    */
  def client(
    socket: SocketChannel,
    context: SSLContext,
    host: String,
    port: Int,
    verifyHostname: Boolean = true,
    alpnProtocols: Array[String] = Http2Protocols,
    protocols: List[TlsVersion] = List(TlsVersion.TLSv1_3, TlsVersion.TLSv1_2),
    cipherSuites: Option[List[String]] = None,
    preRead: Array[Byte] = Array.empty
  ): SSLSocketChannel = {
    val engine = context.createSSLEngine(host, port)
    engine.setUseClientMode(true)

    val params = engine.getSSLParameters

    applyProtocols(params, protocols)

    applyCipherSuites(engine, params, cipherSuites)

    val sniHostName = new javax.net.ssl.SNIHostName(host)
    params.setServerNames(java.util.List.of(sniHostName))

    if verifyHostname then {
      params.setEndpointIdentificationAlgorithm("HTTPS")
    }

    if alpnProtocols.nonEmpty then {
      params.setApplicationProtocols(alpnProtocols)
    }

    engine.setSSLParameters(params)

    new SSLSocketChannel(socket, engine, preRead)
  }

  /** Create a server-mode SSLSocketChannel.
    *
    * @param socket
    *   Accepted TCP socket from client
    * @param context
    *   SSL context configured with server certificate and key
    * @param alpnProtocols
    *   ALPN protocols to offer (empty array disables ALPN)
    * @param protocols
    *   TLS protocol versions to enable, in preference order
    * @param cipherSuites
    *   Explicit cipher suite allowlist; None applies the TlsConfig default list
    * @return
    *   SSLSocketChannel ready for handshake
    */
  def server(
    socket: SocketChannel,
    context: SSLContext,
    alpnProtocols: Array[String] = Http2Protocols,
    protocols: List[TlsVersion] = List(TlsVersion.TLSv1_3, TlsVersion.TLSv1_2),
    cipherSuites: Option[List[String]] = None,
    preRead: Array[Byte] = Array.empty
  ): SSLSocketChannel = {
    val engine = context.createSSLEngine()
    engine.setUseClientMode(false)

    val params = engine.getSSLParameters

    applyProtocols(params, protocols)

    applyCipherSuites(engine, params, cipherSuites)

    if alpnProtocols.nonEmpty then {
      params.setApplicationProtocols(alpnProtocols)
    }

    engine.setSSLParameters(params)

    new SSLSocketChannel(socket, engine, preRead)
  }

  /** Apply the configured TLS protocol restrictions to SSLParameters.
    *
    * Defends against JVM defaults enabling older, vulnerable TLS versions. SSLContext-level
    * configuration alone does not cover every engine, so the restriction is applied to each
    * engine's SSLParameters. An empty protocols list is treated as "use JVM defaults" (logged as a
    * warning upstream — this path should not normally be hit because TlsConfig.default populates
    * the field).
    */
  private def applyProtocols(params: javax.net.ssl.SSLParameters, protocols: List[TlsVersion]): Unit = {
    if protocols.nonEmpty then {
      params.setProtocols(protocols.map(_.value).toArray)
    }
  }

  /** Apply cipher suite restrictions, intersecting with what the engine actually supports.
    *
    * Using setEnabledCipherSuites with a suite the JVM doesn't support throws
    * IllegalArgumentException. We filter to the intersection so unsupported suites are silently
    * dropped (e.g. a JVM compiled without ChaCha20 won't fail, it just uses AES-GCM).
    *
    * If the intersection is empty, the JVM defaults are left in place: better to succeed with
    * default hardening than fail the handshake entirely; operators can investigate via logs.
    */
  private def applyCipherSuites(
    engine: javax.net.ssl.SSLEngine,
    params: javax.net.ssl.SSLParameters,
    cipherSuites: Option[List[String]]
  ): Unit = {
    val requested = cipherSuites.getOrElse(TlsConfig.defaultCipherSuites)
    if requested.nonEmpty then {
      val supported = engine.getSupportedCipherSuites.toSet
      val intersection = requested.filter(supported.contains).toArray
      if intersection.nonEmpty then {
        params.setCipherSuites(intersection)
      }
    }
  }
}
