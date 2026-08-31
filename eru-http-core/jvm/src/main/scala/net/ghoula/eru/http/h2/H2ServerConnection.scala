package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.*
import net.ghoula.eru.http.SSLSocketChannel

/** HTTP/2 server connection handler.
  *
  * Manages a single HTTP/2 connection from the server side including:
  *   - Connection preface exchange (receive client preface, send server SETTINGS)
  *   - Frame reading/writing
  *   - Request reception and response sending
  *   - HPACK encoding/decoding
  *   - Flow control
  *
  * Uses Eru's effect-based Ref for all state management, ensuring thread-safe and fiber-safe
  * concurrent access without mutable variables.
  *
  * @param channel
  *   the underlying channel (raw or TLS-wrapped)
  * @param connection
  *   the H2Connection state manager
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-3.4 RFC 9113 Section 3.4]]
  */
final class H2ServerConnection private (
  channel: WritableByteChannel & ReadableByteChannel,
  val connection: H2Connection
) {

  /** Read buffer for incoming frames (large enough for max frame + header). */
  private val readBuffer = ByteBuffer.allocate(H2Frame.DefaultMaxFrameSize + H2Frame.HeaderSize)

  /** Underlying socket for timeout control, when available. Reserved for future use when
    * implementing read timeouts.
    */
  @scala.annotation.unused
  private val underlyingSocket: Option[java.net.Socket] = channel match {
    case ssl: SSLSocketChannel => Some(ssl.underlyingSocket)
    case sc: java.nio.channels.SocketChannel => Some(sc.socket())
    case _ => None
  }

  private val resetBudget = new RingBudget(H2ServerConnection.ResetBudget, H2ServerConnection.ResetWindowNanos)
  private val settingsBudget = new RingBudget(H2ServerConnection.SettingsBudget, H2ServerConnection.SettingsWindowNanos)
  private val pingBudget = new RingBudget(H2ServerConnection.PingBudget, H2ServerConnection.PingWindowNanos)

  /** Per-frame-type rolling-window rate budget used as an anti-flood guard.
    *
    * Several HTTP/2 frame types are cheap for the sender but cause the receiver to do real work
    * (allocate state, write an ACK, spawn a handler fiber). Without a rate cap an attacker can
    * flood these frames and exhaust the server's write path or state memory.
    *
    * Each guarded frame type has its own `RingBudget`: a fixed-size ring buffer of recent
    * timestamps. `recordAndCheck` stores the current timestamp and returns true iff all `capacity`
    * slots in the ring fit within the configured time window — i.e. the peer exceeded N-per-window.
    * On trip, the caller sends GOAWAY(ENHANCE_YOUR_CALM) and fails.
    *
    * Only peer-initiated frames count. Our own frames (e.g. sending RST_STREAM to reject a bad
    * client frame) must not trip our own budget. All observations happen on the single frame-read
    * fiber, so plain arrays plus vars are safe — no atomics needed.
    */
  private final class RingBudget(capacity: Int, windowNanos: Long) {
    private val timestamps = new Array[Long](capacity)
    private var nextIdx = 0
    private var filled = false

    /** Record an event at System.nanoTime() and return true iff the budget is now exhausted (all
      * `capacity` slots hold timestamps within the last `windowNanos`).
      */
    def recordAndCheck(): Boolean = {
      val now = System.nanoTime()
      timestamps(nextIdx) = now
      nextIdx = (nextIdx + 1) % capacity
      if nextIdx == 0 then filled = true

      if !filled then false
      else {
        val oldest = timestamps(nextIdx)
        (now - oldest) < windowNanos
      }
    }
  }

  /** Handle a peer-initiated RST_STREAM: check budget, close + remove the stream.
    *
    * Returns a failed effect (via `sendGoawayAndFail`) if the budget is exceeded; otherwise normal
    * handling — reset the stream and remove it from `streamsRef`. `stream.reset()` alone only
    * transitions state to Closed; without `removeStream`, an attacker would still grow memory 1:1
    * with attack size even within the rapid-reset budget.
    */
  private def applyPeerReset(streamId: Int): Eru[H2Error, Unit] =
    if resetBudget.recordAndCheck() then
      sendGoawayAndFail(
        H2ErrorCode.EnhanceYourCalm,
        s"HTTP/2 rapid-reset budget exceeded: more than ${H2ServerConnection.ResetBudget} " +
          s"peer RST_STREAMs within ${H2ServerConnection.ResetWindowNanos / 1_000_000}ms " +
          "(CVE-2023-44487 mitigation)"
      ).map(_ => ())
    else
      connection
        .getStream(streamId)
        .flatMap {
          case Some(stream) => stream.reset().map(_ => ())
          case None => Eru.unit
        }
        .flatMap(_ => connection.removeStream(streamId))

  /** Apply a peer SETTINGS update and ACK, guarded by `settingsBudget` (CVE-2019-9515).
    *
    * Called from every post-preface SETTINGS-frame code path. Only non-ACK SETTINGS count toward
    * the budget — ACK frames are our peer acknowledging OUR SETTINGS updates and cannot be
    * flood-driven (we only send SETTINGS at preface, once).
    */
  private def guardAndApplyPeerSettings(entries: List[SettingsEntry]): Eru[H2Error, Unit] =
    if settingsBudget.recordAndCheck() then
      sendGoawayAndFail(
        H2ErrorCode.EnhanceYourCalm,
        s"HTTP/2 SETTINGS flood budget exceeded: more than ${H2ServerConnection.SettingsBudget} " +
          s"peer SETTINGS frames within ${H2ServerConnection.SettingsWindowNanos / 1_000_000}ms " +
          "(CVE-2019-9515 mitigation)"
      ).map(_ => ())
    else connection.applyPeerSettings(entries).flatMap(_ => sendSettingsAck())

  /** Reply to a peer keepalive PING with a PONG, guarded by `pingBudget` (CVE-2019-9512).
    *
    * Only non-ACK PINGs count toward the budget — an ACK from peer is them responding to our own
    * PING (we don't send any today, so this is defensive).
    */
  private def guardAndAckPing(pingData: Array[Byte]): Eru[H2Error, Unit] =
    if pingBudget.recordAndCheck() then
      sendGoawayAndFail(
        H2ErrorCode.EnhanceYourCalm,
        s"HTTP/2 PING flood budget exceeded: more than ${H2ServerConnection.PingBudget} " +
          s"peer PINGs within ${H2ServerConnection.PingWindowNanos / 1_000_000}ms " +
          "(CVE-2019-9512 mitigation)"
      ).map(_ => ())
    else writeFrame(H2FrameCodec.pingFrame(pingData, ack = true)).map(_ => ())

  /** Receive the client connection preface.
    *
    * Per RFC 9113 Section 3.4, server must receive:
    *   1. Connection preface magic string (24 bytes)
    *   2. SETTINGS frame from client
    *
    * @return
    *   Eru effect that succeeds if preface was received and validated
    */
  def receiveConnectionPreface(): Eru[H2Error, Unit] = {
    Eru.effect {
      val prefaceBuffer = ByteBuffer.allocate(H2Frame.ConnectionPreface.length)
      while prefaceBuffer.hasRemaining do {
        val bytesRead = channel.read(prefaceBuffer)
        if bytesRead < 0 then {
          throw new java.io.EOFException("Connection closed while reading preface")
        }
      }
      prefaceBuffer.flip()

      val received = new Array[Byte](H2Frame.ConnectionPreface.length)
      prefaceBuffer.get(received)
      if !java.util.Arrays.equals(received, H2Frame.ConnectionPreface) then {
        throw new IllegalStateException("Invalid HTTP/2 connection preface magic")
      }
    }.mapError(e => H2Error.InvalidPreface(s"Failed to receive connection preface: ${e.getMessage}")).flatMap { _ =>
      readFrame().flatMap {
        case settings: SettingsFrame if !settings.isAck =>
          connection.applyPeerSettings(settings.settings).flatMap { _ =>
            sendSettingsAck()
          }
        case other =>
          Eru.fail(
            H2Error.InvalidPreface(s"Expected SETTINGS frame after preface, got ${other.getClass.getSimpleName}")
          )
      }
    }
  }

  /** Send the server connection preface (SETTINGS frame).
    *
    * Per RFC 9113 Section 3.4, server sends only SETTINGS (no magic).
    *
    * @return
    *   Eru effect that succeeds if SETTINGS was sent
    */
  def sendConnectionPreface(): Eru[H2Error, Unit] = {
    Eru.effect {
      val settingsEntries = connection.localSettings.toEntries()
      val settingsFrame = H2FrameCodec.settingsFrame(settingsEntries)
      val encoded = H2FrameCodec.encode(settingsFrame)
      while encoded.hasRemaining do {
        channel.write(encoded): Unit
      }
    }.mapError(e => H2Error.NetworkError(s"Failed to send connection preface: ${e.getMessage}", Some(e)))
  }

  /** Send SETTINGS ACK frame. */
  private def sendSettingsAck(): Eru[H2Error, Unit] = {
    Eru.effect {
      val ackFrame = H2FrameCodec.settingsAckFrame()
      val encoded = H2FrameCodec.encode(ackFrame)
      while encoded.hasRemaining do {
        channel.write(encoded): Unit
      }
    }.mapError(e => H2Error.NetworkError(s"Failed to send SETTINGS ACK: ${e.getMessage}", Some(e)))
  }

  /** Read a single frame from the connection.
    *
    * @return
    *   Eru effect with the parsed frame
    */
  def readFrame(): Eru[H2Error, H2ParsedFrame] = {
    connection.peerSettings.flatMap { peerSettings =>
      readFrameBytes(peerSettings.maxFrameSize).flatMap { buffer =>
        H2FrameCodec.parseFrame(buffer, peerSettings.maxFrameSize)
      }
    }.recoverWith {
      case err @ H2Error.ProtocolViolation(_, errorCode) =>
        sendGoaway(errorCode).flatMap(_ => Eru.fail(err))
      case err =>
        Eru.fail(err)
    }
  }

  /** Send GOAWAY frame without failing (for error recovery).
    *
    * After sending GOAWAY, tries to gracefully shutdown the output stream to ensure the client
    * receives the frame before the connection closes.
    */
  private def sendGoaway(errorCode: H2ErrorCode): Eru[H2Error, Unit] = {
    connection.initiateGoaway().flatMap { lastStreamId =>
      Eru.effect {
        val goawayFrame = H2FrameCodec.goAwayFrame(lastStreamId, errorCode, Array.emptyByteArray)
        val encoded = H2FrameCodec.encode(goawayFrame)
        while encoded.hasRemaining do {
          channel.write(encoded): Unit
        }
        channel match {
          case socketChannel: java.nio.channels.SocketChannel =>
            try {
              socketChannel.socket().shutdownOutput()
              Thread.sleep(50)
            } catch {
              case _: Exception => ()
            }
          case _ =>
            Thread.sleep(50)
        }
      }.mapError(e => H2Error.NetworkError(s"Failed to send GOAWAY: ${e.getMessage}", Some(e)))
        .recoverWith(_ => Eru.unit)
    }
  }

  /** Read frame bytes from the channel.
    *
    * @return
    *   Eru effect with a ByteBuffer containing the complete frame
    */
  private def readFrameBytes(maxFrameSize: Int): Eru[H2Error, ByteBuffer] = {
    Eru.effect {
      readBuffer.clear()
      readBuffer.limit(H2Frame.HeaderSize)
      readExactly(H2Frame.HeaderSize)

      val length = ((readBuffer.get(0) & 0xff) << 16) |
        ((readBuffer.get(1) & 0xff) << 8) |
        (readBuffer.get(2) & 0xff)
      val frameType = readBuffer.get(3) & 0xff
      (length, frameType)
    }.mapError { e =>
      H2Error.NetworkError(s"Failed to read frame: ${e.getMessage}", Some(e))
    }.flatMap { case (length, frameType) =>
      if length > maxFrameSize then {
        Eru.effect { drainOversizedFrame(length) }.mapError { e =>
          H2Error.NetworkError(s"Failed to drain oversized frame: ${e.getMessage}", Some(e))
        }.flatMap { _ =>
          Eru.fail(
            H2Error.ProtocolViolation(
              s"Frame size $length exceeds maximum $maxFrameSize (frame type: $frameType)",
              H2ErrorCode.FrameSizeError
            )
          )
        }
      } else {
        Eru.effect {
          if length > 0 then {
            readBuffer.limit(H2Frame.HeaderSize + length)
            readExactly(H2Frame.HeaderSize + length)
          }
          readBuffer.flip()
          readBuffer
        }.mapError { e =>
          H2Error.NetworkError(s"Failed to read frame: ${e.getMessage}", Some(e))
        }
      }
    }
  }

  /** Drain an oversized frame's payload from the channel. */
  private def drainOversizedFrame(length: Int): Unit = {
    val drainBuffer = ByteBuffer.allocate(math.min(length, 8192))
    var remaining = length
    var eof = false
    while remaining > 0 && !eof do {
      drainBuffer.clear()
      drainBuffer.limit(math.min(remaining, drainBuffer.capacity))
      val bytesRead = channel.read(drainBuffer)
      if bytesRead < 0 then {
        eof = true
      } else {
        remaining -= bytesRead
      }
    }
  }

  /** Read exactly n bytes from the channel. */
  private def readExactly(n: Int): Unit = {
    while readBuffer.position < n do {
      val bytesRead = channel.read(readBuffer)
      if bytesRead < 0 then {
        throw new java.io.EOFException("Connection closed while reading frame")
      }
    }
  }

  /** Write a frame to the connection.
    *
    * @param frame
    *   the frame to write
    * @return
    *   Eru effect that succeeds if the frame was written
    */
  def writeFrame(frame: H2ParsedFrame): Eru[H2Error, Unit] = {
    Eru.effect {
      val encoded = H2FrameCodec.encode(frame)
      while encoded.hasRemaining do {
        channel.write(encoded): Unit
      }
    }.mapError(e => H2Error.NetworkError(s"Failed to write frame: ${e.getMessage}", Some(e)))
  }

  /** Receive a request from a client.
    *
    * Reads HEADERS frame and optional DATA frames for request body. The expected Content-Length is
    * extracted for body-size validation per RFC 9113 Section 8.1.2.6.
    *
    * @return
    *   Eru effect with (streamId, headers, optional body)
    */
  def receiveRequest(): Eru[H2Error, (Int, List[(String, String)], Option[Array[Byte]])] = {
    receiveRequestHeaders().flatMap { case (streamId, headers, endStream) =>
      if endStream then {
        Eru.succeed((streamId, headers, None))
      } else {
        val contentLength = headers.find { case (name, _) => name.equalsIgnoreCase("content-length") }.flatMap {
          case (_, value) => value.toLongOption
        }
        receiveData(streamId, contentLength).map { body =>
          (streamId, headers, Some(body))
        }
      }
    }
  }

  /** Receive request headers from a client stream.
    *
    * Handles both single HEADERS frames and HEADERS + CONTINUATION sequences per RFC 9113. Per RFC
    * 9113 Section 7, unknown error codes in a received GOAWAY must not trigger special behavior.
    */
  private def receiveRequestHeaders(): Eru[H2Error, (Int, List[(String, String)], Boolean)] = {
    readFrameLoop { frame =>
      frame match {
        case headers: HeadersFrame =>
          val streamId = headers.streamId

          headers.streamDependency match {
            case Some(dep) if dep == streamId =>
              sendRstStreamOnly(streamId, H2ErrorCode.ProtocolError).map(_ => None)
            case _ =>
              connection
                .registerPeerStream(streamId)
                .flatMap { stream =>
                  if headers.isEndHeaders then {
                    decodeAndFinishHeaders(stream, streamId, headers.headerBlock, headers.isEndStream)
                  } else {
                    readContinuationFrames(streamId, headers.headerBlock).flatMap { completeHeaderBlock =>
                      decodeAndFinishHeaders(stream, streamId, completeHeaderBlock, headers.isEndStream)
                    }
                  }
                }
                .recoverWith { case H2Error.StreamError(sid, errorCode, _) =>
                  sendRstStreamOnly(sid, errorCode).map(_ => None)
                }
          }

        case settings: SettingsFrame if settings.isAck =>
          connection.acknowledgeSettings().map(_ => None)

        case settings: SettingsFrame =>
          guardAndApplyPeerSettings(settings.settings).map(_ => None)

        case ping: PingFrame if !ping.isAck =>
          guardAndAckPing(ping.data).map(_ => None)

        case windowUpdate: WindowUpdateFrame =>
          handleWindowUpdate(windowUpdate).map(_ => None)

        case goaway: GoAwayFrame =>
          val errorCode = H2ErrorCode.fromValueOrNoError(goaway.errorCode)
          connection.receiveGoaway(goaway.lastStreamId, errorCode).flatMap { _ =>
            Eru.fail(
              H2Error.ConnectionError(
                errorCode,
                Some(
                  s"GOAWAY received: lastStreamId=${goaway.lastStreamId}, debug=${new String(goaway.debugData, "UTF-8")}"
                )
              )
            )
          }

        case rst: RstStreamFrame =>
          if rst.streamId == 0 then {
            sendGoawayAndFail(H2ErrorCode.ProtocolError, "RST_STREAM on stream 0")
          } else {
            connection.getStream(rst.streamId).flatMap {
              case Some(_) =>
                applyPeerReset(rst.streamId).map(_ => None)
              case None =>
                sendGoawayAndFail(H2ErrorCode.ProtocolError, s"RST_STREAM on idle stream ${rst.streamId}")
            }
          }

        case priority: PriorityFrame =>
          if priority.streamId == priority.streamDependency then {
            sendRstStreamOnly(priority.streamId, H2ErrorCode.ProtocolError).map(_ => None)
          } else {
            Eru.succeed(None)
          }

        case data: DataFrame =>
          connection.getStream(data.streamId).flatMap {
            case None =>
              sendGoawayAndFail(H2ErrorCode.ProtocolError, s"DATA frame on idle stream ${data.streamId}")
            case Some(stream) =>
              stream.state.flatMap {
                case H2StreamState.Open | H2StreamState.HalfClosedLocal =>
                  Eru.succeed(None)
                case H2StreamState.HalfClosedRemote =>
                  sendRstStreamOnly(data.streamId, H2ErrorCode.StreamClosed).map(_ => None)
                case H2StreamState.Closed =>
                  sendRstStreamOnly(data.streamId, H2ErrorCode.StreamClosed).map(_ => None)
                case _ =>
                  sendRstStreamOnly(data.streamId, H2ErrorCode.StreamClosed).map(_ => None)
              }
          }

        case cont: ContinuationFrame =>
          sendGoawayAndFail(H2ErrorCode.ProtocolError, s"Unexpected CONTINUATION frame on stream ${cont.streamId}")

        case _: PushPromiseFrame =>
          sendGoawayAndFail(H2ErrorCode.ProtocolError, "PUSH_PROMISE received from client")

        case _ =>
          Eru.succeed(None)
      }
    }
  }

  /** Handle WINDOW_UPDATE frame. */
  private def handleWindowUpdate(windowUpdate: WindowUpdateFrame): Eru[H2Error, Unit] = {
    if windowUpdate.streamId == 0 then {
      if windowUpdate.windowSizeIncrement == 0 then {
        sendGoawayAndFail(H2ErrorCode.ProtocolError, "WINDOW_UPDATE with zero increment")
      } else {
        connection
          .replenishConnectionSendWindow(windowUpdate.windowSizeIncrement)
          .map(_ => ())
          .recoverWith { case _: H2Error.FlowControlViolation =>
            sendGoawayAndFail(H2ErrorCode.FlowControlError, "Connection flow control window overflow")
          }
      }
    } else {
      if windowUpdate.windowSizeIncrement == 0 then {
        sendRstStreamOnly(windowUpdate.streamId, H2ErrorCode.ProtocolError)
      } else {
        connection.getStream(windowUpdate.streamId).flatMap {
          case Some(stream) =>
            stream.state.flatMap { streamState =>
              if streamState.isClosed then {
                Eru.unit
              } else {
                stream
                  .replenishSendWindow(windowUpdate.windowSizeIncrement)
                  .map(_ => ())
                  .recoverWith { case _: H2Error.FlowControlViolation =>
                    sendRstStreamOnly(windowUpdate.streamId, H2ErrorCode.FlowControlError)
                  }
              }
            }
          case None =>
            sendGoawayAndFail(H2ErrorCode.ProtocolError, s"WINDOW_UPDATE on idle stream ${windowUpdate.streamId}")
        }
      }
    }
  }

  /** Decode header block and finish header reception. */
  private def decodeAndFinishHeaders(
    stream: H2Stream,
    streamId: Int,
    headerBlock: Array[Byte],
    endStream: Boolean
  ): Eru[H2Error, Option[(Int, List[(String, String)], Boolean)]] = {
    val buffer = ByteBuffer.wrap(headerBlock)
    connection.decodeHeaders(buffer).mapError(_.toH2Error).flatMap { decoded =>
      val headerList = decoded.map { case (name, value, _) => (name, value) }

      validateRequestHeaders(streamId, headerList).flatMap { _ =>
        stream.receiveHeaders(endStream).map { _ =>
          Some((streamId, headerList, endStream))
        }
      }
    }
  }

  /** Validate request headers per RFC 9113 Section 8.3. */
  private def validateRequestHeaders(streamId: Int, headers: List[(String, String)]): Eru[H2Error, Unit] = {
    findHeaderError(headers) match {
      case Some(errorMsg) => sendRstStreamAndFail(streamId, H2ErrorCode.ProtocolError, errorMsg)
      case None => Eru.unit
    }
  }

  /** Find validation error in headers, if any. */
  private def findHeaderError(headers: List[(String, String)]): Option[String] = {
    case class State(methodCount: Int, schemeCount: Int, pathCount: Int, authorityCount: Int, seenRegular: Boolean)

    def validate(remaining: List[(String, String)], state: State): Either[String, State] = {
      remaining match {
        case Nil =>
          if state.methodCount == 0 then Left("Missing :method pseudo-header")
          else if state.schemeCount == 0 then Left("Missing :scheme pseudo-header")
          else if state.pathCount == 0 then Left("Missing :path pseudo-header")
          else Right(state)

        case (name, value) :: rest =>
          if name.exists(c => c >= 'A' && c <= 'Z') then {
            Left(s"Uppercase header name: $name")
          } else if name.startsWith(":") then {
            if state.seenRegular then {
              Left(s"Pseudo-header $name after regular header")
            } else {
              name match {
                case ":method" =>
                  if state.methodCount > 0 then Left("Duplicate :method pseudo-header")
                  else validate(rest, state.copy(methodCount = state.methodCount + 1))
                case ":scheme" =>
                  if state.schemeCount > 0 then Left("Duplicate :scheme pseudo-header")
                  else validate(rest, state.copy(schemeCount = state.schemeCount + 1))
                case ":path" =>
                  if state.pathCount > 0 then Left("Duplicate :path pseudo-header")
                  else if value.isEmpty then Left("Empty :path pseudo-header")
                  else validate(rest, state.copy(pathCount = state.pathCount + 1))
                case ":authority" =>
                  if state.authorityCount > 0 then Left("Duplicate :authority pseudo-header")
                  else validate(rest, state.copy(authorityCount = state.authorityCount + 1))
                case ":status" =>
                  Left("Response pseudo-header :status in request")
                case unknown =>
                  Left(s"Unknown pseudo-header: $unknown")
              }
            }
          } else {
            val nameLower = name.toLowerCase
            if nameLower == "connection" || nameLower == "proxy-connection" ||
              nameLower == "keep-alive" || nameLower == "transfer-encoding" ||
              nameLower == "upgrade"
            then {
              Left(s"Connection-specific header in HTTP/2: $name")
            } else if nameLower == "te" && value.toLowerCase != "trailers" then {
              Left(s"TE header must be 'trailers', got: $value")
            } else {
              validate(rest, state.copy(seenRegular = true))
            }
          }
      }
    }

    validate(headers, State(0, 0, 0, 0, false)).left.toOption
  }

  /** Send RST_STREAM and return a stream error. */
  private def sendRstStreamAndFail(streamId: Int, errorCode: H2ErrorCode, message: String): Eru[H2Error, Nothing] = {
    val rstFrame = H2FrameCodec.rstStreamFrame(streamId, errorCode)
    writeFrame(rstFrame).flatMap { _ =>
      connection
        .getStream(streamId)
        .flatMap {
          case Some(stream) => stream.reset().map(_ => ())
          case None => Eru.unit
        }
        .flatMap(_ => Eru.fail(H2Error.StreamError(streamId, errorCode, Some(message))))
    }
  }

  /** Send RST_STREAM without failing (for recoverable stream errors). */
  private def sendRstStreamOnly(streamId: Int, errorCode: H2ErrorCode): Eru[H2Error, Unit] = {
    val rstFrame = H2FrameCodec.rstStreamFrame(streamId, errorCode)
    writeFrame(rstFrame).flatMap { _ =>
      connection.getStream(streamId).flatMap {
        case Some(stream) => stream.reset().map(_ => ())
        case None => Eru.unit
      }
    }
  }

  /** Read CONTINUATION frames until END_HEADERS is set.
    *
    * Enforces maxHeaderListSize to prevent CONTINUATION flood attacks (CVE-2024-27316). An attacker
    * can send an unbounded stream of tiny CONTINUATION frames without END_HEADERS, causing OOM.
    */
  private def readContinuationFrames(streamId: Int, initialBlock: Array[Byte]): Eru[H2Error, Array[Byte]] = {
    val maxSize = connection.localSettings.maxHeaderListSize
    val chunks = scala.collection.mutable.ArrayBuffer[Array[Byte]](initialBlock)
    var totalSize = initialBlock.length

    def loop(): Eru[H2Error, Array[Byte]] = {
      readFrame().flatMap {
        case cont: ContinuationFrame if cont.streamId == streamId =>
          totalSize += cont.headerBlock.length
          if totalSize > maxSize then {
            sendGoawayAndFail(
              H2ErrorCode.CompressionError,
              s"Header list size $totalSize exceeds limit $maxSize (RFC 9113 Section 6.5.2)"
            )
          } else {
            chunks += cont.headerBlock
            if cont.isEndHeaders then {
              val result = new Array[Byte](totalSize)
              var offset = 0
              chunks.foreach { chunk =>
                System.arraycopy(chunk, 0, result, offset, chunk.length)
                offset += chunk.length
              }
              Eru.succeed(result)
            } else {
              loop()
            }
          }

        case cont: ContinuationFrame =>
          sendGoawayAndFail(
            H2ErrorCode.ProtocolError,
            s"CONTINUATION for stream ${cont.streamId} while expecting stream $streamId"
          )

        case _ =>
          sendGoawayAndFail(H2ErrorCode.ProtocolError, s"Expected CONTINUATION frame for stream $streamId")
      }
    }

    loop()
  }

  /** Send GOAWAY and return a connection error. */
  private def sendGoawayAndFail(errorCode: H2ErrorCode, message: String): Eru[H2Error, Nothing] = {
    connection.initiateGoaway().flatMap { lastStreamId =>
      val goawayFrame = H2FrameCodec.goAwayFrame(lastStreamId, errorCode, message.getBytes("UTF-8"))
      writeFrame(goawayFrame).flatMap { _ =>
        Eru.fail(H2Error.ConnectionError(errorCode, Some(message)))
      }
    }
  }

  /** Receive DATA frames for a stream.
    *
    * A peer reset of the stream being read finalizes whatever bytes have been collected, after a
    * rapid-reset budget check. A trailer HEADERS on this stream must set END_STREAM per RFC 9113
    * Section 8.1 and must not contain pseudo-headers.
    */
  private def receiveData(streamId: Int, expectedContentLength: Option[Long]): Eru[H2Error, Array[Byte]] = {
    val chunks = scala.collection.mutable.ArrayBuffer[Array[Byte]]()
    var totalReceived: Long = 0

    def loop(): Eru[H2Error, Array[Byte]] = {
      readFrame().flatMap { frame =>
        frame match {
          case data: DataFrame if data.streamId == streamId =>
            chunks += data.data
            totalReceived += data.data.length

            connection.consumeConnectionReceiveWindow(data.data.length).flatMap { _ =>
              connection
                .getStream(streamId)
                .flatMap {
                  case Some(stream) => stream.consumeReceiveWindow(data.data.length).map(_ => ())
                  case None => Eru.unit
                }
                .flatMap { _ =>
                  connection
                    .getStream(streamId)
                    .flatMap {
                      case Some(stream) => stream.receiveData(data.isEndStream).map(_ => ())
                      case None => Eru.unit
                    }
                    .flatMap { _ =>
                      if data.isEndStream then {
                        expectedContentLength match {
                          case Some(expected) if totalReceived != expected =>
                            sendRstStreamOnly(streamId, H2ErrorCode.ProtocolError).flatMap { _ =>
                              Eru.fail(
                                H2Error.StreamError(
                                  streamId,
                                  H2ErrorCode.ProtocolError,
                                  Some(s"Content-Length mismatch: expected $expected, received $totalReceived")
                                )
                              )
                            }
                          case _ =>
                            val totalSize = chunks.map(_.length).sum
                            val result = new Array[Byte](totalSize)
                            var offset = 0
                            chunks.foreach { chunk =>
                              System.arraycopy(chunk, 0, result, offset, chunk.length)
                              offset += chunk.length
                            }
                            Eru.succeed(result)
                        }
                      } else {
                        val increment = data.data.length
                        val connWindowUpdate = H2FrameCodec.windowUpdateFrame(0, increment)
                        val streamWindowUpdate = H2FrameCodec.windowUpdateFrame(streamId, increment)
                        writeFrame(connWindowUpdate).flatMap { _ =>
                          writeFrame(streamWindowUpdate).flatMap { _ =>
                            connection.replenishConnectionReceiveWindow(increment).flatMap { _ =>
                              connection
                                .getStream(streamId)
                                .flatMap {
                                  case Some(stream) => stream.replenishReceiveWindow(increment).map(_ => ())
                                  case None => Eru.unit
                                }
                                .flatMap(_ => loop())
                            }
                          }
                        }
                      }
                    }
                }
            }

          case settings: SettingsFrame if settings.isAck =>
            connection.acknowledgeSettings().flatMap(_ => loop())

          case settings: SettingsFrame =>
            guardAndApplyPeerSettings(settings.settings).flatMap(_ => loop())

          case ping: PingFrame if !ping.isAck =>
            guardAndAckPing(ping.data).flatMap(_ => loop())

          case windowUpdate: WindowUpdateFrame =>
            handleWindowUpdate(windowUpdate).flatMap(_ => loop())

          case goaway: GoAwayFrame =>
            val errorCode = H2ErrorCode.fromValueOrNoError(goaway.errorCode)
            connection.receiveGoaway(goaway.lastStreamId, errorCode).flatMap { _ =>
              Eru.fail(
                H2Error.ConnectionError(
                  errorCode,
                  Some(s"GOAWAY received: lastStreamId=${goaway.lastStreamId}")
                )
              )
            }

          case rst: RstStreamFrame if rst.streamId == streamId =>
            applyPeerReset(streamId).flatMap { _ =>
              val totalSize = chunks.map(_.length).sum
              val result = new Array[Byte](totalSize)
              var offset = 0
              chunks.foreach { chunk =>
                System.arraycopy(chunk, 0, result, offset, chunk.length)
                offset += chunk.length
              }
              Eru.succeed(result)
            }

          case rst: RstStreamFrame =>
            applyPeerReset(rst.streamId).flatMap(_ => loop())

          case headers: HeadersFrame if headers.streamId == streamId =>
            if !headers.isEndStream then {
              sendRstStreamOnly(streamId, H2ErrorCode.ProtocolError).flatMap { _ =>
                Eru.fail(
                  H2Error.StreamError(streamId, H2ErrorCode.ProtocolError, Some("Second HEADERS without END_STREAM"))
                )
              }
            } else {
              val buffer = java.nio.ByteBuffer.wrap(headers.headerBlock)
              connection.decodeHeaders(buffer).mapError(_.toH2Error).flatMap { decoded =>
                val hasPseudoHeader = decoded.exists { case (name, _, _) => name.startsWith(":") }
                if hasPseudoHeader then {
                  sendRstStreamOnly(streamId, H2ErrorCode.ProtocolError).flatMap { _ =>
                    Eru.fail(
                      H2Error.StreamError(streamId, H2ErrorCode.ProtocolError, Some("Pseudo-header in trailers"))
                    )
                  }
                } else {
                  connection
                    .getStream(streamId)
                    .flatMap {
                      case Some(stream) => stream.receiveData(true).map(_ => ())
                      case None => Eru.unit
                    }
                    .flatMap { _ =>
                      val totalSize = chunks.map(_.length).sum
                      val result = new Array[Byte](totalSize)
                      var offset = 0
                      chunks.foreach { chunk =>
                        System.arraycopy(chunk, 0, result, offset, chunk.length)
                        offset += chunk.length
                      }
                      Eru.succeed(result)
                    }
                }
              }
            }

          case _ =>
            loop()
        }
      }
    }

    loop()
  }

  /** Send a response on a stream.
    *
    * Pending control frames are not processed here because the main loop handles all frame reading;
    * sendChunks' yield-and-recheck picks up window changes from SETTINGS processed by the main
    * loop.
    */
  def sendResponse(
    streamId: Int,
    status: Int,
    headers: List[(String, String)] = Nil,
    body: Option[Array[Byte]] = None
  ): Eru[H2Error, Unit] = {
    h2log(s"sendResponse: streamId=$streamId, status=$status, bodyLen=${body.map(_.length)}")
    connection.getStream(streamId).flatMap {
      case None =>
        Eru.fail(H2Error.StreamError(streamId, H2ErrorCode.ProtocolError, Some(s"Unknown stream $streamId")))
      case Some(stream) =>
        stream.state.flatMap { streamState =>
          if streamState.isClosed then {
            Eru.unit
          } else {
            val pseudoHeaders = List((":status", status.toString))
            val allHeaders = pseudoHeaders ++ headers

            connection.peerSettings.flatMap { peerSettings =>
              val headerBuffer = ByteBuffer.allocate(peerSettings.maxFrameSize)

              connection.encodeHeaders(allHeaders, headerBuffer).mapError(_.toH2Error).flatMap { _ =>
                headerBuffer.flip()
                val headerBlock = new Array[Byte](headerBuffer.remaining)
                headerBuffer.get(headerBlock)

                val endStream = body.isEmpty || body.exists(_.isEmpty)
                val headersFrame =
                  H2FrameCodec.headersFrame(streamId, headerBlock, endStream = endStream, endHeaders = true)

                writeFrame(headersFrame).flatMap { _ =>
                  stream.sendHeaders(endStream).flatMap { _ =>
                    body match {
                      case Some(data) if data.nonEmpty =>
                        sendData(stream, data, endStream = true)
                      case _ =>
                        Eru.unit
                    }
                  }
                }
              }
            }
          }
        }
    }
  }

  /** Debug logging, enabled with `-Dh2.debug=true`. */
  private val h2Debug = java.lang.Boolean.getBoolean("h2.debug")
  private def h2log(msg: String): Unit = if h2Debug then println(s"[H2Server] $msg")

  /** Process any pending control frames from the socket.
    *
    * This is called before making flow control decisions to ensure SETTINGS changes are applied
    * before we compute window sizes. Per RFC 9113 Section 6.9.2, changes to
    * SETTINGS_INITIAL_WINDOW_SIZE must be reflected in all stream windows.
    *
    * Checks for already-decrypted data in the SSL application buffer — a lightweight non-blocking
    * check that catches SETTINGS frames which arrived in the same TCP segment as the HEADERS frame.
    * For non-SSL channels this optimization is skipped.
    */
  private def processPendingControlFrames(): Eru[H2Error, Unit] = {
    h2log("processPendingControlFrames: checking for buffered data")
    Eru.effect {
      channel match {
        case ssl: SSLSocketChannel =>
          val has = ssl.hasBufferedData
          h2log(s"processPendingControlFrames: hasBufferedData=$has")
          has
        case _ =>
          h2log("processPendingControlFrames: non-SSL channel, skipping")
          false
      }
    }.mapError(e => H2Error.NetworkError(s"Failed to check for pending data: ${e.getMessage}", Some(e))).flatMap {
      hasPendingData =>
        if hasPendingData then {
          h2log("processPendingControlFrames: has pending data, reading frame")
          readPendingControlFrame()
        } else {
          h2log("processPendingControlFrames: no pending data, returning")
          Eru.unit
        }
    }
  }

  /** Read one pending frame and process if it's a control frame.
    *
    * Returns immediately after processing control frames, re-checking for more. Non-control frames
    * are unexpected here and cause an error (shouldn't happen in normal operation since we only
    * call this between request/response).
    */
  private def readPendingControlFrame(): Eru[H2Error, Unit] = {
    readFrame().flatMap { frame =>
      frame match {
        case settings: SettingsFrame if settings.isAck =>
          h2log("readPendingControlFrame: SETTINGS ACK")
          connection.acknowledgeSettings().flatMap(_ => processPendingControlFrames())

        case settings: SettingsFrame =>
          h2log(s"readPendingControlFrame: SETTINGS ${settings.settings}")
          guardAndApplyPeerSettings(settings.settings).flatMap(_ => processPendingControlFrames())

        case ping: PingFrame if !ping.isAck =>
          h2log("readPendingControlFrame: PING")
          guardAndAckPing(ping.data).flatMap(_ => processPendingControlFrames())

        case windowUpdate: WindowUpdateFrame =>
          h2log("readPendingControlFrame: WINDOW_UPDATE")
          handleWindowUpdate(windowUpdate).flatMap(_ => processPendingControlFrames())

        case goaway: GoAwayFrame =>
          val errorCode = H2ErrorCode.fromValueOrNoError(goaway.errorCode)
          h2log(s"readPendingControlFrame: GOAWAY $errorCode")
          connection.receiveGoaway(goaway.lastStreamId, errorCode)

        case _: PingFrame =>
          h2log("readPendingControlFrame: PING ACK")
          processPendingControlFrames()

        case other =>
          h2log(s"readPendingControlFrame: unexpected ${other.getClass.getSimpleName}")
          Eru.unit
      }
    }
  }

  /** Send DATA frames for response body.
    *
    * When the window is exhausted, waits for the appropriate semaphore signal, released by
    * replenishSendWindow() or replenishConnectionSendWindow() when the window transitions from <=0
    * to >0 (from a SETTINGS change or WINDOW_UPDATE).
    */
  private def sendData(stream: H2Stream, data: Array[Byte], endStream: Boolean): Eru[H2Error, Unit] = {
    connection.peerSettings.flatMap { peerSettings =>
      val maxDataSize = peerSettings.maxFrameSize
      h2log(s"sendData: streamId=${stream.streamId}, dataLen=${data.length}, maxDataSize=$maxDataSize")

      def sendChunks(offset: Int): Eru[H2Error, Unit] = {
        if offset >= data.length then {
          h2log("sendChunks: done (offset >= data.length)")
          Eru.unit
        } else {
          for {
            streamWindow <- stream.sendWindow
            connWindow <- connection.connectionSendWindow
            remaining = data.length - offset
            allowedByFlow = math.min(math.max(0, streamWindow), math.max(0, connWindow))
            chunkSize = math.min(math.min(remaining, maxDataSize), allowedByFlow)
            _ = h2log(
              s"sendChunks: offset=$offset, streamWindow=$streamWindow, connWindow=$connWindow, chunkSize=$chunkSize"
            )
            result <-
              if chunkSize <= 0 then {
                h2log("sendChunks: window exhausted, waiting for window signal")
                val waitEffect = if streamWindow <= 0 && connWindow <= 0 then {
                  stream.waitForWindowAvailable.eru
                } else if streamWindow <= 0 then {
                  stream.waitForWindowAvailable.eru
                } else {
                  connection.waitForConnectionWindowAvailable.eru
                }
                waitEffect.flatMap { _ =>
                  h2log("sendChunks: window signal received, retrying")
                  sendChunks(offset)
                }
              } else {
                val isLast = offset + chunkSize >= data.length
                val chunk = java.util.Arrays.copyOfRange(data, offset, offset + chunkSize)
                h2log(s"sendChunks: sending $chunkSize bytes, isLast=$isLast")

                connection.consumeConnectionSendWindow(chunkSize).flatMap { _ =>
                  stream.consumeSendWindow(chunkSize).flatMap { _ =>
                    val dataFrame = H2FrameCodec.dataFrame(stream.streamId, chunk, endStream = isLast && endStream)
                    writeFrame(dataFrame).flatMap { _ =>
                      h2log("sendChunks: DATA frame sent")
                      stream.sendData(isLast && endStream).flatMap { _ =>
                        sendChunks(offset + chunkSize)
                      }
                    }
                  }
                }
              }
          } yield result
        }
      }

      sendChunks(0)
    }
  }

  /** Read frames in a loop until we get the expected result. */
  private def readFrameLoop[A](handler: H2ParsedFrame => Eru[H2Error, Option[A]]): Eru[H2Error, A] = {
    def loop(): Eru[H2Error, A] = {
      readFrame().flatMap { frame =>
        handler(frame).flatMap {
          case Some(result) => Eru.succeed(result)
          case None => loop()
        }
      }
    }
    loop()
  }

  /** Send GOAWAY and close the connection. */
  def shutdown(errorCode: H2ErrorCode = H2ErrorCode.NoError, debugData: String = ""): Eru[H2Error, Unit] = {
    connection.initiateGoaway().flatMap { lastStreamId =>
      val goawayFrame = H2FrameCodec.goAwayFrame(lastStreamId, errorCode, debugData.getBytes("UTF-8"))
      writeFrame(goawayFrame)
    }
  }
}

object H2ServerConnection {

  /** HTTP/2 rapid-reset mitigation (CVE-2023-44487).
    *
    * A peer is allowed at most `ResetBudget` peer-initiated RST_STREAM frames within any rolling
    * `ResetWindowNanos` period. Exceeding this triggers GOAWAY(ENHANCE_YOUR_CALM) and closes the
    * connection. The budget sits well above what bursty legitimate clients need (request
    * cancellation on tab close, client timeouts, etc.).
    */
  val ResetBudget: Int = 100
  val ResetWindowNanos: Long = 10L * 1_000_000_000L

  /** HTTP/2 SETTINGS flood mitigation (CVE-2019-9515).
    *
    * A peer is allowed at most `SettingsBudget` non-ACK SETTINGS frames within any rolling
    * `SettingsWindowNanos` period. RFC 9113 §6.5 requires us to ACK every SETTINGS, which writes a
    * 9-byte frame to the socket — an attacker pumping SETTINGS saturates our write path with O(1)
    * cost to itself. Legitimate clients update SETTINGS rarely (mostly once at startup); 50/10s
    * leaves headroom for a reasonable update rate.
    */
  val SettingsBudget: Int = 50
  val SettingsWindowNanos: Long = 10L * 1_000_000_000L

  /** HTTP/2 PING flood mitigation (CVE-2019-9512).
    *
    * A peer is allowed at most `PingBudget` non-ACK PING frames within any rolling
    * `PingWindowNanos` period. RFC 9113 §6.7 requires an auto-PONG, and the attacker's O(1) cost
    * forces an O(1) write on our side. Legitimate keepalive is once every few seconds; 20/10s is
    * very generous.
    */
  val PingBudget: Int = 20
  val PingWindowNanos: Long = 10L * 1_000_000_000L

  /** Create a new HTTP/2 server connection.
    *
    * @param channel
    *   the underlying channel (should be TLS-wrapped for h2, raw for h2c)
    * @param localSettings
    *   settings to send to client
    * @return
    *   Eru effect with a new H2ServerConnection
    */
  def apply(
    channel: WritableByteChannel & ReadableByteChannel,
    localSettings: H2Settings = H2Settings.default
  )(using EruRuntime): Eru[Nothing, H2ServerConnection] = {
    H2Connection.server(localSettings).map { connection =>
      new H2ServerConnection(channel, connection)
    }
  }

  /** Create and initialize an HTTP/2 server connection.
    *
    * Receives client preface and sends server SETTINGS.
    *
    * @param channel
    *   the underlying channel
    * @param localSettings
    *   settings to send to client
    * @return
    *   Eru effect with the initialized connection
    */
  def accept(
    channel: WritableByteChannel & ReadableByteChannel,
    localSettings: H2Settings = H2Settings.default
  )(using EruRuntime): Eru[H2Error, H2ServerConnection] = {
    apply(channel, localSettings).flatMap { conn =>
      conn.sendConnectionPreface().flatMap { _ =>
        conn.receiveConnectionPreface().map(_ => conn)
      }
    }
  }
}
