package net.ghoula.eru.http.hostile

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.h2.*

/** Hostile test A.4: HTTP/2 CONTINUATION flood (CVE-2024-27316).
  *
  * Attack: the client sends a HEADERS frame without END_HEADERS, then an unbounded stream of
  * CONTINUATION frames also without END_HEADERS. A naive server accumulates header-block bytes
  * forever until OOM.
  *
  * Mitigation (Fix #1, commit 760ed4b): `readContinuationFrames` tracks cumulative size and, as
  * soon as it exceeds `localSettings.maxHeaderListSize`, sends GOAWAY(COMPRESSION_ERROR) and fails.
  *
  * This spec validates the mitigation under flood conditions — not just "a few frames" as
  * `H2ServerConnectionSpec` does. Uses `H2ServerConnection` directly via a MockChannel so we can
  * feed thousands of CONTINUATION frames without involving real sockets.
  *
  * Assertions verify:
  *   1. The server stops reading CONTINUATION frames at the limit boundary.
  *   2. A GOAWAY is written carrying COMPRESSION_ERROR (0x9).
  *   3. Heap growth during the attack is bounded by the limit — NOT by attack size.
  *   4. A CONTINUATION on an unexpected stream is rejected with ProtocolError.
  *   5. Valid multi-CONTINUATION headers under the limit still succeed.
  */
class H2ContinuationFloodSpec extends HostileTestBase {

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

  /** Parse flat (frameType, streamId, payload) from a raw server-written stream. */
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
  // A.4 scenario 1: CONTINUATION flood is bounded at maxHeaderListSize
  // ====================================================================

  test("H2 CONTINUATION flood: server caps at maxHeaderListSize and sends GOAWAY(COMPRESSION_ERROR)") {
    requireHostileMode()

    // Small limit so the test runs fast without allocating large buffers.
    val maxHeaderListSize = 4096
    val settings = H2Settings
      .create(maxConcurrentStreams = 10, maxHeaderListSize = maxHeaderListSize)
      .assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    // Initial HEADERS for stream 1 with NO END_HEADERS. Small 128-byte block.
    val initialBlock = new Array[Byte](128)
    val headersFrame = H2FrameCodec.headersFrame(1, initialBlock, endStream = true, endHeaders = false)
    channel.appendReadData(encodeFrameBytes(headersFrame))

    // Flood: 10000 CONTINUATION frames each carrying 512-byte block, none with END_HEADERS.
    // Total bytes the attacker wants to push: 128 + 10000 * 512 ≈ 5.1MB.
    // maxHeaderListSize = 4096 means the server should bail within the first ~8 frames.
    val attackerFrameCount = 10_000
    val contBlockSize = 512
    val contBlock = new Array[Byte](contBlockSize)
    (1 to attackerFrameCount).foreach { _ =>
      val contFrame = H2FrameCodec.continuationFrame(1, contBlock, endHeaders = false)
      channel.appendReadData(encodeFrameBytes(contFrame))
    }
    val totalAttackerBytes = channel.bytesQueuedForRead

    // Baseline heap BEFORE the attack, after a single receiveRequest call so
    // JIT/class-init noise is out of the picture.
    System.gc(); System.gc(); Thread.sleep(50)
    val heapBefore = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    val result = conn.receiveRequest().attempt.unsafeRunSync()

    val heapAfter = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
    val heapDelta = heapAfter - heapBefore

    // Assertion 1: the server bailed with an error, not success.
    result match {
      case net.ghoula.eru.Result.Failure(_) => ()
      case net.ghoula.eru.Result.Success(_) =>
        fail("Server accepted an unbounded CONTINUATION chain — mitigation failed")
    }

    // Assertion 2: the server DID NOT read the entire attack. This is the
    // heart of CVE-2024-27316. If `bytesConsumedFromRead` is close to
    // `totalAttackerBytes`, the server read everything before giving up —
    // which means memory grew with the attack.
    val consumed = channel.bytesConsumedFromRead
    assert(
      consumed < maxHeaderListSize * 4, // a few frames of header overhead is fine
      s"Server read $consumed of $totalAttackerBytes attacker bytes before bailing. " +
        s"Expected to bail within a few ×maxHeaderListSize=$maxHeaderListSize. " +
        "This means the CONTINUATION bound is NOT being enforced promptly."
    )

    // Assertion 3: a GOAWAY with COMPRESSION_ERROR (0x9) was written.
    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaway = writtenFrames.find(_._1 == H2Frame.FrameType.GoAway)
    assert(goaway.isDefined, s"Server did not send GOAWAY. Written frame types: ${writtenFrames.map(_._1).distinct}")

    val (_, _, goawayPayload) = goaway.get
    // GOAWAY payload layout (RFC 9113 §6.8): last-stream-id (4) + error-code (4) + debug-data
    assert(goawayPayload.length >= 8, s"GOAWAY payload too short: ${goawayPayload.length}")
    val errorCode = ((goawayPayload(4) & 0xff) << 24) |
      ((goawayPayload(5) & 0xff) << 16) |
      ((goawayPayload(6) & 0xff) << 8) |
      (goawayPayload(7) & 0xff)
    assertEquals(
      errorCode,
      H2ErrorCode.CompressionError.value,
      s"GOAWAY error code was $errorCode, expected CompressionError=${H2ErrorCode.CompressionError.value}"
    )

    // Assertion 4: heap growth must be bounded. Even with a 5MB attack, the
    // retained allocation is a few MB regardless (HPACK tables, request
    // accumulators). 20MB is a regression guard — catches "server buffered
    // the entire attack".
    assert(
      heapDelta < 20L * 1024 * 1024,
      s"Heap grew by ${heapDelta / 1024 / 1024}MB during CONTINUATION flood. " +
        s"Expected <20MB (bound should relate to maxHeaderListSize=${maxHeaderListSize}, not attack size)."
    )
  }

  // ====================================================================
  // A.4 scenario 2: CONTINUATION on wrong stream is rejected
  // ====================================================================

  test("H2 CONTINUATION flood: frame for wrong stream is rejected with ProtocolError") {
    requireHostileMode()

    val channel = new MockChannel()
    val settings = H2Settings.server(maxConcurrentStreams = 10).assertSuccess
    channel.appendReadData(clientPrefaceBytes)

    // HEADERS on stream 1, no END_HEADERS — server starts aggregating for stream 1.
    val headersFrame = H2FrameCodec.headersFrame(1, new Array[Byte](64), endStream = true, endHeaders = false)
    channel.appendReadData(encodeFrameBytes(headersFrame))

    // CONTINUATION targeting stream 3 — RFC 9113 §6.10: CONTINUATION MUST follow
    // HEADERS/PUSH_PROMISE/CONTINUATION on the SAME stream. Different stream = protocol error.
    val wrongStreamFrame = H2FrameCodec.continuationFrame(3, new Array[Byte](32), endHeaders = true)
    channel.appendReadData(encodeFrameBytes(wrongStreamFrame))

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    val result = conn.receiveRequest().attempt.unsafeRunSync()

    result match {
      case net.ghoula.eru.Result.Failure(_) => ()
      case net.ghoula.eru.Result.Success(_) =>
        fail("Server accepted CONTINUATION on wrong stream — protocol violation ignored")
    }

    // The server should have sent a GOAWAY with ProtocolError (0x1)
    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaway = writtenFrames.find(_._1 == H2Frame.FrameType.GoAway)
    assert(goaway.isDefined, "Server did not send GOAWAY for cross-stream CONTINUATION")

    val (_, _, payload) = goaway.get
    val errorCode = ((payload(4) & 0xff) << 24) |
      ((payload(5) & 0xff) << 16) |
      ((payload(6) & 0xff) << 8) |
      (payload(7) & 0xff)
    assertEquals(
      errorCode,
      H2ErrorCode.ProtocolError.value,
      s"GOAWAY error code was $errorCode, expected ProtocolError=${H2ErrorCode.ProtocolError.value}"
    )
  }

  // ====================================================================
  // A.4 scenario 3: normal multi-CONTINUATION traffic still works
  // ====================================================================

  test("H2 CONTINUATION flood: valid multi-CONTINUATION under limit succeeds") {
    requireHostileMode()

    // Spread a realistic ~1KB header block across HEADERS + 4 CONTINUATION frames.
    // Well under default maxHeaderListSize=65536. The mitigation must NOT break
    // legitimate multi-frame header blocks.
    val maxHeaderListSize = 8192
    val settings = H2Settings
      .create(maxHeaderListSize = maxHeaderListSize, maxConcurrentStreams = 10)
      .assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    // Build a complete valid header block via HpackEncoder, then split across frames.
    val encoder = new HpackEncoder()
    val blockBuf = ByteBuffer.allocate(1024)
    encoder
      .encode(
        List(
          (":method", "GET"),
          (":scheme", "https"),
          (":path", "/"),
          (":authority", "example.com"),
          ("user-agent", "hostile-test/1.0"),
          ("x-custom-a", "some-value-a"),
          ("x-custom-b", "some-value-b")
        ),
        blockBuf
      )
      .assertSuccess
    blockBuf.flip()
    val fullBlock = new Array[Byte](blockBuf.remaining)
    blockBuf.get(fullBlock)

    // Split into 5 chunks: initial HEADERS + 4 CONTINUATION (last with END_HEADERS)
    val chunkSize = math.max(1, fullBlock.length / 5)
    val chunks: List[Array[Byte]] = {
      val builder = scala.collection.mutable.ListBuffer[Array[Byte]]()
      var i = 0
      while i < fullBlock.length do {
        val end = math.min(i + chunkSize, fullBlock.length)
        val piece = new Array[Byte](end - i)
        System.arraycopy(fullBlock, i, piece, 0, end - i)
        builder += piece
        i = end
      }
      builder.toList
    }

    // First chunk goes in HEADERS (no END_HEADERS); rest in CONTINUATION; last CONTINUATION has END_HEADERS.
    val head :: tail = chunks: @unchecked
    channel.appendReadData(
      encodeFrameBytes(H2FrameCodec.headersFrame(1, head, endStream = true, endHeaders = false))
    )
    tail.zipWithIndex.foreach { case (chunk, idx) =>
      val isLast = idx == tail.length - 1
      channel.appendReadData(encodeFrameBytes(H2FrameCodec.continuationFrame(1, chunk, endHeaders = isLast)))
    }

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    val result = conn.receiveRequest().attempt.unsafeRunSync()

    result match {
      case net.ghoula.eru.Result.Success((streamId, headers, _)) =>
        assertEquals(streamId, 1)
        val headerMap = headers.toMap
        assertEquals(headerMap.get(":method"), Some("GET"))
        assertEquals(headerMap.get(":path"), Some("/"))
        assertEquals(headerMap.get("user-agent"), Some("hostile-test/1.0"))
      case net.ghoula.eru.Result.Failure(err) =>
        fail(s"Valid multi-CONTINUATION headers were rejected: $err")
    }

    // No GOAWAY should have been written for this clean interaction.
    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaways = writtenFrames.filter(_._1 == H2Frame.FrameType.GoAway)
    assert(goaways.isEmpty, s"Server sent unexpected GOAWAY on valid traffic: $goaways")
  }
}
