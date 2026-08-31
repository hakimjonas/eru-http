package net.ghoula.eru.http.websocket

import net.ghoula.eru.http.Bytes

/** High-level WebSocket message abstraction.
  *
  * Unlike WebSocketFrame which represents raw protocol frames (including fragmentation and control
  * frames), WebSocketMessage represents complete application-level messages.
  *
  * A single message may span multiple frames when fragmented; fragments are reassembled before a
  * message is delivered. Messages only represent the two data types: Text and Binary.
  */
sealed trait WebSocketMessage

object WebSocketMessage {

  /** Text message containing UTF-8 encoded string data.
    *
    * Per RFC 6455 Section 5.6, text messages are interpreted as UTF-8 encoded text. Invalid UTF-8
    * sequences result in connection closure with code 1007 (Invalid Payload Data).
    *
    * @param value
    *   the text content
    */
  final case class Text(value: String) extends WebSocketMessage

  /** Binary message containing arbitrary byte data.
    *
    * Per RFC 6455 Section 5.6, binary messages are delivered as-is without any interpretation.
    *
    * @param value
    *   the binary content
    */
  final case class Binary(value: Bytes) extends WebSocketMessage

  extension (message: WebSocketMessage) {

    /** Check if this is a text message.
      */
    def isText: Boolean = message match {
      case _: Text => true
      case _: Binary => false
    }

    /** Check if this is a binary message.
      */
    def isBinary: Boolean = !isText

    /** Get the message size in bytes.
      */
    def size: Int = message match {
      case Text(v) => v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      case Binary(v) => v.length
    }
  }
}
