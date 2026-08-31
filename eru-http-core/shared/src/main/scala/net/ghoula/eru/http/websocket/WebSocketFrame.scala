package net.ghoula.eru.http.websocket

import net.ghoula.eru.http.Bytes

/** WebSocket frame opcode as defined in RFC 6455 Section 5.2.
  *
  * Opcodes define the interpretation of the frame payload:
  *   - 0x0: Continuation frame
  *   - 0x1: Text frame (UTF-8 encoded)
  *   - 0x2: Binary frame
  *   - 0x8: Connection close
  *   - 0x9: Ping
  *   - 0xA: Pong
  */
enum WebSocketOpcode(val value: Int) {

  /** Continuation frame - continues a fragmented message. */
  case Continuation extends WebSocketOpcode(0x0)

  /** Text frame - payload is UTF-8 encoded text. */
  case Text extends WebSocketOpcode(0x1)

  /** Binary frame - payload is arbitrary binary data. */
  case Binary extends WebSocketOpcode(0x2)

  /** Connection close frame - initiates or confirms close handshake. */
  case Close extends WebSocketOpcode(0x8)

  /** Ping frame - heartbeat request. */
  case Ping extends WebSocketOpcode(0x9)

  /** Pong frame - heartbeat response. */
  case Pong extends WebSocketOpcode(0xa)
}

object WebSocketOpcode {

  /** Parse an opcode from its integer value.
    *
    * @param value
    *   the opcode value (0-15)
    * @return
    *   the corresponding opcode, or None if reserved/unknown
    */
  def fromValue(value: Int): Option[WebSocketOpcode] = value match {
    case 0x0 => Some(Continuation)
    case 0x1 => Some(Text)
    case 0x2 => Some(Binary)
    case 0x8 => Some(Close)
    case 0x9 => Some(Ping)
    case 0xa => Some(Pong)
    case _ => None
  }

  extension (opcode: WebSocketOpcode) {

    /** Whether this is a control frame (Close, Ping, Pong).
      *
      * Control frames are identified by opcodes where the most significant bit of the opcode is 1.
      * Per RFC 6455 Section 5.5, control frames MUST have a payload length of 125 bytes or less and
      * MUST NOT be fragmented.
      */
    def isControl: Boolean = opcode match {
      case Close | Ping | Pong => true
      case _ => false
    }

    /** Whether this is a data frame (Continuation, Text, Binary).
      */
    def isData: Boolean = !isControl
  }
}

/** WebSocket frame as defined in RFC 6455 Section 5.2.
  *
  * A frame is the basic unit of WebSocket communication. Each frame has:
  *   - FIN bit: indicates if this is the final fragment
  *   - Opcode: defines the frame type
  *   - Payload: the frame data
  *
  * Control frames (Close, Ping, Pong) MUST NOT be fragmented and MUST have payload <= 125 bytes.
  */
sealed trait WebSocketFrame {

  /** Whether this is the final fragment of a message. */
  def fin: Boolean

  /** The frame opcode. */
  def opcode: WebSocketOpcode

  /** The frame payload data. */
  def payload: Bytes
}

object WebSocketFrame {

  /** Text frame carrying UTF-8 encoded text.
    *
    * @param text
    *   the text content
    * @param fin
    *   whether this is the final fragment
    */
  final case class Text(text: String, fin: Boolean = true) extends WebSocketFrame {
    def opcode: WebSocketOpcode = WebSocketOpcode.Text
    def payload: Bytes = Bytes.fromString(text, net.ghoula.eru.http.Charset.UTF8)
  }

  /** Binary frame carrying arbitrary binary data.
    *
    * @param data
    *   the binary content
    * @param fin
    *   whether this is the final fragment
    */
  final case class Binary(data: Bytes, fin: Boolean = true) extends WebSocketFrame {
    def opcode: WebSocketOpcode = WebSocketOpcode.Binary
    def payload: Bytes = data
  }

  /** Continuation frame for fragmented messages.
    *
    * @param data
    *   the continuation payload
    * @param fin
    *   whether this is the final fragment
    */
  final case class Continuation(data: Bytes, fin: Boolean = true) extends WebSocketFrame {
    def opcode: WebSocketOpcode = WebSocketOpcode.Continuation
    def payload: Bytes = data
  }

  /** Close frame to initiate or confirm connection close.
    *
    * Per RFC 6455 Section 5.5.1, the close frame MAY contain a body that indicates the reason for
    * closing. The first two bytes of the body MUST be a 2-byte unsigned integer representing a
    * status code, followed by optional UTF-8 encoded reason text.
    *
    * @param code
    *   optional close status code
    * @param reason
    *   optional UTF-8 encoded reason text
    */
  final case class Close(code: Option[WebSocketCloseCode] = None, reason: Option[String] = None)
      extends WebSocketFrame {
    def fin: Boolean = true
    def opcode: WebSocketOpcode = WebSocketOpcode.Close
    def payload: Bytes = {
      (code, reason) match {
        case (None, _) => Bytes.empty
        case (Some(c), None) =>
          val bytes = new Array[Byte](2)
          bytes(0) = ((c.value >> 8) & 0xff).toByte
          bytes(1) = (c.value & 0xff).toByte
          Bytes.fromArray(bytes)
        case (Some(c), Some(r)) =>
          val reasonBytes = r.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          val bytes = new Array[Byte](2 + reasonBytes.length)
          bytes(0) = ((c.value >> 8) & 0xff).toByte
          bytes(1) = (c.value & 0xff).toByte
          System.arraycopy(reasonBytes, 0, bytes, 2, reasonBytes.length)
          Bytes.fromArray(bytes)
      }
    }
  }

  /** Ping frame for connection keepalive.
    *
    * Per RFC 6455 Section 5.5.2, a Ping frame MAY include application data. Upon receiving a Ping
    * frame, an endpoint MUST send a Pong frame in response with the same payload, unless it has
    * already received a Close frame.
    *
    * @param data
    *   optional application data (max 125 bytes)
    */
  final case class Ping(data: Bytes = Bytes.empty) extends WebSocketFrame {
    def fin: Boolean = true
    def opcode: WebSocketOpcode = WebSocketOpcode.Ping
    def payload: Bytes = data
  }

  /** Pong frame as response to Ping.
    *
    * Per RFC 6455 Section 5.5.3, a Pong frame sent in response to a Ping frame MUST have identical
    * application data as found in the Ping frame.
    *
    * @param data
    *   application data from the corresponding Ping (max 125 bytes)
    */
  final case class Pong(data: Bytes = Bytes.empty) extends WebSocketFrame {
    def fin: Boolean = true
    def opcode: WebSocketOpcode = WebSocketOpcode.Pong
    def payload: Bytes = data
  }

  /** Create a raw frame from components.
    *
    * This is primarily for internal use by the frame parser. A close-frame payload of length 1 is
    * invalid per RFC 6455 and is treated as having no code. The close payload is decoded read-only
    * and zero-copy via its underlying byte array.
    */
  private[websocket] def fromRaw(
    fin: Boolean,
    opcode: WebSocketOpcode,
    payload: Bytes
  ): WebSocketFrame = opcode match {
    case WebSocketOpcode.Text =>
      Text(payload.asString(net.ghoula.eru.http.Charset.UTF8), fin)

    case WebSocketOpcode.Binary =>
      Binary(payload, fin)

    case WebSocketOpcode.Continuation =>
      Continuation(payload, fin)

    case WebSocketOpcode.Close =>
      if payload.length == 0 then Close(None, None)
      else if payload.length == 1 then Close(None, None)
      else {
        val arr = payload.unsafeArray
        val codeValue = ((arr(0) & 0xff) << 8) | (arr(1) & 0xff)
        val code = WebSocketCloseCode.fromValue(codeValue)
        val reason =
          if payload.length > 2 then
            Some(
              new String(
                arr,
                2,
                payload.length - 2,
                java.nio.charset.StandardCharsets.UTF_8
              )
            )
          else None
        Close(code, reason)
      }

    case WebSocketOpcode.Ping =>
      Ping(payload)

    case WebSocketOpcode.Pong =>
      Pong(payload)
  }
}
