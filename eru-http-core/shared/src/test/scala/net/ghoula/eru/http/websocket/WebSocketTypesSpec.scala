package net.ghoula.eru.http.websocket

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*

/** Tests for the shared WebSocket type model.
  *
  * `WebSocketMessage.size` counts UTF-8 bytes: plain ASCII is 1 byte per character, while e.g.
  * e-acute is 2 bytes.
  */
class WebSocketTypesSpec extends FunSuite {

  test("WebSocketOpcode values are correct per RFC 6455") {
    assertEquals(WebSocketOpcode.Continuation.value, 0x0)
    assertEquals(WebSocketOpcode.Text.value, 0x1)
    assertEquals(WebSocketOpcode.Binary.value, 0x2)
    assertEquals(WebSocketOpcode.Close.value, 0x8)
    assertEquals(WebSocketOpcode.Ping.value, 0x9)
    assertEquals(WebSocketOpcode.Pong.value, 0xa)
  }

  test("WebSocketOpcode.fromValue parses valid opcodes") {
    assertEquals(WebSocketOpcode.fromValue(0x0), Some(WebSocketOpcode.Continuation))
    assertEquals(WebSocketOpcode.fromValue(0x1), Some(WebSocketOpcode.Text))
    assertEquals(WebSocketOpcode.fromValue(0x2), Some(WebSocketOpcode.Binary))
    assertEquals(WebSocketOpcode.fromValue(0x8), Some(WebSocketOpcode.Close))
    assertEquals(WebSocketOpcode.fromValue(0x9), Some(WebSocketOpcode.Ping))
    assertEquals(WebSocketOpcode.fromValue(0xa), Some(WebSocketOpcode.Pong))
  }

  test("WebSocketOpcode.fromValue returns None for reserved opcodes") {
    assertEquals(WebSocketOpcode.fromValue(0x3), None)
    assertEquals(WebSocketOpcode.fromValue(0x4), None)
    assertEquals(WebSocketOpcode.fromValue(0x7), None)

    assertEquals(WebSocketOpcode.fromValue(0xb), None)
    assertEquals(WebSocketOpcode.fromValue(0xf), None)
  }

  test("WebSocketOpcode.isControl identifies control frames") {
    assert(WebSocketOpcode.Close.isControl)
    assert(WebSocketOpcode.Ping.isControl)
    assert(WebSocketOpcode.Pong.isControl)

    assert(!WebSocketOpcode.Text.isControl)
    assert(!WebSocketOpcode.Binary.isControl)
    assert(!WebSocketOpcode.Continuation.isControl)
  }

  test("WebSocketOpcode.isData identifies data frames") {
    assert(WebSocketOpcode.Text.isData)
    assert(WebSocketOpcode.Binary.isData)
    assert(WebSocketOpcode.Continuation.isData)

    assert(!WebSocketOpcode.Close.isData)
    assert(!WebSocketOpcode.Ping.isData)
    assert(!WebSocketOpcode.Pong.isData)
  }

  test("WebSocketCloseCode predefined values are correct per RFC 6455") {
    assertEquals(WebSocketCloseCode.NormalClosure.value, 1000)
    assertEquals(WebSocketCloseCode.GoingAway.value, 1001)
    assertEquals(WebSocketCloseCode.ProtocolError.value, 1002)
    assertEquals(WebSocketCloseCode.UnsupportedData.value, 1003)
    assertEquals(WebSocketCloseCode.NoStatusReceived.value, 1005)
    assertEquals(WebSocketCloseCode.AbnormalClosure.value, 1006)
    assertEquals(WebSocketCloseCode.InvalidPayloadData.value, 1007)
    assertEquals(WebSocketCloseCode.PolicyViolation.value, 1008)
    assertEquals(WebSocketCloseCode.MessageTooBig.value, 1009)
    assertEquals(WebSocketCloseCode.MandatoryExtension.value, 1010)
    assertEquals(WebSocketCloseCode.InternalError.value, 1011)
    assertEquals(WebSocketCloseCode.TLSHandshakeFailure.value, 1015)
  }

  test("WebSocketCloseCode.apply validates codes") {
    assert(WebSocketCloseCode(1000).isSuccess)
    assert(WebSocketCloseCode(1001).isSuccess)
    assert(WebSocketCloseCode(1011).isSuccess)

    assert(WebSocketCloseCode(3000).isSuccess)
    assert(WebSocketCloseCode(3500).isSuccess)
    assert(WebSocketCloseCode(3999).isSuccess)

    assert(WebSocketCloseCode(4000).isSuccess)
    assert(WebSocketCloseCode(4500).isSuccess)
    assert(WebSocketCloseCode(4999).isSuccess)

    assert(WebSocketCloseCode(1004).isFailure)
    assert(WebSocketCloseCode(1005).isFailure)
    assert(WebSocketCloseCode(1006).isFailure)
    assert(WebSocketCloseCode(1015).isFailure)

    assert(WebSocketCloseCode(999).isFailure)
    assert(WebSocketCloseCode(1016).isFailure)
    assert(WebSocketCloseCode(2999).isFailure)
    assert(WebSocketCloseCode(5000).isFailure)
  }

  test("WebSocketCloseCode.fromValue parses valid codes") {
    assertEquals(WebSocketCloseCode.fromValue(1000).map(_.value), Some(1000))
    assertEquals(WebSocketCloseCode.fromValue(1015).map(_.value), Some(1015))

    assertEquals(WebSocketCloseCode.fromValue(3000).map(_.value), Some(3000))
    assertEquals(WebSocketCloseCode.fromValue(4999).map(_.value), Some(4999))

    assertEquals(WebSocketCloseCode.fromValue(999), None)
    assertEquals(WebSocketCloseCode.fromValue(5000), None)
  }

  test("WebSocketCloseCode.isNormal identifies normal closure codes") {
    assert(WebSocketCloseCode.NormalClosure.isNormal)
    assert(WebSocketCloseCode.GoingAway.isNormal)

    assert(!WebSocketCloseCode.ProtocolError.isNormal)
    assert(!WebSocketCloseCode.InternalError.isNormal)
  }

  test("WebSocketCloseCode.isReserved identifies reserved codes") {
    assert(WebSocketCloseCode.NoStatusReceived.isReserved)
    assert(WebSocketCloseCode.AbnormalClosure.isReserved)
    assert(WebSocketCloseCode.TLSHandshakeFailure.isReserved)

    assert(!WebSocketCloseCode.NormalClosure.isReserved)
    assert(!WebSocketCloseCode.ProtocolError.isReserved)
  }

  test("WebSocketCloseCode.description returns human-readable descriptions") {
    assertEquals(WebSocketCloseCode.NormalClosure.description, "Normal Closure")
    assertEquals(WebSocketCloseCode.ProtocolError.description, "Protocol Error")
    assertEquals(WebSocketCloseCode.MessageTooBig.description, "Message Too Big")
  }

  test("WebSocketFrame.Text creates correct frame") {
    val frame = WebSocketFrame.Text("hello", fin = true)

    assert(frame.fin)
    assertEquals(frame.opcode, WebSocketOpcode.Text)
    assertEquals(frame.text, "hello")
  }

  test("WebSocketFrame.Binary creates correct frame") {
    val data = Bytes.fromString("binary data", Charset.UTF8)
    val frame = WebSocketFrame.Binary(data, fin = true)

    assert(frame.fin)
    assertEquals(frame.opcode, WebSocketOpcode.Binary)
    assert(frame.data === data)
  }

  test("WebSocketFrame.Close encodes status code and reason") {
    val frame = WebSocketFrame.Close(Some(WebSocketCloseCode.NormalClosure), Some("goodbye"))
    val payload = frame.payload.toArray

    assertEquals((payload(0) & 0xff), 0x03)
    assertEquals((payload(1) & 0xff), 0xe8)

    val reason = new String(payload, 2, payload.length - 2, java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(reason, "goodbye")
  }

  test("WebSocketFrame.Close with no code has empty payload") {
    val frame = WebSocketFrame.Close(None, None)
    assertEquals(frame.payload.length, 0)
  }

  test("WebSocketFrame.Ping and Pong have correct opcodes") {
    val ping = WebSocketFrame.Ping(Bytes.empty)
    val pong = WebSocketFrame.Pong(Bytes.empty)

    assertEquals(ping.opcode, WebSocketOpcode.Ping)
    assertEquals(pong.opcode, WebSocketOpcode.Pong)
    assert(ping.fin)
    assert(pong.fin)
  }

  test("WebSocketMessage.Text stores string value") {
    val msg = WebSocketMessage.Text("hello world")
    assert(msg.isText)
    assert(!msg.isBinary)
    assertEquals(msg.value, "hello world")
  }

  test("WebSocketMessage.Binary stores bytes value") {
    val data = Bytes.fromString("binary", Charset.UTF8)
    val msg = WebSocketMessage.Binary(data)
    assert(msg.isBinary)
    assert(!msg.isText)
    assert(msg.value === data)
  }

  test("WebSocketMessage.size returns correct byte count") {
    val textMsg = WebSocketMessage.Text("hello")
    assertEquals(textMsg.size, 5)

    val utf8Msg = WebSocketMessage.Text("\u00e9")
    assertEquals(utf8Msg.size, 2)

    val binaryMsg = WebSocketMessage.Binary(Bytes.fromArray(Array[Byte](1, 2, 3, 4, 5)))
    assertEquals(binaryMsg.size, 5)
  }

  test("WebSocketError.errorMessage provides descriptive messages") {
    val handshakeFailed = WebSocketError.HandshakeFailed("invalid key", "RFC 6455 Section 4")
    assert(handshakeFailed.errorMessage.contains("handshake failed"))
    assert(handshakeFailed.errorMessage.contains("invalid key"))

    val invalidFrame = WebSocketError.InvalidFrame("bad opcode", "RFC 6455 Section 5")
    assert(invalidFrame.errorMessage.contains("Invalid WebSocket frame"))

    val connectionClosed = WebSocketError.ConnectionClosed(
      Some(WebSocketCloseCode.NormalClosure),
      Some("done"),
      clean = true
    )
    assert(connectionClosed.errorMessage.contains("closed"))
    assert(connectionClosed.errorMessage.contains("clean"))
    assert(connectionClosed.errorMessage.contains("1000"))
  }

  test("WebSocketError.suggestedCloseCode returns appropriate codes") {
    assertEquals(
      WebSocketError.InvalidFrame("test", "RFC").suggestedCloseCode,
      Some(WebSocketCloseCode.ProtocolError)
    )
    assertEquals(
      WebSocketError.InvalidUTF8("test").suggestedCloseCode,
      Some(WebSocketCloseCode.InvalidPayloadData)
    )
    assertEquals(
      WebSocketError.MessageTooLarge(100, 50).suggestedCloseCode,
      Some(WebSocketCloseCode.MessageTooBig)
    )
    assertEquals(WebSocketError.HandshakeFailed("test", "RFC").suggestedCloseCode, None)
  }

  test("WebSocketError.toException creates Exception") {
    val error = WebSocketError.NetworkError("connection lost", None)
    val ex = error.toException
    assert(ex.getMessage.contains("connection lost"))
  }
}
