package net.ghoula.eru.http.hostile

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.h2.*

/** Hostile test for CVE-2019-9512 (HTTP/2 PING flood).
  *
  * Attack: on an established HTTP/2 connection, the client streams non-ACK PING frames. RFC 9113
  * §6.7 requires the receiver to respond to every PING with a PONG carrying the same 8-byte opaque
  * data. A naive server writes one PONG per inbound PING, saturating the write path.
  *
  * Mitigation: a per-connection ring-buffer budget on non-ACK PING frames. When the budget
  * (`H2ServerConnection.PingBudget` per `H2ServerConnection.PingWindowNanos`) is exceeded, the
  * server sends GOAWAY(ENHANCE_YOUR_CALM) and fails. Legitimate keepalive pings are once per
  * several seconds; 20/10s leaves generous headroom.
  *
  * The written-frame assertions rely on the following: a PONG is a PING frame with the ACK flag
  * set; the GOAWAY payload parsed here is [flags, lastStreamId(4), errorCode(4), debug...], so the
  * error code sits at payload offset 5-8; and the server writes at most PingBudget plus slack PONG
  * frames — not one per inbound PING.
  */
class H2PingFloodSpec extends HostileTestBase {

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

  private def parseWrittenFrames(data: Array[Byte]): List[(Int, Int, Array[Byte])] = {
    val out = scala.collection.mutable.ListBuffer.empty[(Int, Int, Array[Byte])]
    val buf = ByteBuffer.wrap(data)
    while buf.remaining >= H2Frame.HeaderSize do {
      val start = buf.position
      val length = ((buf.get(start) & 0xff) << 16) |
        ((buf.get(start + 1) & 0xff) << 8) |
        (buf.get(start + 2) & 0xff)
      val frameType = buf.get(start + 3) & 0xff
      val flags = buf.get(start + 4) & 0xff
      val streamId = ((buf.get(start + 5) & 0x7f) << 24) |
        ((buf.get(start + 6) & 0xff) << 16) |
        ((buf.get(start + 7) & 0xff) << 8) |
        (buf.get(start + 8) & 0xff)
      val payloadStart = start + H2Frame.HeaderSize
      val payloadEnd = payloadStart + length
      if buf.capacity >= payloadEnd then {
        val payload = new Array[Byte](length + 1)
        payload(0) = flags.toByte
        System.arraycopy(data, payloadStart, payload, 1, length)
        out += ((frameType, streamId, payload))
        buf.position(payloadEnd): Unit
      } else {
        buf.position(buf.limit): Unit
      }
    }
    out.toList
  }

  test("H2 PING flood (CVE-2019-9512): server caps PONGs and sends GOAWAY(ENHANCE_YOUR_CALM)") {
    requireHostileMode()

    val settings = H2Settings.server(maxConcurrentStreams = 100).assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    val attackCount = 500
    val pingData = new Array[Byte](8)
    (0 until attackCount).foreach { i =>
      pingData(0) = i.toByte
      val pingFrame = H2FrameCodec.pingFrame(pingData, ack = false)
      channel.appendReadData(encodeFrameBytes(pingFrame))
    }

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    val result = conn.receiveRequest().attempt.unsafeRunSync()

    result match {
      case net.ghoula.eru.Result.Failure(_) => ()
      case net.ghoula.eru.Result.Success(_) =>
        fail("Server accepted an unbounded PING flood without bailing")
    }

    val writtenFrames = parseWrittenFrames(channel.getWrittenData)

    val goaway = writtenFrames.find(_._1 == H2Frame.FrameType.GoAway)
    assert(
      goaway.isDefined,
      s"server did not send GOAWAY after PING flood; frames: ${writtenFrames.map(_._1).distinct}"
    )

    val (_, _, payload) = goaway.get
    val errorCode = ((payload(5) & 0xff) << 24) |
      ((payload(6) & 0xff) << 16) |
      ((payload(7) & 0xff) << 8) |
      (payload(8) & 0xff)
    assertEquals(
      errorCode,
      H2ErrorCode.EnhanceYourCalm.value,
      s"GOAWAY error code was $errorCode (${H2ErrorCode.fromValue(errorCode)}), expected EnhanceYourCalm"
    )

    val pongCount = writtenFrames.count { case (ft, _, pl) =>
      ft == H2Frame.FrameType.Ping && (pl(0) & H2Frame.Flags.Ack) != 0
    }
    val budget = H2ServerConnection.PingBudget
    assert(
      pongCount <= budget + 2,
      s"server wrote $pongCount PONGs — expected ≤ $budget + slack. If this is near $attackCount, budget isn't enforced."
    )
  }

  test("H2 PING flood: a single legitimate keepalive PING is ACKed without GOAWAY") {
    requireHostileMode()

    val settings = H2Settings.server(maxConcurrentStreams = 100).assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    val pingData = new Array[Byte](8)
    pingData(0) = 0x42.toByte
    channel.appendReadData(encodeFrameBytes(H2FrameCodec.pingFrame(pingData, ack = false)))

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    conn.receiveRequest().attempt.unsafeRunSync(): Unit

    val writtenFrames = parseWrittenFrames(channel.getWrittenData)

    val goaways = writtenFrames.filter(_._1 == H2Frame.FrameType.GoAway)
    assert(goaways.isEmpty, s"server sent unexpected GOAWAY on single PING: $goaways")

    val pongCount = writtenFrames.count { case (ft, _, pl) =>
      ft == H2Frame.FrameType.Ping && (pl(0) & H2Frame.Flags.Ack) != 0
    }
    assertEquals(pongCount, 1, s"expected exactly one PONG for one keepalive PING, got $pongCount")
  }
}
