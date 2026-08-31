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
      val requestTarget = buildRequestTarget(request.uri)
      val requestLine = s"${request.method.value}$SP$requestTarget$SP${formatVersion(request.version)}$CRLF"

      val headersStr = buildHeaders(request.headers)

      // Encode through writeString (bytes up to U+00FF direct, above that UTF-8) so this path and
      // writeRequestWithBuffer produce identical bytes.
      val headerText = requestLine + headersStr + CRLF
      val buffer = ByteBuffer.allocate(headerText.length * 3 + 16)
      writeString(buffer, headerText)
      buffer.flip()
      writeAll(channel, buffer)
    }.flatMap { _ =>
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
      buffer.clear()

      val requestTarget = buildRequestTarget(request.uri)
      writeString(buffer, request.method.value)
      buffer.put(' '.toByte)
      writeString(buffer, requestTarget)
      buffer.put(' '.toByte)
      writeString(buffer, formatVersion(request.version))
      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      request.headers.foreach { (name, value) =>
        writeString(buffer, name)
        buffer.put(':'.toByte)
        buffer.put(' '.toByte)
        writeString(buffer, value)
        buffer.put('\r'.toByte)
        buffer.put('\n'.toByte): Unit
      }

      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      buffer.flip()
      writeAll(channel, buffer)
    }.flatMap { _ =>
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
      val statusLine =
        s"${formatVersion(response.version)}$SP${response.status.value}$SP${response.status.reasonPhrase}$CRLF"

      val headersStr = buildHeaders(response.headers)

      // Encode through writeString (bytes up to U+00FF direct, above that UTF-8) so this path and
      // writeResponseWithBuffer produce identical bytes.
      val headerText = statusLine + headersStr + CRLF
      val buffer = ByteBuffer.allocate(headerText.length * 3 + 16)
      writeString(buffer, headerText)
      buffer.flip()
      writeAll(channel, buffer)
    }.flatMap { _ =>
      writeBody(channel, response.body)
    }.mapError {
      case e: HttpError => e
      case e: Exception => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
      case e: Throwable => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
    }

  /** Write an HTTP response to a channel using a reusable ByteBuffer.
    *
    * This version writes headers directly to the provided buffer, avoiding string concatenation and
    * getBytes() calls.
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
      buffer.clear()

      writeString(buffer, formatVersion(response.version))
      buffer.put(' '.toByte)
      writeInt(buffer, response.status.value)
      buffer.put(' '.toByte)
      writeString(buffer, response.status.reasonPhrase)
      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      response.headers.foreach { (name, value) =>
        writeString(buffer, name)
        buffer.put(':'.toByte)
        buffer.put(' '.toByte)
        writeString(buffer, value)
        buffer.put('\r'.toByte)
        buffer.put('\n'.toByte): Unit
      }

      buffer.put('\r'.toByte)
      buffer.put('\n'.toByte)

      buffer.flip()
      writeAll(channel, buffer)
    }.flatMap { _ =>
      writeBody(channel, response.body)
    }.mapError {
      case e: HttpError => e
      case e: Exception => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
      case e: Throwable => HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
    }

  /** Write a string to a ByteBuffer.
    *
    * Bytes up to U+00FF are written as single bytes (ASCII text, and the Latin-1 range that RFC
    * 9110 calls obs-text when it appears in header values). Characters above U+00FF are encoded as
    * UTF-8, so both writer paths produce identical bytes for any input.
    */
  private def writeString(buffer: ByteBuffer, s: String): Unit = {
    var i = 0
    while i < s.length do {
      val c = s.charAt(i)
      if c < 0x100 then {
        buffer.put(c.toByte)
        i += 1
      } else {
        val codePoint = s.codePointAt(i)
        buffer.put(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8))
        i += Character.charCount(codePoint)
      }
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
    *
    * Text and binary bodies are written zero-copy via their underlying byte array.
    */
  private def writeBody(channel: WritableByteChannel, body: Body): Eru[HttpError, Unit] = {
    body match {
      case Body.Empty =>
        Eru.unit

      case t: Body.Text =>
        Eru.effect {
          writeAll(channel, ByteBuffer.wrap(t.bytes.unsafeArray))
        }.mapError(e => HttpError.NetworkError(s"Error writing text body: ${e.getMessage}", Some(e)))

      case Body.Binary(bytes, _) =>
        Eru.effect {
          writeAll(channel, ByteBuffer.wrap(bytes.unsafeArray))
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
            Eru.effect {
              val chunkSize = Integer.toHexString(chunk.size)
              writeAll(channel, ByteBuffer.wrap((chunkSize + CRLF).getBytes(StandardCharsets.UTF_8)))
              writeAll(channel, ByteBuffer.wrap(chunk.bytes.unsafeArray))
              writeAll(channel, ByteBuffer.wrap(CRLF.getBytes(StandardCharsets.UTF_8)))
            }.mapError { case e: Exception => HttpError.NetworkError(s"Error writing chunk: ${e.getMessage}", Some(e)) }
              .flatMap(_ => writeChunks(nextStream))

          case Some((_, nextStream)) =>
            writeChunks(nextStream)

          case None =>
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
    * Pulls chunks and writes them directly until the specified length is reached. Full chunks are
    * written zero-copy via their underlying byte array; only a final truncated chunk allocates a
    * fresh array.
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
                val data =
                  if toWrite == chunk.size then chunk.bytes.unsafeArray
                  else chunk.bytes.unsafeArray.take(toWrite)
                writeAll(channel, ByteBuffer.wrap(data))
              }.mapError { case e: Exception =>
                HttpError.NetworkError(s"Error writing chunk: ${e.getMessage}", Some(e))
              }
                .flatMap(_ => writeChunks(nextStream, bytesWritten + toWrite))

            case Some((_, nextStream)) =>
              writeChunks(nextStream, bytesWritten)

            case None =>
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
    * ensures all bytes are written; a zero-byte write (which should not happen in blocking mode) is
    * handled defensively by yielding the thread.
    */
  private def writeAll(channel: WritableByteChannel, buffer: ByteBuffer): Unit = {
    while buffer.hasRemaining do {
      val written = channel.write(buffer)
      if written == 0 then {
        Thread.`yield`()
      }
    }
  }
}
