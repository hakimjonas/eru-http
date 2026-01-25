package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 server connection. */
class H2ServerConnectionSpec extends FunSuite {

  // Runtime needed for H2ServerConnection/H2Stream creation
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

    def appendReadData(data: Array[Byte]): Unit = {
      readData = readData ++ data
    }

    def getWrittenData: Array[Byte] = writtenData.toArray

    def clearWrittenData(): Unit = writtenData.clear()

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

  test("H2ServerConnection creates with default settings") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    assertEquals(conn.connection.isClient, false)
    assertEquals(conn.connection.activeStreamCount.assertSuccess, 0)
  }

  test("H2ServerConnection creates with custom settings") {
    val channel = new MockChannel()
    val settings = H2Settings.server(maxConcurrentStreams = 200).assertSuccess
    val conn = H2ServerConnection(channel, settings).assertSuccess

    assertEquals(conn.connection.localSettings.maxConcurrentStreams, 200)
  }

  // ============================================================================
  // Connection Preface
  // ============================================================================

  test("sendConnectionPreface sends SETTINGS frame") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    conn.sendConnectionPreface().assertSuccess

    val written = channel.getWrittenData

    // Server connection preface is just SETTINGS (no magic)
    assert(written.length >= H2Frame.HeaderSize, "Should have sent SETTINGS frame")

    // Parse SETTINGS frame header
    val buffer = ByteBuffer.wrap(written)
    val header = H2FrameCodec.parseHeader(buffer).assertSuccess

    assertEquals(header.frameType, H2Frame.FrameType.Settings)
    assertEquals(header.streamId, 0)
    assert(!header.isAck, "Server SETTINGS should not be ACK")
  }

  test("receiveConnectionPreface validates client magic and SETTINGS") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    // Prepare client preface: magic + SETTINGS
    val clientSettings = List(
      SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 50),
      SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 32768)
    )
    val settingsFrame = H2FrameCodec.settingsFrame(clientSettings)
    val encodedSettings = H2FrameCodec.encode(settingsFrame)

    // Client preface = magic + SETTINGS
    val settingsBytes = new Array[Byte](encodedSettings.remaining)
    encodedSettings.get(settingsBytes)
    val clientPreface = H2Frame.ConnectionPreface ++ settingsBytes
    channel.setReadData(clientPreface)

    // Receive the preface
    conn.receiveConnectionPreface().assertSuccess

    // Verify peer settings were applied
    val peerSettings = conn.connection.peerSettings.assertSuccess
    assertEquals(peerSettings.maxConcurrentStreams, 50)
    assertEquals(peerSettings.initialWindowSize, 32768)

    // Verify SETTINGS ACK was sent
    val written = channel.getWrittenData
    assert(written.length >= H2Frame.HeaderSize, "Should have sent SETTINGS ACK")

    val ackBuffer = ByteBuffer.wrap(written)
    val ackHeader = H2FrameCodec.parseHeader(ackBuffer).assertSuccess
    assertEquals(ackHeader.frameType, H2Frame.FrameType.Settings)
    assert(ackHeader.isAck, "Should be SETTINGS ACK")
  }

  test("receiveConnectionPreface rejects invalid magic") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    // Send invalid magic
    val invalidPreface = "HTTP/1.1 400 Bad Request".getBytes("UTF-8")
    channel.setReadData(invalidPreface ++ new Array[Byte](24 - invalidPreface.length))

    val result = conn.receiveConnectionPreface()
    assert(result.isFailure, "Should reject invalid magic")
  }

  // ============================================================================
  // Frame I/O
  // ============================================================================

  test("readFrame parses HEADERS frame") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    // Prepare a HEADERS frame (stream 1, from client)
    val headerBlock = Array[Byte](0x82.toByte) // :method = GET (HPACK static index)
    val headersFrame = H2FrameCodec.headersFrame(1, headerBlock, endStream = true, endHeaders = true)
    val encoded = H2FrameCodec.encode(headersFrame)

    val data = new Array[Byte](encoded.remaining)
    encoded.get(data)
    channel.setReadData(data)

    conn.readFrame().assertSuccess match {
      case headers: HeadersFrame =>
        assertEquals(headers.streamId, 1)
        assert(headers.isEndStream, "Should have END_STREAM")
        assert(headers.isEndHeaders, "Should have END_HEADERS")
      case other =>
        fail(s"Expected HeadersFrame, got ${other.getClass}")
    }
  }

  test("writeFrame sends HEADERS frame") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    val headerBlock = Array[Byte](0x88.toByte) // :status = 200 (HPACK static index)
    val headersFrame = H2FrameCodec.headersFrame(1, headerBlock, endStream = true, endHeaders = true)
    conn.writeFrame(headersFrame).assertSuccess

    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case headers: HeadersFrame =>
        assertEquals(headers.streamId, 1)
        assert(headers.isEndStream, "Should have END_STREAM")
      case other =>
        fail(s"Expected HeadersFrame, got ${other.getClass}")
    }
  }

  // ============================================================================
  // Response Sending
  // ============================================================================

  test("sendResponse sends HEADERS with status") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    // Manually create a stream (simulating a received request)
    conn.connection.registerPeerStream(1).assertSuccess

    // Mark stream as having received headers (as if client sent request)
    conn.connection.getStream(1).assertSuccess.get.receiveHeaders(endStream = true).assertSuccess

    // Send response
    conn.sendResponse(1, 200, List(("content-type", "text/plain"))).assertSuccess

    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case headers: HeadersFrame =>
        assertEquals(headers.streamId, 1)
        assert(headers.isEndStream, "Empty body should set END_STREAM")
        assert(headers.isEndHeaders, "Should have END_HEADERS")

        // Decode headers to verify :status
        val headerBuffer = ByteBuffer.wrap(headers.headerBlock)
        val decoded = conn.connection.decodeHeaders(headerBuffer).assertSuccess
        val headerMap = decoded.map { case (name, value, _) => (name, value) }.toMap

        assertEquals(headerMap.get(":status"), Some("200"))
        assertEquals(headerMap.get("content-type"), Some("text/plain"))
      case other =>
        fail(s"Expected HeadersFrame, got ${other.getClass}")
    }
  }

  test("sendResponse sends HEADERS and DATA for body") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    // Manually create and prepare stream
    conn.connection.registerPeerStream(1).assertSuccess
    conn.connection.getStream(1).assertSuccess.get.receiveHeaders(endStream = true).assertSuccess

    val body = "Hello, HTTP/2!".getBytes("UTF-8")
    conn.sendResponse(1, 200, List(("content-length", body.length.toString)), Some(body)).assertSuccess

    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    // First frame should be HEADERS (without END_STREAM)
    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case headers: HeadersFrame =>
        assertEquals(headers.streamId, 1)
        assert(!headers.isEndStream, "HEADERS should not have END_STREAM when body follows")
      case other =>
        fail(s"Expected HeadersFrame, got ${other.getClass}")
    }

    // Second frame should be DATA with END_STREAM
    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case data: DataFrame =>
        assertEquals(data.streamId, 1)
        assert(data.isEndStream, "DATA should have END_STREAM")
        assertEquals(new String(data.data, "UTF-8"), "Hello, HTTP/2!")
      case other =>
        fail(s"Expected DataFrame, got ${other.getClass}")
    }
  }

  // ============================================================================
  // Shutdown
  // ============================================================================

  test("shutdown sends GOAWAY frame") {
    val channel = new MockChannel()
    val conn = H2ServerConnection(channel).assertSuccess

    conn.shutdown(H2ErrorCode.NoError, "server shutting down").assertSuccess

    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case goaway: GoAwayFrame =>
        assertEquals(goaway.errorCode, H2ErrorCode.NoError.value)
        assertEquals(new String(goaway.debugData, "UTF-8"), "server shutting down")
      case other =>
        fail(s"Expected GoAwayFrame, got ${other.getClass}")
    }
  }

  // ============================================================================
  // Full Accept Flow
  // ============================================================================

  test("H2ServerConnection.accept performs full handshake") {
    val channel = new MockChannel()

    // Prepare client preface
    val clientSettings = List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 100))
    val settingsFrame = H2FrameCodec.settingsFrame(clientSettings)
    val encodedSettings = H2FrameCodec.encode(settingsFrame)
    val settingsBytes = new Array[Byte](encodedSettings.remaining)
    encodedSettings.get(settingsBytes)

    val clientPreface = H2Frame.ConnectionPreface ++ settingsBytes
    channel.setReadData(clientPreface)

    // Accept connection
    val conn = H2ServerConnection.accept(channel).assertSuccess

    // Verify handshake completed
    val peerSettings = conn.connection.peerSettings.assertSuccess
    assertEquals(peerSettings.maxConcurrentStreams, 100)

    // Verify server sent SETTINGS and SETTINGS ACK
    val written = channel.getWrittenData
    val buffer = ByteBuffer.wrap(written)

    // First frame: server SETTINGS
    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case settings: SettingsFrame =>
        assert(!settings.isAck, "First should be server SETTINGS")
      case other =>
        fail(s"Expected SettingsFrame, got ${other.getClass}")
    }

    // Second frame: SETTINGS ACK
    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case settings: SettingsFrame =>
        assert(settings.isAck, "Second should be SETTINGS ACK")
      case other =>
        fail(s"Expected SettingsFrame ACK, got ${other.getClass}")
    }
  }
}
