package net.ghoula.eru.http.hostile

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.h2.*

/** Hostile test for CVE-2019-9515 (HTTP/2 SETTINGS flood).
  *
  * Attack: on an established HTTP/2 connection, the client sends an unbounded stream of non-ACK
  * SETTINGS frames. RFC 9113 §6.5 requires the receiver to ACK every SETTINGS with a SETTINGS-ACK
  * frame. A naive server writes one ACK per inbound SETTINGS, saturating the write path. The
  * attacker pays O(1) per frame while the server pays a write + state update.
  *
  * Mitigation: a per-connection ring buffer tracks non-ACK SETTINGS timestamps. When
  * `H2ServerConnection.SettingsBudget` non-ACK SETTINGS are seen within
  * `H2ServerConnection.SettingsWindowNanos`, the server sends GOAWAY(ENHANCE_YOUR_CALM) and fails
  * the connection.
  *
  * The written-frame assertions rely on the following: a SETTINGS-ACK is an empty-payload SETTINGS
  * frame; the server writes its own initial non-ACK SETTINGS plus at most one ACK per tolerated
  * inbound SETTINGS until the budget trips, so the ACK count is bounded by the budget plus slack
  * rather than scaling with the attack size.
  */
class H2SettingsFloodSpec extends HostileTestBase {

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

  test("H2 SETTINGS flood (CVE-2019-9515): server caps ACKs and sends GOAWAY(ENHANCE_YOUR_CALM)") {
    requireHostileMode()

    val settings = H2Settings.server(maxConcurrentStreams = 100).assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    val attackCount = 500
    (1 to attackCount).foreach { _ =>
      val settingsFrame = H2FrameCodec.settingsFrame(Nil)
      channel.appendReadData(encodeFrameBytes(settingsFrame))
    }

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    val result = conn.receiveRequest().attempt.unsafeRunSync()

    result match {
      case net.ghoula.eru.Result.Failure(_) => ()
      case net.ghoula.eru.Result.Success(_) =>
        fail("Server accepted an unbounded SETTINGS flood without bailing")
    }

    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaway = writtenFrames.find(_._1 == H2Frame.FrameType.GoAway)
    assert(
      goaway.isDefined,
      s"server did not send GOAWAY after SETTINGS flood; frames: ${writtenFrames.map(_._1).distinct}"
    )

    val (_, _, payload) = goaway.get
    val errorCode = ((payload(4) & 0xff) << 24) |
      ((payload(5) & 0xff) << 16) |
      ((payload(6) & 0xff) << 8) |
      (payload(7) & 0xff)
    assertEquals(
      errorCode,
      H2ErrorCode.EnhanceYourCalm.value,
      s"GOAWAY error code was $errorCode (${H2ErrorCode.fromValue(errorCode)}), expected EnhanceYourCalm"
    )

    val settingsAckCount = writtenFrames.count { case (ft, _, pl) =>
      ft == H2Frame.FrameType.Settings && pl.isEmpty
    }
    val budget = H2ServerConnection.SettingsBudget
    assert(
      settingsAckCount <= budget + 5,
      s"server wrote $settingsAckCount SETTINGS ACKs — expected ≤ $budget + slack. " +
        "If the count is ≈ attack size the budget isn't being enforced."
    )
  }

  test("H2 SETTINGS flood: a single legitimate SETTINGS update is accepted without GOAWAY") {
    requireHostileMode()

    val settings = H2Settings.server(maxConcurrentStreams = 100).assertSuccess

    val channel = new MockChannel()
    channel.appendReadData(clientPrefaceBytes)

    val clientUpdate = H2FrameCodec.settingsFrame(
      List(SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 131072))
    )
    channel.appendReadData(encodeFrameBytes(clientUpdate))

    val conn = H2ServerConnection.accept(channel, settings).assertSuccess
    conn.receiveRequest().attempt.unsafeRunSync(): Unit

    val writtenFrames = parseWrittenFrames(channel.getWrittenData)
    val goaways = writtenFrames.filter(_._1 == H2Frame.FrameType.GoAway)
    assert(goaways.isEmpty, s"server sent unexpected GOAWAY on single SETTINGS: $goaways")
  }
}
