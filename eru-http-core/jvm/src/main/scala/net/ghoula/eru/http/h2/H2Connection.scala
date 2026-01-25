package net.ghoula.eru.http.h2

import net.ghoula.eru.*

/** HTTP/2 connection state and stream management.
  *
  * Manages connection-level state using Eru's effect-based Ref for thread-safe, fiber-safe
  * concurrent access:
  *   - Stream lifecycle (creation, lookup, cleanup)
  *   - Connection-level flow control
  *   - HPACK encoder/decoder state
  *   - Settings (local and peer)
  *
  * @param isClient
  *   true if this is a client connection, false for server
  * @param localSettings
  *   our settings sent to peer
  * @param peerSettingsRef
  *   atomic reference to settings received from peer
  * @param streamsRef
  *   atomic reference to active streams map
  * @param connectionSendWindowRef
  *   atomic reference to connection-level send window
  * @param connectionReceiveWindowRef
  *   atomic reference to connection-level receive window
  * @param lastPeerStreamIdRef
  *   atomic reference to highest peer-initiated stream ID
  * @param lastLocalStreamIdRef
  *   atomic reference to highest locally-initiated stream ID
  * @param settingsAckedRef
  *   atomic reference to settings ACK status
  * @param goawayStateRef
  *   atomic reference to GOAWAY state
  * @param hpackEncoder
  *   HPACK encoder
  * @param hpackDecoder
  *   HPACK decoder
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-5 RFC 9113 Section 5]]
  */
final class H2Connection private (
  val isClient: Boolean,
  val localSettings: H2Settings,
  private val peerSettingsRef: Ref[H2Settings],
  private val streamsRef: Ref[Map[Int, H2Stream]],
  private val connectionSendWindowRef: Ref[Int],
  private val connectionReceiveWindowRef: Ref[Int],
  private val lastPeerStreamIdRef: Ref[Int],
  private val lastLocalStreamIdRef: Ref[Int],
  private val settingsAckedRef: Ref[Boolean],
  private val goawayStateRef: Ref[GoawayState],
  private val hpackEncoder: HpackEncoder,
  private val hpackDecoder: HpackDecoder,
  private val connectionWindowAvailableSemaphore: Semaphore
)(using private val eruRuntime: EruRuntime) {

  // ============================================================================
  // Connection State
  // ============================================================================

  /** Get peer's settings. */
  def peerSettings: Eru[Nothing, H2Settings] = peerSettingsRef.get

  /** Get the connection-level send window. */
  def connectionSendWindow: Eru[Nothing, Int] = connectionSendWindowRef.get

  /** Get the connection-level receive window. */
  def connectionReceiveWindow: Eru[Nothing, Int] = connectionReceiveWindowRef.get

  /** Check if settings have been acknowledged. */
  def settingsAcked: Eru[Nothing, Boolean] = settingsAckedRef.get

  /** Check if GOAWAY has been sent or received. */
  def isGoingAway: Eru[Nothing, Boolean] = goawayStateRef.get.map(_.isGoingAway)

  /** Check if the connection is still usable for new streams. */
  def canCreateStreams: Eru[Nothing, Boolean] = {
    for {
      goawayState <- goawayStateRef.get
      active <- activeStreamCount
      peerSettings <- peerSettingsRef.get
    } yield !goawayState.isGoingAway && active < peerSettings.maxConcurrentStreams
  }

  // ============================================================================
  // Stream Management
  // ============================================================================

  /** Get a stream by ID.
    *
    * @param streamId
    *   the stream identifier
    * @return
    *   Eru effect with Some(stream) if exists, None otherwise
    */
  def getStream(streamId: Int): Eru[Nothing, Option[H2Stream]] =
    streamsRef.get.map(_.get(streamId))

  /** Get the number of streams (for debugging). */
  def streamCount: Eru[Nothing, Int] = streamsRef.get.map(_.size)

  /** Get the number of active streams (Open, HalfClosedLocal, HalfClosedRemote).
    *
    * @return
    *   Eru effect with the count of active streams
    */
  def activeStreamCount: Eru[Nothing, Int] = {
    streamsRef.get.flatMap { streams =>
      // Collect states from all streams and count active ones
      def countActive(remaining: List[H2Stream], acc: Int): Eru[Nothing, Int] = {
        remaining match {
          case Nil => Eru.succeed(acc)
          case head :: tail =>
            head.state.flatMap { state =>
              countActive(tail, if state.isActive then acc + 1 else acc)
            }
        }
      }
      countActive(streams.values.toList, 0)
    }
  }

  /** Get the next stream ID that will be used for a locally-initiated stream.
    *
    * This is useful for the client to know the stream ID before sendRequest completes, allowing
    * concurrent frame reading (for WINDOW_UPDATE processing) during large uploads.
    *
    * @return
    *   Eru effect with the next stream ID
    */
  def nextStreamId: Eru[Nothing, Int] = {
    lastLocalStreamIdRef.get.map { lastLocalId =>
      if isClient && lastLocalId == -1 then 1 else lastLocalId + 2
    }
  }

  /** Create a new locally-initiated stream.
    *
    * Client streams use odd IDs (1, 3, 5, ...). Server streams use even IDs (2, 4, 6, ...).
    *
    * @return
    *   Eru effect with the new stream or error
    */
  def createStream(): Eru[H2Error, H2Stream] = {
    for {
      canCreate <- canCreateStreams
      _ <-
        if !canCreate then
          Eru.fail(
            H2Error.ConnectionError(
              H2ErrorCode.RefusedStream,
              Some("Max concurrent streams exceeded or connection going away")
            )
          )
        else Eru.unit

      peerSettings <- peerSettingsRef.get
      lastLocalId <- lastLocalStreamIdRef.get

      nextId = if isClient && lastLocalId == -1 then 1 else lastLocalId + 2

      stream <- H2Stream(nextId, peerSettings.initialWindowSize)(using eruRuntime)

      _ <- lastLocalStreamIdRef.set(nextId)
      _ <- streamsRef.update(streams => streams + (nextId -> stream))
    } yield stream
  }

  /** Register a peer-initiated stream.
    *
    * @param streamId
    *   the stream ID from the peer
    * @return
    *   Eru effect with the new stream or error
    */
  def registerPeerStream(streamId: Int): Eru[H2Error, H2Stream] = {
    // Validate stream ID
    val isOdd = streamId % 2 == 1
    val expectedOdd = !isClient // Server expects odd (client-initiated), client expects even (server-initiated)

    if isOdd != expectedOdd then {
      Eru.fail(
        H2Error.ProtocolViolation(
          s"Stream $streamId has wrong parity for peer-initiated stream",
          H2ErrorCode.ProtocolError
        )
      )
    } else {
      for {
        lastPeerId <- lastPeerStreamIdRef.get
        _ <-
          if streamId <= lastPeerId then
            Eru.fail(
              H2Error.ProtocolViolation(
                s"Stream $streamId must be greater than last peer stream ID $lastPeerId",
                H2ErrorCode.ProtocolError
              )
            )
          else Eru.unit

        goawayState <- goawayStateRef.get
        _ <-
          if streamId > goawayState.lastStreamId then
            Eru.fail(
              H2Error.ProtocolViolation(
                s"Stream $streamId exceeds GOAWAY last stream ID ${goawayState.lastStreamId}",
                H2ErrorCode.ProtocolError
              )
            )
          else Eru.unit

        // Check if peer has exceeded our advertised max concurrent streams (RFC 9113 Section 5.1.2)
        active <- activeStreamCount
        _ <-
          if active >= localSettings.maxConcurrentStreams then
            Eru.fail(
              H2Error.StreamError(
                streamId,
                H2ErrorCode.RefusedStream,
                Some(s"Max concurrent streams (${localSettings.maxConcurrentStreams}) exceeded")
              )
            )
          else Eru.unit

        peerSettings <- peerSettingsRef.get
        stream <- H2Stream(streamId, peerSettings.initialWindowSize)(using eruRuntime)

        _ <- lastPeerStreamIdRef.set(streamId)
        _ <- streamsRef.update(streams => streams + (streamId -> stream))
      } yield stream
    }
  }

  /** Get or create a stream by ID.
    *
    * @param streamId
    *   the stream identifier
    * @return
    *   Eru effect with the stream or error
    */
  def getOrCreateStream(streamId: Int): Eru[H2Error, H2Stream] = {
    streamsRef.get.flatMap { streams =>
      streams.get(streamId) match {
        case Some(stream) => Eru.succeed(stream)
        case None =>
          // Determine if this is a local or peer stream
          val isOdd = streamId % 2 == 1
          val isLocalStream = if isClient then isOdd else !isOdd

          if isLocalStream then {
            // Should have been created locally first
            Eru.fail(
              H2Error.ProtocolViolation(
                s"Unknown locally-initiated stream $streamId",
                H2ErrorCode.ProtocolError
              )
            )
          } else {
            registerPeerStream(streamId)
          }
      }
    }
  }

  /** Close a stream and clean up resources.
    *
    * @param streamId
    *   the stream to close
    */
  def closeStream(streamId: Int): Eru[Nothing, Unit] = {
    streamsRef.get.flatMap { streams =>
      streams.get(streamId) match {
        case Some(stream) => stream.reset().attempt.map(_ => ())
        case None => Eru.unit
      }
    }
  }

  // ============================================================================
  // Settings
  // ============================================================================

  /** Apply peer settings from a SETTINGS frame.
    *
    * @param entries
    *   the settings entries
    * @return
    *   Eru effect that succeeds if valid
    */
  // Debug flag - enable with -Dh2.debug=true
  private val h2Debug = java.lang.Boolean.getBoolean("h2.debug")
  private def h2log(msg: String): Unit = if h2Debug then println(s"[H2] $msg")

  def applyPeerSettings(entries: List[SettingsEntry]): Eru[H2Error, Unit] = {
    for {
      settings <- peerSettingsRef.get
      oldInitialWindowSize = settings.initialWindowSize
      _ = h2log(s"applyPeerSettings: entries=$entries, oldInitialWindowSize=$oldInitialWindowSize")

      // Apply settings - H2Settings is internally mutable, so we apply directly
      // The Ref ensures only one fiber accesses this at a time via CAS semantics
      _ <- settings.apply(entries)

      // Adjust stream windows if initial window size changed
      newInitialWindowSize = settings.initialWindowSize
      delta = newInitialWindowSize - oldInitialWindowSize
      _ = h2log(s"applyPeerSettings: newInitialWindowSize=$newInitialWindowSize, delta=$delta")
      _ <-
        if delta != 0 then {
          streamsRef.get.flatMap { streams =>
            h2log(s"applyPeerSettings: adjusting ${streams.size} streams by delta=$delta")
            // Apply delta to each active stream
            Eru.foreach(streams.values.toList) { stream =>
              stream.state.flatMap { state =>
                h2log(s"  stream ${stream.streamId}: state=$state, isActive=${state.isActive}")
                if state.isActive then {
                  stream.sendWindow.flatMap { oldWindow =>
                    stream.replenishSendWindow(delta).attempt.flatMap {
                      case Result.Success(newWindow) =>
                        h2log(s"  stream ${stream.streamId}: window $oldWindow -> $newWindow")
                        Eru.succeed(())
                      case Result.Failure(err) =>
                        h2log(s"  stream ${stream.streamId}: replenish failed: $err")
                        Eru.succeed(())
                    }
                  }
                } else {
                  Eru.unit
                }
              }
            }
          }
        } else Eru.unit
    } yield ()
  }

  /** Mark that our SETTINGS have been acknowledged. */
  def acknowledgeSettings(): Eru[Nothing, Unit] = settingsAckedRef.set(true)

  // ============================================================================
  // Flow Control
  // ============================================================================

  /** Consume from the connection-level send window.
    *
    * @param size
    *   number of bytes to consume
    * @return
    *   Eru effect that succeeds if window has capacity
    */
  def consumeConnectionSendWindow(size: Int): Eru[H2Error, Int] = {
    connectionSendWindowRef.modify { current =>
      if size <= current then {
        val newWindow = current - size
        (newWindow, Right(newWindow))
      } else {
        (current, Left(H2Error.FlowControlViolation(0, s"Connection send window exhausted: need $size, have $current")))
      }
    }.flatMap {
      case Right(newWindow) => Eru.succeed(newWindow)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Replenish the connection-level send window (from WINDOW_UPDATE).
    *
    * When the window transitions from ≤0 to >0, releases the connectionWindowAvailableSemaphore to
    * wake up any waiting senders.
    *
    * @param increment
    *   window size increment
    * @return
    *   Eru effect that succeeds with new window size
    */
  def replenishConnectionSendWindow(increment: Int): Eru[H2Error, Int] = {
    connectionSendWindowRef.modify { current =>
      val newWindow = current.toLong + increment
      if newWindow > Int.MaxValue then {
        (current, Left((current, H2Error.FlowControlViolation(0, "Connection flow control window overflow"))))
      } else {
        // Return both old and new window values to detect transition
        (newWindow.toInt, Right((current, newWindow.toInt)))
      }
    }.flatMap {
      case Right((oldWindow, newWindow)) =>
        // Signal waiters when window becomes available (transitions from ≤0 to >0)
        val shouldSignal = oldWindow <= 0 && newWindow > 0
        if shouldSignal then {
          connectionWindowAvailableSemaphore.release.eru.map(_ => newWindow)
        } else {
          Eru.succeed(newWindow)
        }
      case Left((_, error)) => Eru.fail(error)
    }
  }

  /** Wait until the connection-level send window becomes available (>0).
    *
    * This suspends the current fiber until replenishConnectionSendWindow signals that the window
    * has transitioned from ≤0 to >0.
    *
    * @return
    *   Suspending effect that completes when window is available
    */
  def waitForConnectionWindowAvailable: Suspending[Nothing, Unit] = {
    connectionWindowAvailableSemaphore.acquire
  }

  /** Consume from the connection-level receive window.
    *
    * @param size
    *   number of bytes received
    * @return
    *   Eru effect that succeeds with remaining window
    */
  def consumeConnectionReceiveWindow(size: Int): Eru[H2Error, Int] = {
    connectionReceiveWindowRef.modify { current =>
      if size <= current then {
        val newWindow = current - size
        (newWindow, Right(newWindow))
      } else {
        (current, Left(H2Error.FlowControlViolation(0, s"Peer sent $size bytes but connection window is $current")))
      }
    }.flatMap {
      case Right(newWindow) => Eru.succeed(newWindow)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Replenish the connection-level receive window (before sending WINDOW_UPDATE).
    *
    * @param increment
    *   window size increment
    * @return
    *   Eru effect that succeeds with new window size
    */
  def replenishConnectionReceiveWindow(increment: Int): Eru[H2Error, Int] = {
    connectionReceiveWindowRef.modify { current =>
      val newWindow = current.toLong + increment
      if newWindow > Int.MaxValue then {
        (current, Left(H2Error.FlowControlViolation(0, "Connection flow control window overflow")))
      } else {
        (newWindow.toInt, Right(newWindow.toInt))
      }
    }.flatMap {
      case Right(newWindow) => Eru.succeed(newWindow)
      case Left(error) => Eru.fail(error)
    }
  }

  // ============================================================================
  // GOAWAY
  // ============================================================================

  /** Mark connection as going away (sending GOAWAY).
    *
    * @return
    *   Eru effect with the last stream ID to include in GOAWAY
    */
  def initiateGoaway(): Eru[Nothing, Int] = {
    for {
      lastPeerId <- lastPeerStreamIdRef.get
      _ <- goawayStateRef.update(_.copy(sent = true))
    } yield lastPeerId
  }

  /** Get the error code from the received GOAWAY. */
  def goawayErrorCode: Eru[Nothing, H2ErrorCode] = goawayStateRef.get.map(_.errorCode)

  /** Handle received GOAWAY frame.
    *
    * @param lastStreamId
    *   the last stream ID the peer will process
    * @param errorCode
    *   the error code
    */
  def receiveGoaway(lastStreamId: Int, errorCode: H2ErrorCode): Eru[Nothing, Unit] = {
    goawayStateRef.update(_.copy(received = true, lastStreamId = lastStreamId, errorCode = errorCode)).map(_ => ())
  }

  // ============================================================================
  // HPACK
  // ============================================================================

  /** Encode headers using HPACK.
    *
    * @param headers
    *   the headers to encode
    * @param buffer
    *   the buffer to write to
    * @param sensitive
    *   header names that should never be indexed
    * @return
    *   Eru effect with bytes written
    */
  def encodeHeaders(
    headers: List[(String, String)],
    buffer: java.nio.ByteBuffer,
    sensitive: Set[String] = Set.empty
  ): Eru[HpackError, Int] = {
    hpackEncoder.encode(headers, buffer, sensitive)
  }

  /** Decode headers using HPACK.
    *
    * @param buffer
    *   the buffer containing encoded headers
    * @return
    *   Eru effect with decoded headers
    */
  def decodeHeaders(buffer: java.nio.ByteBuffer): Eru[HpackError, List[(String, String, Boolean)]] = {
    hpackDecoder.decode(buffer)
  }

  /** Get a snapshot of connection state for debugging. */
  def snapshot: Eru[Nothing, H2ConnectionSnapshot] = {
    for {
      streams <- streamsRef.get
      sendWindow <- connectionSendWindowRef.get
      recvWindow <- connectionReceiveWindowRef.get
      goaway <- goawayStateRef.get
    } yield H2ConnectionSnapshot(
      isClient = isClient,
      streamCount = streams.size,
      sendWindow = sendWindow,
      receiveWindow = recvWindow,
      isGoingAway = goaway.isGoingAway
    )
  }

  override def toString: String = s"H2Connection(isClient=$isClient)"
}

/** GOAWAY state tracking. */
private[h2] case class GoawayState(
  sent: Boolean = false,
  received: Boolean = false,
  lastStreamId: Int = Int.MaxValue,
  errorCode: H2ErrorCode = H2ErrorCode.NoError
) {
  def isGoingAway: Boolean = sent || received
}

/** Immutable snapshot of connection state for debugging. */
case class H2ConnectionSnapshot(
  isClient: Boolean,
  streamCount: Int,
  sendWindow: Int,
  receiveWindow: Int,
  isGoingAway: Boolean
) {
  override def toString: String =
    s"H2Connection(isClient=$isClient, streams=$streamCount, " +
      s"sendWindow=$sendWindow, recvWindow=$receiveWindow, goaway=$isGoingAway)"
}

object H2Connection {

  /** Create a client connection.
    *
    * @param localSettings
    *   settings to send to server
    * @return
    *   Eru effect with a new client connection
    */
  def client(localSettings: H2Settings = H2Settings.default)(using EruRuntime): Eru[Nothing, H2Connection] = {
    for {
      peerSettingsRef <- Ref.make(H2Settings.default)
      streamsRef <- Ref.make(Map.empty[Int, H2Stream])
      connectionSendWindowRef <- Ref.make(H2Frame.DefaultSettings.InitialWindowSize)
      connectionReceiveWindowRef <- Ref.make(localSettings.initialWindowSize)
      lastPeerStreamIdRef <- Ref.make(0)
      lastLocalStreamIdRef <- Ref.make(-1) // Client starts at 1 (odd)
      settingsAckedRef <- Ref.make(false)
      goawayStateRef <- Ref.make(GoawayState())
      connectionWindowSemaphore <- Semaphore.make(0) // Start with 0 permits, signal when window available
    } yield new H2Connection(
      isClient = true,
      localSettings = localSettings,
      peerSettingsRef = peerSettingsRef,
      streamsRef = streamsRef,
      connectionSendWindowRef = connectionSendWindowRef,
      connectionReceiveWindowRef = connectionReceiveWindowRef,
      lastPeerStreamIdRef = lastPeerStreamIdRef,
      lastLocalStreamIdRef = lastLocalStreamIdRef,
      settingsAckedRef = settingsAckedRef,
      goawayStateRef = goawayStateRef,
      hpackEncoder = HpackEncoder(),
      hpackDecoder = HpackDecoder(),
      connectionWindowAvailableSemaphore = connectionWindowSemaphore
    )
  }

  /** Create a server connection.
    *
    * @param localSettings
    *   settings to send to client
    * @return
    *   Eru effect with a new server connection
    */
  def server(localSettings: H2Settings = H2Settings.default)(using EruRuntime): Eru[Nothing, H2Connection] = {
    for {
      peerSettingsRef <- Ref.make(H2Settings.default)
      streamsRef <- Ref.make(Map.empty[Int, H2Stream])
      connectionSendWindowRef <- Ref.make(H2Frame.DefaultSettings.InitialWindowSize)
      connectionReceiveWindowRef <- Ref.make(localSettings.initialWindowSize)
      lastPeerStreamIdRef <- Ref.make(0)
      lastLocalStreamIdRef <- Ref.make(0) // Server starts at 2 (even)
      settingsAckedRef <- Ref.make(false)
      goawayStateRef <- Ref.make(GoawayState())
      connectionWindowSemaphore <- Semaphore.make(0) // Start with 0 permits, signal when window available
    } yield new H2Connection(
      isClient = false,
      localSettings = localSettings,
      peerSettingsRef = peerSettingsRef,
      streamsRef = streamsRef,
      connectionSendWindowRef = connectionSendWindowRef,
      connectionReceiveWindowRef = connectionReceiveWindowRef,
      lastPeerStreamIdRef = lastPeerStreamIdRef,
      lastLocalStreamIdRef = lastLocalStreamIdRef,
      settingsAckedRef = settingsAckedRef,
      goawayStateRef = goawayStateRef,
      hpackEncoder = HpackEncoder(),
      hpackDecoder = HpackDecoder(),
      connectionWindowAvailableSemaphore = connectionWindowSemaphore
    )
  }
}
