package net.ghoula.eru.http.h2

import munit.FunSuite

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 connection management per RFC 9113. */
class H2ConnectionSpec extends FunSuite {

  // Runtime needed for H2Connection/H2Stream creation
  given EruRuntime = EruRuntime.shared

  // ============================================================================
  // Connection Creation
  // ============================================================================

  test("Client connection starts with correct state") {
    val conn = H2Connection.client().assertSuccess
    assertEquals(conn.isClient, true)
    assertEquals(conn.activeStreamCount.assertSuccess, 0)
    assertEquals(conn.settingsAcked.assertSuccess, false)
    assertEquals(conn.isGoingAway.assertSuccess, false)
    assert(conn.canCreateStreams.assertSuccess)
  }

  test("Server connection starts with correct state") {
    val conn = H2Connection.server().assertSuccess
    assertEquals(conn.isClient, false)
    assertEquals(conn.activeStreamCount.assertSuccess, 0)
    assertEquals(conn.settingsAcked.assertSuccess, false)
    assertEquals(conn.isGoingAway.assertSuccess, false)
    assert(conn.canCreateStreams.assertSuccess)
  }

  // ============================================================================
  // Stream Creation - Client
  // ============================================================================

  test("Client creates odd-numbered streams") {
    val conn = H2Connection.client().assertSuccess

    val stream1 = conn.createStream().assertSuccess
    assertEquals(stream1.streamId, 1)

    val stream2 = conn.createStream().assertSuccess
    assertEquals(stream2.streamId, 3)

    val stream3 = conn.createStream().assertSuccess
    assertEquals(stream3.streamId, 5)
  }

  test("Client stream starts in idle state") {
    val conn = H2Connection.client().assertSuccess
    val stream = conn.createStream().assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Idle)
  }

  // ============================================================================
  // Stream Creation - Server
  // ============================================================================

  test("Server creates even-numbered streams") {
    val conn = H2Connection.server().assertSuccess

    val stream1 = conn.createStream().assertSuccess
    assertEquals(stream1.streamId, 2)

    val stream2 = conn.createStream().assertSuccess
    assertEquals(stream2.streamId, 4)
  }

  // ============================================================================
  // Peer Stream Registration
  // ============================================================================

  test("Server registers client-initiated streams (odd)") {
    val conn = H2Connection.server().assertSuccess
    val stream = conn.registerPeerStream(1).assertSuccess
    assertEquals(stream.streamId, 1)
  }

  test("Client registers server-initiated streams (even)") {
    val conn = H2Connection.client().assertSuccess
    val stream = conn.registerPeerStream(2).assertSuccess
    assertEquals(stream.streamId, 2)
  }

  test("Peer stream ID must increase") {
    val conn = H2Connection.server().assertSuccess
    conn.registerPeerStream(5).assertSuccess

    val result = conn.registerPeerStream(3) // Lower than 5
    assert(result.isFailure)
  }

  test("Client rejects odd peer stream IDs") {
    val conn = H2Connection.client().assertSuccess
    val result = conn.registerPeerStream(1) // Odd = client-initiated
    assert(result.isFailure)
  }

  test("Server rejects even peer stream IDs") {
    val conn = H2Connection.server().assertSuccess
    val result = conn.registerPeerStream(2) // Even = server-initiated
    assert(result.isFailure)
  }

  // ============================================================================
  // Stream Lookup
  // ============================================================================

  test("getStream returns existing stream") {
    val conn = H2Connection.client().assertSuccess
    val stream = conn.createStream().assertSuccess

    val result = conn.getStream(1).assertSuccess
    assertEquals(result.map(_.streamId), Some(stream.streamId))
  }

  test("getStream returns None for unknown stream") {
    val conn = H2Connection.client().assertSuccess
    assertEquals(conn.getStream(99).assertSuccess, None)
  }

  test("getOrCreateStream creates peer stream if needed") {
    val conn = H2Connection.server().assertSuccess
    val stream = conn.getOrCreateStream(1).assertSuccess // Client-initiated
    assertEquals(stream.streamId, 1)
  }

  test("getOrCreateStream returns existing stream") {
    val conn = H2Connection.client().assertSuccess
    val created = conn.createStream().assertSuccess
    val fetched = conn.getOrCreateStream(1).assertSuccess
    assertEquals(created.streamId, fetched.streamId)
  }

  // ============================================================================
  // Active Stream Count
  // ============================================================================

  test("activeStreamCount counts only active streams") {
    val conn = H2Connection.client().assertSuccess
    assertEquals(conn.activeStreamCount.assertSuccess, 0)

    val stream1 = conn.createStream().assertSuccess
    stream1.sendHeaders(endStream = false).assertSuccess
    assertEquals(conn.activeStreamCount.assertSuccess, 1)

    val stream2 = conn.createStream().assertSuccess
    stream2.sendHeaders(endStream = false).assertSuccess
    assertEquals(conn.activeStreamCount.assertSuccess, 2)

    stream1.reset().assertSuccess // Close stream 1
    assertEquals(conn.activeStreamCount.assertSuccess, 1)
  }

  // ============================================================================
  // Max Concurrent Streams
  // ============================================================================

  test("canCreateStreams respects peer maxConcurrentStreams") {
    val conn = H2Connection.client().assertSuccess
    // Apply peer settings with max 2 streams
    conn
      .applyPeerSettings(
        List(
          SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 2)
        )
      )
      .assertSuccess

    // Create and activate 2 streams
    val s1 = conn.createStream().assertSuccess
    s1.sendHeaders(endStream = false).assertSuccess
    val s2 = conn.createStream().assertSuccess
    s2.sendHeaders(endStream = false).assertSuccess

    // Third stream should fail
    assert(!conn.canCreateStreams.assertSuccess)
    val result = conn.createStream()
    assert(result.isFailure)
  }

  // ============================================================================
  // Connection Flow Control
  // ============================================================================

  test("Connection starts with default send window") {
    val conn = H2Connection.client().assertSuccess
    assertEquals(conn.connectionSendWindow.assertSuccess, 65535)
  }

  test("consumeConnectionSendWindow reduces window") {
    val conn = H2Connection.client().assertSuccess
    conn.consumeConnectionSendWindow(1000).assertSuccess
    assertEquals(conn.connectionSendWindow.assertSuccess, 65535 - 1000)
  }

  test("consumeConnectionSendWindow fails when exhausted") {
    val conn = H2Connection.client().assertSuccess
    val result = conn.consumeConnectionSendWindow(70000)
    assert(result.isFailure)
  }

  test("replenishConnectionSendWindow increases window") {
    val conn = H2Connection.client().assertSuccess
    conn.consumeConnectionSendWindow(1000).assertSuccess
    conn.replenishConnectionSendWindow(500).assertSuccess
    assertEquals(conn.connectionSendWindow.assertSuccess, 65535 - 1000 + 500)
  }

  test("replenishConnectionSendWindow fails on overflow") {
    val conn = H2Connection.client().assertSuccess
    val result = conn.replenishConnectionSendWindow(Int.MaxValue)
    assert(result.isFailure)
  }

  test("consumeConnectionReceiveWindow reduces window") {
    val conn = H2Connection.client().assertSuccess
    conn.consumeConnectionReceiveWindow(1000).assertSuccess
    assertEquals(conn.connectionReceiveWindow.assertSuccess, 65535 - 1000)
  }

  // ============================================================================
  // Settings
  // ============================================================================

  test("applyPeerSettings updates peer settings") {
    val conn = H2Connection.client().assertSuccess
    conn
      .applyPeerSettings(
        List(
          SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 32768)
        )
      )
      .assertSuccess

    val peerSettings = conn.peerSettings.assertSuccess
    assertEquals(peerSettings.maxFrameSize, 32768)
  }

  test("acknowledgeSettings sets flag") {
    val conn = H2Connection.client().assertSuccess
    assertEquals(conn.settingsAcked.assertSuccess, false)

    conn.acknowledgeSettings().assertSuccess
    assertEquals(conn.settingsAcked.assertSuccess, true)
  }

  // ============================================================================
  // GOAWAY
  // ============================================================================

  test("initiateGoaway returns last peer stream ID") {
    val conn = H2Connection.server().assertSuccess
    conn.registerPeerStream(5).assertSuccess

    val lastId = conn.initiateGoaway().assertSuccess
    assertEquals(lastId, 5)
    assert(conn.isGoingAway.assertSuccess)
    assert(!conn.canCreateStreams.assertSuccess)
  }

  test("receiveGoaway marks connection as going away") {
    val conn = H2Connection.client().assertSuccess
    conn.receiveGoaway(10, H2ErrorCode.NoError).assertSuccess

    assert(conn.isGoingAway.assertSuccess)
    assertEquals(conn.goawayErrorCode.assertSuccess, H2ErrorCode.NoError)
    assert(!conn.canCreateStreams.assertSuccess)
  }

  test("Cannot register peer stream after GOAWAY with lower lastStreamId") {
    val conn = H2Connection.server().assertSuccess
    conn.receiveGoaway(3, H2ErrorCode.NoError).assertSuccess

    // Stream 5 is after the GOAWAY cutoff
    val result = conn.registerPeerStream(5)
    assert(result.isFailure)
  }

  // ============================================================================
  // Stream Close
  // ============================================================================

  test("closeStream resets stream state") {
    val conn = H2Connection.client().assertSuccess
    val stream = conn.createStream().assertSuccess
    stream.sendHeaders(endStream = false).assertSuccess

    conn.closeStream(1).assertSuccess
    assertEquals(stream.state.assertSuccess, H2StreamState.Closed)
  }

  // ============================================================================
  // toString
  // ============================================================================

  test("toString provides useful debug info") {
    val conn = H2Connection.client().assertSuccess
    val str = conn.toString

    assert(str.contains("isClient=true"))
  }

  test("snapshot provides full debug info") {
    val conn = H2Connection.client().assertSuccess
    val snapshot = conn.snapshot.assertSuccess
    val str = snapshot.toString

    assert(str.contains("isClient=true"))
    assert(str.contains("sendWindow="))
    assert(str.contains("recvWindow="))
  }
}
