package net.ghoula.eru.http.h2

/** HTTP/2 frame types and structures as defined in RFC 9113 Section 4.
  *
  * Frame format:
  * {{{
  * +-----------------------------------------------+
  * |                 Length (24)                   |
  * +---------------+---------------+---------------+
  * |   Type (8)    |   Flags (8)   |
  * +-+-------------+---------------+---------------+
  * |R|         Stream Identifier (31)              |
  * +-+-------------+-------------------------------+
  * |                   Frame Payload ...
  * +-----------------------------------------------+
  * }}}
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-4 RFC 9113 Section 4]]
  */
object H2Frame {

  /** Frame header size in bytes (9 bytes). */
  val HeaderSize: Int = 9

  /** Maximum frame payload size per RFC 9113 Section 4.2. Default is 16,384 bytes. */
  val DefaultMaxFrameSize: Int = 16384

  /** Minimum allowed max frame size (2^14). */
  val MinMaxFrameSize: Int = 16384

  /** Maximum allowed max frame size (2^24 - 1). */
  val MaxMaxFrameSize: Int = 16777215

  /** Connection preface magic bytes per RFC 9113 Section 3.4. */
  val ConnectionPreface: Array[Byte] = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("US-ASCII")

  /** Frame type codes as defined in RFC 9113. Priority is deprecated in RFC 9113. */
  object FrameType {
    val Data: Byte = 0x00
    val Headers: Byte = 0x01
    val Priority: Byte = 0x02
    val RstStream: Byte = 0x03
    val Settings: Byte = 0x04
    val PushPromise: Byte = 0x05
    val Ping: Byte = 0x06
    val GoAway: Byte = 0x07
    val WindowUpdate: Byte = 0x08
    val Continuation: Byte = 0x09

    def name(frameType: Byte): String = frameType match {
      case Data => "DATA"
      case Headers => "HEADERS"
      case Priority => "PRIORITY"
      case RstStream => "RST_STREAM"
      case Settings => "SETTINGS"
      case PushPromise => "PUSH_PROMISE"
      case Ping => "PING"
      case GoAway => "GOAWAY"
      case WindowUpdate => "WINDOW_UPDATE"
      case Continuation => "CONTINUATION"
      case other => s"UNKNOWN(0x${other.toInt.toHexString})"
    }
  }

  /** Common frame flags. */
  object Flags {

    /** END_STREAM flag (DATA, HEADERS) - indicates final frame in stream. */
    val EndStream: Byte = 0x01

    /** ACK flag (SETTINGS, PING) - acknowledgment. */
    val Ack: Byte = 0x01

    /** END_HEADERS flag (HEADERS, PUSH_PROMISE, CONTINUATION) - final header fragment. */
    val EndHeaders: Byte = 0x04

    /** PADDED flag (DATA, HEADERS, PUSH_PROMISE) - frame is padded. */
    val Padded: Byte = 0x08

    /** PRIORITY flag (HEADERS) - contains priority information. */
    val Priority: Byte = 0x20

    /** Check if a flag is set. */
    def isSet(flags: Byte, flag: Byte): Boolean = (flags & flag) != 0
  }

  /** SETTINGS parameter identifiers. */
  object SettingsParam {
    val HeaderTableSize: Int = 0x01
    val EnablePush: Int = 0x02
    val MaxConcurrentStreams: Int = 0x03
    val InitialWindowSize: Int = 0x04
    val MaxFrameSize: Int = 0x05
    val MaxHeaderListSize: Int = 0x06
  }

  /** Default settings values per RFC 9113, with eru-http production hardening.
    *
    * Two values differ from the RFC's "no limit" defaults:
    *   - MaxHeaderListSize: 64KB (vs RFC "unlimited") — bounds memory during HPACK decode.
    *   - MaxConcurrentStreams: 128 (vs RFC "no limit") — bounds per-connection stream state memory
    *     against exhaustion attacks. Matches nginx's `http2_max_concurrent_streams` default.
    */
  object DefaultSettings {
    val HeaderTableSize: Int = 4096
    val EnablePush: Boolean = true
    val MaxConcurrentStreams: Int = 128
    val InitialWindowSize: Int = 65535
    val MaxFrameSize: Int = 16384
    val MaxHeaderListSize: Int = 65536
  }
}

/** Common frame header present in all frames.
  *
  * @param length
  *   payload length (24 bits, 0 to 16,777,215)
  * @param frameType
  *   frame type identifier
  * @param flags
  *   frame-specific flags
  * @param streamId
  *   stream identifier (31 bits, R bit must be 0)
  */
final case class H2FrameHeader(
  length: Int,
  frameType: Byte,
  flags: Byte,
  streamId: Int
) {
  require(length >= 0 && length <= H2Frame.MaxMaxFrameSize, s"Invalid frame length: $length")
  require(streamId >= 0, s"Invalid stream ID: $streamId")

  /** Check if the END_STREAM flag is set. */
  def isEndStream: Boolean = H2Frame.Flags.isSet(flags, H2Frame.Flags.EndStream)

  /** Check if the END_HEADERS flag is set. */
  def isEndHeaders: Boolean = H2Frame.Flags.isSet(flags, H2Frame.Flags.EndHeaders)

  /** Check if the ACK flag is set. */
  def isAck: Boolean = H2Frame.Flags.isSet(flags, H2Frame.Flags.Ack)

  /** Check if the PADDED flag is set. */
  def isPadded: Boolean = H2Frame.Flags.isSet(flags, H2Frame.Flags.Padded)

  /** Check if the PRIORITY flag is set. */
  def hasPriority: Boolean = H2Frame.Flags.isSet(flags, H2Frame.Flags.Priority)

  override def toString: String =
    s"H2FrameHeader(type=${H2Frame.FrameType.name(frameType)}, length=$length, flags=0x${flags.toInt.toHexString}, streamId=$streamId)"
}

/** Parsed HTTP/2 frames. */
sealed trait H2ParsedFrame {
  def header: H2FrameHeader
  def streamId: Int = header.streamId
}

/** DATA frame (type=0x00) - conveys arbitrary, variable-length sequences of octets.
  *
  * {{{
  * +---------------+
  * |Pad Length? (8)|
  * +---------------+-----------------------------------------------+
  * |                            Data (*)                         ...
  * +---------------------------------------------------------------+
  * |                           Padding (*)                       ...
  * +---------------------------------------------------------------+
  * }}}
  *
  * @param header
  *   the frame header
  * @param data
  *   the payload data (without padding)
  */
final case class DataFrame(header: H2FrameHeader, data: Array[Byte]) extends H2ParsedFrame {
  def isEndStream: Boolean = header.isEndStream
}

/** HEADERS frame (type=0x01) - opens a stream and carries header block fragment.
  *
  * {{{
  * +---------------+
  * |Pad Length? (8)|
  * +-+-------------+-----------------------------------------------+
  * |E|                 Stream Dependency? (31)                     |
  * +-+-------------+-----------------------------------------------+
  * |  Weight? (8)  |
  * +-+-------------+-----------------------------------------------+
  * |                   Header Block Fragment (*)                 ...
  * +---------------------------------------------------------------+
  * |                           Padding (*)                       ...
  * +---------------------------------------------------------------+
  * }}}
  *
  * @param header
  *   the frame header
  * @param headerBlock
  *   the HPACK-encoded header block fragment
  * @param streamDependency
  *   optional stream dependency (if PRIORITY flag set)
  * @param exclusive
  *   exclusive dependency flag (if PRIORITY flag set)
  * @param weight
  *   priority weight (if PRIORITY flag set), 1-256
  */
final case class HeadersFrame(
  header: H2FrameHeader,
  headerBlock: Array[Byte],
  streamDependency: Option[Int] = None,
  exclusive: Boolean = false,
  weight: Option[Int] = None
) extends H2ParsedFrame {
  def isEndStream: Boolean = header.isEndStream
  def isEndHeaders: Boolean = header.isEndHeaders
}

/** PRIORITY frame (type=0x02) - specifies stream priority. DEPRECATED in RFC 9113.
  *
  * {{{
  * +-+-------------------------------------------------------------+
  * |E|                  Stream Dependency (31)                     |
  * +-+-------------+-----------------------------------------------+
  * |   Weight (8)  |
  * +-+-------------+
  * }}}
  */
final case class PriorityFrame(
  header: H2FrameHeader,
  streamDependency: Int,
  exclusive: Boolean,
  weight: Int
) extends H2ParsedFrame

/** RST_STREAM frame (type=0x03) - terminates a stream.
  *
  * {{{
  * +---------------------------------------------------------------+
  * |                        Error Code (32)                        |
  * +---------------------------------------------------------------+
  * }}}
  */
final case class RstStreamFrame(header: H2FrameHeader, errorCode: Int) extends H2ParsedFrame {
  def h2ErrorCode: Option[H2ErrorCode] = H2ErrorCode.fromValue(errorCode)
}

/** A single SETTINGS parameter. */
final case class SettingsEntry(id: Int, value: Int)

/** SETTINGS frame (type=0x04) - conveys configuration parameters.
  *
  * {{{
  * +-------------------------------+
  * |       Identifier (16)         |
  * +-------------------------------+-------------------------------+
  * |                        Value (32)                             |
  * +---------------------------------------------------------------+
  * }}}
  *
  * Payload is zero or more 6-byte entries.
  */
final case class SettingsFrame(header: H2FrameHeader, settings: List[SettingsEntry]) extends H2ParsedFrame {
  def isAck: Boolean = header.isAck
}

/** PUSH_PROMISE frame (type=0x05) - notifies peer of intent to initiate a stream.
  *
  * {{{
  * +---------------+
  * |Pad Length? (8)|
  * +-+-------------+-----------------------------------------------+
  * |R|                  Promised Stream ID (31)                    |
  * +-+-----------------------------+-------------------------------+
  * |                   Header Block Fragment (*)                 ...
  * +---------------------------------------------------------------+
  * |                           Padding (*)                       ...
  * +---------------------------------------------------------------+
  * }}}
  */
final case class PushPromiseFrame(
  header: H2FrameHeader,
  promisedStreamId: Int,
  headerBlock: Array[Byte]
) extends H2ParsedFrame {
  def isEndHeaders: Boolean = header.isEndHeaders
}

/** PING frame (type=0x06) - measures round-trip time and performs liveness check.
  *
  * {{{
  * +---------------------------------------------------------------+
  * |                      Opaque Data (64)                         |
  * +---------------------------------------------------------------+
  * }}}
  *
  * Payload is exactly 8 bytes.
  */
final case class PingFrame(header: H2FrameHeader, data: Array[Byte]) extends H2ParsedFrame {
  require(data.length == 8, s"PING frame must have exactly 8 bytes of opaque data, got ${data.length}")

  def isAck: Boolean = header.isAck
}

/** GOAWAY frame (type=0x07) - initiates connection shutdown.
  *
  * {{{
  * +-+-------------------------------------------------------------+
  * |R|                  Last-Stream-ID (31)                        |
  * +-+-------------------------------------------------------------+
  * |                      Error Code (32)                          |
  * +---------------------------------------------------------------+
  * |                  Additional Debug Data (*)                    |
  * +---------------------------------------------------------------+
  * }}}
  */
final case class GoAwayFrame(
  header: H2FrameHeader,
  lastStreamId: Int,
  errorCode: Int,
  debugData: Array[Byte] = Array.empty
) extends H2ParsedFrame {
  def h2ErrorCode: Option[H2ErrorCode] = H2ErrorCode.fromValue(errorCode)
}

/** WINDOW_UPDATE frame (type=0x08) - flow control.
  *
  * {{{
  * +-+-------------------------------------------------------------+
  * |R|              Window Size Increment (31)                     |
  * +-+-------------------------------------------------------------+
  * }}}
  */
final case class WindowUpdateFrame(header: H2FrameHeader, windowSizeIncrement: Int) extends H2ParsedFrame {
  require(
    windowSizeIncrement > 0 && windowSizeIncrement <= Int.MaxValue,
    s"Window size increment must be 1 to 2^31-1, got $windowSizeIncrement"
  )
}

/** CONTINUATION frame (type=0x09) - continues a header block.
  *
  * {{{
  * +---------------------------------------------------------------+
  * |                   Header Block Fragment (*)                 ...
  * +---------------------------------------------------------------+
  * }}}
  */
final case class ContinuationFrame(header: H2FrameHeader, headerBlock: Array[Byte]) extends H2ParsedFrame {
  def isEndHeaders: Boolean = header.isEndHeaders
}

/** Unknown frame type - for forward compatibility with future frame types. */
final case class UnknownFrame(header: H2FrameHeader, payload: Array[Byte]) extends H2ParsedFrame
