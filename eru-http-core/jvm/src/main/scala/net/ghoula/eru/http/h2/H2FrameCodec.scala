package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import scala.collection.mutable.ArrayBuffer

import net.ghoula.eru.*

/** HTTP/2 frame encoder and decoder as defined in RFC 9113 Section 4.
  *
  * Handles parsing frames from byte buffers and encoding frames to bytes.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-4 RFC 9113 Section 4]]
  */
object H2FrameCodec {

  /** Parse a frame header from the buffer.
    *
    * The length is 24 bits (3 bytes, big-endian) and the stream ID is 31 bits (4 bytes, big-endian,
    * with the reserved R bit ignored).
    *
    * @param buffer
    *   buffer containing at least 9 bytes for the header
    * @return
    *   Eru effect that succeeds with the parsed header or fails with H2Error
    */
  def parseHeader(buffer: ByteBuffer): Eru[H2Error, H2FrameHeader] = {
    if buffer.remaining < H2Frame.HeaderSize then {
      Eru.fail(
        H2Error.InvalidFrame(s"Buffer has ${buffer.remaining} bytes, need ${H2Frame.HeaderSize} for frame header")
      )
    } else {
      val length = ((buffer.get() & 0xff) << 16) |
        ((buffer.get() & 0xff) << 8) |
        (buffer.get() & 0xff)

      val frameType = buffer.get()
      val flags = buffer.get()

      val streamId = buffer.getInt() & 0x7fffffff

      Eru.succeed(H2FrameHeader(length, frameType, flags, streamId))
    }
  }

  /** Parse a complete frame (header + payload) from the buffer.
    *
    * @param buffer
    *   buffer containing the complete frame
    * @param maxFrameSize
    *   maximum allowed payload size
    * @return
    *   Eru effect that succeeds with the parsed frame or fails with H2Error
    */
  def parseFrame(buffer: ByteBuffer, maxFrameSize: Int = H2Frame.DefaultMaxFrameSize): Eru[H2Error, H2ParsedFrame] = {
    parseHeader(buffer).flatMap { header =>
      if header.length > maxFrameSize then {
        Eru.fail(
          H2Error.InvalidFrame(
            s"Frame payload length ${header.length} exceeds maximum $maxFrameSize",
            "RFC 9113 Section 4.2"
          )
        )
      } else if buffer.remaining < header.length then {
        Eru.fail(H2Error.InvalidFrame(s"Buffer has ${buffer.remaining} bytes, need ${header.length} for frame payload"))
      } else {
        parsePayload(header, buffer)
      }
    }
  }

  /** Parse frame payload based on frame type. */
  private def parsePayload(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, H2ParsedFrame] = {
    header.frameType match {
      case H2Frame.FrameType.Data => parseDataFrame(header, buffer)
      case H2Frame.FrameType.Headers => parseHeadersFrame(header, buffer)
      case H2Frame.FrameType.Priority => parsePriorityFrame(header, buffer)
      case H2Frame.FrameType.RstStream => parseRstStreamFrame(header, buffer)
      case H2Frame.FrameType.Settings => parseSettingsFrame(header, buffer)
      case H2Frame.FrameType.PushPromise => parsePushPromiseFrame(header, buffer)
      case H2Frame.FrameType.Ping => parsePingFrame(header, buffer)
      case H2Frame.FrameType.GoAway => parseGoAwayFrame(header, buffer)
      case H2Frame.FrameType.WindowUpdate => parseWindowUpdateFrame(header, buffer)
      case H2Frame.FrameType.Continuation => parseContinuationFrame(header, buffer)
      case _ => parseUnknownFrame(header, buffer)
    }
  }

  private def parseDataFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, DataFrame] = {
    if header.streamId == 0 then {
      Eru.fail(H2Error.ProtocolViolation("DATA frame on stream 0", H2ErrorCode.ProtocolError))
    } else {
      extractPaddedPayload(header, buffer).map { case (data, _) =>
        DataFrame(header, data)
      }
    }
  }

  private def parseHeadersFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, HeadersFrame] = {
    if header.streamId == 0 then {
      Eru.fail(H2Error.ProtocolViolation("HEADERS frame on stream 0", H2ErrorCode.ProtocolError))
    } else {
      var offset = 0
      var padLength = 0

      if header.isPadded then {
        padLength = buffer.get() & 0xff
        offset += 1
      }

      var streamDependency: Option[Int] = None
      var exclusive = false
      var weight: Option[Int] = None

      if header.hasPriority then {
        val depAndExclusive = buffer.getInt()
        exclusive = (depAndExclusive & 0x80000000) != 0
        streamDependency = Some(depAndExclusive & 0x7fffffff)
        weight = Some((buffer.get() & 0xff) + 1)
        offset += 5
      }

      val headerBlockLength = header.length - offset - padLength
      if headerBlockLength < 0 then {
        Eru.fail(H2Error.InvalidFrame("Invalid HEADERS frame: padding exceeds payload"))
      } else {
        val headerBlock = new Array[Byte](headerBlockLength)
        buffer.get(headerBlock)

        if padLength > 0 then {
          buffer.position(buffer.position() + padLength): Unit
        }

        Eru.succeed(HeadersFrame(header, headerBlock, streamDependency, exclusive, weight))
      }
    }
  }

  private def parsePriorityFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, PriorityFrame] = {
    if header.streamId == 0 then {
      Eru.fail(H2Error.ProtocolViolation("PRIORITY frame on stream 0", H2ErrorCode.ProtocolError))
    } else if header.length != 5 then {
      Eru.fail(H2Error.InvalidFrame(s"PRIORITY frame must be 5 bytes, got ${header.length}", "RFC 9113 Section 6.3"))
    } else {
      val depAndExclusive = buffer.getInt()
      val exclusive = (depAndExclusive & 0x80000000) != 0
      val streamDependency = depAndExclusive & 0x7fffffff
      val weight = (buffer.get() & 0xff) + 1

      Eru.succeed(PriorityFrame(header, streamDependency, exclusive, weight))
    }
  }

  private def parseRstStreamFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, RstStreamFrame] = {
    if header.streamId == 0 then {
      Eru.fail(H2Error.ProtocolViolation("RST_STREAM frame on stream 0", H2ErrorCode.ProtocolError))
    } else if header.length != 4 then {
      Eru.fail(H2Error.InvalidFrame(s"RST_STREAM frame must be 4 bytes, got ${header.length}", "RFC 9113 Section 6.4"))
    } else {
      val errorCode = buffer.getInt()
      Eru.succeed(RstStreamFrame(header, errorCode))
    }
  }

  private def parseSettingsFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, SettingsFrame] = {
    if header.streamId != 0 then {
      Eru.fail(H2Error.ProtocolViolation("SETTINGS frame on non-zero stream", H2ErrorCode.ProtocolError))
    } else if header.isAck && header.length != 0 then {
      Eru.fail(H2Error.InvalidFrame("SETTINGS ACK frame must have empty payload", "RFC 9113 Section 6.5"))
    } else if header.length % 6 != 0 then {
      Eru.fail(
        H2Error.InvalidFrame(
          s"SETTINGS frame length must be multiple of 6, got ${header.length}",
          "RFC 9113 Section 6.5"
        )
      )
    } else {
      val settings = ArrayBuffer[SettingsEntry]()
      var remaining = header.length

      while remaining > 0 do {
        val id = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff)
        val value = buffer.getInt()
        settings += SettingsEntry(id, value)
        remaining -= 6
      }

      Eru.succeed(SettingsFrame(header, settings.toList))
    }
  }

  private def parsePushPromiseFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, PushPromiseFrame] = {
    if header.streamId == 0 then {
      Eru.fail(H2Error.ProtocolViolation("PUSH_PROMISE frame on stream 0", H2ErrorCode.ProtocolError))
    } else {
      var offset = 0
      var padLength = 0

      if header.isPadded then {
        padLength = buffer.get() & 0xff
        offset += 1
      }

      val promisedStreamId = buffer.getInt() & 0x7fffffff
      offset += 4

      val headerBlockLength = header.length - offset - padLength
      if headerBlockLength < 0 then {
        Eru.fail(H2Error.InvalidFrame("Invalid PUSH_PROMISE frame: padding exceeds payload"))
      } else {
        val headerBlock = new Array[Byte](headerBlockLength)
        buffer.get(headerBlock)

        if padLength > 0 then {
          buffer.position(buffer.position() + padLength): Unit
        }

        Eru.succeed(PushPromiseFrame(header, promisedStreamId, headerBlock))
      }
    }
  }

  private def parsePingFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, PingFrame] = {
    if header.streamId != 0 then {
      Eru.fail(H2Error.ProtocolViolation("PING frame on non-zero stream", H2ErrorCode.ProtocolError))
    } else if header.length != 8 then {
      Eru.fail(H2Error.InvalidFrame(s"PING frame must be 8 bytes, got ${header.length}", "RFC 9113 Section 6.7"))
    } else {
      val data = new Array[Byte](8)
      buffer.get(data)
      Eru.succeed(PingFrame(header, data))
    }
  }

  private def parseGoAwayFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, GoAwayFrame] = {
    if header.streamId != 0 then {
      Eru.fail(H2Error.ProtocolViolation("GOAWAY frame on non-zero stream", H2ErrorCode.ProtocolError))
    } else if header.length < 8 then {
      Eru.fail(
        H2Error.InvalidFrame(s"GOAWAY frame must be at least 8 bytes, got ${header.length}", "RFC 9113 Section 6.8")
      )
    } else {
      val lastStreamId = buffer.getInt() & 0x7fffffff
      val errorCode = buffer.getInt()

      val debugDataLength = header.length - 8
      val debugData = if debugDataLength > 0 then {
        val data = new Array[Byte](debugDataLength)
        buffer.get(data)
        data
      } else {
        Array.empty[Byte]
      }

      Eru.succeed(GoAwayFrame(header, lastStreamId, errorCode, debugData))
    }
  }

  private def parseWindowUpdateFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, WindowUpdateFrame] = {
    if header.length != 4 then {
      Eru.fail(
        H2Error.InvalidFrame(s"WINDOW_UPDATE frame must be 4 bytes, got ${header.length}", "RFC 9113 Section 6.9")
      )
    } else {
      val increment = buffer.getInt() & 0x7fffffff
      if increment == 0 then {
        Eru.fail(H2Error.ProtocolViolation("WINDOW_UPDATE increment must not be 0", H2ErrorCode.ProtocolError))
      } else {
        Eru.succeed(WindowUpdateFrame(header, increment))
      }
    }
  }

  private def parseContinuationFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, ContinuationFrame] = {
    if header.streamId == 0 then {
      Eru.fail(H2Error.ProtocolViolation("CONTINUATION frame on stream 0", H2ErrorCode.ProtocolError))
    } else {
      val headerBlock = new Array[Byte](header.length)
      buffer.get(headerBlock)
      Eru.succeed(ContinuationFrame(header, headerBlock))
    }
  }

  private def parseUnknownFrame(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, UnknownFrame] = {
    val payload = new Array[Byte](header.length)
    buffer.get(payload)
    Eru.succeed(UnknownFrame(header, payload))
  }

  /** Extract payload from a potentially padded frame.
    *
    * Per RFC 9113 Section 6.1: If the length of the padding is the length of the frame payload or
    * greater, the recipient MUST treat this as a connection error of type PROTOCOL_ERROR.
    */
  private def extractPaddedPayload(header: H2FrameHeader, buffer: ByteBuffer): Eru[H2Error, (Array[Byte], Int)] = {
    val (padLength, dataLength) =
      if header.isPadded then {
        val pad = buffer.get() & 0xff
        (pad, header.length - 1 - pad)
      } else {
        (0, header.length)
      }

    if header.isPadded && padLength >= header.length then {
      Eru.fail(
        H2Error.ProtocolViolation(
          s"Padding length ($padLength) exceeds frame payload (${header.length})",
          H2ErrorCode.ProtocolError
        )
      )
    } else if dataLength < 0 then {
      Eru.fail(
        H2Error.ProtocolViolation(
          s"Invalid padding: data length would be negative ($dataLength)",
          H2ErrorCode.ProtocolError
        )
      )
    } else {
      val data = new Array[Byte](dataLength)
      buffer.get(data)

      if padLength > 0 then {
        buffer.position(buffer.position() + padLength): Unit
      }

      Eru.succeed((data, padLength))
    }
  }

  /** Encode a frame to a ByteBuffer.
    *
    * @param frame
    *   the frame to encode
    * @return
    *   a ByteBuffer containing the encoded frame
    */
  def encode(frame: H2ParsedFrame): ByteBuffer = frame match {
    case f: DataFrame => encodeDataFrame(f)
    case f: HeadersFrame => encodeHeadersFrame(f)
    case f: PriorityFrame => encodePriorityFrame(f)
    case f: RstStreamFrame => encodeRstStreamFrame(f)
    case f: SettingsFrame => encodeSettingsFrame(f)
    case f: PushPromiseFrame => encodePushPromiseFrame(f)
    case f: PingFrame => encodePingFrame(f)
    case f: GoAwayFrame => encodeGoAwayFrame(f)
    case f: WindowUpdateFrame => encodeWindowUpdateFrame(f)
    case f: ContinuationFrame => encodeContinuationFrame(f)
    case f: UnknownFrame => encodeUnknownFrame(f)
  }

  /** Encode a frame header: 24-bit big-endian length, frame type, flags, and 31-bit stream ID with
    * the reserved R bit cleared.
    */
  private def encodeHeader(header: H2FrameHeader, buffer: ByteBuffer): Unit = {
    buffer.put(((header.length >> 16) & 0xff).toByte)
    buffer.put(((header.length >> 8) & 0xff).toByte)
    buffer.put((header.length & 0xff).toByte)

    buffer.put(header.frameType)
    buffer.put(header.flags)

    buffer.putInt(header.streamId & 0x7fffffff): Unit
  }

  private def encodeDataFrame(frame: DataFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + frame.data.length)
    encodeHeader(frame.header, buffer)
    buffer.put(frame.data)
    buffer.flip()
    buffer
  }

  private def encodeHeadersFrame(frame: HeadersFrame): ByteBuffer = {
    val payloadSize = frame.headerBlock.length +
      (if frame.header.hasPriority then 5 else 0)

    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + payloadSize)
    encodeHeader(frame.header, buffer)

    if frame.header.hasPriority then {
      val dep = frame.streamDependency.getOrElse(0)
      val depWithExclusive = if frame.exclusive then dep | 0x80000000 else dep
      buffer.putInt(depWithExclusive)
      buffer.put((frame.weight.getOrElse(16) - 1).toByte): Unit
    }

    buffer.put(frame.headerBlock)
    buffer.flip()
    buffer
  }

  private def encodePriorityFrame(frame: PriorityFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + 5)
    encodeHeader(frame.header, buffer)
    val depWithExclusive = if frame.exclusive then frame.streamDependency | 0x80000000 else frame.streamDependency
    buffer.putInt(depWithExclusive)
    buffer.put((frame.weight - 1).toByte)
    buffer.flip()
    buffer
  }

  private def encodeRstStreamFrame(frame: RstStreamFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + 4)
    encodeHeader(frame.header, buffer)
    buffer.putInt(frame.errorCode)
    buffer.flip()
    buffer
  }

  private def encodeSettingsFrame(frame: SettingsFrame): ByteBuffer = {
    val payloadSize = frame.settings.length * 6
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + payloadSize)
    encodeHeader(frame.header, buffer)

    for (entry <- frame.settings) {
      buffer.put(((entry.id >> 8) & 0xff).toByte)
      buffer.put((entry.id & 0xff).toByte)
      buffer.putInt(entry.value)
    }

    buffer.flip()
    buffer
  }

  private def encodePushPromiseFrame(frame: PushPromiseFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + 4 + frame.headerBlock.length)
    encodeHeader(frame.header, buffer)
    buffer.putInt(frame.promisedStreamId & 0x7fffffff)
    buffer.put(frame.headerBlock)
    buffer.flip()
    buffer
  }

  private def encodePingFrame(frame: PingFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + 8)
    encodeHeader(frame.header, buffer)
    buffer.put(frame.data)
    buffer.flip()
    buffer
  }

  private def encodeGoAwayFrame(frame: GoAwayFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + 8 + frame.debugData.length)
    encodeHeader(frame.header, buffer)
    buffer.putInt(frame.lastStreamId & 0x7fffffff)
    buffer.putInt(frame.errorCode)
    buffer.put(frame.debugData)
    buffer.flip()
    buffer
  }

  private def encodeWindowUpdateFrame(frame: WindowUpdateFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + 4)
    encodeHeader(frame.header, buffer)
    buffer.putInt(frame.windowSizeIncrement & 0x7fffffff)
    buffer.flip()
    buffer
  }

  private def encodeContinuationFrame(frame: ContinuationFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + frame.headerBlock.length)
    encodeHeader(frame.header, buffer)
    buffer.put(frame.headerBlock)
    buffer.flip()
    buffer
  }

  private def encodeUnknownFrame(frame: UnknownFrame): ByteBuffer = {
    val buffer = ByteBuffer.allocate(H2Frame.HeaderSize + frame.payload.length)
    encodeHeader(frame.header, buffer)
    buffer.put(frame.payload)
    buffer.flip()
    buffer
  }

  /** Create a DATA frame. */
  def dataFrame(streamId: Int, data: Array[Byte], endStream: Boolean = false): DataFrame = {
    val flags = if endStream then H2Frame.Flags.EndStream else 0.toByte
    DataFrame(H2FrameHeader(data.length, H2Frame.FrameType.Data, flags, streamId), data)
  }

  /** Create a HEADERS frame. */
  def headersFrame(
    streamId: Int,
    headerBlock: Array[Byte],
    endStream: Boolean = false,
    endHeaders: Boolean = true
  ): HeadersFrame = {
    var flags: Byte = 0
    if endStream then flags = (flags | H2Frame.Flags.EndStream).toByte
    if endHeaders then flags = (flags | H2Frame.Flags.EndHeaders).toByte
    HeadersFrame(H2FrameHeader(headerBlock.length, H2Frame.FrameType.Headers, flags, streamId), headerBlock)
  }

  /** Create a SETTINGS frame. */
  def settingsFrame(settings: List[SettingsEntry]): SettingsFrame = {
    val length = settings.length * 6
    SettingsFrame(H2FrameHeader(length, H2Frame.FrameType.Settings, 0, 0), settings)
  }

  /** Create a SETTINGS ACK frame. */
  def settingsAckFrame(): SettingsFrame = {
    SettingsFrame(H2FrameHeader(0, H2Frame.FrameType.Settings, H2Frame.Flags.Ack, 0), List.empty)
  }

  /** Create a PING frame. */
  def pingFrame(data: Array[Byte], ack: Boolean = false): PingFrame = {
    require(data.length == 8, "PING frame data must be exactly 8 bytes")
    val flags = if ack then H2Frame.Flags.Ack else 0.toByte
    PingFrame(H2FrameHeader(8, H2Frame.FrameType.Ping, flags, 0), data)
  }

  /** Create a GOAWAY frame. */
  def goAwayFrame(lastStreamId: Int, errorCode: H2ErrorCode, debugData: Array[Byte] = Array.empty): GoAwayFrame = {
    val length = 8 + debugData.length
    GoAwayFrame(H2FrameHeader(length, H2Frame.FrameType.GoAway, 0, 0), lastStreamId, errorCode.value, debugData)
  }

  /** Create a RST_STREAM frame. */
  def rstStreamFrame(streamId: Int, errorCode: H2ErrorCode): RstStreamFrame = {
    RstStreamFrame(H2FrameHeader(4, H2Frame.FrameType.RstStream, 0, streamId), errorCode.value)
  }

  /** Create a WINDOW_UPDATE frame. */
  def windowUpdateFrame(streamId: Int, increment: Int): WindowUpdateFrame = {
    WindowUpdateFrame(H2FrameHeader(4, H2Frame.FrameType.WindowUpdate, 0, streamId), increment)
  }

  /** Create a CONTINUATION frame. */
  def continuationFrame(streamId: Int, headerBlock: Array[Byte], endHeaders: Boolean = true): ContinuationFrame = {
    val flags = if endHeaders then H2Frame.Flags.EndHeaders else 0.toByte
    ContinuationFrame(H2FrameHeader(headerBlock.length, H2Frame.FrameType.Continuation, flags, streamId), headerBlock)
  }
}
