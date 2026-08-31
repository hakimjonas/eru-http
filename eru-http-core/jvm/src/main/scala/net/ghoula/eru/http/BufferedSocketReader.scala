package net.ghoula.eru.http

import jdk.net.ExtendedSocketOptions

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, SocketChannel}

/** Buffered reader for byte channels.
  *
  * Reads data in chunks and buffers it, which minimizes syscalls compared to byte-by-byte reads.
  *
  * Can be reused across multiple requests on the same connection by calling reset().
  *
  * Seed bytes (when the caller peeked ahead on the socket for protocol detection) are written to
  * the buffer first; the buffer is then flipped into read mode so subsequent readLine / fillBuffer
  * calls see those bytes before any fresh channel read.
  *
  * @param channel
  *   Channel to read from (SocketChannel, SSLSocketChannel, etc.)
  * @param bufferSize
  *   Size of internal read buffer (default 8KB)
  * @param maxLineLength
  *   Maximum line length to prevent memory exhaustion (default 8KB)
  * @param seedBytes
  *   Bytes already read from the channel that should be consumed first
  */
private[http] final class BufferedSocketReader(
  channel: ReadableByteChannel,
  bufferSize: Int = 8192,
  maxLineLength: Int = 8192,
  seedBytes: Array[Byte] = Array.emptyByteArray
) {

  /** Direct buffer for zero-copy I/O (allocated off-heap). */
  private val buffer = ByteBuffer.allocateDirect(bufferSize)
  if seedBytes.nonEmpty then {
    require(seedBytes.length <= bufferSize, s"seed bytes (${seedBytes.length}) exceed buffer size ($bufferSize)")
    buffer.put(seedBytes): Unit
  }
  buffer.flip(): Unit

  /** Reusable StringBuilder to avoid allocations per line. */
  private val lineBuffer = new StringBuilder(256)

  /** Reset reader state for reuse on next request.
    *
    * Preserves any unconsumed bytes in the buffer so that HTTP/1.1 pipelined requests (where the
    * client sends request N+1 before receiving the response to request N) are not silently dropped.
    * buffer.compact() copies remaining bytes to position 0 and puts the buffer back in write mode;
    * flip() returns it to read mode.
    */
  def reset(): Unit = {
    buffer.compact()
    buffer.flip()
    lineBuffer.clear()
  }

  /** Read a single line up to CRLF.
    *
    * Reads from buffer, refilling from socket as needed.
    *
    * @throws java.io.EOFException
    *   when the connection closes before a complete line arrives
    * @throws IllegalStateException
    *   when the line exceeds `maxLineLength` bytes
    */
  def readLine(): String = {
    lineBuffer.clear()
    var foundCR = false
    var bytesRead = 0
    var lineComplete = false

    while bytesRead < maxLineLength && !lineComplete do {
      if !buffer.hasRemaining then {
        fillBuffer()
      }

      if !buffer.hasRemaining then {
        throw new java.io.EOFException("Connection closed while reading line")
      }

      val byte = buffer.get()
      val char = byte.toChar
      bytesRead += 1

      if foundCR && char == '\n' then {
        lineComplete = true
      } else if foundCR then {
        lineBuffer.append('\r')
        if char == '\r' then {
          foundCR = true
        } else {
          lineBuffer.append(char)
          foundCR = false
        }
      } else if char == '\r' then {
        foundCR = true
      } else {
        lineBuffer.append(char)
      }
    }

    if lineComplete then lineBuffer.toString
    else throw new IllegalStateException(s"Line length exceeded $maxLineLength bytes")
  }

  /** Read exact number of bytes from socket.
    *
    * Reads from buffer, refilling from socket as needed.
    *
    * @throws java.io.EOFException
    *   when the connection closes before `count` bytes arrive
    */
  def readBytes(count: Int): Array[Byte] = {
    val result = new Array[Byte](count)
    var offset = 0

    while offset < count do {
      if !buffer.hasRemaining then {
        fillBuffer()
      }

      if !buffer.hasRemaining then {
        throw new java.io.EOFException(s"Connection closed after reading $offset of $count bytes")
      }

      val available = Math.min(buffer.remaining(), count - offset)
      buffer.get(result, offset, available)
      offset += available
    }

    result
  }

  /** Check if there's buffered data available without blocking.
    *
    * This is useful for checking if there are pending frames before making flow control decisions.
    */
  def hasBufferedData: Boolean = buffer.hasRemaining

  /** Block until at least one byte arrives from the channel, then return whether data is buffered.
    *
    * A single blocking read (or a no-op when the buffer already holds data). There is no socket
    * timeout here — the CALLER bounds the wait; NativeHttpServer wraps this in Eru `.timeout` with
    * `idleTimeout` to reap silent keep-alive connections without letting a slow header drip run
    * past `readHeaderTimeout`. False means the peer closed (read returned end-of-stream).
    */
  def awaitData(): Boolean = {
    if !buffer.hasRemaining then fillBuffer()
    buffer.hasRemaining
  }

  /** Per-read timeout in milliseconds applied in fillBuffer, 0 (default) for no timeout.
    *
    * For plain SocketChannels the read routes through the socket's InputStream so SO_TIMEOUT is
    * honored (channel reads ignore it); a timeout surfaces as java.net.SocketTimeoutException and
    * leaves the channel open. For SSLSocketChannel the timeout is forwarded to the channel, whose
    * raw reads honor it the same way. The caller sets this per phase (header deadline vs idle gap)
    * so a stalled peer cannot hold the read forever and the server can still answer 408.
    */
  @volatile var readTimeoutMillis: Int = 0

  private lazy val socketStream: Option[java.io.InputStream] = channel match {
    case sc: SocketChannel => Some(sc.socket().getInputStream)
    case _ => None
  }

  private val rawReadScratch = new Array[Byte](math.max(bufferSize, 8192))

  /** Refill internal buffer from channel.
    *
    * TCP_QUICKACK is set before each read because it is not sticky; this prevents the 40ms delayed
    * ACK on Linux by immediately ACKing received data instead of waiting for the delayed ACK timer.
    * The option is only available on Linux SocketChannels and is silently ignored otherwise (e.g.
    * non-Linux platforms or SSLSocketChannel wrappers).
    */
  private def fillBuffer(): Unit = {
    buffer.clear()

    channel match {
      case ssl: SSLSocketChannel =>
        ssl.readTimeoutMillis = readTimeoutMillis
        val bytesRead = channel.read(buffer)
        buffer.flip()
        if bytesRead < 0 then {
          ()
        }
      case sc: SocketChannel =>
        try {
          sc.setOption(ExtendedSocketOptions.TCP_QUICKACK, true)
        } catch {
          case _: Exception => ()
        }
        if readTimeoutMillis > 0 then {
          if sc.socket().getSoTimeout != readTimeoutMillis then sc.socket().setSoTimeout(readTimeoutMillis)
          val n = socketStream.get.read(rawReadScratch, 0, math.min(rawReadScratch.length, buffer.remaining()))
          if n > 0 then buffer.put(rawReadScratch, 0, n): Unit
          buffer.flip(): Unit
        } else {
          val bytesRead = channel.read(buffer)
          buffer.flip()
          if bytesRead < 0 then {
            ()
          }
        }
      case _ =>
        val bytesRead = channel.read(buffer)
        buffer.flip()
        if bytesRead < 0 then {
          ()
        }
    }
  }
}
