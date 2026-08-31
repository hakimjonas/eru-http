package net.ghoula.eru.http.h2

import munit.FunSuite

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 stream state machine per RFC 9113 Section 5.1. */
class H2StreamSpec extends FunSuite {

  // Runtime needed for H2Stream creation (Semaphore)
  given EruRuntime = EruRuntime.shared

  // ============================================================================
  // Idle State Transitions
  // ============================================================================

  test("Stream starts in idle state") {
    val stream = H2Stream(1).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Idle)
  }

  test("Idle -> Open on sending HEADERS without END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Open)
  }

  test("Idle -> HalfClosedLocal on sending HEADERS with END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedLocal)
  }

  test("Idle -> Open on receiving HEADERS without END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.receiveHeaders(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Open)
  }

  test("Idle -> HalfClosedRemote on receiving HEADERS with END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.receiveHeaders(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedRemote)
  }

  // ============================================================================
  // Reserved (Local) State Transitions
  // ============================================================================

  test("ReservedLocal -> HalfClosedRemote on sending HEADERS without END_STREAM") {
    val stream = H2Stream.reservedLocal(2).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.ReservedLocal)
    stream.sendHeaders(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedRemote)
  }

  test("ReservedLocal -> Closed on sending HEADERS with END_STREAM") {
    val stream = H2Stream.reservedLocal(2).assertSuccess
    stream.sendHeaders(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  test("ReservedLocal -> Closed on reset") {
    val stream = H2Stream.reservedLocal(2).assertSuccess
    stream.reset().assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  // ============================================================================
  // Reserved (Remote) State Transitions
  // ============================================================================

  test("ReservedRemote -> HalfClosedLocal on receiving HEADERS without END_STREAM") {
    val stream = H2Stream.reservedRemote(2).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.ReservedRemote)
    stream.receiveHeaders(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedLocal)
  }

  test("ReservedRemote -> Closed on receiving HEADERS with END_STREAM") {
    val stream = H2Stream.reservedRemote(2).assertSuccess
    stream.receiveHeaders(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  test("ReservedRemote -> Closed on reset") {
    val stream = H2Stream.reservedRemote(2).assertSuccess
    stream.reset().assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  // ============================================================================
  // Open State Transitions
  // ============================================================================

  test("Open -> HalfClosedLocal on sending DATA with END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Open)

    stream.sendData(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedLocal)
  }

  test("Open -> HalfClosedRemote on receiving DATA with END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.receiveHeaders(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Open)

    stream.receiveData(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedRemote)
  }

  test("Open stays Open on sending DATA without END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess
    stream.sendData(endStream = false).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Open)
  }

  test("Open -> Closed on reset") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess
    stream.reset().assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  // ============================================================================
  // Half-Closed (Local) State Transitions
  // ============================================================================

  test("HalfClosedLocal -> Closed on receiving DATA with END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = true).assertSuccess // Now in HalfClosedLocal
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedLocal)

    stream.receiveData(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  test("Cannot send DATA in HalfClosedLocal state") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = true).assertSuccess // Now in HalfClosedLocal

    val result = stream.sendData(endStream = false)
    assert(result.isFailure)
  }

  // ============================================================================
  // Half-Closed (Remote) State Transitions
  // ============================================================================

  test("HalfClosedRemote -> Closed on sending DATA with END_STREAM") {
    val stream = H2Stream(1).assertSuccess
    stream.receiveHeaders(endStream = true).assertSuccess // Now in HalfClosedRemote
    assertEquals(stream.state.assertSuccess, H2StreamState.HalfClosedRemote)

    stream.sendData(endStream = true).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  test("Cannot receive DATA in HalfClosedRemote state") {
    val stream = H2Stream(1).assertSuccess
    stream.receiveHeaders(endStream = true).assertSuccess // Now in HalfClosedRemote

    val result = stream.receiveData(endStream = false)
    assert(result.isFailure)
  }

  // ============================================================================
  // Closed State Validation
  // ============================================================================

  test("Cannot send DATA in Closed state") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = true).assertSuccess
    stream.receiveHeaders(endStream = true).assertSuccess // Now Closed
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)

    val result = stream.sendData(endStream = false)
    assert(result.isFailure)
  }

  test("Cannot receive DATA in Closed state") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = true).assertSuccess
    stream.receiveHeaders(endStream = true).assertSuccess // Now Closed

    val result = stream.receiveData(endStream = false)
    assert(result.isFailure)
  }

  // ============================================================================
  // Flow Control Tests
  // ============================================================================

  test("Stream has default initial window size") {
    val stream = H2Stream(1).assertSuccess
    assertEquals(stream.sendWindow.assertSuccess, 65535)
    assertEquals(stream.receiveWindow.assertSuccess, 65535)
  }

  test("Custom initial window size") {
    val stream = H2Stream(1, initialWindowSize = 32768).assertSuccess
    assertEquals(stream.sendWindow.assertSuccess, 32768)
    assertEquals(stream.receiveWindow.assertSuccess, 32768)
  }

  test("Consume send window") {
    val stream = H2Stream(1).assertSuccess
    stream.consumeSendWindow(1000).assertSuccess
    assertEquals(stream.sendWindow.assertSuccess, 65535 - 1000)
  }

  test("Consume send window fails when exhausted") {
    val stream = H2Stream(1, initialWindowSize = 100).assertSuccess
    val result = stream.consumeSendWindow(200)
    assert(result.isFailure)
  }

  test("Replenish send window") {
    val stream = H2Stream(1).assertSuccess
    stream.consumeSendWindow(1000).assertSuccess
    stream.replenishSendWindow(500).assertSuccess
    assertEquals(stream.sendWindow.assertSuccess, 65535 - 1000 + 500)
  }

  test("Send window overflow fails") {
    val stream = H2Stream(1).assertSuccess
    val result = stream.replenishSendWindow(Int.MaxValue)
    assert(result.isFailure)
  }

  test("Consume receive window") {
    val stream = H2Stream(1).assertSuccess
    stream.consumeReceiveWindow(1000).assertSuccess
    assertEquals(stream.receiveWindow.assertSuccess, 65535 - 1000)
  }

  test("Consume receive window fails on flow control violation") {
    val stream = H2Stream(1, initialWindowSize = 100).assertSuccess
    val result = stream.consumeReceiveWindow(200)
    assert(result.isFailure)
  }

  test("Replenish receive window") {
    val stream = H2Stream(1).assertSuccess
    stream.consumeReceiveWindow(1000).assertSuccess
    stream.replenishReceiveWindow(500).assertSuccess
    assertEquals(stream.receiveWindow.assertSuccess, 65535 - 1000 + 500)
  }

  // ============================================================================
  // Frame Validation Tests
  // ============================================================================

  test("canSend validates DATA frame") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess

    stream.canSend(H2Frame.FrameType.Data).assertSuccess // Open state allows DATA
  }

  test("canSend rejects DATA frame in Idle state") {
    val stream = H2Stream(1).assertSuccess
    val result = stream.canSend(H2Frame.FrameType.Data)
    assert(result.isFailure)
  }

  test("canReceive validates HEADERS frame") {
    val stream = H2Stream(1).assertSuccess
    stream.canReceive(H2Frame.FrameType.Headers).assertSuccess // Idle state allows receiving HEADERS
  }

  test("RST_STREAM can always be sent") {
    val stream = H2Stream(1).assertSuccess
    stream.canSend(H2Frame.FrameType.RstStream).assertSuccess // Even in Idle state
  }

  test("WINDOW_UPDATE can be sent on non-closed streams") {
    val stream = H2Stream(1).assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess
    stream.canSend(H2Frame.FrameType.WindowUpdate).assertSuccess
  }

  // ============================================================================
  // Stream State Helper Tests
  // ============================================================================

  test("H2StreamState.canSendData") {
    assert(H2StreamState.Open.canSendData)
    assert(H2StreamState.HalfClosedRemote.canSendData)
    assert(!H2StreamState.HalfClosedLocal.canSendData)
    assert(!H2StreamState.Closed.canSendData)
    assert(!H2StreamState.Idle.canSendData)
  }

  test("H2StreamState.canReceiveData") {
    assert(H2StreamState.Open.canReceiveData)
    assert(H2StreamState.HalfClosedLocal.canReceiveData)
    assert(!H2StreamState.HalfClosedRemote.canReceiveData)
    assert(!H2StreamState.Closed.canReceiveData)
    assert(!H2StreamState.Idle.canReceiveData)
  }

  test("H2StreamState.isActive") {
    assert(H2StreamState.Open.isActive)
    assert(H2StreamState.HalfClosedLocal.isActive)
    assert(H2StreamState.HalfClosedRemote.isActive)
    assert(!H2StreamState.Idle.isActive)
    assert(!H2StreamState.Closed.isActive)
    assert(!H2StreamState.ReservedLocal.isActive)
  }

  test("H2StreamState.isClosed") {
    assert(H2StreamState.Closed.isClosed)
    assert(!H2StreamState.Open.isClosed)
    assert(!H2StreamState.HalfClosedLocal.isClosed)
  }

  test("H2StreamState.isReserved") {
    assert(H2StreamState.ReservedLocal.isReserved)
    assert(H2StreamState.ReservedRemote.isReserved)
    assert(!H2StreamState.Open.isReserved)
    assert(!H2StreamState.Closed.isReserved)
  }
}
