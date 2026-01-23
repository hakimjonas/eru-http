package net.ghoula.eru.http.websocket

import net.ghoula.eru.*

/** WebSocket close status code as defined in RFC 6455 Section 7.4.
  *
  * Close codes indicate the reason for closing the WebSocket connection. Codes in the range
  * 1000-2999 are reserved for definition by the protocol, 3000-3999 for libraries/frameworks, and
  * 4000-4999 for application use.
  */
opaque type WebSocketCloseCode = Int

object WebSocketCloseCode {

  /** 1000: Normal closure - the connection successfully completed its purpose.
    */
  val NormalClosure: WebSocketCloseCode = 1000

  /** 1001: Going away - endpoint is going away (e.g., server shutting down, browser navigating
    * away).
    */
  val GoingAway: WebSocketCloseCode = 1001

  /** 1002: Protocol error - endpoint received a frame that violates the WebSocket protocol.
    */
  val ProtocolError: WebSocketCloseCode = 1002

  /** 1003: Unsupported data - endpoint received data of a type it cannot accept.
    */
  val UnsupportedData: WebSocketCloseCode = 1003

  /** 1005: No status received - reserved, MUST NOT be set as a status code in a Close control
    * frame.
    *
    * This is a reserved value that may be used by applications to indicate that no status code was
    * present.
    */
  val NoStatusReceived: WebSocketCloseCode = 1005

  /** 1006: Abnormal closure - reserved, MUST NOT be set as a status code in a Close control frame.
    *
    * This is a reserved value that may be used by applications to indicate the connection was
    * closed abnormally (e.g., without sending or receiving a Close frame).
    */
  val AbnormalClosure: WebSocketCloseCode = 1006

  /** 1007: Invalid frame payload data - endpoint received a message with data inconsistent with the
    * message type (e.g., non-UTF-8 data in a text message).
    */
  val InvalidPayloadData: WebSocketCloseCode = 1007

  /** 1008: Policy violation - endpoint received a message that violates its policy.
    */
  val PolicyViolation: WebSocketCloseCode = 1008

  /** 1009: Message too big - endpoint received a message too big to process.
    */
  val MessageTooBig: WebSocketCloseCode = 1009

  /** 1010: Mandatory extension - client expected the server to negotiate one or more extensions,
    * but the server didn't include them in the response.
    */
  val MandatoryExtension: WebSocketCloseCode = 1010

  /** 1011: Internal error - server encountered an unexpected condition that prevented it from
    * fulfilling the request.
    */
  val InternalError: WebSocketCloseCode = 1011

  /** 1012: Service restart - server is restarting.
    */
  val ServiceRestart: WebSocketCloseCode = 1012

  /** 1013: Try again later - server is overloaded, client should reconnect later.
    */
  val TryAgainLater: WebSocketCloseCode = 1013

  /** 1014: Bad gateway - server acting as gateway received an invalid response.
    */
  val BadGateway: WebSocketCloseCode = 1014

  /** 1015: TLS handshake failure - reserved, MUST NOT be set as a status code in a Close control
    * frame.
    *
    * This is a reserved value that may be used by applications to indicate the connection was
    * closed due to failure to perform a TLS handshake.
    */
  val TLSHandshakeFailure: WebSocketCloseCode = 1015

  /** Create a WebSocketCloseCode from an integer with validation.
    *
    * Per RFC 6455 Section 7.4, valid close codes are:
    *   - 1000-1015: Protocol-defined codes
    *   - 3000-3999: Library/framework codes
    *   - 4000-4999: Application codes
    *
    * Codes 1004, 1005, 1006, and 1015 are reserved and MUST NOT be set by applications.
    *
    * @param value
    *   the close code value
    * @return
    *   a validated WebSocketCloseCode or an InvalidCloseCode error
    */
  def apply(value: Int): Eru[InvalidCloseCode, WebSocketCloseCode] = {
    if isValidCode(value) then Eru.succeed(value)
    else
      Eru.fail(
        InvalidCloseCode(
          value,
          s"Close code $value is not valid per RFC 6455 Section 7.4"
        )
      )
  }

  /** Create a WebSocketCloseCode from an integer without validation.
    *
    * This should only be used when parsing received frames, where reserved codes may appear.
    */
  private[websocket] def unsafeFromInt(value: Int): WebSocketCloseCode = value

  /** Parse a close code from its integer value.
    *
    * Unlike apply(), this accepts any value including reserved codes, returning None only for
    * completely invalid ranges.
    *
    * @param value
    *   the close code value
    * @return
    *   Some(code) if in valid range, None otherwise
    */
  def fromValue(value: Int): Option[WebSocketCloseCode] = {
    if value >= 1000 && value <= 4999 then Some(value)
    else None
  }

  /** Check if a code is valid for sending in a Close frame.
    *
    * Per RFC 6455 Section 7.4.2:
    *   - 1000-1003: Defined status codes (valid)
    *   - 1004: Reserved (MUST NOT be sent)
    *   - 1005: Reserved (never sent on wire)
    *   - 1006: Reserved (never sent on wire)
    *   - 1007-1011: Defined status codes (valid)
    *   - 1012-1014: IANA registered codes (valid)
    *   - 1015: Reserved (never sent on wire)
    *   - 1016-2999: Reserved for future protocol use (MUST NOT be sent)
    *   - 3000-3999: Library/framework codes (valid)
    *   - 4000-4999: Application codes (valid)
    */
  private def isValidCode(value: Int): Boolean = {
    val reserved = Set(1004, 1005, 1006, 1015)
    if reserved.contains(value) then false
    else if value >= 1000 && value <= 1014 then true
    else if value >= 1016 && value <= 2999 then false // Reserved for future use
    else if value >= 3000 && value <= 3999 then true
    else if value >= 4000 && value <= 4999 then true
    else false
  }

  /** Check if a received close code is valid per RFC 6455.
    *
    * This is used to validate close codes received from peers. Invalid codes should be responded to
    * with 1002 (Protocol Error).
    */
  def isValidReceivedCode(value: Int): Boolean = isValidCode(value)

  extension (code: WebSocketCloseCode) {

    /** The numeric value of this close code.
      */
    def value: Int = code

    /** Whether this close code indicates a normal closure.
      */
    def isNormal: Boolean = code == NormalClosure || code == GoingAway

    /** Whether this close code indicates an error.
      */
    def isError: Boolean = code match {
      case NormalClosure | GoingAway => false
      case _ => true
    }

    /** Whether this is a reserved code that should not be sent.
      */
    def isReserved: Boolean = code match {
      case NoStatusReceived | AbnormalClosure | TLSHandshakeFailure => true
      case 1004 => true // Reserved but unused
      case _ => false
    }

    /** Human-readable description of the close code.
      */
    def description: String = code match {
      case NormalClosure => "Normal Closure"
      case GoingAway => "Going Away"
      case ProtocolError => "Protocol Error"
      case UnsupportedData => "Unsupported Data"
      case NoStatusReceived => "No Status Received"
      case AbnormalClosure => "Abnormal Closure"
      case InvalidPayloadData => "Invalid Payload Data"
      case PolicyViolation => "Policy Violation"
      case MessageTooBig => "Message Too Big"
      case MandatoryExtension => "Mandatory Extension"
      case InternalError => "Internal Error"
      case ServiceRestart => "Service Restart"
      case TryAgainLater => "Try Again Later"
      case BadGateway => "Bad Gateway"
      case TLSHandshakeFailure => "TLS Handshake Failure"
      case c if c >= 3000 && c <= 3999 => s"Library/Framework Code ($c)"
      case c if c >= 4000 && c <= 4999 => s"Application Code ($c)"
      case c => s"Unknown Code ($c)"
    }
  }

  /** Error for invalid close codes.
    */
  final case class InvalidCloseCode(
    code: Int,
    reason: String,
    rfc: String = "RFC 6455 Section 7.4"
  ) extends Exception(s"Invalid WebSocket close code $code: $reason ($rfc)")
}
