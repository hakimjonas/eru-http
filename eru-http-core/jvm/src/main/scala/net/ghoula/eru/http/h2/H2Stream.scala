package net.ghoula.eru.http.h2

import net.ghoula.eru.*

/** HTTP/2 stream state machine per RFC 9113 Section 5.1.
  *
  * Manages stream state transitions and validates frame operations using Eru's effect-based state
  * management via Ref for thread-safe, fiber-safe concurrent access.
  *
  * @param streamId
  *   the stream identifier (odd for client-initiated, even for server-initiated)
  * @param stateRef
  *   atomic reference to current stream state
  * @param sendWindowRef
  *   atomic reference to send flow control window
  * @param receiveWindowRef
  *   atomic reference to receive flow control window
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-5.1 RFC 9113 Section 5.1]]
  */
final class H2Stream private (
  val streamId: Int,
  private val stateRef: Ref[H2StreamState],
  private val sendWindowRef: Ref[Int],
  private val receiveWindowRef: Ref[Int],
  private val windowAvailableSemaphore: Semaphore
) {

  /** Current stream state. */
  def state: Eru[Nothing, H2StreamState] = stateRef.get

  /** Current send window size. */
  def sendWindow: Eru[Nothing, Int] = sendWindowRef.get

  /** Current receive window size. */
  def receiveWindow: Eru[Nothing, Int] = receiveWindowRef.get

  // ============================================================================
  // State Transitions
  // ============================================================================

  /** Apply a state transition for sending HEADERS.
    *
    * @param endStream
    *   true if END_STREAM flag is set
    * @return
    *   Eru effect that succeeds with the new state or fails with H2Error
    */
  def sendHeaders(endStream: Boolean): Eru[H2Error, H2StreamState] = {
    stateRef.modify { currentState =>
      currentState match {
        case H2StreamState.Idle =>
          val newState = if endStream then H2StreamState.HalfClosedLocal else H2StreamState.Open
          (newState, Right(newState))

        case H2StreamState.ReservedLocal =>
          val newState = if endStream then H2StreamState.Closed else H2StreamState.HalfClosedRemote
          (newState, Right(newState))

        case H2StreamState.Open if endStream =>
          // Sending trailing headers with END_STREAM
          (H2StreamState.HalfClosedLocal, Right(H2StreamState.HalfClosedLocal))

        case H2StreamState.HalfClosedRemote =>
          // Server sending response headers (with or without body)
          val newState = if endStream then H2StreamState.Closed else H2StreamState.HalfClosedRemote
          (newState, Right(newState))

        case other =>
          (other, Left(H2Error.StreamStateViolation(streamId, s"Cannot send HEADERS in state $other")))
      }
    }.flatMap {
      case Right(newState) => Eru.succeed(newState)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Apply a state transition for receiving HEADERS.
    *
    * @param endStream
    *   true if END_STREAM flag is set
    * @return
    *   Eru effect that succeeds with the new state or fails with H2Error
    */
  def receiveHeaders(endStream: Boolean): Eru[H2Error, H2StreamState] = {
    stateRef.modify { currentState =>
      currentState match {
        case H2StreamState.Idle =>
          val newState = if endStream then H2StreamState.HalfClosedRemote else H2StreamState.Open
          (newState, Right(newState))

        case H2StreamState.ReservedRemote =>
          val newState = if endStream then H2StreamState.Closed else H2StreamState.HalfClosedLocal
          (newState, Right(newState))

        case H2StreamState.Open =>
          // Receiving headers (possibly trailing) with optional END_STREAM
          val newState = if endStream then H2StreamState.HalfClosedRemote else H2StreamState.Open
          (newState, Right(newState))

        case H2StreamState.HalfClosedLocal =>
          // Receiving response headers (client sent END_STREAM, server responds)
          val newState = if endStream then H2StreamState.Closed else H2StreamState.HalfClosedLocal
          (newState, Right(newState))

        case other =>
          (other, Left(H2Error.StreamStateViolation(streamId, s"Cannot receive HEADERS in state $other")))
      }
    }.flatMap {
      case Right(newState) => Eru.succeed(newState)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Apply a state transition for sending DATA.
    *
    * @param endStream
    *   true if END_STREAM flag is set
    * @return
    *   Eru effect that succeeds with the new state or fails with H2Error
    */
  def sendData(endStream: Boolean): Eru[H2Error, H2StreamState] = {
    stateRef.modify { currentState =>
      currentState match {
        case H2StreamState.Open =>
          if endStream then {
            (H2StreamState.HalfClosedLocal, Right(H2StreamState.HalfClosedLocal))
          } else {
            (H2StreamState.Open, Right(H2StreamState.Open))
          }

        case H2StreamState.HalfClosedRemote =>
          if endStream then {
            (H2StreamState.Closed, Right(H2StreamState.Closed))
          } else {
            (H2StreamState.HalfClosedRemote, Right(H2StreamState.HalfClosedRemote))
          }

        case other =>
          (other, Left(H2Error.StreamStateViolation(streamId, s"Cannot send DATA in state $other")))
      }
    }.flatMap {
      case Right(newState) => Eru.succeed(newState)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Apply a state transition for receiving DATA.
    *
    * @param endStream
    *   true if END_STREAM flag is set
    * @return
    *   Eru effect that succeeds with the new state or fails with H2Error
    */
  def receiveData(endStream: Boolean): Eru[H2Error, H2StreamState] = {
    stateRef.modify { currentState =>
      currentState match {
        case H2StreamState.Open =>
          if endStream then {
            (H2StreamState.HalfClosedRemote, Right(H2StreamState.HalfClosedRemote))
          } else {
            (H2StreamState.Open, Right(H2StreamState.Open))
          }

        case H2StreamState.HalfClosedLocal =>
          if endStream then {
            (H2StreamState.Closed, Right(H2StreamState.Closed))
          } else {
            (H2StreamState.HalfClosedLocal, Right(H2StreamState.HalfClosedLocal))
          }

        case other =>
          (other, Left(H2Error.StreamStateViolation(streamId, s"Cannot receive DATA in state $other")))
      }
    }.flatMap {
      case Right(newState) => Eru.succeed(newState)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Apply a state transition for sending PUSH_PROMISE.
    *
    * PUSH_PROMISE reserves a new stream. This is called on the CURRENT stream to validate it's in a
    * state that allows sending PUSH_PROMISE.
    *
    * @return
    *   Eru effect that succeeds if allowed or fails with H2Error
    */
  def sendPushPromise(): Eru[H2Error, Unit] = {
    stateRef.get.flatMap { currentState =>
      if currentState.isActive then {
        Eru.unit
      } else {
        Eru.fail(H2Error.StreamStateViolation(streamId, s"Cannot send PUSH_PROMISE on stream in state $currentState"))
      }
    }
  }

  /** Apply a state transition for receiving PUSH_PROMISE.
    *
    * @return
    *   Eru effect that succeeds if allowed or fails with H2Error
    */
  def receivePushPromise(): Eru[H2Error, Unit] = {
    stateRef.get.flatMap { currentState =>
      if currentState.isActive then {
        Eru.unit
      } else {
        Eru.fail(
          H2Error.StreamStateViolation(streamId, s"Cannot receive PUSH_PROMISE on stream in state $currentState")
        )
      }
    }
  }

  /** Apply a state transition for sending or receiving RST_STREAM.
    *
    * @return
    *   Eru effect that always succeeds (RST_STREAM can be sent/received in any state)
    */
  def reset(): Eru[H2Error, H2StreamState] = {
    stateRef.set(H2StreamState.Closed).map(_ => H2StreamState.Closed)
  }

  // ============================================================================
  // Flow Control
  // ============================================================================

  /** Consume from the send window (when sending DATA).
    *
    * @param size
    *   number of bytes to consume
    * @return
    *   Eru effect that succeeds if window has capacity or fails with H2Error
    */
  def consumeSendWindow(size: Int): Eru[H2Error, Int] = {
    sendWindowRef.modify { current =>
      if size <= current then {
        val newWindow = current - size
        (newWindow, Right(newWindow))
      } else {
        (current, Left(H2Error.FlowControlViolation(streamId, s"Send window exhausted: need $size, have $current")))
      }
    }.flatMap {
      case Right(newWindow) => Eru.succeed(newWindow)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Replenish the send window (when receiving WINDOW_UPDATE or SETTINGS change).
    *
    * When the window transitions from ≤0 to >0, releases the windowAvailableSemaphore to wake up
    * any waiting senders.
    *
    * @param increment
    *   window size increment (can be negative for SETTINGS changes)
    * @return
    *   Eru effect that succeeds with new window size or fails if overflow
    */
  def replenishSendWindow(increment: Int): Eru[H2Error, Int] = {
    sendWindowRef.modify { current =>
      val newWindow = current.toLong + increment
      if newWindow > Int.MaxValue then {
        (
          current,
          Left(
            (current, H2Error.FlowControlViolation(streamId, s"Flow control window overflow: $current + $increment"))
          )
        )
      } else {
        // Return both old and new window values to detect transition
        (newWindow.toInt, Right((current, newWindow.toInt)))
      }
    }.flatMap {
      case Right((oldWindow, newWindow)) =>
        // Signal waiters when window becomes available (transitions from ≤0 to >0)
        val shouldSignal = oldWindow <= 0 && newWindow > 0
        if shouldSignal then {
          windowAvailableSemaphore.release.eru.map(_ => newWindow)
        } else {
          Eru.succeed(newWindow)
        }
      case Left((_, error)) => Eru.fail(error)
    }
  }

  /** Wait until the send window becomes available (>0).
    *
    * This suspends the current fiber until replenishSendWindow signals that the window has
    * transitioned from ≤0 to >0.
    *
    * @return
    *   Suspending effect that completes when window is available
    */
  def waitForWindowAvailable: Suspending[Nothing, Unit] = {
    windowAvailableSemaphore.acquire
  }

  /** Consume from the receive window (when receiving DATA).
    *
    * @param size
    *   number of bytes received
    * @return
    *   Eru effect that succeeds with remaining window or fails if sender violated flow control
    */
  def consumeReceiveWindow(size: Int): Eru[H2Error, Int] = {
    receiveWindowRef.modify { current =>
      if size <= current then {
        val newWindow = current - size
        (newWindow, Right(newWindow))
      } else {
        // Sender violated flow control
        (current, Left(H2Error.FlowControlViolation(streamId, s"Received $size bytes but window is $current")))
      }
    }.flatMap {
      case Right(newWindow) => Eru.succeed(newWindow)
      case Left(error) => Eru.fail(error)
    }
  }

  /** Replenish the receive window (before sending WINDOW_UPDATE).
    *
    * @param increment
    *   window size increment
    * @return
    *   Eru effect that succeeds with new window size or fails if overflow
    */
  def replenishReceiveWindow(increment: Int): Eru[H2Error, Int] = {
    receiveWindowRef.modify { current =>
      val newWindow = current.toLong + increment
      if newWindow > Int.MaxValue then {
        (current, Left(H2Error.FlowControlViolation(streamId, s"Flow control window overflow: $current + $increment")))
      } else {
        (newWindow.toInt, Right(newWindow.toInt))
      }
    }.flatMap {
      case Right(newWindow) => Eru.succeed(newWindow)
      case Left(error) => Eru.fail(error)
    }
  }

  // ============================================================================
  // Validation
  // ============================================================================

  /** Check if a frame can be sent in the current state.
    *
    * @param frameType
    *   the frame type to send
    * @return
    *   Eru effect that succeeds if allowed or fails with H2Error
    */
  def canSend(frameType: Byte): Eru[H2Error, Unit] = {
    stateRef.get.flatMap { currentState =>
      val allowed = frameType match {
        case H2Frame.FrameType.Data =>
          currentState.canSendData
        case H2Frame.FrameType.Headers =>
          currentState.canSendHeaders
        case H2Frame.FrameType.RstStream =>
          true // Can always send RST_STREAM
        case H2Frame.FrameType.Priority =>
          true // Can always send PRIORITY (deprecated but allowed)
        case H2Frame.FrameType.WindowUpdate =>
          !currentState.isClosed // Can send on any non-closed stream
        case H2Frame.FrameType.Continuation =>
          currentState.canSendHeaders // Same rules as HEADERS
        case _ =>
          false
      }

      if allowed then {
        Eru.unit
      } else {
        Eru.fail(
          H2Error.StreamStateViolation(
            streamId,
            s"Cannot send ${H2Frame.FrameType.name(frameType)} in state $currentState"
          )
        )
      }
    }
  }

  /** Check if a frame can be received in the current state.
    *
    * @param frameType
    *   the frame type to receive
    * @return
    *   Eru effect that succeeds if allowed or fails with H2Error
    */
  def canReceive(frameType: Byte): Eru[H2Error, Unit] = {
    stateRef.get.flatMap { currentState =>
      val allowed = frameType match {
        case H2Frame.FrameType.Data =>
          currentState.canReceiveData
        case H2Frame.FrameType.Headers =>
          currentState.canReceiveHeaders
        case H2Frame.FrameType.RstStream =>
          true // Can always receive RST_STREAM
        case H2Frame.FrameType.Priority =>
          true // Can always receive PRIORITY
        case H2Frame.FrameType.WindowUpdate =>
          !currentState.isClosed
        case H2Frame.FrameType.Continuation =>
          currentState.canReceiveHeaders
        case _ =>
          false
      }

      if allowed then {
        Eru.unit
      } else {
        Eru.fail(
          H2Error.StreamStateViolation(
            streamId,
            s"Cannot receive ${H2Frame.FrameType.name(frameType)} in state $currentState"
          )
        )
      }
    }
  }

  /** Get a snapshot of stream state for debugging/logging. */
  def snapshot: Eru[Nothing, H2StreamSnapshot] = {
    for {
      s <- stateRef.get
      sw <- sendWindowRef.get
      rw <- receiveWindowRef.get
    } yield H2StreamSnapshot(streamId, s, sw, rw)
  }

  override def toString: String =
    s"H2Stream(id=$streamId)"
}

/** Immutable snapshot of stream state for debugging. */
case class H2StreamSnapshot(
  streamId: Int,
  state: H2StreamState,
  sendWindow: Int,
  receiveWindow: Int
) {
  override def toString: String =
    s"H2Stream(id=$streamId, state=$state, sendWindow=$sendWindow, recvWindow=$receiveWindow)"
}

object H2Stream {

  /** Default initial window size per RFC 9113 Section 6.9.2. */
  val DefaultInitialWindowSize: Int = 65535

  /** Create a new stream in idle state.
    *
    * @param streamId
    *   the stream identifier
    * @param initialWindowSize
    *   initial flow control window (default 65535)
    * @return
    *   Eru effect with the new H2Stream
    */
  def apply(
    streamId: Int,
    initialWindowSize: Int = DefaultInitialWindowSize
  )(using EruRuntime): Eru[Nothing, H2Stream] = {
    for {
      stateRef <- Ref.make(H2StreamState.Idle)
      sendWindowRef <- Ref.make(initialWindowSize)
      receiveWindowRef <- Ref.make(initialWindowSize)
      // Semaphore starts at 0 - waiters block until window becomes available
      windowSemaphore <- Semaphore.make(0)
    } yield new H2Stream(streamId, stateRef, sendWindowRef, receiveWindowRef, windowSemaphore)
  }

  /** Create a stream reserved by local PUSH_PROMISE. */
  def reservedLocal(streamId: Int, initialWindowSize: Int = DefaultInitialWindowSize)(using
    EruRuntime
  ): Eru[Nothing, H2Stream] = {
    for {
      stateRef <- Ref.make(H2StreamState.ReservedLocal)
      sendWindowRef <- Ref.make(initialWindowSize)
      receiveWindowRef <- Ref.make(initialWindowSize)
      windowSemaphore <- Semaphore.make(0)
    } yield new H2Stream(streamId, stateRef, sendWindowRef, receiveWindowRef, windowSemaphore)
  }

  /** Create a stream reserved by remote PUSH_PROMISE. */
  def reservedRemote(streamId: Int, initialWindowSize: Int = DefaultInitialWindowSize)(using
    EruRuntime
  ): Eru[Nothing, H2Stream] = {
    for {
      stateRef <- Ref.make(H2StreamState.ReservedRemote)
      sendWindowRef <- Ref.make(initialWindowSize)
      receiveWindowRef <- Ref.make(initialWindowSize)
      windowSemaphore <- Semaphore.make(0)
    } yield new H2Stream(streamId, stateRef, sendWindowRef, receiveWindowRef, windowSemaphore)
  }
}
