package net.ghoula.eru.http.websocket

import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.channels.Channels

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*

class WebSocketFrameParserSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  private def createReader(bytes: Array[Byte]): BufferedSocketReader = {
    val channel = Channels.newChannel(new ByteArrayInputStream(bytes))
    new BufferedSocketReader(channel)
  }

  private def mask(data: Array[Byte], key: Array[Byte]): Array[Byte] = {
    val result = new Array[Byte](data.length)
    var i = 0
    while i < data.length do {
      result(i) = (data(i) ^ key(i & 3)).toByte
      i += 1
    }
    result
  }

  test("parseFrame parses unmasked text frame") {
    val payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val frame = Array[Byte](
      0x81.toByte, // FIN=1, opcode=1 (text)
      payload.length.toByte // No mask, length=5
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Text(text, fin) =>
        assert(fin)
        assertEquals(text, "hello")
      case _ => fail("Expected Text frame")
    }
  }

  test("parseFrame parses masked text frame") {
    val payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val maskKey = Array[Byte](0x37, 0xfa.toByte, 0x21, 0x3d)
    val maskedPayload = mask(payload, maskKey)

    val frame = Array[Byte](
      0x81.toByte, // FIN=1, opcode=1 (text)
      (0x80 | payload.length).toByte // Mask=1, length=5
    ) ++ maskKey ++ maskedPayload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = true)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Text(text, fin) =>
        assert(fin)
        assertEquals(text, "hello")
      case _ => fail("Expected Text frame")
    }
  }

  test("parseFrame parses binary frame") {
    val payload = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05)
    val frame = Array[Byte](
      0x82.toByte, // FIN=1, opcode=2 (binary)
      payload.length.toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Binary(data, fin) =>
        assert(fin)
        assertEquals(data.toArray.toList, payload.toList)
      case _ => fail("Expected Binary frame")
    }
  }

  test("parseFrame parses Close frame with code and reason") {
    val closeCode = 1000 // Normal closure
    val reason = "goodbye"
    val reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val payload = Array[Byte](
      ((closeCode >> 8) & 0xff).toByte,
      (closeCode & 0xff).toByte
    ) ++ reasonBytes

    val frame = Array[Byte](
      0x88.toByte, // FIN=1, opcode=8 (close)
      payload.length.toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Close(code, reasonOpt) =>
        assertEquals(code.map(_.value), Some(1000))
        assertEquals(reasonOpt, Some("goodbye"))
      case _ => fail("Expected Close frame")
    }
  }

  test("parseFrame parses Ping frame") {
    val payload = "ping".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val frame = Array[Byte](
      0x89.toByte, // FIN=1, opcode=9 (ping)
      payload.length.toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Ping(data) =>
        assertEquals(data.toArray.toList, payload.toList)
      case _ => fail("Expected Ping frame")
    }
  }

  test("parseFrame parses Pong frame") {
    val payload = "pong".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val frame = Array[Byte](
      0x8a.toByte, // FIN=1, opcode=10 (pong)
      payload.length.toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Pong(data) =>
        assertEquals(data.toArray.toList, payload.toList)
      case _ => fail("Expected Pong frame")
    }
  }

  test("parseFrame parses 16-bit extended payload length") {
    val payloadSize = 1000 // > 125, needs 16-bit extended length
    val payload = new Array[Byte](payloadSize)
    java.util.Arrays.fill(payload, 0x41.toByte) // 'A'

    val frame = Array[Byte](
      0x82.toByte, // FIN=1, opcode=2 (binary)
      126.toByte, // Extended 16-bit length
      ((payloadSize >> 8) & 0xff).toByte,
      (payloadSize & 0xff).toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 10000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Binary(data, _) =>
        assertEquals(data.length, payloadSize)
      case _ => fail("Expected Binary frame")
    }
  }

  test("parseFrame rejects frame when mask expected but not present") {
    val payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val frame = Array[Byte](
      0x81.toByte,
      payload.length.toByte // No mask bit
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = true)
    val err = result.assertFailure
    err match {
      case WebSocketError.ProtocolViolation(msg, _) =>
        assert(msg.contains("must be masked"))
      case _ => fail(s"Expected ProtocolViolation, got $err")
    }
  }

  test("parseFrame rejects frame when mask not expected but present") {
    val payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val maskKey = Array[Byte](0x37, 0xfa.toByte, 0x21, 0x3d)
    val maskedPayload = mask(payload, maskKey)

    val frame = Array[Byte](
      0x81.toByte,
      (0x80 | payload.length).toByte // Mask bit set
    ) ++ maskKey ++ maskedPayload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.ProtocolViolation(msg, _) =>
        assert(msg.contains("must not be masked"))
      case _ => fail(s"Expected ProtocolViolation, got $err")
    }
  }

  test("parseFrame rejects unknown opcode") {
    val frame = Array[Byte](
      0x83.toByte, // FIN=1, opcode=3 (reserved)
      0x00.toByte
    )

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("Unknown opcode"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("parseFrame rejects RSV bits set") {
    val frame = Array[Byte](
      0xc1.toByte, // FIN=1, RSV1=1, opcode=1
      0x00.toByte
    )

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("RSV"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("parseFrame rejects control frame with payload > 125 bytes") {
    val payload = new Array[Byte](126) // Too large for control frame
    java.util.Arrays.fill(payload, 0x00.toByte)

    val frame = Array[Byte](
      0x89.toByte, // Ping
      126.toByte, // Extended length
      0x00.toByte,
      126.toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("Control frame payload too large"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("parseFrame rejects fragmented control frame") {
    val frame = Array[Byte](
      0x09.toByte, // FIN=0, opcode=9 (ping) - fragmented!
      0x00.toByte
    )

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("must not be fragmented"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("parseFrame rejects payload exceeding maxPayloadSize") {
    val payloadSize = 1000
    val payload = new Array[Byte](payloadSize)
    java.util.Arrays.fill(payload, 0x41.toByte)

    val frame = Array[Byte](
      0x82.toByte,
      126.toByte,
      ((payloadSize >> 8) & 0xff).toByte,
      (payloadSize & 0xff).toByte
    ) ++ payload

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 500, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.MessageTooLarge(size, max) =>
        assertEquals(size, payloadSize.toLong)
        assertEquals(max, 500L)
      case _ => fail(s"Expected MessageTooLarge, got $err")
    }
  }

  test("parseFrame validates UTF-8 in text frames") {
    val invalidUtf8 = Array[Byte](0xc0.toByte, 0x80.toByte) // Invalid UTF-8 sequence

    val frame = Array[Byte](
      0x81.toByte, // FIN=1, text
      invalidUtf8.length.toByte
    ) ++ invalidUtf8

    val result = WebSocketFrameParser.parseFrame(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidUTF8(_) => () // Expected
      case _ => fail(s"Expected InvalidUTF8, got $err")
    }
  }

  test("parseMessage handles fragmented text message") {
    val part1 = "hello ".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val part2 = "world".getBytes(java.nio.charset.StandardCharsets.UTF_8)

    val frames = Array[Byte](
      0x01.toByte, // FIN=0, opcode=1 (text, start of fragmentation)
      part1.length.toByte
    ) ++ part1 ++ Array[Byte](
      0x80.toByte, // FIN=1, opcode=0 (continuation, final)
      part2.length.toByte
    ) ++ part2

    val result = WebSocketFrameParser.parseMessage(createReader(frames), 1000, expectMasked = false)
    val f = result.assertSuccess
    f match {
      case WebSocketFrame.Text(text, fin) =>
        assert(fin)
        assertEquals(text, "hello world")
      case _ => fail("Expected Text frame")
    }
  }

  test("parseMessage rejects continuation without preceding data frame") {
    val frame = Array[Byte](
      0x80.toByte, // FIN=1, opcode=0 (continuation)
      0x00.toByte
    )

    val result = WebSocketFrameParser.parseMessage(createReader(frame), 1000, expectMasked = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.ProtocolViolation(msg, _) =>
        assert(msg.contains("continuation frame without preceding"))
      case _ => fail(s"Expected ProtocolViolation, got $err")
    }
  }
}
