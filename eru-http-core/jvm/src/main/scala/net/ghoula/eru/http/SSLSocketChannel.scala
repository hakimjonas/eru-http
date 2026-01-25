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
  * ==Thread Safety==
  *
  * Read operations are NOT thread-safe and should only be called from a single thread. Write
  * operations are synchronized to allow concurrent writes from multiple threads (useful for HTTP/2
  * where multiple streams may send responses concurrently).
  *
  * Note: SSLEngine is not designed for concurrent access, so read() and write() must not be called
  * concurrently from multiple threads. The connection pool ensures exclusive access by tracking
  * connections in an "inUse" set - a connection is only returned to the available pool after the
  * request completes.
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
  engine: SSLEngine
) extends ReadableByteChannel
    with WritableByteChannel {

  // Buffer sizes from SSLEngine session (typically ~16KB each)
  private val session = engine.getSession
  private val appBufferSize = session.getApplicationBufferSize
  private val netBufferSize = session.getPacketBufferSize

  // Network buffers for encrypted data (initialized empty in read mode)
  private val netInBuffer = {
    val buf = ByteBuffer.allocateDirect(netBufferSize)
    buf.flip() // Start in read mode with no data (position=0, limit=0)
    buf
  }
  private val netOutBuffer = ByteBuffer.allocateDirect(netBufferSize)

  // Application buffer for decrypted incoming data (initialized empty in read mode)
  private val appInBuffer = {
    val buf = ByteBuffer.allocateDirect(appBufferSize)
    buf.flip() // Start in read mode with no data (position=0, limit=0)
    buf
  }

  // Track if handshake is complete
  private var handshakeComplete = false

  // Lock for synchronized write operations (allows concurrent writes from multiple threads)
  private val writeLock = new java.util.concurrent.locks.ReentrantLock()

  // Debug flag - enable with -Dssl.debug=true
  private val debug = java.lang.Boolean.getBoolean("ssl.debug")

  private def log(msg: String): Unit =
    if debug then println(s"[SSL ${if engine.getUseClientMode then "CLIENT" else "SERVER"}] $msg")

  /** Perform TLS handshake.
    *
    * This is a blocking operation that completes the full TLS handshake. Must be called before
    * reading or writing data. Safe to use with Virtual Threads.
    *
    * @throws Exception
    *   if handshake fails
    */
  def doHandshake(): Unit = {
    log("Starting handshake")
    engine.beginHandshake()
    runHandshake()

    // Reset appInBuffer to empty read mode after handshake
    // (handshake unwraps don't produce application data, but clear() left it in write mode)
    appInBuffer.clear()
    appInBuffer.flip(): Unit

    log(
      s"Handshake complete. netInBuffer: pos=${netInBuffer.position()}, lim=${netInBuffer.limit()}, rem=${netInBuffer.remaining()}"
    )
    handshakeComplete = true
  }

  /** Get the negotiated application protocol from ALPN.
    *
    * Must be called after `doHandshake()` completes.
    *
    * @return
    *   the negotiated protocol ("h2", "http/1.1", or "" if no ALPN)
    */
  def getApplicationProtocol: String = {
    // Java's SSLEngine.getApplicationProtocol returns null when no ALPN was negotiated
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
    // Read from network if buffer has no data
    if !netInBuffer.hasRemaining then {
      netInBuffer.clear()
      val bytesRead = socket.read(netInBuffer)
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
        // Need more data from network - compact existing data and read more
        netInBuffer.compact()
        val bytesRead = socket.read(netInBuffer)
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
    */
  private def runDelegatedTasks(): HandshakeStatus = {
    // scalafix:off DisableSyntax.null
    // Java SSLEngine API returns null when no more delegated tasks
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
    * @param dst
    *   Destination buffer for decrypted data
    * @return
    *   Number of bytes read, or -1 if end of stream
    */
  // scalafix:off DisableSyntax.return
  // Returns are used for early exit from infinite loop - idiomatic for this pattern
  override def read(dst: ByteBuffer): Int = {
    if !handshakeComplete then {
      throw new IllegalStateException("TLS handshake not complete")
    }

    log(s"read() called. dst.remaining=${dst.remaining()}")

    // Loop until we have application data or EOF
    // This is necessary because TLS may have control messages (like NewSessionTicket)
    // that consume network data but produce no application data
    while true do {
      log(s"  appInBuffer: pos=${appInBuffer.position()}, lim=${appInBuffer.limit()}, rem=${appInBuffer.remaining()}")
      log(s"  netInBuffer: pos=${netInBuffer.position()}, lim=${netInBuffer.limit()}, rem=${netInBuffer.remaining()}")

      // First, check if we have leftover decrypted data
      if appInBuffer.hasRemaining then {
        val count = math.min(dst.remaining(), appInBuffer.remaining())
        // slice() then limit() separately to avoid Buffer/ByteBuffer return type issue
        val slice = appInBuffer.slice()
        slice.limit(count)
        dst.put(slice)
        appInBuffer.position(appInBuffer.position() + count): Unit
        log(s"  Returned $count bytes from leftover appInBuffer")
        return count
      }

      // Read from network ONLY if netInBuffer is exhausted
      if !netInBuffer.hasRemaining then {
        netInBuffer.clear()
        val bytesRead = socket.read(netInBuffer)
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
            () // Continue processing
          case Status.BUFFER_UNDERFLOW =>
            // Need more network data to complete the TLS record
            netInBuffer.compact() // Preserve partial record at buffer start
            val extraBytes = socket.read(netInBuffer)
            if extraBytes < 0 then {
              // EOF with partial TLS record - return what we have decrypted so far
              done = true
            } else {
              netInBuffer.flip(): Unit // Back to read mode with more data
              // Continue loop to retry unwrap
            }
          case Status.BUFFER_OVERFLOW =>
            // App buffer full - return what we have
            done = true
          case Status.CLOSED =>
            return -1
        }

        // Handle renegotiation if needed
        if result.getHandshakeStatus != HandshakeStatus.NOT_HANDSHAKING &&
          result.getHandshakeStatus != HandshakeStatus.FINISHED
        then {
          runHandshake()
        }
      }

      // Transfer decrypted data to appInBuffer for return (flip to read mode)
      appInBuffer.flip()

      // If we have data now, the loop will return it on next iteration
      // If we have no data (consumed a control message), loop to read more
      if !appInBuffer.hasRemaining then {
        log("  No app data produced, reading more...")
        // Reset appInBuffer for next unwrap cycle
        appInBuffer.clear()
        appInBuffer.flip(): Unit
      }
    }

    // Unreachable, but Scala needs it
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

    // Synchronize writes to prevent TLS stream corruption when multiple threads
    // write concurrently (e.g., HTTP/2 with multiple stream responses)
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
            // Network buffer too small - flush and retry
            throw new IllegalStateException("Network buffer overflow during write")
          case Status.BUFFER_UNDERFLOW =>
            throw new IllegalStateException("Unexpected buffer underflow during write")
          case Status.CLOSED =>
            throw new java.io.IOException("SSLEngine closed")
        }

        // Handle renegotiation if needed
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

  /** Check if there is buffered data available to read without blocking.
    *
    * This checks the application input buffer that holds decrypted data.
    */
  def hasBufferedData: Boolean = appInBuffer.hasRemaining

  /** Check if there's pending network data that hasn't been decrypted yet.
    *
    * This checks both the network input buffer (encrypted data already read from socket) and the
    * kernel socket buffer (encrypted data not yet read).
    */
  def hasPendingNetworkData: Boolean = {
    netInBuffer.hasRemaining || {
      try socket.socket().getInputStream.available() > 0
      catch { case _: Exception => false }
    }
  }

  /** Close the TLS channel.
    *
    * Sends TLS close_notify alert and closes the underlying socket.
    */
  override def close(): Unit = {
    log("close() called")
    if socket.isOpen then {
      try {
        // Send close_notify
        engine.closeOutbound()
        netOutBuffer.clear()
        engine.wrap(ByteBuffer.allocate(0), netOutBuffer)
        netOutBuffer.flip()
        writeAll(netOutBuffer)
      } catch {
        case _: Exception => () // Ignore close errors
      } finally {
        socket.close()
      }
    }
  }
}

object SSLSocketChannel {

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
    * @return
    *   SSLSocketChannel ready for handshake
    */
  /** ALPN protocols for HTTP/2 with HTTP/1.1 fallback. */
  val Http2Protocols: Array[String] = Array("h2", "http/1.1")

  /** ALPN protocols for HTTP/1.1 only. */
  val Http1Protocols: Array[String] = Array("http/1.1")

  def client(
    socket: SocketChannel,
    context: SSLContext,
    host: String,
    port: Int,
    verifyHostname: Boolean = true,
    alpnProtocols: Array[String] = Http2Protocols
  ): SSLSocketChannel = {
    val engine = context.createSSLEngine(host, port)
    engine.setUseClientMode(true)

    // Configure SSL parameters
    val params = engine.getSSLParameters

    // Enable SNI (Server Name Indication)
    val sniHostName = new javax.net.ssl.SNIHostName(host)
    params.setServerNames(java.util.List.of(sniHostName))

    // Enable hostname verification if requested
    if verifyHostname then {
      params.setEndpointIdentificationAlgorithm("HTTPS")
    }

    // Enable ALPN (Application-Layer Protocol Negotiation) for HTTP/2
    if alpnProtocols.nonEmpty then {
      params.setApplicationProtocols(alpnProtocols)
    }

    engine.setSSLParameters(params)

    new SSLSocketChannel(socket, engine)
  }

  /** Create a server-mode SSLSocketChannel.
    *
    * @param socket
    *   Accepted TCP socket from client
    * @param context
    *   SSL context configured with server certificate and key
    * @param alpnProtocols
    *   ALPN protocols to offer (empty array disables ALPN)
    * @return
    *   SSLSocketChannel ready for handshake
    */
  def server(
    socket: SocketChannel,
    context: SSLContext,
    alpnProtocols: Array[String] = Http2Protocols
  ): SSLSocketChannel = {
    val engine = context.createSSLEngine()
    engine.setUseClientMode(false)

    // Configure ALPN for server
    if alpnProtocols.nonEmpty then {
      val params = engine.getSSLParameters
      params.setApplicationProtocols(alpnProtocols)
      engine.setSSLParameters(params)
    }

    new SSLSocketChannel(socket, engine)
  }
}
