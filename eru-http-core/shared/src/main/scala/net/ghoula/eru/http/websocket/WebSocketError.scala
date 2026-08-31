package net.ghoula.eru.http.websocket

/** WebSocket-specific errors as defined by protocol requirements in RFC 6455.
  *
  * These errors cover the common failure modes in WebSocket communication:
  *   - Handshake failures (invalid upgrade, missing headers, wrong accept key)
  *   - Frame parsing errors (invalid opcode, oversized payload, masking violations)
  *   - Protocol violations (fragmentation rules, UTF-8 validation)
  *   - Connection state errors (closed connection, timeout)
  */
enum WebSocketError {

  /** Handshake failed - the HTTP upgrade to WebSocket was rejected or invalid.
    *
    * @param message
    *   description of the handshake failure
    * @param rfc
    *   relevant RFC section
    */
  case HandshakeFailed(message: String, rfc: String = "RFC 6455 Section 4")

  /** Invalid frame received - the frame structure violates the protocol.
    *
    * @param message
    *   description of the frame error
    * @param rfc
    *   relevant RFC section
    */
  case InvalidFrame(message: String, rfc: String = "RFC 6455 Section 5")

  /** Protocol violation - a protocol rule was violated.
    *
    * @param message
    *   description of the violation
    * @param closeCode
    *   the appropriate close code for this violation
    */
  case ProtocolViolation(message: String, closeCode: WebSocketCloseCode)

  /** Connection closed - the WebSocket connection has been closed.
    *
    * @param code
    *   optional close code from the Close frame
    * @param reason
    *   optional reason text from the Close frame
    * @param clean
    *   whether the connection was closed cleanly (via Close handshake)
    */
  case ConnectionClosed(
    code: Option[WebSocketCloseCode],
    reason: Option[String],
    clean: Boolean
  )

  /** Message too large - the message exceeded the configured maximum size.
    *
    * @param size
    *   the size of the message
    * @param maxSize
    *   the configured maximum size
    */
  case MessageTooLarge(size: Long, maxSize: Long)

  /** Invalid UTF-8 - a text message contained invalid UTF-8 sequences.
    *
    * Per RFC 6455 Section 5.6, text frames must contain valid UTF-8 data.
    *
    * @param message
    *   description of the UTF-8 error
    */
  case InvalidUTF8(message: String)

  /** Network error - an I/O error occurred on the underlying connection.
    *
    * @param message
    *   description of the network error
    * @param cause
    *   the underlying exception, if any
    */
  case NetworkError(message: String, cause: Option[Throwable] = None)

  /** Timeout - an operation timed out.
    *
    * @param message
    *   description of what timed out
    */
  case Timeout(message: String)

  /** The message describing this error.
    */
  def errorMessage: String = this match {
    case HandshakeFailed(msg, rfc) => s"WebSocket handshake failed: $msg ($rfc)"
    case InvalidFrame(msg, rfc) => s"Invalid WebSocket frame: $msg ($rfc)"
    case ProtocolViolation(msg, _) => s"WebSocket protocol violation: $msg"
    case ConnectionClosed(code, reason, clean) =>
      val codeStr = code.map(c => s"code=${c.value}").getOrElse("no code")
      val reasonStr = reason.map(r => s", reason=$r").getOrElse("")
      val cleanStr = if clean then "clean" else "unclean"
      s"WebSocket connection closed ($cleanStr, $codeStr$reasonStr)"
    case MessageTooLarge(size, max) => s"WebSocket message too large: $size bytes (max: $max)"
    case InvalidUTF8(msg) => s"Invalid UTF-8 in WebSocket text message: $msg"
    case NetworkError(msg, _) => s"WebSocket network error: $msg"
    case Timeout(msg) => s"WebSocket timeout: $msg"
  }

  /** Convert this error to an exception.
    */
  def toException: Exception = this match {
    case NetworkError(_, cause) => cause.fold(new Exception(errorMessage))(c => new Exception(errorMessage, c))
    case _ => new Exception(errorMessage)
  }

  /** Get the appropriate close code for this error, if applicable.
    *
    * Handshake failures happen before the WebSocket is established and ConnectionClosed errors are
    * already closed, so neither produces a close code.
    */
  def suggestedCloseCode: Option[WebSocketCloseCode] = this match {
    case ProtocolViolation(_, code) => Some(code)
    case InvalidFrame(_, _) => Some(WebSocketCloseCode.ProtocolError)
    case InvalidUTF8(_) => Some(WebSocketCloseCode.InvalidPayloadData)
    case MessageTooLarge(_, _) => Some(WebSocketCloseCode.MessageTooBig)
    case NetworkError(_, _) => Some(WebSocketCloseCode.AbnormalClosure)
    case Timeout(_) => Some(WebSocketCloseCode.AbnormalClosure)
    case HandshakeFailed(_, _) => None
    case ConnectionClosed(_, _, _) => None
  }
}
