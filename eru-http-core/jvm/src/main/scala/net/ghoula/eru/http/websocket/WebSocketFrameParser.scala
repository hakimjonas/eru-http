package net.ghoula.eru.http.websocket

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** WebSocket frame parser as defined in RFC 6455 Section 5.2.
  *
  * Frame format:
  * {{{
  *  0                   1                   2                   3
  *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
  * +-+-+-+-+-------+-+-------------+-------------------------------+
  * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
  * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
  * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
  * | |1|2|3|       |K|             |                               |
  * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
  * |     Extended payload length continued, if payload len == 127  |
  * + - - - - - - - - - - - - - - - +-------------------------------+
  * |                               |Masking-key, if MASK set to 1  |
  * +-------------------------------+-------------------------------+
  * | Masking-key (continued)       |          Payload Data         |
  * +-------------------------------- - - - - - - - - - - - - - - - +
  * :                     Payload Data continued ...                :
  * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
  * |                     Payload Data continued ...                |
  * +---------------------------------------------------------------+
  * }}}
  */
object WebSocketFrameParser {

  /** Maximum frame size for control frames per RFC 6455 Section 5.5.
    */
  private val MaxControlFrameSize = 125L

  /** Parse a single WebSocket frame from a BufferedSocketReader.
    *
    * @param reader
    *   the buffered socket reader
    * @param maxPayloadSize
    *   maximum allowed payload size (to prevent memory exhaustion)
    * @param expectMasked
    *   whether frames must be masked (true for server receiving from client)
    * @return
    *   the parsed frame or an error
    */
  def parseFrame(
    reader: BufferedSocketReader,
    maxPayloadSize: Long,
    expectMasked: Boolean
  ): Eru[WebSocketError, WebSocketFrame] = {
    for {
      header <- readBytes(reader, 2)

      fin = (header(0) & 0x80) != 0
      rsv1 = (header(0) & 0x40) != 0
      rsv2 = (header(0) & 0x20) != 0
      rsv3 = (header(0) & 0x10) != 0
      opcodeValue = header(0) & 0x0f
      masked = (header(1) & 0x80) != 0
      payloadLenByte = header(1) & 0x7f

      _ <-
        if rsv1 || rsv2 || rsv3 then
          Eru.fail(
            WebSocketError.InvalidFrame(
              "RSV bits must be 0 (no extensions negotiated)",
              "RFC 6455 Section 5.2"
            )
          )
        else Eru.unit

      opcode <- WebSocketOpcode.fromValue(opcodeValue) match {
        case Some(op) => Eru.succeed(op)
        case None =>
          Eru.fail(
            WebSocketError.InvalidFrame(
              s"Unknown opcode: $opcodeValue",
              "RFC 6455 Section 5.2"
            )
          )
      }

      _ <-
        if expectMasked && !masked then
          Eru.fail(
            WebSocketError.ProtocolViolation(
              "Client frames must be masked",
              WebSocketCloseCode.ProtocolError
            )
          )
        else if !expectMasked && masked then
          Eru.fail(
            WebSocketError.ProtocolViolation(
              "Server frames must not be masked",
              WebSocketCloseCode.ProtocolError
            )
          )
        else Eru.unit

      payloadLen <- parsePayloadLength(reader, payloadLenByte)

      _ <-
        if opcode.isControl then {
          if payloadLen > MaxControlFrameSize then
            Eru.fail(
              WebSocketError.InvalidFrame(
                s"Control frame payload too large: $payloadLen bytes (max: $MaxControlFrameSize)",
                "RFC 6455 Section 5.5"
              )
            )
          else if !fin then
            Eru.fail(
              WebSocketError.InvalidFrame(
                "Control frames must not be fragmented",
                "RFC 6455 Section 5.5"
              )
            )
          else Eru.unit
        } else Eru.unit

      _ <-
        if payloadLen > maxPayloadSize then Eru.fail(WebSocketError.MessageTooLarge(payloadLen, maxPayloadSize))
        else Eru.unit

      maskingKey <-
        if masked then readBytes(reader, 4).map(Some(_))
        else Eru.succeed(None)

      payloadBytes <-
        if payloadLen == 0 then Eru.succeed(Array.emptyByteArray)
        else readBytes(reader, payloadLen.toInt)

      unmaskedPayload: Array[Byte] = maskingKey match {
        case Some(key) => unmask(payloadBytes, key)
        case None => payloadBytes
      }

      _ <-
        if opcode == WebSocketOpcode.Text && fin then validateUtf8(unmaskedPayload)
        else Eru.unit

    } yield WebSocketFrame.fromRaw(fin, opcode, Bytes.fromArray(unmaskedPayload))
  }

  /** Parse payload length from the initial byte and extended length bytes.
    */
  private def parsePayloadLength(reader: BufferedSocketReader, lenByte: Int): Eru[WebSocketError, Long] = {
    if lenByte <= 125 then {
      Eru.succeed(lenByte.toLong)
    } else if lenByte == 126 then {
      readBytes(reader, 2).map { bytes =>
        ((bytes(0) & 0xff) << 8) | (bytes(1) & 0xff)
      }
    } else {
      readBytes(reader, 8).flatMap { bytes =>
        var len = 0L
        for i <- 0 until 8 do {
          len = (len << 8) | (bytes(i) & 0xff)
        }
        if len < 0 then
          Eru.fail(
            WebSocketError.InvalidFrame(
              "Payload length must be non-negative",
              "RFC 6455 Section 5.2"
            )
          )
        else Eru.succeed(len)
      }
    }
  }

  /** Read exact number of bytes from the reader.
    */
  private def readBytes(reader: BufferedSocketReader, count: Int): Eru[WebSocketError, Array[Byte]] = {
    Eru.effect {
      reader.readBytes(count)
    }.mapError {
      case e: java.io.EOFException =>
        WebSocketError.ConnectionClosed(None, Some(e.getMessage), clean = false)
      case e: Exception =>
        WebSocketError.NetworkError(s"Error reading from socket: ${e.getMessage}", Some(e))
    }
  }

  /** Unmask payload data using XOR with the masking key.
    *
    * Per RFC 6455 Section 5.3: j = i MOD 4 transformed-octet-i = original-octet-i XOR
    * masking-key-octet-j
    */
  private def unmask(data: Array[Byte], key: Array[Byte]): Array[Byte] = {
    val result = new Array[Byte](data.length)
    var i = 0
    while i < data.length do {
      result(i) = (data(i) ^ key(i & 3)).toByte
      i += 1
    }
    result
  }

  private def validateUtf8(data: Array[Byte]): Eru[WebSocketError, Unit] = {
    try {
      val decoder = java.nio.charset.StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
      decoder.decode(java.nio.ByteBuffer.wrap(data))
      Eru.unit
    } catch {
      case e: java.nio.charset.CharacterCodingException =>
        Eru.fail(WebSocketError.InvalidUTF8(e.getMessage))
    }
  }

  /** Fragmentation state for tracking message assembly across control frames.
    *
    * Per RFC 6455 Section 5.4, control frames MAY be injected in the middle of a fragmented
    * message. This state allows us to resume fragmentation after handling control frames.
    */
  final case class FragmentationState(
    firstOpcode: WebSocketOpcode,
    accumulatedData: Array[Byte]
  )

  /** Result of parsing a message that may include control frames during fragmentation.
    */
  sealed trait ParseResult
  object ParseResult {

    /** A complete data message (Text or Binary). */
    final case class Message(frame: WebSocketFrame) extends ParseResult

    /** A control frame that arrived during fragmentation. */
    final case class ControlFrame(frame: WebSocketFrame, fragmentState: Option[FragmentationState]) extends ParseResult
  }

  /** Parse a complete message (handling fragmentation and interleaved control frames).
    *
    * This reads frames until a final frame is received, reassembling fragmented messages. Control
    * frames that arrive during fragmentation are returned with fragmentation state so parsing can
    * be resumed.
    *
    * @param reader
    *   the buffered socket reader
    * @param maxMessageSize
    *   maximum total message size
    * @param expectMasked
    *   whether frames must be masked
    * @param resumeState
    *   optional fragmentation state to resume from
    * @return
    *   the parse result (message or control frame with state)
    */
  def parseMessageWithState(
    reader: BufferedSocketReader,
    maxMessageSize: Long,
    expectMasked: Boolean,
    resumeState: Option[FragmentationState] = None
  ): Eru[WebSocketError, ParseResult] = {

    def loop(
      firstOpcode: Option[WebSocketOpcode],
      accumulatedData: Array[Byte]
    ): Eru[WebSocketError, ParseResult] = {
      parseFrameRaw(reader, maxMessageSize - accumulatedData.length, expectMasked).flatMap {
        // Control frames can appear in the middle of fragmented messages
        // Return them with fragmentation state so caller can handle and resume
        case (frame @ WebSocketFrame.Close(_, _), _) =>
          val state = firstOpcode.map(op => FragmentationState(op, accumulatedData))
          Eru.succeed(ParseResult.ControlFrame(frame, state))

        case (frame @ WebSocketFrame.Ping(_), _) =>
          val state = firstOpcode.map(op => FragmentationState(op, accumulatedData))
          Eru.succeed(ParseResult.ControlFrame(frame, state))

        case (frame @ WebSocketFrame.Pong(_), _) =>
          val state = firstOpcode.map(op => FragmentationState(op, accumulatedData))
          Eru.succeed(ParseResult.ControlFrame(frame, state))

        // Handle fragmented data frames
        case (_, rawPayload) if rawPayload.opcode == WebSocketOpcode.Text && rawPayload.fin =>
          firstOpcode match {
            case None =>
              // Complete unfragmented message - validate UTF-8 on raw bytes
              validateUtf8(rawPayload.data).map { _ =>
                ParseResult.Message(
                  WebSocketFrame.Text(new String(rawPayload.data, java.nio.charset.StandardCharsets.UTF_8), fin = true)
                )
              }
            case Some(_) =>
              // This shouldn't happen - text frame in middle of fragmentation
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  "Received non-continuation data frame while expecting continuation",
                  WebSocketCloseCode.ProtocolError
                )
              )
          }

        case (frame: WebSocketFrame.Binary, _) if frame.fin =>
          firstOpcode match {
            case None =>
              Eru.succeed(ParseResult.Message(frame))
            case Some(_) =>
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  "Received non-continuation data frame while expecting continuation",
                  WebSocketCloseCode.ProtocolError
                )
              )
          }

        case (_, rawPayload) if rawPayload.opcode == WebSocketOpcode.Text =>
          // Start of fragmented text message - keep raw bytes, don't decode yet
          loop(Some(WebSocketOpcode.Text), rawPayload.data)

        case (frame: WebSocketFrame.Binary, _) =>
          // Start of fragmented binary message
          loop(Some(WebSocketOpcode.Binary), frame.payload.toArray)

        case (frame: WebSocketFrame.Continuation, _) =>
          firstOpcode match {
            case None =>
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  "Received continuation frame without preceding data frame",
                  WebSocketCloseCode.ProtocolError
                )
              )
            case Some(opcode) =>
              val newData = accumulatedData ++ frame.payload.toArray
              if newData.length > maxMessageSize then {
                Eru.fail(WebSocketError.MessageTooLarge(newData.length, maxMessageSize))
              } else if frame.fin then {
                // Final fragment - reconstruct complete message
                opcode match {
                  case WebSocketOpcode.Text =>
                    // Validate complete UTF-8
                    validateUtf8(newData).map { _ =>
                      ParseResult.Message(
                        WebSocketFrame.Text(
                          new String(newData, java.nio.charset.StandardCharsets.UTF_8),
                          fin = true
                        )
                      )
                    }
                  case WebSocketOpcode.Binary =>
                    Eru.succeed(ParseResult.Message(WebSocketFrame.Binary(Bytes.fromArray(newData), fin = true)))
                  case _ =>
                    Eru.fail(
                      WebSocketError.InvalidFrame(
                        s"Unexpected opcode in fragmented message: $opcode",
                        "RFC 6455 Section 5.4"
                      )
                    )
                }
              } else {
                // More fragments expected
                loop(Some(opcode), newData)
              }
          }

        case (frame, _) =>
          Eru.fail(
            WebSocketError.InvalidFrame(
              s"Unexpected frame type: ${frame.opcode}",
              "RFC 6455 Section 5.4"
            )
          )
      }
    }

    resumeState match {
      case Some(state) =>
        // Resume fragmentation from saved state
        loop(Some(state.firstOpcode), state.accumulatedData)
      case None =>
        loop(None, Array.empty)
    }
  }

  /** Raw payload data before frame construction. */
  private case class RawPayload(opcode: WebSocketOpcode, data: Array[Byte], fin: Boolean)

  /** Parse a single frame and return both the frame and raw payload data.
    *
    * This allows the caller to access raw bytes before they're converted to String, which is
    * necessary for proper UTF-8 validation of fragmented text messages.
    */
  private def parseFrameRaw(
    reader: BufferedSocketReader,
    maxPayloadSize: Long,
    expectMasked: Boolean
  ): Eru[WebSocketError, (WebSocketFrame, RawPayload)] = {
    for {
      header <- readBytes(reader, 2)

      fin = (header(0) & 0x80) != 0
      rsv1 = (header(0) & 0x40) != 0
      rsv2 = (header(0) & 0x20) != 0
      rsv3 = (header(0) & 0x10) != 0
      opcodeValue = header(0) & 0x0f
      masked = (header(1) & 0x80) != 0
      payloadLenByte = header(1) & 0x7f

      _ <-
        if rsv1 || rsv2 || rsv3 then
          Eru.fail(
            WebSocketError.InvalidFrame(
              "RSV bits must be 0 (no extensions negotiated)",
              "RFC 6455 Section 5.2"
            )
          )
        else Eru.unit

      opcode <- WebSocketOpcode.fromValue(opcodeValue) match {
        case Some(op) => Eru.succeed(op)
        case None =>
          Eru.fail(
            WebSocketError.InvalidFrame(
              s"Unknown opcode: $opcodeValue",
              "RFC 6455 Section 5.2"
            )
          )
      }

      _ <-
        if expectMasked && !masked then
          Eru.fail(
            WebSocketError.ProtocolViolation(
              "Client frames must be masked",
              WebSocketCloseCode.ProtocolError
            )
          )
        else if !expectMasked && masked then
          Eru.fail(
            WebSocketError.ProtocolViolation(
              "Server frames must not be masked",
              WebSocketCloseCode.ProtocolError
            )
          )
        else Eru.unit

      payloadLen <- parsePayloadLength(reader, payloadLenByte)

      _ <-
        if opcode.isControl then {
          if payloadLen > MaxControlFrameSize then
            Eru.fail(
              WebSocketError.InvalidFrame(
                s"Control frame payload too large: $payloadLen bytes (max: $MaxControlFrameSize)",
                "RFC 6455 Section 5.5"
              )
            )
          else if !fin then
            Eru.fail(
              WebSocketError.InvalidFrame(
                "Control frames must not be fragmented",
                "RFC 6455 Section 5.5"
              )
            )
          else Eru.unit
        } else Eru.unit

      _ <-
        if payloadLen > maxPayloadSize then Eru.fail(WebSocketError.MessageTooLarge(payloadLen, maxPayloadSize))
        else Eru.unit

      maskingKey <-
        if masked then readBytes(reader, 4).map(Some(_))
        else Eru.succeed(None)

      payloadBytes <-
        if payloadLen == 0 then Eru.succeed(Array.emptyByteArray)
        else readBytes(reader, payloadLen.toInt)

      unmaskedPayload: Array[Byte] = maskingKey match {
        case Some(key) => unmask(payloadBytes, key)
        case None => payloadBytes
      }

      // For Close frames, validate the close code and UTF-8 in the reason field
      _ <- opcode match {
        case WebSocketOpcode.Close if unmaskedPayload.length >= 2 =>
          val closeCodeValue = ((unmaskedPayload(0) & 0xff) << 8) | (unmaskedPayload(1) & 0xff)
          // Validate close code per RFC 6455 Section 7.4.2
          if !WebSocketCloseCode.isValidReceivedCode(closeCodeValue) then
            Eru.fail(
              WebSocketError.ProtocolViolation(
                s"Invalid close code: $closeCodeValue",
                WebSocketCloseCode.ProtocolError
              )
            )
          else if unmaskedPayload.length > 2 then {
            // Validate UTF-8 in the reason field
            val reasonBytes = java.util.Arrays.copyOfRange(unmaskedPayload, 2, unmaskedPayload.length)
            validateUtf8(reasonBytes).mapError { _ =>
              WebSocketError.ProtocolViolation(
                "Invalid UTF-8 in close frame reason",
                WebSocketCloseCode.InvalidPayloadData
              )
            }
          } else Eru.unit
        case _ => Eru.unit
      }

      rawPayload = RawPayload(opcode, unmaskedPayload, fin)
      frame = WebSocketFrame.fromRaw(fin, opcode, Bytes.fromArray(unmaskedPayload))
    } yield (frame, rawPayload)
  }

  /** Parse a complete message (handling fragmentation).
    *
    * This reads frames until a final frame is received, reassembling fragmented messages.
    *
    * @param reader
    *   the buffered socket reader
    * @param maxMessageSize
    *   maximum total message size
    * @param expectMasked
    *   whether frames must be masked
    * @return
    *   the complete message or an error
    */
  def parseMessage(
    reader: BufferedSocketReader,
    maxMessageSize: Long,
    expectMasked: Boolean
  ): Eru[WebSocketError, WebSocketFrame] = {

    def loop(
      firstOpcode: Option[WebSocketOpcode],
      accumulatedData: Array[Byte]
    ): Eru[WebSocketError, WebSocketFrame] = {
      parseFrameRaw(reader, maxMessageSize - accumulatedData.length, expectMasked).flatMap {
        // Control frames can appear in the middle of fragmented messages
        case (frame @ WebSocketFrame.Close(_, _), _) =>
          Eru.succeed(frame)

        case (frame @ WebSocketFrame.Ping(_), _) =>
          Eru.succeed(frame)

        case (frame @ WebSocketFrame.Pong(_), _) =>
          Eru.succeed(frame)

        // Handle fragmented data frames
        case (_, rawPayload) if rawPayload.opcode == WebSocketOpcode.Text && rawPayload.fin =>
          firstOpcode match {
            case None =>
              // Complete unfragmented message - validate UTF-8 on raw bytes
              validateUtf8(rawPayload.data).map { _ =>
                WebSocketFrame.Text(new String(rawPayload.data, java.nio.charset.StandardCharsets.UTF_8), fin = true)
              }
            case Some(_) =>
              // This shouldn't happen - text frame in middle of fragmentation
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  "Received non-continuation data frame while expecting continuation",
                  WebSocketCloseCode.ProtocolError
                )
              )
          }

        case (frame: WebSocketFrame.Binary, _) if frame.fin =>
          firstOpcode match {
            case None =>
              Eru.succeed(frame)
            case Some(_) =>
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  "Received non-continuation data frame while expecting continuation",
                  WebSocketCloseCode.ProtocolError
                )
              )
          }

        case (_, rawPayload) if rawPayload.opcode == WebSocketOpcode.Text =>
          // Start of fragmented text message - keep raw bytes, don't decode yet
          loop(Some(WebSocketOpcode.Text), rawPayload.data)

        case (frame: WebSocketFrame.Binary, _) =>
          // Start of fragmented binary message
          loop(Some(WebSocketOpcode.Binary), frame.payload.toArray)

        case (frame: WebSocketFrame.Continuation, _) =>
          firstOpcode match {
            case None =>
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  "Received continuation frame without preceding data frame",
                  WebSocketCloseCode.ProtocolError
                )
              )
            case Some(opcode) =>
              val newData = accumulatedData ++ frame.payload.toArray
              if newData.length > maxMessageSize then {
                Eru.fail(WebSocketError.MessageTooLarge(newData.length, maxMessageSize))
              } else if frame.fin then {
                // Final fragment - reconstruct complete message
                opcode match {
                  case WebSocketOpcode.Text =>
                    // Validate complete UTF-8
                    validateUtf8(newData).map { _ =>
                      WebSocketFrame.Text(
                        new String(newData, java.nio.charset.StandardCharsets.UTF_8),
                        fin = true
                      )
                    }
                  case WebSocketOpcode.Binary =>
                    Eru.succeed(WebSocketFrame.Binary(Bytes.fromArray(newData), fin = true))
                  case _ =>
                    Eru.fail(
                      WebSocketError.InvalidFrame(
                        s"Unexpected opcode in fragmented message: $opcode",
                        "RFC 6455 Section 5.4"
                      )
                    )
                }
              } else {
                // More fragments expected
                loop(Some(opcode), newData)
              }
          }

        case (frame, _) =>
          Eru.fail(
            WebSocketError.InvalidFrame(
              s"Unexpected frame type: ${frame.opcode}",
              "RFC 6455 Section 5.4"
            )
          )
      }
    }

    loop(None, Array.empty)
  }
}
