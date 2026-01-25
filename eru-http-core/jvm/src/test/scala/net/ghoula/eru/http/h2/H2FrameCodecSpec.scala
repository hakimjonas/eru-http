package net.ghoula.eru.http.h2

import munit.FunSuite

import java.nio.ByteBuffer

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 frame encoding and decoding per RFC 9113 Section 4. */
class H2FrameCodecSpec extends FunSuite {

  // ============================================================================
  // Frame Header Tests
  // ============================================================================

  test("Parse frame header from valid bytes") {
    // Build a header: length=100, type=DATA, flags=0x01, streamId=5
    val buffer = ByteBuffer.allocate(9)
    buffer.put(0.toByte) // length high byte
    buffer.put(0.toByte) // length mid byte
    buffer.put(100.toByte) // length low byte
    buffer.put(0.toByte) // type = DATA
    buffer.put(0x01.toByte) // flags = END_STREAM
    buffer.putInt(5) // stream ID
    buffer.flip()

    val header = H2FrameCodec.parseHeader(buffer).assertSuccess

    assertEquals(header.length, 100)
    assertEquals(header.frameType, H2Frame.FrameType.Data)
    assertEquals(header.flags, 0x01.toByte)
    assertEquals(header.streamId, 5)
    assert(header.isEndStream)
  }

  test("Parse header fails on insufficient bytes") {
    val buffer = ByteBuffer.allocate(5) // Only 5 bytes, need 9
    buffer.flip()

    val result = H2FrameCodec.parseHeader(buffer)
    assert(result.isFailure)
  }

  test("Frame header R bit is ignored for stream ID") {
    val buffer = ByteBuffer.allocate(9)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    // Set R bit (0x80000000) along with stream ID 42
    buffer.putInt(0x80000000 | 42)
    buffer.flip()

    val header = H2FrameCodec.parseHeader(buffer).assertSuccess
    assertEquals(header.streamId, 42)
  }

  // ============================================================================
  // DATA Frame Tests
  // ============================================================================

  test("Round-trip DATA frame") {
    val originalData = "Hello, HTTP/2!".getBytes("UTF-8")
    val frame = H2FrameCodec.dataFrame(1, originalData, endStream = true)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: DataFrame =>
        assertEquals(decoded.streamId, 1)
        assertEquals(decoded.data.toList, originalData.toList)
        assert(decoded.isEndStream)
      case other =>
        fail(s"Expected DataFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("DATA frame on stream 0 is rejected") {
    val buffer = ByteBuffer.allocate(13)
    // Header: length=4, type=DATA, flags=0, streamId=0
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(4.toByte)
    buffer.put(H2Frame.FrameType.Data)
    buffer.put(0.toByte)
    buffer.putInt(0) // Stream 0 - invalid!
    buffer.put(Array[Byte](1, 2, 3, 4))
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // HEADERS Frame Tests
  // ============================================================================

  test("Round-trip HEADERS frame") {
    val headerBlock = Array[Byte](0x82.toByte, 0x86.toByte, 0x84.toByte) // Indexed headers
    val frame = H2FrameCodec.headersFrame(1, headerBlock, endStream = false, endHeaders = true)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: HeadersFrame =>
        assertEquals(decoded.streamId, 1)
        assertEquals(decoded.headerBlock.toList, headerBlock.toList)
        assert(decoded.isEndHeaders)
        assert(!decoded.isEndStream)
      case other =>
        fail(s"Expected HeadersFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("HEADERS frame on stream 0 is rejected") {
    val buffer = ByteBuffer.allocate(12)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(3.toByte)
    buffer.put(H2Frame.FrameType.Headers)
    buffer.put(0x04.toByte) // END_HEADERS
    buffer.putInt(0) // Stream 0 - invalid!
    buffer.put(Array[Byte](1, 2, 3))
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // SETTINGS Frame Tests
  // ============================================================================

  test("Round-trip SETTINGS frame with multiple parameters") {
    val settings = List(
      SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 8192),
      SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 100),
      SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 32768)
    )
    val frame = H2FrameCodec.settingsFrame(settings)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: SettingsFrame =>
        assertEquals(decoded.streamId, 0)
        assertEquals(decoded.settings.length, 3)
        assertEquals(decoded.settings(0).id, H2Frame.SettingsParam.HeaderTableSize)
        assertEquals(decoded.settings(0).value, 8192)
        assertEquals(decoded.settings(1).id, H2Frame.SettingsParam.MaxConcurrentStreams)
        assertEquals(decoded.settings(1).value, 100)
        assert(!decoded.isAck)
      case other =>
        fail(s"Expected SettingsFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("Round-trip SETTINGS ACK frame") {
    val frame = H2FrameCodec.settingsAckFrame()

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: SettingsFrame =>
        assertEquals(decoded.streamId, 0)
        assertEquals(decoded.settings.length, 0)
        assert(decoded.isAck)
      case other =>
        fail(s"Expected SettingsFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("SETTINGS frame on non-zero stream is rejected") {
    val buffer = ByteBuffer.allocate(9)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(0.toByte) // Empty payload
    buffer.put(H2Frame.FrameType.Settings)
    buffer.put(0.toByte)
    buffer.putInt(1) // Stream 1 - invalid!
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  test("SETTINGS ACK with non-empty payload is rejected") {
    val buffer = ByteBuffer.allocate(15)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(6.toByte) // 6 bytes payload
    buffer.put(H2Frame.FrameType.Settings)
    buffer.put(0x01.toByte) // ACK flag
    buffer.putInt(0)
    // Add one settings entry
    buffer.put(0.toByte)
    buffer.put(1.toByte)
    buffer.putInt(4096)
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // PING Frame Tests
  // ============================================================================

  test("Round-trip PING frame") {
    val data = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    val frame = H2FrameCodec.pingFrame(data, ack = false)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: PingFrame =>
        assertEquals(decoded.streamId, 0)
        assertEquals(decoded.data.toList, data.toList)
        assert(!decoded.isAck)
      case other =>
        fail(s"Expected PingFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("Round-trip PING ACK frame") {
    val data = Array[Byte](8, 7, 6, 5, 4, 3, 2, 1)
    val frame = H2FrameCodec.pingFrame(data, ack = true)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: PingFrame =>
        assert(decoded.isAck)
      case other =>
        fail(s"Expected PingFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("PING frame with wrong size is rejected") {
    val buffer = ByteBuffer.allocate(13)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(4.toByte) // Only 4 bytes - should be 8!
    buffer.put(H2Frame.FrameType.Ping)
    buffer.put(0.toByte)
    buffer.putInt(0)
    buffer.put(Array[Byte](1, 2, 3, 4))
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // GOAWAY Frame Tests
  // ============================================================================

  test("Round-trip GOAWAY frame") {
    val debugData = "Connection closed".getBytes("UTF-8")
    val frame = H2FrameCodec.goAwayFrame(100, H2ErrorCode.NoError, debugData)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: GoAwayFrame =>
        assertEquals(decoded.streamId, 0)
        assertEquals(decoded.lastStreamId, 100)
        assertEquals(decoded.errorCode, H2ErrorCode.NoError.value)
        assertEquals(new String(decoded.debugData, "UTF-8"), "Connection closed")
      case other =>
        fail(s"Expected GoAwayFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("GOAWAY frame without debug data") {
    val frame = H2FrameCodec.goAwayFrame(50, H2ErrorCode.ProtocolError)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: GoAwayFrame =>
        assertEquals(decoded.lastStreamId, 50)
        assertEquals(decoded.errorCode, H2ErrorCode.ProtocolError.value)
        assertEquals(decoded.debugData.length, 0)
      case other =>
        fail(s"Expected GoAwayFrame, got ${other.getClass.getSimpleName}")
    }
  }

  // ============================================================================
  // RST_STREAM Frame Tests
  // ============================================================================

  test("Round-trip RST_STREAM frame") {
    val frame = H2FrameCodec.rstStreamFrame(3, H2ErrorCode.Cancel)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: RstStreamFrame =>
        assertEquals(decoded.streamId, 3)
        assertEquals(decoded.errorCode, H2ErrorCode.Cancel.value)
        assertEquals(decoded.h2ErrorCode, Some(H2ErrorCode.Cancel))
      case other =>
        fail(s"Expected RstStreamFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("RST_STREAM frame on stream 0 is rejected") {
    val buffer = ByteBuffer.allocate(13)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(4.toByte)
    buffer.put(H2Frame.FrameType.RstStream)
    buffer.put(0.toByte)
    buffer.putInt(0) // Stream 0 - invalid!
    buffer.putInt(0)
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // WINDOW_UPDATE Frame Tests
  // ============================================================================

  test("Round-trip WINDOW_UPDATE frame on connection") {
    val frame = H2FrameCodec.windowUpdateFrame(0, 65535)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: WindowUpdateFrame =>
        assertEquals(decoded.streamId, 0)
        assertEquals(decoded.windowSizeIncrement, 65535)
      case other =>
        fail(s"Expected WindowUpdateFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("Round-trip WINDOW_UPDATE frame on stream") {
    val frame = H2FrameCodec.windowUpdateFrame(5, 32768)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: WindowUpdateFrame =>
        assertEquals(decoded.streamId, 5)
        assertEquals(decoded.windowSizeIncrement, 32768)
      case other =>
        fail(s"Expected WindowUpdateFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("WINDOW_UPDATE with zero increment is rejected") {
    val buffer = ByteBuffer.allocate(13)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(4.toByte)
    buffer.put(H2Frame.FrameType.WindowUpdate)
    buffer.put(0.toByte)
    buffer.putInt(1)
    buffer.putInt(0) // Zero increment - invalid!
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // CONTINUATION Frame Tests
  // ============================================================================

  test("Round-trip CONTINUATION frame") {
    val headerBlock = Array[Byte](0x40.toByte, 0x88.toByte, 0x25.toByte)
    val frame = H2FrameCodec.continuationFrame(1, headerBlock, endHeaders = true)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: ContinuationFrame =>
        assertEquals(decoded.streamId, 1)
        assertEquals(decoded.headerBlock.toList, headerBlock.toList)
        assert(decoded.isEndHeaders)
      case other =>
        fail(s"Expected ContinuationFrame, got ${other.getClass.getSimpleName}")
    }
  }

  test("CONTINUATION frame on stream 0 is rejected") {
    val buffer = ByteBuffer.allocate(12)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(3.toByte)
    buffer.put(H2Frame.FrameType.Continuation)
    buffer.put(0x04.toByte)
    buffer.putInt(0) // Stream 0 - invalid!
    buffer.put(Array[Byte](1, 2, 3))
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer)
    assert(result.isFailure)
  }

  // ============================================================================
  // PRIORITY Frame Tests (deprecated but must be handled)
  // ============================================================================

  test("Round-trip PRIORITY frame") {
    val header = H2FrameHeader(5, H2Frame.FrameType.Priority, 0, 3)
    val frame = PriorityFrame(header, streamDependency = 1, exclusive = true, weight = 128)

    val encoded = H2FrameCodec.encode(frame)

    H2FrameCodec.parseFrame(encoded).assertSuccess match {
      case decoded: PriorityFrame =>
        assertEquals(decoded.streamId, 3)
        assertEquals(decoded.streamDependency, 1)
        assert(decoded.exclusive)
        assertEquals(decoded.weight, 128)
      case other =>
        fail(s"Expected PriorityFrame, got ${other.getClass.getSimpleName}")
    }
  }

  // ============================================================================
  // Unknown Frame Type Tests
  // ============================================================================

  test("Unknown frame type is parsed as UnknownFrame") {
    val buffer = ByteBuffer.allocate(13)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.put(4.toByte)
    buffer.put(0xff.toByte) // Unknown type
    buffer.put(0.toByte)
    buffer.putInt(1)
    buffer.put(Array[Byte](1, 2, 3, 4))
    buffer.flip()

    H2FrameCodec.parseFrame(buffer).assertSuccess match {
      case decoded: UnknownFrame =>
        assertEquals(decoded.header.frameType, 0xff.toByte)
        assertEquals(decoded.payload.toList, List[Byte](1, 2, 3, 4))
      case other =>
        fail(s"Expected UnknownFrame, got ${other.getClass.getSimpleName}")
    }
  }

  // ============================================================================
  // Frame Size Validation Tests
  // ============================================================================

  test("Frame exceeding max size is rejected") {
    val buffer = ByteBuffer.allocate(9)
    buffer.put(0x01.toByte) // Length = 65536 (exceeds default 16384)
    buffer.put(0x00.toByte)
    buffer.put(0x00.toByte)
    buffer.put(0.toByte)
    buffer.put(0.toByte)
    buffer.putInt(1)
    buffer.flip()

    val result = H2FrameCodec.parseFrame(buffer, maxFrameSize = 16384)
    assert(result.isFailure)
  }

  // ============================================================================
  // Connection Preface Tests
  // ============================================================================

  test("Connection preface has correct value") {
    val expected = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
    assertEquals(new String(H2Frame.ConnectionPreface, "US-ASCII"), expected)
    assertEquals(H2Frame.ConnectionPreface.length, 24)
  }
}
