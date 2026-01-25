package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 client connection. */
class H2ClientConnectionSpec extends FunSuite {

  // Runtime needed for H2ClientConnection/H2Stream creation
  given EruRuntime = EruRuntime.shared

  // ============================================================================
  // Mock Channel for Testing
  // ============================================================================

  /** A mock channel that allows us to control what's read and capture what's written. */
  class MockChannel extends WritableByteChannel with ReadableByteChannel {
    private val writtenData = scala.collection.mutable.ArrayBuffer[Byte]()
    private var readData: Array[Byte] = Array.empty
    private var readOffset = 0
    private var _isOpen = true

    def setReadData(data: Array[Byte]): Unit = {
      readData = data
      readOffset = 0
    }

    def getWrittenData: Array[Byte] = writtenData.toArray

    override def read(dst: ByteBuffer): Int = {
      if readOffset >= readData.length then {
        -1 // EOF
      } else {
        val available = readData.length - readOffset
        val toRead = math.min(available, dst.remaining)
        dst.put(readData, readOffset, toRead)
        readOffset += toRead
        toRead
      }
    }

    override def write(src: ByteBuffer): Int = {
      val bytes = new Array[Byte](src.remaining)
      src.get(bytes)
      writtenData ++= bytes
      bytes.length
    }

    override def isOpen: Boolean = _isOpen

    override def close(): Unit = _isOpen = false
  }

  // ============================================================================
  // Connection Creation
  // ============================================================================

  test("H2ClientConnection creates with default settings") {
    val channel = new MockChannel()
    val conn = H2ClientConnection(channel).assertSuccess

    assertEquals(conn.connection.isClient, true)
    assertEquals(conn.connection.activeStreamCount.assertSuccess, 0)
  }

  test("H2ClientConnection creates with custom settings") {
    val channel = new MockChannel()
    val settings = H2Settings.client(maxConcurrentStreams = 50).assertSuccess
    val conn = H2ClientConnection(channel, settings).assertSuccess

    assertEquals(conn.connection.localSettings.maxConcurrentStreams, 50)
  }

  // ============================================================================
  // Connection Preface
  // ============================================================================

  test("sendConnectionPreface sends magic and SETTINGS") {
    val channel = new MockChannel()
    val conn = H2ClientConnection(channel).assertSuccess

    conn.sendConnectionPreface().assertSuccess

    val written = channel.getWrittenData

    // Should start with connection preface magic (24 bytes)
    val prefaceBytes = H2Frame.ConnectionPreface
    assert(
      written.length >= prefaceBytes.length,
      s"Expected at least ${prefaceBytes.length} bytes, got ${written.length}"
    )
    assert(written.take(prefaceBytes.length).sameElements(prefaceBytes), "Connection preface magic mismatch")

    // Should be followed by a SETTINGS frame
    val settingsStart = prefaceBytes.length
    assert(written.length >= settingsStart + H2Frame.HeaderSize, "Should have SETTINGS frame after preface")

    // Parse SETTINGS frame header
    val settingsBuffer = ByteBuffer.wrap(written.drop(settingsStart))
    val header = H2FrameCodec.parseHeader(settingsBuffer).assertSuccess

    assertEquals(header.frameType, H2Frame.FrameType.Settings)
    assertEquals(header.streamId, 0)
  }

  test("receiveConnectionPreface accepts server SETTINGS") {
    val channel = new MockChannel()
    val conn = H2ClientConnection(channel).assertSuccess

    // Prepare server SETTINGS frame
    val serverSettings = List(
      SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 100),
      SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 32768)
    )
    val settingsFrame = H2FrameCodec.settingsFrame(serverSettings)
    val encodedSettings = H2FrameCodec.encode(settingsFrame)

    // Set up channel to return server SETTINGS
    val data = new Array[Byte](encodedSettings.remaining)
    encodedSettings.get(data)
    channel.setReadData(data)

    // Receive the preface
    conn.receiveConnectionPreface().assertSuccess

    // Verify peer settings were applied
    val peerSettings = conn.connection.peerSettings.assertSuccess
    assertEquals(peerSettings.maxConcurrentStreams, 100)
    assertEquals(peerSettings.maxFrameSize, 32768)

    // Verify SETTINGS ACK was sent
    val written = channel.getWrittenData
    assert(written.length >= H2Frame.HeaderSize, "Should have sent SETTINGS ACK")

    val ackBuffer = ByteBuffer.wrap(written)
    val ackHeader = H2FrameCodec.parseHeader(ackBuffer).assertSuccess
    assertEquals(ackHeader.frameType, H2Frame.FrameType.Settings)
    assert(ackHeader.isAck, "Should be SETTINGS ACK")
  }

  // ============================================================================
  // Frame I/O
  // ============================================================================

  test("readFrame parses PING frame") {
    val channel = new MockChannel()
    val conn = H2ClientConnection(channel).assertSuccess

    // Prepare a PING frame
    val pingData = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    val pingFrame = H2FrameCodec.pingFrame(pingData, ack = false)
    val encoded = H2FrameCodec.encode(pingFrame)

    val data = new Array[Byte](encoded.remaining)
    encoded.get(data)
    channel.setReadData(data)

    conn.readFrame().assertSuccess match {
      case ping: PingFrame =>
        assert(ping.data.sameElements(pingData), "PING data mismatch")
      case other =>
        fail(s"Expected PingFrame, got ${other.getClass}")
    }
  }

  test("writeFrame sends GOAWAY") {
    val channel = new MockChannel()
    val conn = H2ClientConnection(channel).assertSuccess

    val goawayFrame = H2FrameCodec.goAwayFrame(10, H2ErrorCode.NoError, "goodbye".getBytes("UTF-8"))
    conn.writeFrame(goawayFrame).assertSuccess

    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case goaway: GoAwayFrame =>
        assertEquals(goaway.lastStreamId, 10)
        assertEquals(goaway.errorCode, H2ErrorCode.NoError.value)
        assertEquals(new String(goaway.debugData, "UTF-8"), "goodbye")
      case other =>
        fail(s"Expected GoAwayFrame, got ${other.getClass}")
    }
  }

  // ============================================================================
  // Shutdown
  // ============================================================================

  test("shutdown sends GOAWAY frame") {
    val channel = new MockChannel()
    val conn = H2ClientConnection(channel).assertSuccess

    conn.shutdown(H2ErrorCode.NoError, "graceful shutdown").assertSuccess

    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case goaway: GoAwayFrame =>
        assertEquals(goaway.errorCode, H2ErrorCode.NoError.value)
        assertEquals(new String(goaway.debugData, "UTF-8"), "graceful shutdown")
      case other =>
        fail(s"Expected GoAwayFrame, got ${other.getClass}")
    }
  }

  // ============================================================================
  // ALPN Protocol Constants
  // ============================================================================

  test("SSLSocketChannel.Http2Protocols contains h2 and http/1.1") {
    import net.ghoula.eru.http.SSLSocketChannel

    val protocols = SSLSocketChannel.Http2Protocols
    assert(protocols.contains("h2"), "Should contain h2")
    assert(protocols.contains("http/1.1"), "Should contain http/1.1")
    assertEquals(protocols.head, "h2") // h2 should be preferred
  }

  test("SSLSocketChannel.Http1Protocols contains only http/1.1") {
    import net.ghoula.eru.http.SSLSocketChannel

    val protocols = SSLSocketChannel.Http1Protocols
    assertEquals(protocols.length, 1)
    assertEquals(protocols.head, "http/1.1")
  }
}
