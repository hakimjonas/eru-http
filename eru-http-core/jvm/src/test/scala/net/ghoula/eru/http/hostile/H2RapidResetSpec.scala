package net.ghoula.eru.http.hostile

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.h2.*

/** Hostile test for CVE-2023-44487 (HTTP/2 Rapid Reset).
  *
  * Attack: on a single H2 connection, the client opens a stream with HEADERS and IMMEDIATELY sends
  * RST_STREAM to cancel it. Because RST_STREAM transitions the stream to Closed, it stops counting
  * against `maxConcurrentStreams`. The attacker can therefore open (and cancel) tens of thousands
  * of streams per second, and for each one the server has already:
  *   - Allocated an `H2Stream` object in the connection's streams map.
  *   - (In the full server path) forked a handler fiber that may have started running before
  *     observing the RST.
  *
  * Without mitigation, the amortized per-reset cost × attack rate exceeds handler CPU capacity, and
  * the streams map grows unbounded because `stream.reset()` does NOT remove from the map.
  *
  * This spec exercises the attack at the `H2ServerConnection` layer using a MockChannel pre-loaded
  * with N (HEADERS+RST_STREAM) pairs. It drives `receiveRequest()` in a loop and counts how far the
  * server gets before bailing — and what the final stream-map size is.
  *
  * Expected behavior under mitigation (B.4):
  *   - Within a bounded reset budget (e.g. 100 per rolling window), the server tolerates resets.
  *   - Beyond the budget, the server sends `GOAWAY(ENHANCE_YOUR_CALM)` and closes the connection.
  *   - The streams map stays bounded across the attack — closed streams are removed from the map.
  *
  * Before the fix is wired in, this test documents the current behavior and fails on the regression
  * bounds.
  */
class H2RapidResetSpec extends HostileTestBase {

  given EruRuntime = EruRuntime.shared

  private class MockChannel extends WritableByteChannel with ReadableByteChannel {
    private val written = scala.collection.mutable.ArrayBuffer[Byte]()
    private var readData: Array[Byte] = Array.empty
    private var readOffset = 0
    @volatile private var openFlag = true

    def appendReadData(data: Array[Byte]): Unit = { readData = readData ++ data }
    def bytesQueuedForRead: Int = readData.length - readOffset
    def bytesConsumedFromRead: Int = readOffset
    def getWrittenData: Array[Byte] = written.synchronized(written.toArray)

    override def read(dst: ByteBuffer): Int = {
      if readOffset >= readData.length then -1
      else {
        val toRead = math.min(readData.length - readOffset, dst.remaining)
        dst.put(readData, readOffset, toRead)
        readOffset += toRead
        toRead
      }
    }
    override def write(src: ByteBuffer): Int = written.synchronized {
      val bytes = new Array[Byte](src.remaining)
      src.get(bytes)
      written ++= bytes
      bytes.length
    }
    override def isOpen: Boolean = openFlag
    override def close(): Unit = openFlag = false
  }

  private def clientPrefaceBytes: Array[Byte] = {
    val settings = H2FrameCodec.settingsFrame(Nil)
    val buf = H2FrameCodec.encode(settings)
    val arr = new Array[Byte](buf.remaining)
    buf.get(arr)
    H2Frame.ConnectionPreface ++ arr
  }

  private def encodeFrameBytes(frame: H2ParsedFrame): Array[Byte] = {
    val buf = H2FrameCodec.encode(frame)
    val out = new Array[Byte](buf.remaining)
    buf.get(out)
    out
  }

  /** Build a minimal-but-valid HEADERS payload using HpackEncoder.
    *
    * Pseudo-headers for a GET on a fresh stream. Without these the server rejects the frame with
    * ProtocolError before we get a chance to observe the reset-handling code path.
    */
  private def buildHeadersBlock(): Array[Byte] = {
    val encoder = new HpackEncoder()
    val buf = ByteBuffer.allocate(256)
    encoder
      .encode(
        List(
          (":method", "GET"),
          (":scheme", "https"),
          (":path", "/"),
          (":authority", "example.com")
        ),
        buf
      )
      .assertSuccess
    buf.flip()
    val bytes = new Array[Byte](buf.remaining)
    buf.get(bytes)
    bytes
  }

  /** Parse flat (frameType, streamId, payload) tuples from a raw server-written stream. */
  private def parseWrittenFrames(data: Array[Byte]): List[(Int, Int, Array[Byte])] = {
    val out = scala.collection.mutable.ListBuffer.empty[(Int, Int, Array[Byte])]
    val buf = ByteBuffer.wrap(data)
    while buf.remaining >= H2Frame.HeaderSize do {
      val start = buf.position
      val length = ((buf.get(start) & 0xff) << 16) |
        ((buf.get(start + 1) & 0xff) << 8) |
        (buf.get(start + 2) & 0xff)
      val frameType = buf.get(start + 3) & 0xff
      val streamId = ((buf.get(start + 5) & 0x7f) << 24) |
        ((buf.get(start + 6) & 0xff) << 16) |
        ((buf.get(start + 7) & 0xff) << 8) |
        (buf.get(start + 8) & 0xff)
      val payloadStart = start + H2Frame.HeaderSize
      val payloadEnd = payloadStart + length
      if buf.capacity >= payloadEnd then {
        val payload = new Array[Byte](length)
        System.arraycopy(data, payloadStart, payload, 0, length)
        out += ((frameType, streamId, payload))
        buf.position(payloadEnd): Unit
      } else {
        buf.position(buf.limit): Unit
      }
    }
    out.toList
  }

  // ====================================================================
  // Rapid-reset attack: many HEADERS+RST on distinct stream IDs
  // ====================================================================

  test("H2 rapid reset (CVE-2023-44487): server caps resets and sends GOAWAY(ENHANCE_YOUR_CALM)") {
    requireHostileMode()

    // Generous maxConcurrentStreams so concurrency isn't the limit — rapid reset
    // specifically bypasses concurrent-stream caps because each stream closes
    // the moment RST is received.
    val settings = H2Settings
      .create(maxConcurrentStreams = 1000)
      .assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    // Build (HEADERS + RST_STREAM) pairs on client-odd stream IDs 1, 3, 5, ...
    // Attack size: 5000 cancellations. Small enough to run in < 1s, large
    // enough to overwhelm any sane rolling budget.
    val attackCount = 5000
    val headerBlock = buildHeadersBlock()
    (1 to attackCount).foreach { i =>
      val streamId = 2 * i - 1 // 1, 3, 5, ...
      val headers = H2FrameCodec.headersFrame(streamId, headerBlock, endStream = true, endHeaders = true)
      val rst = H2FrameCodec.rstStreamFrame(streamId, H2ErrorCode.Cancel)
      channel.appendReadData(encodeFrameBytes(headers))
      channel.appendReadData(encodeFrameBytes(rst))
    }

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess

    // Drive receiveRequest in a loop until the server bails. Count successes
    // (how many HEADERS got through before the rolling budget was exceeded).
    var requestsAccepted = 0
    var keepGoing = true
    while keepGoing && requestsAccepted < attackCount * 2 do {
      conn.receiveRequest().attempt.unsafeRunSync() match {
        case net.ghoula.eru.Result.Success(_) =>
          requestsAccepted += 1
        case net.ghoula.eru.Result.Failure(_) =>
          keepGoing = false
      }
    }

    // Assertion 1: the server MUST bail shortly after the reset budget is
    // exhausted. With ResetBudget=100, the attacker gets ~100 HEADERS through
    // before the 100th RST trips the rolling-window check. A small margin
    // absorbs one extra receiveRequest that delivers the final HEADERS before
    // the subsequent RST triggers GOAWAY.
    assert(
      requestsAccepted >= H2ServerConnection.ResetBudget &&
        requestsAccepted <= H2ServerConnection.ResetBudget + 10,
      s"Server processed $requestsAccepted of $attackCount requests before bailing — " +
        s"expected ~${H2ServerConnection.ResetBudget}. If higher: budget isn't enforced. " +
        "If lower: budget is triggering on legitimate traffic."
    )

    // Assertion 2: a GOAWAY with ENHANCE_YOUR_CALM (0xb) must have been sent.
    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaway = writtenFrames.find(_._1 == H2Frame.FrameType.GoAway)
    assert(
      goaway.isDefined,
      "Server did not send GOAWAY after rapid-reset flood. " +
        s"Written frame types: ${writtenFrames.map(_._1).distinct}"
    )

    val (_, _, goawayPayload) = goaway.get
    val errorCode = ((goawayPayload(4) & 0xff) << 24) |
      ((goawayPayload(5) & 0xff) << 16) |
      ((goawayPayload(6) & 0xff) << 8) |
      (goawayPayload(7) & 0xff)
    assertEquals(
      errorCode,
      H2ErrorCode.EnhanceYourCalm.value,
      s"GOAWAY error code was $errorCode (${H2ErrorCode.fromValue(errorCode)}), " +
        s"expected EnhanceYourCalm=${H2ErrorCode.EnhanceYourCalm.value}"
    )

    // Assertion 3: the connection's stream-map does not grow without bound.
    // `stream.reset()` in the current code leaves the stream in `streamsRef`
    // even though state is Closed — so without B.4 explicitly removing reset
    // streams, the map grows 1:1 with attack size. The mitigation must keep
    // it bounded.
    val finalStreamCount = conn.connection.streamCount.unsafeRunSync()
    assert(
      finalStreamCount < 200,
      s"Stream map size at end of attack: $finalStreamCount. " +
        "Expected bounded (<200). If map size ≈ attack count, closed streams are not being " +
        "cleaned up — memory DoS vector."
    )
  }

  // ====================================================================
  // Regression: legitimate single reset (client cancels one stream) works
  // ====================================================================

  test("H2 rapid reset: a single legitimate reset is accepted without GOAWAY") {
    requireHostileMode()

    val settings = H2Settings.server(maxConcurrentStreams = 100).assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    val headerBlock = buildHeadersBlock()
    // One successful request, then one canceled request.
    channel.appendReadData(
      encodeFrameBytes(H2FrameCodec.headersFrame(1, headerBlock, endStream = true, endHeaders = true))
    )
    channel.appendReadData(
      encodeFrameBytes(H2FrameCodec.headersFrame(3, headerBlock, endStream = true, endHeaders = true))
    )
    channel.appendReadData(encodeFrameBytes(H2FrameCodec.rstStreamFrame(3, H2ErrorCode.Cancel)))

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess

    // First request should succeed. Server will then see HEADERS for stream 3
    // and return it as the second receiveRequest — but before a handler runs,
    // RST_STREAM is already in the read queue. In the current code path, the
    // HEADERS on stream 3 is consumed by receiveRequestHeaders and the RST
    // is seen later. Either way, one single reset should NOT trip the budget.

    val first = conn.receiveRequest().attempt.unsafeRunSync()
    first match {
      case net.ghoula.eru.Result.Success((sid, _, _)) => assertEquals(sid, 1)
      case net.ghoula.eru.Result.Failure(err) => fail(s"First legitimate request failed: $err")
    }

    val second = conn.receiveRequest().attempt.unsafeRunSync()
    second match {
      case net.ghoula.eru.Result.Success((sid, _, _)) => assertEquals(sid, 3)
      case net.ghoula.eru.Result.Failure(err) =>
        // Acceptable: server may see HEADERS+RST as a peer-canceled request and
        // skip it; or may decode HEADERS first and expose the pair to the
        // caller. What is NOT acceptable is a GOAWAY.
        val writtenFrames = parseWrittenFrames(channel.getWrittenData)
        val goaway = writtenFrames.find(_._1 == H2Frame.FrameType.GoAway)
        assert(
          goaway.isEmpty,
          s"Server sent GOAWAY on a single legitimate reset. err=$err, goaway=$goaway"
        )
    }

    // No GOAWAY at all from this interaction.
    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaways = writtenFrames.filter(_._1 == H2Frame.FrameType.GoAway)
    assert(goaways.isEmpty, s"Unexpected GOAWAY on benign reset interaction: $goaways")
  }
}
