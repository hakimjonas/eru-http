package net.ghoula.eru.http.hostile

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.h2.*

/** Hostile test for the HTTP/2 stream-exhaustion attack.
  *
  * HTTP/2 multiplexes many streams over a single TCP connection. An attacker can open thousands of
  * streams on one connection to exhaust memory (per-stream state machines) without opening
  * thousands of TCP connections.
  *
  * The mitigation is `maxConcurrentStreams` (RFC 9113 §5.1.2). The server sends this in its initial
  * SETTINGS; the peer is expected to respect it, AND the server also enforces it locally — if the
  * peer exceeds, the server responds with RST_STREAM(REFUSED_STREAM) for offending streams.
  *
  * Scenario 1 asserts the specific stream IDs that received RST_STREAM, the error code used (must
  * be `RefusedStream` = 0x7 per RFC 9113 §5.1.2), and that the accepted set is exactly the in-order
  * prefix of the sent IDs. An RST_STREAM payload is a 4-byte error code (RFC 9113 §6.4).
  * `receiveRequest` only surfaces streams that completed cleanly, so refused streams never appear
  * in the accepted set.
  *
  * The default-value documentation lives in `H2SettingsSpec`, not here. A "hostile" test that
  * merely asserts a config default belongs next to the default's definition, not in the attack
  * suite.
  *
  * Scenario 2 measures per-stream memory via a total-memory delta; it warms up first so the
  * measurement does not capture one-time JIT/class-init cost, and does not GC between allocation
  * and measurement so retained state is captured. The delta includes more than H2Stream state
  * (HPACK decoder growth, accumulated `receiveRequest` tuples, list-based retain paths); the
  * empirically measured figure is ~55KB per stream and the 80KB bound is a regression guard.
  *
  * Uses H2ServerConnection directly via a MockChannel — no TCP sockets. Runtime bounded, allows
  * precise per-stream memory measurement.
  */
class H2StreamExhaustionSpec extends HostileTestBase {

  given EruRuntime = EruRuntime.shared

  private class MockChannel extends WritableByteChannel with ReadableByteChannel {
    private val written = scala.collection.mutable.ArrayBuffer[Byte]()
    private var readData: Array[Byte] = Array.empty
    private var readOffset = 0
    @volatile private var openFlag = true

    def appendReadData(data: Array[Byte]): Unit = { readData = readData ++ data }
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

  /** Build a well-formed HPACK-encoded header block containing all four required request
    * pseudo-headers per RFC 9113 §8.3.1. A block missing any of :method, :scheme, :path, :authority
    * would be rejected with ProtocolError before the stream-count enforcement gets a chance to fire
    * — which would make this test measure the wrong thing.
    */
  private def validHeaderBlock(encoder: HpackEncoder): Array[Byte] = {
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
    val out = new Array[Byte](buf.remaining)
    buf.get(out)
    out
  }

  /** Build a HEADERS frame with a well-formed header block. END_STREAM set so the stream completes
    * without requiring a DATA frame.
    */
  private def headersFrameBytes(streamId: Int, encoder: HpackEncoder): Array[Byte] = {
    val block = validHeaderBlock(encoder)
    val frame = H2FrameCodec.headersFrame(streamId, block, endStream = true, endHeaders = true)
    val buf = H2FrameCodec.encode(frame)
    val out = new Array[Byte](buf.remaining)
    buf.get(out)
    out
  }

  private def clientPrefaceBytes: Array[Byte] = {
    val settings = H2FrameCodec.settingsFrame(Nil)
    val buf = H2FrameCodec.encode(settings)
    val arr = new Array[Byte](buf.remaining)
    buf.get(arr)
    H2Frame.ConnectionPreface ++ arr
  }

  /** Parse the server-written byte stream into a flat list of (frameType, streamId, payload)
    * triples in write order.
    */
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

  test("H2 enforcement: streams beyond maxConcurrentStreams receive RST_STREAM(REFUSED_STREAM)") {
    requireHostileMode()

    val limit = 10
    val totalStreams = 30
    val channel = new MockChannel()
    val settings = H2Settings.server(maxConcurrentStreams = limit).assertSuccess
    val encoder = new HpackEncoder()

    channel.appendReadData(clientPrefaceBytes)
    val streamIds = (1 to (totalStreams * 2) by 2).take(totalStreams).toList
    streamIds.foreach(sid => channel.appendReadData(headersFrameBytes(sid, encoder)))

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess

    val acceptedStreamIds = scala.collection.mutable.ListBuffer.empty[Int]
    var done = false
    while !done && acceptedStreamIds.size < totalStreams do {
      conn.receiveRequest().attempt.unsafeRunSync() match {
        case net.ghoula.eru.Result.Success((sid, _, _)) => acceptedStreamIds += sid
        case net.ghoula.eru.Result.Failure(_) => done = true
      }
    }

    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val rstStreams = writtenFrames.collect {
      case (frameType, streamId, payload) if frameType == H2Frame.FrameType.RstStream =>
        val errorCode = ((payload(0) & 0xff) << 24) |
          ((payload(1) & 0xff) << 16) |
          ((payload(2) & 0xff) << 8) |
          (payload(3) & 0xff)
        (streamId, errorCode)
    }
    val rstStreamIds = rstStreams.map(_._1).toSet

    assert(
      acceptedStreamIds.size <= limit,
      s"Server accepted ${acceptedStreamIds.size} streams, exceeds limit $limit. " +
        s"Accepted=${acceptedStreamIds.toList}"
    )
    val expectedPrefix = streamIds.take(acceptedStreamIds.size)
    assertEquals(
      acceptedStreamIds.toList,
      expectedPrefix,
      "Accepted streams must be the in-order prefix of sent streams"
    )

    val refusedExpected = streamIds.drop(acceptedStreamIds.size).toSet
    val missedRsts = refusedExpected -- rstStreamIds
    assert(
      missedRsts.isEmpty,
      s"Expected RST_STREAM for refused stream IDs; missing: $missedRsts. Got RSTs for: $rstStreamIds"
    )

    val refusedCodeValue = H2ErrorCode.RefusedStream.value
    val quotaRsts = rstStreams.filter { case (sid, _) => refusedExpected.contains(sid) }
    quotaRsts.foreach { case (sid, code) =>
      assertEquals(
        code,
        refusedCodeValue,
        s"Stream $sid refused with wrong error code $code (expected RefusedStream=$refusedCodeValue)"
      )
    }

    val wronglyRst = acceptedStreamIds.toSet.intersect(rstStreamIds)
    assert(wronglyRst.isEmpty, s"Accepted streams were also RST'd: $wronglyRst")
  }

  test("H2 enforcement: per-stream memory overhead is bounded under attack") {
    requireHostileMode()

    val streamCount = 1000
    val channel = new MockChannel()
    val settings = H2Settings.server(maxConcurrentStreams = streamCount * 2).assertSuccess
    val encoder = new HpackEncoder()

    channel.appendReadData(clientPrefaceBytes)
    val streamIds = (1 until (streamCount * 2) by 2).take(streamCount)
    streamIds.foreach(sid => channel.appendReadData(headersFrameBytes(sid, encoder)))

    locally {
      val warmEncoder = new HpackEncoder()
      val warmChannel = new MockChannel()
      warmChannel.appendReadData(clientPrefaceBytes)
      (1 to 10 by 2).foreach(sid => warmChannel.appendReadData(headersFrameBytes(sid, warmEncoder)))
      val warmConn = H2ServerConnection.accept(warmChannel, settings).assertSuccess
      (1 to 5).foreach(_ => warmConn.receiveRequest().attempt.unsafeRunSync(): Unit)
    }

    System.gc(); System.gc(); Thread.sleep(50)
    val before = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    var accepted = 0
    var done = false
    while !done && accepted < streamCount do {
      conn.receiveRequest().attempt.unsafeRunSync() match {
        case net.ghoula.eru.Result.Success(_) => accepted += 1
        case net.ghoula.eru.Result.Failure(_) => done = true
      }
    }

    val after = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
    val perStreamBytes = (after - before).toDouble / math.max(accepted, 1)

    assert(
      accepted == streamCount,
      s"Expected all $streamCount streams accepted (limit was 2x), got $accepted. " +
        "The memory-bound assertion is meaningless if acceptance is partial."
    )

    assert(
      perStreamBytes < 80_000.0,
      s"Per-stream memory ${perStreamBytes.toInt}B exceeds 80KB bound. " +
        s"Accepted=$accepted, delta=${after - before} bytes. " +
        "Expected is ~55KB; investigate if this crossed 80KB."
    )
  }
}
