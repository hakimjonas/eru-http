package net.ghoula.eru.http

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.nio.charset.StandardCharsets

import net.ghoula.eru.*

/** HTTP/1.1 Writer for requests and responses.
  *
  * Implements RFC 9112 (HTTP/1.1) serialization using blocking NIO. Designed to work efficiently
  * with Eru's Virtual Threads.
  *
  * Accepts any WritableByteChannel, allowing both plain SocketChannel and SSL-wrapped channels.
  */
object HttpWriter {

  private val CRLF = "\r\n"
  private val SP = " "
  private val COLON = ": "

  /** Write an HTTP request to a channel.
    *
    * Format (RFC 9112 Section 3): request-line = method SP request-target SP HTTP-version CRLF *(
    * header-field CRLF ) CRLF [ message-body ]
    *
    * @param channel
    *   The channel to write to (SocketChannel, SSLSocketChannel, etc.)
    * @param request
    *   The request to write
    */
  def writeRequest(channel: WritableByteChannel, request: Request[Body]): Eru[HttpError, Unit] =
    Eru.effect {
      // Build request line
      val requestTarget = buildRequestTarget(request.uri)
      val requestLine = s"${request.method.value}$SP$requestTarget$SP${formatVersion(request.version)}$CRLF"

      // Build headers
      val headersStr = buildHeaders(request.headers)

      // Write request line + headers + empty line
      val headerBytes = (requestLine + headersStr + CRLF).getBytes(StandardCharsets.UTF_8)
      writeAll(channel, ByteBuffer.wrap(headerBytes))
    }.flatMap { _ =>
      // Write body
      writeBody(channel, request.body)
    }.mapError {
      case e: HttpError => e
      case e: Exception => HttpError.NetworkError(s"Error writing request: ${e.getMessage}", Some(e))
      case e: Throwable => HttpError.NetworkError(s"Error writing request: ${e.getMessage}", Some(e))
    }

  /** Write an HTTP request to a channel using a pooled ByteBuffer.
    *
    * This version reuses a provided ByteBuffer to avoid allocations.
    *
    * @param channel
    *   The channel to write to (SocketChannel, SSLSocketChannel, etc.)
    * @param request
    *   The request to write
    * @param buffer
    *   A reusable ByteBuffer (will be cleared before use)
    */
  def writeRequestWithBuffer(
    channel: WritableByteChannel,
    request: Request[Body],
    buffer: ByteBuffer
  ): Eru[HttpError, Unit] =
    Eru.effect {
      // Clear and prepare buffer
      buffer.clear()

      // Write request line directly to buffer
      val requestTarget = buildRequestTarget(request.uri)
      writeString(buffer, request.method.value)
      buffer.put(' '.toByte)
      writeString(buffer, requestTarget)
      buffer.put(' '.toByte)
      writeString(buffer, formatVersion(request.version))
      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      // Write headers directly to buffer (zero allocation)
      request.headers.foreach { (name, value) =>
        writeString(buffer, name)
        buffer.put(':'.toByte)
        buffer.put(' '.toByte)
        writeString(buffer, value)
        buffer.put('\r'.toByte)
        buffer.put('\n'.toByte): Unit
      }

      // Write empty line (end of headers)
      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      // Flip buffer and write to channel
      buffer.flip()
      writeAll(channel, buffer)
    }.flatMap { _ =>
      // Write body (still allocates for body, but headers are pooled)
      writeBody(channel, request.body)
    }.mapError {
      case e: HttpError => e
      case e: Exception => HttpError.NetworkError(s"Error writing request: ${e.getMessage}", Some(e))
      case e: Throwable => HttpError.NetworkError(s"Error writing request: ${e.getMessage}", Some(e))
    }

  /** Write an HTTP response to a channel.
    *
    * Format (RFC 9112 Section 4): status-line = HTTP-version SP status-code SP [ reason-phrase ]
    * CRLF *( header-field CRLF ) CRLF [ message-body ]
    *
    * @param channel
    *   The channel to write to (SocketChannel, SSLSocketChannel, etc.)
    * @param response
    *   The response to write
    */
  def writeResponse(channel: WritableByteChannel, response: Response[Body]): Eru[HttpError, Unit] =
    Eru.effect {
      // Build status line
      val statusLine =
        s"${formatVersion(response.version)}$SP${response.status.value}$SP${response.status.reasonPhrase}$CRLF"

      // Build headers
      val headersStr = buildHeaders(response.headers)

      // Write status line + headers + empty line
      val headerBytes = (statusLine + headersStr + CRLF).getBytes(StandardCharsets.UTF_8)
      writeAll(channel, ByteBuffer.wrap(headerBytes))
    }.flatMap { _ =>
      // Write body
      writeBody(channel, response.body)
    }.mapError {
      case e: HttpError => e
      case e: Exception => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
      case e: Throwable => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
    }

  /** Write an HTTP response to a channel using a reusable ByteBuffer.
    *
    * This zero-allocation version writes headers directly to the provided buffer, avoiding string
    * concatenation and getBytes() calls. Critical for high-throughput servers.
    *
    * @param channel
    *   The channel to write to (SocketChannel, SSLSocketChannel, etc.)
    * @param response
    *   The response to write
    * @param buffer
    *   A reusable ByteBuffer (will be cleared before use, must be large enough for headers)
    */
  def writeResponseWithBuffer(
    channel: WritableByteChannel,
    response: Response[Body],
    buffer: ByteBuffer
  ): Eru[HttpError, Unit] =
    Eru.effect {
      // Clear buffer for reuse
      buffer.clear()

      // Write status line directly to buffer
      writeString(buffer, formatVersion(response.version))
      buffer.put(' '.toByte)
      writeInt(buffer, response.status.value)
      buffer.put(' '.toByte)
      writeString(buffer, response.status.reasonPhrase)
      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      // Write headers directly to buffer (zero allocation)
      response.headers.foreach { (name, value) =>
        writeString(buffer, name)
        buffer.put(':'.toByte)
        buffer.put(' '.toByte)
        writeString(buffer, value)
        buffer.put('\r'.toByte)
        buffer.put('\n'.toByte): Unit
      }

      // Write empty line (end of headers)
      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      // Flip buffer and write to channel
      buffer.flip()
      writeAll(channel, buffer)
    }.flatMap { _ =>
      // Write body (still allocates for body, but headers are zero-allocation)
      writeBody(channel, response.body)
    }.mapError {
      case e: HttpError => e
      case e: Exception => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
      case e: Throwable => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
    }

  /** Write a string to ByteBuffer as UTF-8 bytes (assumes ASCII for performance).
    */
  private def writeString(buffer: ByteBuffer, s: String): Unit = {
    var i = 0
    while i < s.length do {
      buffer.put(s.charAt(i).toByte)
      i += 1
    }
  }

  /** Write an integer to ByteBuffer as ASCII digits.
    */
  private def writeInt(buffer: ByteBuffer, n: Int): Unit = {
    val s = n.toString
    writeString(buffer, s)
  }

  /** Build request target from URI
    *
    * RFC 9112 Section 3.2:
    *   - origin-form: absolute-path [ "?" query ]
    *   - absolute-form: absolute-URI (for proxy requests)
    */
  private def buildRequestTarget(uri: Uri): String = {
    val path = if uri.path.isEmpty then "/" else uri.path
    uri.query match {
      case Some(query) => s"$path?$query"
      case None => path
    }
  }

  /** Format HTTP version for wire protocol
    */
  private def formatVersion(version: HttpVersion): String = version match {
    case HttpVersion.HTTP_1_0 => "HTTP/1.0"
    case HttpVersion.HTTP_1_1 => "HTTP/1.1"
    case HttpVersion.HTTP_2_0 => "HTTP/2.0"
    case HttpVersion.HTTP_3_0 => "HTTP/3.0"
  }

  /** Build headers string
    *
    * RFC 9112 Section 5: header-field = field-name ":" OWS field-value OWS
    */
  private def buildHeaders(headers: Headers): String = {
    val builder = new StringBuilder
    headers.foreach { (name, value) =>
      builder.append(name)
      builder.append(COLON)
      builder.append(value)
      builder.append(CRLF)
    }
    builder.toString
  }

  /** Write message body to channel
    */
  private def writeBody(channel: WritableByteChannel, body: Body): Eru[HttpError, Unit] = {
    body match {
      case Body.Empty =>
        // No body to write
        Eru.unit

      case Body.Text(text, _, charset) =>
        Eru.effect {
          val bytes = text.getBytes(charset.toJavaCharset)
          writeAll(channel, ByteBuffer.wrap(bytes))
        }.mapError(e => HttpError.NetworkError(s"Error writing text body: ${e.getMessage}", Some(e)))

      case Body.Binary(bytes, _) =>
        Eru.effect {
          writeAll(channel, ByteBuffer.wrap(bytes.toArray))
        }.mapError(e => HttpError.NetworkError(s"Error writing binary body: ${e.getMessage}", Some(e)))

      case Body.Stream(chunks, contentLength, _) =>
        contentLength match {
          case Some(length) =>
            writeStreamWithLength(channel, chunks, length)
          case None =>
            writeChunkedStream(channel, chunks)
        }
    }
  }

  /** Write streaming body with chunked transfer encoding.
    *
    * Uses HTTP/1.1 chunked encoding: each chunk is prefixed with its size in hex, followed by CRLF,
    * then the chunk data, then CRLF. Final chunk is "0\r\n\r\n".
    */
  private def writeChunkedStream(
    channel: WritableByteChannel,
    chunks: Eru[Nothing, ChunkStream]
  ): Eru[HttpError, Unit] = {
    chunks.flatMap { stream =>
      def writeChunks(s: ChunkStream): Eru[HttpError, Unit] = {
        s.pull.flatMap {
          case Some((chunk, nextStream)) if chunk.nonEmpty =>
            // Write chunk in chunked encoding format
            Eru.effect {
              val chunkSize = Integer.toHexString(chunk.size)
              writeAll(channel, ByteBuffer.wrap((chunkSize + CRLF).getBytes(StandardCharsets.UTF_8)))
              writeAll(channel, ByteBuffer.wrap(chunk.bytes.toArray))
              writeAll(channel, ByteBuffer.wrap(CRLF.getBytes(StandardCharsets.UTF_8)))
            }.mapError { case e: Exception => HttpError.NetworkError(s"Error writing chunk: ${e.getMessage}", Some(e)) }
              .flatMap(_ => writeChunks(nextStream))

          case Some((_, nextStream)) =>
            // Empty chunk, skip and continue
            writeChunks(nextStream)

          case None =>
            // End of stream - write final chunk
            Eru.effect {
              writeAll(channel, ByteBuffer.wrap(s"0$CRLF$CRLF".getBytes(StandardCharsets.UTF_8)))
            }.mapError { case e: Exception =>
              HttpError.NetworkError(s"Error writing final chunk: ${e.getMessage}", Some(e))
            }
        }
      }
      writeChunks(stream)
    }
  }

  /** Write streaming body with fixed content length.
    *
    * Pulls chunks and writes them directly until the specified length is reached.
    */
  private def writeStreamWithLength(
    channel: WritableByteChannel,
    chunks: Eru[Nothing, ChunkStream],
    length: Long
  ): Eru[HttpError, Unit] = {
    chunks.flatMap { stream =>
      def writeChunks(s: ChunkStream, bytesWritten: Long): Eru[HttpError, Unit] = {
        if bytesWritten >= length then Eru.unit
        else {
          s.pull.flatMap {
            case Some((chunk, nextStream)) if chunk.nonEmpty =>
              val toWrite = math.min(chunk.size, (length - bytesWritten).toInt)
              Eru.effect {
                val data = if toWrite == chunk.size then chunk.bytes.toArray else chunk.bytes.toArray.take(toWrite)
                writeAll(channel, ByteBuffer.wrap(data))
              }.mapError { case e: Exception =>
                HttpError.NetworkError(s"Error writing chunk: ${e.getMessage}", Some(e))
              }
                .flatMap(_ => writeChunks(nextStream, bytesWritten + toWrite))

            case Some((_, nextStream)) =>
              // Empty chunk, skip and continue
              writeChunks(nextStream, bytesWritten)

            case None =>
              // Stream exhausted - check if we wrote enough
              if bytesWritten < length then
                Eru.fail(HttpError.NetworkError(s"Stream exhausted after $bytesWritten bytes, expected $length", None))
              else Eru.unit
          }
        }
      }
      writeChunks(stream, 0L)
    }
  }

  /** Write all bytes from buffer to channel (handles partial writes)
    *
    * In blocking mode, channel.write() may still write fewer bytes than requested. This method
    * ensures all bytes are written.
    */
  private def writeAll(channel: WritableByteChannel, buffer: ByteBuffer): Unit = {
    while buffer.hasRemaining do {
      val written = channel.write(buffer)
      if written == 0 then {
        // Should not happen in blocking mode, but handle defensively
        Thread.`yield`()
      }
    }
  }

  /** Write chunked body (Transfer-Encoding: chunked)
    *
    * RFC 9112 Section 7.1: Chunked transfer coding Format: chunk-size CRLF chunk-data CRLF ... 0
    * CRLF CRLF
    *
    * This is useful for streaming responses when content length is unknown.
    */
  def writeChunkedBody(channel: WritableByteChannel, chunks: Iterator[Array[Byte]]): Eru[HttpError, Unit] =
    Eru.effect {
      chunks.foreach { chunk =>
        if chunk.nonEmpty then {
          // Write chunk size in hex
          val chunkSize = Integer.toHexString(chunk.length)
          writeAll(channel, ByteBuffer.wrap((chunkSize + CRLF).getBytes(StandardCharsets.UTF_8)))

          // Write chunk data
          writeAll(channel, ByteBuffer.wrap(chunk))

          // Write trailing CRLF
          writeAll(channel, ByteBuffer.wrap(CRLF.getBytes(StandardCharsets.UTF_8)))
        }
      }

      // Write last chunk (size 0) and trailing CRLF
      writeAll(channel, ByteBuffer.wrap(s"0$CRLF$CRLF".getBytes(StandardCharsets.UTF_8)))
    }.mapError { case e: Exception =>
      HttpError.NetworkError(s"Error writing chunked body: ${e.getMessage}", Some(e))
    }
}
