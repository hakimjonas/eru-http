package net.ghoula.eru.http

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

import net.ghoula.eru.*

/** HTTP/1.1 Writer for requests and responses.
  *
  * Implements RFC 9112 (HTTP/1.1) serialization using blocking NIO.
  * Designed to work efficiently with Eru's Virtual Threads.
  */
object HttpWriter {

  private val CRLF = "\r\n"
  private val SP = " "
  private val COLON = ": "

  /** Write an HTTP request to a socket channel.
    *
    * Format (RFC 9112 Section 3):
    * request-line = method SP request-target SP HTTP-version CRLF
    * *( header-field CRLF )
    * CRLF
    * [ message-body ]
    *
    * @param socket The socket channel to write to (must be in blocking mode)
    * @param request The request to write
    */
  def writeRequest(socket: SocketChannel, request: Request[Body]): Eru[HttpError, Unit] =
    Eru.effect {
      // Build request line
      val requestTarget = buildRequestTarget(request.uri)
      val requestLine = s"${request.method.value}$SP$requestTarget$SP${formatVersion(request.version)}$CRLF"

      // Build headers
      val headersStr = buildHeaders(request.headers)

      // Write request line + headers + empty line
      val headerBytes = (requestLine + headersStr + CRLF).getBytes(StandardCharsets.UTF_8)
      writeAll(socket, ByteBuffer.wrap(headerBytes))

      // Write body
      writeBody(socket, request.body)
    }.mapError { case e: Exception =>
      HttpError.NetworkError(s"Error writing request: ${e.getMessage}", Some(e))
    }

  /** Write an HTTP response to a socket channel.
    *
    * Format (RFC 9112 Section 4):
    * status-line = HTTP-version SP status-code SP [ reason-phrase ] CRLF
    * *( header-field CRLF )
    * CRLF
    * [ message-body ]
    *
    * @param socket The socket channel to write to (must be in blocking mode)
    * @param response The response to write
    */
  def writeResponse(socket: SocketChannel, response: Response[Body]): Eru[HttpError, Unit] =
    Eru.effect {
      // Build status line
      val statusLine = s"${formatVersion(response.version)}$SP${response.status.value}$SP${response.status.reasonPhrase}$CRLF"

      // Build headers
      val headersStr = buildHeaders(response.headers)

      // Write status line + headers + empty line
      val headerBytes = (statusLine + headersStr + CRLF).getBytes(StandardCharsets.UTF_8)
      writeAll(socket, ByteBuffer.wrap(headerBytes))

      // Write body
      writeBody(socket, response.body)
    }.mapError { case e: Exception =>
      HttpError.NetworkError(s"Error writing response: ${e.getMessage}", Some(e))
    }

  /** Build request target from URI
    *
    * RFC 9112 Section 3.2:
    * - origin-form: absolute-path [ "?" query ]
    * - absolute-form: absolute-URI (for proxy requests)
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
    headers.toList.foreach { case (name, value) =>
      builder.append(name)
      builder.append(COLON)
      builder.append(value)
      builder.append(CRLF)
    }
    builder.toString
  }

  /** Write message body to socket
    */
  private def writeBody(socket: SocketChannel, body: Body): Unit = {
    body match {
      case Body.Empty =>
        // No body to write
        ()

      case Body.Text(text, _, charset) =>
        val bytes = text.getBytes(charset.toJavaCharset)
        writeAll(socket, ByteBuffer.wrap(bytes))

      case Body.Binary(bytes, _) =>
        writeAll(socket, ByteBuffer.wrap(bytes.toArray))

      case Body.Stream(_, _, _) =>
        // TODO: Implement streaming body support
        throw new UnsupportedOperationException("Streaming bodies not yet supported")
    }
  }

  /** Write all bytes from buffer to socket (handles partial writes)
    *
    * In blocking mode, socket.write() may still write fewer bytes than requested.
    * This method ensures all bytes are written.
    */
  private def writeAll(socket: SocketChannel, buffer: ByteBuffer): Unit = {
    while buffer.hasRemaining do {
      val written = socket.write(buffer)
      if written == 0 then {
        // Should not happen in blocking mode, but handle defensively
        Thread.`yield`()
      }
    }
  }

  /** Write chunked body (Transfer-Encoding: chunked)
    *
    * RFC 9112 Section 7.1: Chunked transfer coding
    * Format: chunk-size CRLF chunk-data CRLF ... 0 CRLF CRLF
    *
    * This is useful for streaming responses when content length is unknown.
    */
  def writeChunkedBody(socket: SocketChannel, chunks: Iterator[Array[Byte]]): Eru[HttpError, Unit] =
    Eru.effect {
      chunks.foreach { chunk =>
        if chunk.nonEmpty then {
          // Write chunk size in hex
          val chunkSize = Integer.toHexString(chunk.length)
          writeAll(socket, ByteBuffer.wrap((chunkSize + CRLF).getBytes(StandardCharsets.UTF_8)))

          // Write chunk data
          writeAll(socket, ByteBuffer.wrap(chunk))

          // Write trailing CRLF
          writeAll(socket, ByteBuffer.wrap(CRLF.getBytes(StandardCharsets.UTF_8)))
        }
      }

      // Write last chunk (size 0) and trailing CRLF
      writeAll(socket, ByteBuffer.wrap(s"0$CRLF$CRLF".getBytes(StandardCharsets.UTF_8)))
    }.mapError { case e: Exception =>
      HttpError.NetworkError(s"Error writing chunked body: ${e.getMessage}", Some(e))
    }
}
