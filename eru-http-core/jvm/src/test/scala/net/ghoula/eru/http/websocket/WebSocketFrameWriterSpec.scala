package net.ghoula.eru.http.websocket

import munit.FunSuite

import java.io.ByteArrayOutputStream
import java.nio.channels.{Channels, WritableByteChannel}

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*

class WebSocketFrameWriterSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  private def createChannel(): (WritableByteChannel, ByteArrayOutputStream) = {
    val baos = new ByteArrayOutputStream()
    val channel = Channels.newChannel(baos)
    (channel, baos)
  }

  private def unmask(data: Array[Byte], key: Array[Byte]): Array[Byte] = {
    val result = new Array[Byte](data.length)
    var i = 0
    while i < data.length do {
      result(i) = (data(i) ^ key(i & 3)).toByte
      i += 1
    }
    result
  }

  test("writeFrame writes unmasked text frame") {
    val (channel, baos) = createChannel()
    val frame = WebSocketFrame.Text("hello", fin = true)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x81) // FIN=1, opcode=1
    assertEquals(bytes(1) & 0xff, 5) // No mask, length=5
    assertEquals(new String(bytes.drop(2), java.nio.charset.StandardCharsets.UTF_8), "hello")
  }

  test("writeFrame writes masked text frame") {
    val (channel, baos) = createChannel()
    val frame = WebSocketFrame.Text("hello", fin = true)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = true)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x81) // FIN=1, opcode=1
    assertEquals(bytes(1) & 0xff, 0x85) // Mask=1, length=5

    val maskKey = bytes.slice(2, 6)
    val maskedPayload = bytes.drop(6)
    val unmaskedPayload = unmask(maskedPayload, maskKey)
    assertEquals(new String(unmaskedPayload, java.nio.charset.StandardCharsets.UTF_8), "hello")
  }

  test("writeFrame writes binary frame") {
    val (channel, baos) = createChannel()
    val data = Bytes.fromArray(Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05))
    val frame = WebSocketFrame.Binary(data, fin = true)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x82) // FIN=1, opcode=2
    assertEquals(bytes(1) & 0xff, 5) // No mask, length=5
    assertEquals(bytes.drop(2).toList, data.toArray.toList)
  }

  test("writeFrame writes Close frame with code and reason") {
    val (channel, baos) = createChannel()
    val frame = WebSocketFrame.Close(Some(WebSocketCloseCode.NormalClosure), Some("goodbye"))

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x88) // FIN=1, opcode=8
    val payloadLen = bytes(1) & 0xff
    assertEquals(payloadLen, 2 + 7) // 2-byte code + "goodbye"

    val code = ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
    assertEquals(code, 1000)

    val reason = new String(bytes.drop(4), java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(reason, "goodbye")
  }

  test("writeFrame writes Ping frame") {
    val (channel, baos) = createChannel()
    val pingData = Bytes.fromString("ping", Charset.UTF8)
    val frame = WebSocketFrame.Ping(pingData)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x89) // FIN=1, opcode=9
    assertEquals(bytes(1) & 0xff, 4) // length=4
    assertEquals(new String(bytes.drop(2), java.nio.charset.StandardCharsets.UTF_8), "ping")
  }

  test("writeFrame writes Pong frame") {
    val (channel, baos) = createChannel()
    val pongData = Bytes.fromString("pong", Charset.UTF8)
    val frame = WebSocketFrame.Pong(pongData)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x8a) // FIN=1, opcode=10
    assertEquals(bytes(1) & 0xff, 4) // length=4
    assertEquals(new String(bytes.drop(2), java.nio.charset.StandardCharsets.UTF_8), "pong")
  }

  test("writeFrame writes 16-bit extended payload length") {
    val (channel, baos) = createChannel()
    val payloadSize = 1000 // > 125, needs 16-bit extended length
    val data = Bytes.fromArray(new Array[Byte](payloadSize))
    val frame = WebSocketFrame.Binary(data, fin = true)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x82) // FIN=1, opcode=2
    assertEquals(bytes(1) & 0xff, 126) // Extended 16-bit length indicator

    val extLen = ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
    assertEquals(extLen, payloadSize)
    assertEquals(bytes.length, 4 + payloadSize)
  }

  test("writeFrame writes 64-bit extended payload length") {
    val (channel, baos) = createChannel()
    val payloadSize = 70000 // > 65535, needs 64-bit extended length
    val data = Bytes.fromArray(new Array[Byte](payloadSize))
    val frame = WebSocketFrame.Binary(data, fin = true)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x82) // FIN=1, opcode=2
    assertEquals(bytes(1) & 0xff, 127) // Extended 64-bit length indicator

    // Read 64-bit length (big-endian)
    var extLen = 0L
    for i <- 0 until 8 do {
      extLen = (extLen << 8) | (bytes(2 + i) & 0xff)
    }
    assertEquals(extLen, payloadSize.toLong)
    assertEquals(bytes.length, 10 + payloadSize)
  }

  test("writeFrame writes non-final frame") {
    val (channel, baos) = createChannel()
    val frame = WebSocketFrame.Text("part", fin = false)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x01) // FIN=0, opcode=1
  }

  test("writeText writes simple text") {
    val (channel, baos) = createChannel()

    val result = WebSocketFrameWriter.writeText(channel, "hello", mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x81)
    assertEquals(new String(bytes.drop(2), java.nio.charset.StandardCharsets.UTF_8), "hello")
  }

  test("writeText fragments large messages") {
    val (channel, baos) = createChannel()
    val text = "A" * 100 // 100 bytes

    val result = WebSocketFrameWriter.writeText(channel, text, mask = false, maxFrameSize = 30)
    result.assertSuccess
    val bytes = baos.toByteArray

    // First frame: FIN=0, opcode=text
    assertEquals(bytes(0) & 0xff, 0x01) // FIN=0, opcode=1 (text)
    assertEquals(bytes(1) & 0xff, 30) // 30 bytes payload

    // Find continuation frames
    var offset = 2 + 30 // Skip first frame

    // Second frame: FIN=0, opcode=continuation
    assertEquals(bytes(offset) & 0xff, 0x00) // FIN=0, opcode=0 (continuation)
    assertEquals(bytes(offset + 1) & 0xff, 30)
    offset += 2 + 30

    // Third frame: FIN=0, opcode=continuation
    assertEquals(bytes(offset) & 0xff, 0x00) // FIN=0, opcode=0
    assertEquals(bytes(offset + 1) & 0xff, 30)
    offset += 2 + 30

    // Fourth frame: FIN=1, opcode=continuation (final, 10 bytes remaining)
    assertEquals(bytes(offset) & 0xff, 0x80) // FIN=1, opcode=0 (continuation)
    assertEquals(bytes(offset + 1) & 0xff, 10) // 10 bytes remaining
  }

  test("writeBinary writes binary data") {
    val (channel, baos) = createChannel()
    val data = Bytes.fromArray(Array[Byte](0x01, 0x02, 0x03))

    val result = WebSocketFrameWriter.writeBinary(channel, data, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x82) // FIN=1, opcode=2
    assertEquals(bytes.drop(2).toList, List[Byte](0x01, 0x02, 0x03))
  }

  test("writeClose writes close frame") {
    val (channel, baos) = createChannel()

    val result = WebSocketFrameWriter.writeClose(
      channel,
      Some(WebSocketCloseCode.GoingAway),
      Some("bye"),
      mask = false
    )
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x88) // FIN=1, opcode=8

    val code = ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
    assertEquals(code, 1001)
  }

  test("writeClose rejects reason > 123 bytes") {
    val (channel, _) = createChannel()
    val longReason = "A" * 124 // Too long

    val result = WebSocketFrameWriter.writeClose(
      channel,
      Some(WebSocketCloseCode.NormalClosure),
      Some(longReason),
      mask = false
    )
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("too long"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("writePing writes ping frame") {
    val (channel, baos) = createChannel()
    val data = Bytes.fromString("ping", Charset.UTF8)

    val result = WebSocketFrameWriter.writePing(channel, data, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x89)
  }

  test("writePing rejects payload > 125 bytes") {
    val (channel, _) = createChannel()
    val data = Bytes.fromArray(new Array[Byte](126))

    val result = WebSocketFrameWriter.writePing(channel, data, mask = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("too large"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("writePong writes pong frame") {
    val (channel, baos) = createChannel()
    val data = Bytes.fromString("pong", Charset.UTF8)

    val result = WebSocketFrameWriter.writePong(channel, data, mask = false)
    result.assertSuccess
    val bytes = baos.toByteArray
    assertEquals(bytes(0) & 0xff, 0x8a)
  }

  test("writePong rejects payload > 125 bytes") {
    val (channel, _) = createChannel()
    val data = Bytes.fromArray(new Array[Byte](126))

    val result = WebSocketFrameWriter.writePong(channel, data, mask = false)
    val err = result.assertFailure
    err match {
      case WebSocketError.InvalidFrame(msg, _) =>
        assert(msg.contains("too large"))
      case _ => fail(s"Expected InvalidFrame, got $err")
    }
  }

  test("masking XOR is correct") {
    val (channel, baos) = createChannel()
    val frame = WebSocketFrame.Binary(Bytes.fromArray(Array[Byte](0x00, 0x00, 0x00, 0x00)), fin = true)

    val result = WebSocketFrameWriter.writeFrame(channel, frame, mask = true)
    result.assertSuccess
    val bytes = baos.toByteArray
    val maskKey = bytes.slice(2, 6)
    val maskedPayload = bytes.drop(6)

    // When payload is all zeros, masked payload equals mask key (repeated)
    assertEquals(maskedPayload(0), maskKey(0))
    assertEquals(maskedPayload(1), maskKey(1))
    assertEquals(maskedPayload(2), maskKey(2))
    assertEquals(maskedPayload(3), maskKey(3))
  }
}
