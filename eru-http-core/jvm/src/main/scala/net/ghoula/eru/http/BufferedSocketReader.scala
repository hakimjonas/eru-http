package net.ghoula.eru.http

import jdk.net.ExtendedSocketOptions

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, SocketChannel}

/** Buffered reader for byte channels.
  *
  * Reads data in chunks and buffers it to minimize syscalls. This is a massive performance
  * improvement over byte-by-byte reading.
  *
  * Can be reused across multiple requests on the same connection by calling reset().
  *
  * @param channel
  *   Channel to read from (SocketChannel, SSLSocketChannel, etc.)
  * @param bufferSize
  *   Size of internal read buffer (default 8KB)
  * @param maxLineLength
  *   Maximum line length to prevent memory exhaustion (default 8KB)
  */
private[http] final class BufferedSocketReader(
  channel: ReadableByteChannel,
  bufferSize: Int = 8192,
  maxLineLength: Int = 8192
) {
  // Use direct buffer for zero-copy I/O (allocated off-heap)
  private val buffer = ByteBuffer.allocateDirect(bufferSize)
  buffer.flip(): Unit // Start in read mode with no data

  // Reusable StringBuilder to avoid allocations per line
  private val lineBuffer = new StringBuilder(256)

  /** Reset reader state for reuse on next request.
    *
    * Clears internal buffer and StringBuilder.
    */
  def reset(): Unit = {
    buffer.clear()
    buffer.flip() // Put back in read mode with no data
    lineBuffer.clear()
  }

  /** Read a single line up to CRLF.
    *
    * Reads from buffer, refilling from socket as needed.
    */
  def readLine(): String = {
    lineBuffer.clear() // Reuse existing StringBuilder
    var foundCR = false
    var bytesRead = 0
    var lineComplete = false

    while bytesRead < maxLineLength && !lineComplete do {
      // Ensure buffer has data
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
        // Found CRLF - mark line as complete
        lineComplete = true
      } else if foundCR then {
        // CR not followed by LF - add CR to buffer and continue
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
    */
  def readBytes(count: Int): Array[Byte] = {
    val result = new Array[Byte](count)
    var offset = 0

    while offset < count do {
      // Ensure buffer has data
      if !buffer.hasRemaining then {
        fillBuffer()
      }

      if !buffer.hasRemaining then {
        throw new java.io.EOFException(s"Connection closed after reading $offset of $count bytes")
      }

      // Read as much as possible from buffer
      val available = Math.min(buffer.remaining(), count - offset)
      buffer.get(result, offset, available)
      offset += available
    }

    result
  }

  /** Refill internal buffer from channel. */
  private def fillBuffer(): Unit = {
    buffer.clear() // Switch to write mode

    // Set TCP_QUICKACK before each read (it's not sticky!)
    // This prevents 40ms delayed ACK on Linux by immediately ACKing received data
    // instead of waiting for the delayed ACK timer (typically 40ms)
    // Note: Only available on Linux SocketChannels, silently ignored otherwise
    channel match {
      case sc: SocketChannel =>
        try {
          sc.setOption(ExtendedSocketOptions.TCP_QUICKACK, true)
        } catch {
          case _: Exception => () // Not available on this platform (e.g., non-Linux)
        }
      case _ => () // Not a SocketChannel (e.g., SSLSocketChannel wrapper)
    }

    val bytesRead = channel.read(buffer)
    buffer.flip() // Switch back to read mode

    if bytesRead < 0 then {
      // EOF
      ()
    }
  }
}
