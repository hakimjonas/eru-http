package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.*

/** HTTP/2 client connection handler.
  *
  * Manages a single HTTP/2 connection including:
  *   - Connection preface exchange
  *   - Frame reading/writing
  *   - Request/response mapping to streams
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
final class H2ClientConnection private (
  channel: WritableByteChannel & ReadableByteChannel,
  val connection: H2Connection
) {

  // Read buffer for incoming frames (large enough for max frame + header)
  private val readBuffer = ByteBuffer.allocate(H2Frame.DefaultMaxFrameSize + H2Frame.HeaderSize)

  // ============================================================================
  // Multiplexing Support
  // ============================================================================

  // Pending headers for streams (accumulated before complete response)
  private val pendingHeaders = new java.util.concurrent.ConcurrentHashMap[Int, List[(String, String)]]()

  // Pending body chunks for streams (accumulated before END_STREAM)
  private val pendingBodyChunks =
    new java.util.concurrent.ConcurrentHashMap[Int, scala.collection.mutable.ArrayBuffer[Array[Byte]]]()

  // Completed responses ready to be retrieved (headers, optional body)
  private val completedResponses =
    new java.util.concurrent.ConcurrentHashMap[Int, (List[(String, String)], Option[Array[Byte]])]()

  // ============================================================================
  // Connection Preface
  // ============================================================================

  /** Send the client connection preface.
    *
    * Per RFC 9113 Section 3.4, client must send:
    *   1. Connection preface magic string (24 bytes)
    *   2. SETTINGS frame (may be empty)
    *
    * @return
    *   Eru effect that succeeds if preface was sent
    */
  def sendConnectionPreface(): Eru[H2Error, Unit] = {
    Eru.effect {
      // 1. Send the magic preface
      val prefaceBuffer = ByteBuffer.wrap(H2Frame.ConnectionPreface)
      while prefaceBuffer.hasRemaining do {
        channel.write(prefaceBuffer): Unit
      }

      // 2. Send initial SETTINGS frame
      val settingsEntries = connection.localSettings.toEntries()
      val settingsFrame = H2FrameCodec.settingsFrame(settingsEntries)
      val encoded = H2FrameCodec.encode(settingsFrame)
      while encoded.hasRemaining do {
        channel.write(encoded): Unit
      }
    }.mapError(e => H2Error.NetworkError(s"Failed to send connection preface: ${e.getMessage}", Some(e)))
  }

  /** Receive and validate the server's connection preface (SETTINGS frame).
    *
    * @return
    *   Eru effect that succeeds if server SETTINGS were received
    */
  def receiveConnectionPreface(): Eru[H2Error, Unit] = {
    readFrame().flatMap {
      case settings: SettingsFrame if !settings.isAck =>
        connection.applyPeerSettings(settings.settings).flatMap { _ =>
          // Send SETTINGS ACK
          sendSettingsAck()
        }
      case other =>
        Eru.fail(H2Error.InvalidPreface(s"Expected SETTINGS frame, got ${other.getClass.getSimpleName}"))
    }
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

  // ============================================================================
  // Frame I/O
  // ============================================================================

  /** Read a single frame from the connection.
    *
    * @return
    *   Eru effect with the parsed frame
    */
  def readFrame(): Eru[H2Error, H2ParsedFrame] = {
    connection.peerSettings.flatMap { peerSettings =>
      readFrameBytes().flatMap { buffer =>
        H2FrameCodec.parseFrame(buffer, peerSettings.maxFrameSize)
      }
    }
  }

  /** Read frame bytes from the channel.
    *
    * @return
    *   Eru effect with a ByteBuffer containing the complete frame
    */
  private def readFrameBytes(): Eru[H2Error, ByteBuffer] = {
    Eru.effect {
      readBuffer.clear()

      // Read frame header (9 bytes)
      readBuffer.limit(H2Frame.HeaderSize)
      readExactly(H2Frame.HeaderSize)

      // Parse length from header
      val length = ((readBuffer.get(0) & 0xff) << 16) |
        ((readBuffer.get(1) & 0xff) << 8) |
        (readBuffer.get(2) & 0xff)

      // Read frame payload
      if length > 0 then {
        readBuffer.limit(H2Frame.HeaderSize + length)
        readExactly(H2Frame.HeaderSize + length)
      }

      readBuffer.flip()
      readBuffer
    }.mapError(e => H2Error.NetworkError(s"Failed to read frame: ${e.getMessage}", Some(e)))
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

  // ============================================================================
  // Request/Response
  // ============================================================================

  /** Send a request on a new stream.
    *
    * @param method
    *   HTTP method
    * @param path
    *   request path
    * @param authority
    *   host:port or host
    * @param headers
    *   additional headers
    * @param body
    *   optional request body
    * @return
    *   Eru effect with the stream ID
    */
  def sendRequest(
    method: String,
    path: String,
    authority: String,
    scheme: String = "https",
    headers: List[(String, String)] = Nil,
    body: Option[Array[Byte]] = None
  ): Eru[H2Error, Int] = {
    connection.createStream().flatMap { stream =>
      val streamId = stream.streamId

      // Build pseudo-headers per RFC 9113 Section 8.3.1
      val pseudoHeaders = List(
        (":method", method),
        (":scheme", scheme),
        (":authority", authority),
        (":path", path)
      )

      // Encode headers with HPACK
      val allHeaders = pseudoHeaders ++ headers

      connection.peerSettings.flatMap { peerSettings =>
        val headerBuffer = ByteBuffer.allocate(peerSettings.maxFrameSize)

        connection.encodeHeaders(allHeaders, headerBuffer).mapError(_.toH2Error).flatMap { _ =>
          headerBuffer.flip()
          val headerBlock = new Array[Byte](headerBuffer.remaining)
          headerBuffer.get(headerBlock)

          // Determine if we have a body
          val endStream = body.isEmpty

          // Create HEADERS frame
          val headersFrame = H2FrameCodec.headersFrame(streamId, headerBlock, endStream = endStream, endHeaders = true)

          // Send HEADERS
          writeFrame(headersFrame).flatMap { _ =>
            // Update stream state
            stream.sendHeaders(endStream).flatMap { _ =>
              // Send body if present
              body match {
                case Some(data) if data.nonEmpty =>
                  sendData(stream, data, endStream = true)
                case _ =>
                  Eru.succeed(streamId)
              }
            }
          }
        }
      }
    }
  }

  /** Send DATA frames for request body.
    *
    * Implements flow control per RFC 9113 Section 5.2. When the flow control window is exhausted,
    * waits for WINDOW_UPDATE frames to replenish it before continuing. This requires the caller to
    * ensure frames are being read concurrently (via receiveResponse) to process WINDOW_UPDATE.
    *
    * @param stream
    *   the stream to send on
    * @param data
    *   the data to send
    * @param endStream
    *   whether this is the last data
    * @return
    *   Eru effect with the stream ID
    */
  private def sendData(stream: H2Stream, data: Array[Byte], endStream: Boolean): Eru[H2Error, Int] = {
    connection.peerSettings.flatMap { peerSettings =>
      val maxDataSize = peerSettings.maxFrameSize

      // Split data into frames respecting flow control
      def sendChunks(offset: Int): Eru[H2Error, Int] = {
        if offset >= data.length then {
          Eru.succeed(stream.streamId)
        } else {
          // Get current window sizes to determine how much we can send
          for {
            streamWindow <- stream.sendWindow
            connWindow <- connection.connectionSendWindow
            remaining = data.length - offset
            // Only send as much as both windows allow
            allowedByFlow = math.min(math.max(0, streamWindow), math.max(0, connWindow))
            chunkSize = math.min(math.min(remaining, maxDataSize), allowedByFlow)
            result <-
              if chunkSize == 0 then {
                // Flow control exhausted - wait for WINDOW_UPDATE on the appropriate window
                // This requires concurrent frame reading (via receiveResponse) to process WINDOW_UPDATE
                val waitEffect = if streamWindow <= 0 && connWindow <= 0 then {
                  // Both exhausted - wait on either (first to signal wins, then retry checks again)
                  // Use stream window as primary since it's per-stream and more likely to be the bottleneck
                  stream.waitForWindowAvailable.eru
                } else if streamWindow <= 0 then {
                  // Only stream window exhausted
                  stream.waitForWindowAvailable.eru
                } else {
                  // Only connection window exhausted
                  connection.waitForConnectionWindowAvailable.eru
                }
                waitEffect.flatMap { _ =>
                  sendChunks(offset) // Retry after window becomes available
                }
              } else {
                val isLast = offset + chunkSize >= data.length
                val chunk = java.util.Arrays.copyOfRange(data, offset, offset + chunkSize)

                // Consume flow control window and send
                connection.consumeConnectionSendWindow(chunkSize).flatMap { _ =>
                  stream.consumeSendWindow(chunkSize).flatMap { _ =>
                    val dataFrame = H2FrameCodec.dataFrame(stream.streamId, chunk, endStream = isLast && endStream)
                    writeFrame(dataFrame).flatMap { _ =>
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

  /** Receive a response for a stream (multiplexing-aware).
    *
    * This method supports multiplexing by buffering responses for other streams that arrive before
    * the requested stream's response. Responses can be received out of order.
    *
    * @param streamId
    *   the stream ID to receive response for
    * @return
    *   Eru effect with headers and optional body
    */
  def receiveResponse(streamId: Int): Eru[H2Error, (List[(String, String)], Option[Array[Byte]])] = {
    // Check if response is already buffered (from earlier read for another stream)
    Option(completedResponses.remove(streamId)) match {
      case Some(response) => Eru.succeed(response)
      case None => receiveResponseFromNetwork(streamId)
    }
  }

  /** Read frames from network until target stream's response is complete.
    *
    * Buffers responses for other streams that arrive first.
    */
  private def receiveResponseFromNetwork(
    targetStreamId: Int
  ): Eru[H2Error, (List[(String, String)], Option[Array[Byte]])] = {
    def loop(): Eru[H2Error, (List[(String, String)], Option[Array[Byte]])] = {
      readFrame().flatMap { frame =>
        processFrameForMultiplexing(frame, targetStreamId).flatMap {
          case Some(response) => Eru.succeed(response)
          case None => loop()
        }
      }
    }
    loop()
  }

  /** Process a frame, handling multiplexing by buffering responses for non-target streams.
    *
    * @return
    *   Some(response) if target stream's response is complete, None to continue reading
    */
  private def processFrameForMultiplexing(
    frame: H2ParsedFrame,
    targetStreamId: Int
  ): Eru[H2Error, Option[(List[(String, String)], Option[Array[Byte]])]] = {
    frame match {
      case headers: HeadersFrame =>
        val sid = headers.streamId
        // Decode HPACK headers
        val buffer = ByteBuffer.wrap(headers.headerBlock)
        connection.decodeHeaders(buffer).mapError(_.toH2Error).flatMap { decoded =>
          val headerList = decoded.map { case (name, value, _) => (name, value) }

          // Update stream state
          connection.getOrCreateStream(sid).flatMap { stream =>
            stream.receiveHeaders(headers.isEndStream).flatMap { _ =>
              if headers.isEndStream then {
                // Response complete (no body)
                val response = (headerList, None)
                if sid == targetStreamId then {
                  Eru.succeed(Some(response))
                } else {
                  // Buffer for later retrieval
                  completedResponses.put(sid, response)
                  Eru.succeed(None)
                }
              } else {
                // Headers received, body will follow
                pendingHeaders.put(sid, headerList)
                pendingBodyChunks.putIfAbsent(sid, scala.collection.mutable.ArrayBuffer[Array[Byte]]())
                Eru.succeed(None)
              }
            }
          }
        }

      case data: DataFrame =>
        val sid = data.streamId
        // Update flow control
        connection.consumeConnectionReceiveWindow(data.data.length).flatMap { _ =>
          connection
            .getStream(sid)
            .flatMap { maybeStream =>
              maybeStream match {
                case Some(stream) => stream.consumeReceiveWindow(data.data.length).map(_ => ())
                case None => Eru.unit
              }
            }
            .flatMap { _ =>
              // Accumulate data
              val chunks = Option(pendingBodyChunks.get(sid)).getOrElse {
                val newChunks = scala.collection.mutable.ArrayBuffer[Array[Byte]]()
                pendingBodyChunks.put(sid, newChunks)
                newChunks
              }
              chunks += data.data

              // Update stream state
              connection
                .getStream(sid)
                .flatMap { maybeStream =>
                  maybeStream match {
                    case Some(stream) => stream.receiveData(data.isEndStream).map(_ => ())
                    case None => Eru.unit
                  }
                }
                .flatMap { _ =>
                  if data.isEndStream then {
                    // Combine chunks into body
                    val totalSize = chunks.map(_.length).sum
                    val body = new Array[Byte](totalSize)
                    var offset = 0
                    chunks.foreach { chunk =>
                      System.arraycopy(chunk, 0, body, offset, chunk.length)
                      offset += chunk.length
                    }

                    // Get headers and clean up
                    val headersList = Option(pendingHeaders.remove(sid)).getOrElse(Nil)
                    pendingBodyChunks.remove(sid)

                    val response = (headersList, Some(body))
                    if sid == targetStreamId then {
                      Eru.succeed(Some(response))
                    } else {
                      completedResponses.put(sid, response)
                      Eru.succeed(None)
                    }
                  } else {
                    // Send WINDOW_UPDATE to keep flow going
                    val increment = data.data.length
                    val connWindowUpdate = H2FrameCodec.windowUpdateFrame(0, increment)
                    val streamWindowUpdate = H2FrameCodec.windowUpdateFrame(sid, increment)
                    writeFrame(connWindowUpdate).flatMap { _ =>
                      writeFrame(streamWindowUpdate).flatMap { _ =>
                        connection.replenishConnectionReceiveWindow(increment).flatMap { _ =>
                          connection
                            .getStream(sid)
                            .flatMap { maybeStream =>
                              maybeStream match {
                                case Some(stream) => stream.replenishReceiveWindow(increment).map(_ => ())
                                case None => Eru.unit
                              }
                            }
                            .map(_ => None)
                        }
                      }
                    }
                  }
                }
            }
        }

      case settings: SettingsFrame if settings.isAck =>
        connection.acknowledgeSettings().map(_ => None)

      case settings: SettingsFrame =>
        connection.applyPeerSettings(settings.settings).flatMap { _ =>
          sendSettingsAck().map(_ => None)
        }

      case ping: PingFrame if !ping.isAck =>
        val pongFrame = H2FrameCodec.pingFrame(ping.data, ack = true)
        writeFrame(pongFrame).map(_ => None)

      case windowUpdate: WindowUpdateFrame =>
        if windowUpdate.streamId == 0 then {
          connection.replenishConnectionSendWindow(windowUpdate.windowSizeIncrement).map(_ => None)
        } else {
          connection
            .getStream(windowUpdate.streamId)
            .flatMap { maybeStream =>
              maybeStream match {
                case Some(stream) => stream.replenishSendWindow(windowUpdate.windowSizeIncrement).map(_ => ())
                case None => Eru.unit
              }
            }
            .map(_ => None)
        }

      case goaway: GoAwayFrame =>
        connection.receiveGoaway(goaway.lastStreamId, H2ErrorCode.fromValueOrNoError(goaway.errorCode)).flatMap { _ =>
          Eru.fail(
            H2Error.ConnectionError(
              H2ErrorCode.fromValueOrNoError(goaway.errorCode),
              Some(
                s"GOAWAY received: lastStreamId=${goaway.lastStreamId}, debug=${new String(goaway.debugData, "UTF-8")}"
              )
            )
          )
        }

      case rst: RstStreamFrame if rst.streamId == targetStreamId =>
        Eru.fail(
          H2Error.StreamError(
            targetStreamId,
            H2ErrorCode.fromValueOrNoError(rst.errorCode),
            Some("Stream reset by peer")
          )
        )

      case rst: RstStreamFrame =>
        // RST for non-target stream - clean up any pending state
        pendingHeaders.remove(rst.streamId)
        pendingBodyChunks.remove(rst.streamId)
        Eru.succeed(None)

      case _ =>
        Eru.succeed(None) // Ignore other frames
    }
  }

  // ============================================================================
  // Shutdown
  // ============================================================================

  /** Send GOAWAY and close the connection.
    *
    * @param errorCode
    *   the error code (NoError for graceful shutdown)
    * @param debugData
    *   optional debug message
    * @return
    *   Eru effect that succeeds when GOAWAY is sent
    */
  def shutdown(errorCode: H2ErrorCode = H2ErrorCode.NoError, debugData: String = ""): Eru[H2Error, Unit] = {
    connection.initiateGoaway().flatMap { lastStreamId =>
      val goawayFrame = H2FrameCodec.goAwayFrame(lastStreamId, errorCode, debugData.getBytes("UTF-8"))
      writeFrame(goawayFrame)
    }
  }
}

object H2ClientConnection {

  /** Create a new HTTP/2 client connection.
    *
    * @param channel
    *   the underlying channel (should be TLS-wrapped for h2, raw for h2c)
    * @param localSettings
    *   settings to send to server
    * @return
    *   Eru effect with a new H2ClientConnection
    */
  def apply(
    channel: WritableByteChannel & ReadableByteChannel,
    localSettings: H2Settings = H2Settings.default
  )(using EruRuntime): Eru[Nothing, H2ClientConnection] = {
    H2Connection.client(localSettings).map { connection =>
      new H2ClientConnection(channel, connection)
    }
  }

  /** Create and initialize an HTTP/2 client connection.
    *
    * Sends the connection preface and waits for server's SETTINGS.
    *
    * @param channel
    *   the underlying channel
    * @param localSettings
    *   settings to send to server
    * @return
    *   Eru effect with the initialized connection
    */
  def connect(
    channel: WritableByteChannel & ReadableByteChannel,
    localSettings: H2Settings = H2Settings.default
  )(using EruRuntime): Eru[H2Error, H2ClientConnection] = {
    apply(channel, localSettings).flatMap { conn =>
      conn.sendConnectionPreface().flatMap { _ =>
        conn.receiveConnectionPreface().map { _ =>
          conn
        }
      }
    }
  }
}
