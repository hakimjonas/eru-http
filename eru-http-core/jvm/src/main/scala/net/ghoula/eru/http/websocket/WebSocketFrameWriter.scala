package net.ghoula.eru.http.websocket

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.security.SecureRandom

import net.ghoula.eru.*
import net.ghoula.eru.http.Bytes

/** WebSocket frame writer as defined in RFC 6455 Section 5.2.
  *
  * Writes WebSocket frames to a channel with optional masking. Per the RFC:
  *   - Client-to-server frames MUST be masked
  *   - Server-to-client frames MUST NOT be masked
  */
object WebSocketFrameWriter {

  /** Thread-local SecureRandom for generating masking keys.
    */
  private val secureRandom = new ThreadLocal[SecureRandom] {
    override def initialValue(): SecureRandom = new SecureRandom()
  }

  /** Write a WebSocket frame to a channel.
    *
    * @param channel
    *   the channel to write to
    * @param frame
    *   the frame to write
    * @param mask
    *   whether to mask the payload (required for client-to-server)
    * @return
    *   success or an error
    */
  def writeFrame(
    channel: WritableByteChannel,
    frame: WebSocketFrame,
    mask: Boolean
  ): Eru[WebSocketError, Unit] = {
    val payload = frame.payload.toArray
    val payloadLen = payload.length

    val headerSize = 2 +
      (if payloadLen <= 125 then 0 else if payloadLen <= 65535 then 2 else 8) +
      (if mask then 4 else 0)

    val frameSize = headerSize + payloadLen
    val buffer = ByteBuffer.allocate(frameSize)

    val byte0 = (if frame.fin then 0x80 else 0x00) | frame.opcode.value
    buffer.put(byte0.toByte)

    val maskBit = if mask then 0x80 else 0x00
    if payloadLen <= 125 then {
      buffer.put((maskBit | payloadLen).toByte)
    } else if payloadLen <= 65535 then {
      buffer.put((maskBit | 126).toByte)
      buffer.putShort(payloadLen.toShort)
    } else {
      buffer.put((maskBit | 127).toByte)
      buffer.putLong(payloadLen.toLong)
    }

    if mask then {
      val maskingKey = new Array[Byte](4)
      secureRandom.get().nextBytes(maskingKey)
      buffer.put(maskingKey)
      var i = 0
      while i < payloadLen do {
        buffer.put((payload(i) ^ maskingKey(i & 3)).toByte)
        i += 1
      }
    } else {
      buffer.put(payload)
    }

    buffer.flip()
    writeAll(channel, buffer)
  }

  /** Write a text message, fragmenting if necessary.
    *
    * @param channel
    *   the channel to write to
    * @param text
    *   the text to send
    * @param mask
    *   whether to mask the payload
    * @param maxFrameSize
    *   maximum frame payload size (0 for no fragmentation)
    */
  def writeText(
    channel: WritableByteChannel,
    text: String,
    mask: Boolean,
    maxFrameSize: Int = 0
  ): Eru[WebSocketError, Unit] = {
    val data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    writeData(channel, data, WebSocketOpcode.Text, mask, maxFrameSize)
  }

  /** Write a binary message, fragmenting if necessary.
    *
    * @param channel
    *   the channel to write to
    * @param data
    *   the binary data to send
    * @param mask
    *   whether to mask the payload
    * @param maxFrameSize
    *   maximum frame payload size (0 for no fragmentation)
    */
  def writeBinary(
    channel: WritableByteChannel,
    data: Bytes,
    mask: Boolean,
    maxFrameSize: Int = 0
  ): Eru[WebSocketError, Unit] = {
    writeData(channel, data.toArray, WebSocketOpcode.Binary, mask, maxFrameSize)
  }

  /** Write data frames, fragmenting if necessary.
    */
  private def writeData(
    channel: WritableByteChannel,
    data: Array[Byte],
    opcode: WebSocketOpcode,
    mask: Boolean,
    maxFrameSize: Int
  ): Eru[WebSocketError, Unit] = {
    def createSingleFrame(op: WebSocketOpcode): Option[WebSocketFrame] = op match {
      case WebSocketOpcode.Text =>
        Some(WebSocketFrame.Text(new String(data, java.nio.charset.StandardCharsets.UTF_8), fin = true))
      case WebSocketOpcode.Binary => Some(WebSocketFrame.Binary(Bytes.fromArray(data), fin = true))
      case _ => None
    }

    def createFragmentFrame(op: WebSocketOpcode, chunk: Array[Byte], isFinal: Boolean): Option[WebSocketFrame] =
      op match {
        case WebSocketOpcode.Text =>
          Some(WebSocketFrame.Text(new String(chunk, java.nio.charset.StandardCharsets.UTF_8), fin = isFinal))
        case WebSocketOpcode.Binary => Some(WebSocketFrame.Binary(Bytes.fromArray(chunk), fin = isFinal))
        case WebSocketOpcode.Continuation => Some(WebSocketFrame.Continuation(Bytes.fromArray(chunk), fin = isFinal))
        case _ => None
      }

    if maxFrameSize <= 0 || data.length <= maxFrameSize then {
      createSingleFrame(opcode) match {
        case Some(frame) => writeFrame(channel, frame, mask)
        case None =>
          Eru.fail(WebSocketError.InvalidFrame(s"Cannot fragment opcode: $opcode", "RFC 6455 Section 5.4"))
      }
    } else {
      var offset = 0
      var isFirst = true

      def writeFragment(): Eru[WebSocketError, Unit] = {
        if offset >= data.length then Eru.unit
        else {
          val remaining = data.length - offset
          val chunkSize = Math.min(remaining, maxFrameSize)
          val chunk = new Array[Byte](chunkSize)
          System.arraycopy(data, offset, chunk, 0, chunkSize)
          offset += chunkSize

          val isFinal = offset >= data.length
          val frameOpcode = if isFirst then opcode else WebSocketOpcode.Continuation
          isFirst = false

          createFragmentFrame(frameOpcode, chunk, isFinal) match {
            case Some(frame) => writeFrame(channel, frame, mask).flatMap(_ => writeFragment())
            case None =>
              Eru.fail(
                WebSocketError.InvalidFrame(
                  s"Unexpected opcode during fragmentation: $frameOpcode",
                  "RFC 6455 Section 5.4"
                )
              )
          }
        }
      }

      writeFragment()
    }
  }

  /** Write a Close frame.
    *
    * @param channel
    *   the channel to write to
    * @param code
    *   optional close code
    * @param reason
    *   optional reason text
    * @param mask
    *   whether to mask the payload
    */
  def writeClose(
    channel: WritableByteChannel,
    code: Option[WebSocketCloseCode],
    reason: Option[String],
    mask: Boolean
  ): Eru[WebSocketError, Unit] = {
    val reasonBytes = reason.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    if reasonBytes.exists(_.length > 123) then
      Eru.fail(WebSocketError.InvalidFrame("Close reason too long (max 123 bytes)", "RFC 6455 Section 5.5.1"))
    else writeFrame(channel, WebSocketFrame.Close(code, reason), mask)
  }

  /** Write a Ping frame.
    *
    * @param channel
    *   the channel to write to
    * @param data
    *   optional application data (max 125 bytes)
    * @param mask
    *   whether to mask the payload
    */
  def writePing(
    channel: WritableByteChannel,
    data: Bytes,
    mask: Boolean
  ): Eru[WebSocketError, Unit] = {
    if data.length > 125 then
      Eru.fail(
        WebSocketError.InvalidFrame(
          "Ping payload too large (max 125 bytes)",
          "RFC 6455 Section 5.5.2"
        )
      )
    else writeFrame(channel, WebSocketFrame.Ping(data), mask)
  }

  /** Write a Pong frame.
    *
    * @param channel
    *   the channel to write to
    * @param data
    *   application data from the Ping (max 125 bytes)
    * @param mask
    *   whether to mask the payload
    */
  def writePong(
    channel: WritableByteChannel,
    data: Bytes,
    mask: Boolean
  ): Eru[WebSocketError, Unit] = {
    if data.length > 125 then
      Eru.fail(
        WebSocketError.InvalidFrame(
          "Pong payload too large (max 125 bytes)",
          "RFC 6455 Section 5.5.3"
        )
      )
    else writeFrame(channel, WebSocketFrame.Pong(data), mask)
  }

  /** Write all bytes from buffer to channel.
    */
  private def writeAll(channel: WritableByteChannel, buffer: ByteBuffer): Eru[WebSocketError, Unit] = {
    Eru.effect {
      while buffer.hasRemaining do {
        val written = channel.write(buffer)
        if written == 0 then Thread.`yield`()
      }
    }.mapError { e =>
      WebSocketError.NetworkError(s"Error writing to socket: ${e.getMessage}", Some(e))
    }
  }
}
