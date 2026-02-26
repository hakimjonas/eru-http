package net.ghoula.eru.http.h2

/** HTTP/2 error codes as defined in RFC 9113 Section 7.
  *
  * Error codes are 32-bit values used in RST_STREAM and GOAWAY frames to indicate why a stream or
  * connection is being terminated.
  */
enum H2ErrorCode(val value: Int) {

  /** Graceful shutdown - no error occurred. */
  case NoError extends H2ErrorCode(0x0)

  /** Protocol error detected. */
  case ProtocolError extends H2ErrorCode(0x1)

  /** Implementation fault. */
  case InternalError extends H2ErrorCode(0x2)

  /** Flow control limits exceeded. */
  case FlowControlError extends H2ErrorCode(0x3)

  /** Settings not acknowledged within timeout. */
  case SettingsTimeout extends H2ErrorCode(0x4)

  /** Frame received for closed stream. */
  case StreamClosed extends H2ErrorCode(0x5)

  /** Frame size incorrect. */
  case FrameSizeError extends H2ErrorCode(0x6)

  /** Stream not processed. */
  case RefusedStream extends H2ErrorCode(0x7)

  /** Stream cancelled. */
  case Cancel extends H2ErrorCode(0x8)

  /** Compression state not updated. */
  case CompressionError extends H2ErrorCode(0x9)

  /** TCP connection established but not usable. */
  case ConnectError extends H2ErrorCode(0xa)

  /** Processing capacity exceeded. */
  case EnhanceYourCalm extends H2ErrorCode(0xb)

  /** Underlying transport properties inadequate. */
  case InadequateSecurity extends H2ErrorCode(0xc)

  /** Endpoint requires HTTP/1.1. */
  case Http11Required extends H2ErrorCode(0xd)
}

object H2ErrorCode {

  /** Parse an error code from its integer value.
    *
    * @param value
    *   the error code value
    * @return
    *   the corresponding error code, or None if unknown
    */
  def fromValue(value: Int): Option[H2ErrorCode] = value match {
    case 0x0 => Some(NoError)
    case 0x1 => Some(ProtocolError)
    case 0x2 => Some(InternalError)
    case 0x3 => Some(FlowControlError)
    case 0x4 => Some(SettingsTimeout)
    case 0x5 => Some(StreamClosed)
    case 0x6 => Some(FrameSizeError)
    case 0x7 => Some(RefusedStream)
    case 0x8 => Some(Cancel)
    case 0x9 => Some(CompressionError)
    case 0xa => Some(ConnectError)
    case 0xb => Some(EnhanceYourCalm)
    case 0xc => Some(InadequateSecurity)
    case 0xd => Some(Http11Required)
    case _ => None
  }

  /** Get an error code by value, defaulting to ProtocolError if unknown.
    *
    * @param value
    *   the 32-bit error code
    * @return
    *   the matching H2ErrorCode, or ProtocolError if unknown
    */
  def fromValueOrProtocolError(value: Int): H2ErrorCode = {
    fromValue(value).getOrElse(H2ErrorCode.ProtocolError)
  }

  /** Get an error code by value, defaulting to NoError if unknown.
    *
    * Per RFC 9113 Section 7, unknown error codes MUST NOT trigger any special behavior. They MAY be
    * treated as equivalent to INTERNAL_ERROR, but we use NoError for graceful handling.
    *
    * @param value
    *   the 32-bit error code
    * @return
    *   the matching H2ErrorCode, or NoError if unknown
    */
  def fromValueOrNoError(value: Int): H2ErrorCode = {
    fromValue(value).getOrElse(H2ErrorCode.NoError)
  }
}

/** HTTP/2 specific errors covering all failure modes in HTTP/2 communication.
  */
enum H2Error {

  /** Connection preface invalid or missing.
    *
    * @param message
    *   description of the preface error
    */
  case InvalidPreface(message: String)

  /** Frame parsing error - frame structure violates protocol.
    *
    * @param message
    *   description of the frame error
    * @param rfc
    *   relevant RFC section
    */
  case InvalidFrame(message: String, rfc: String = "RFC 9113 Section 4")

  /** HPACK decompression error - header block is invalid.
    *
    * @param message
    *   description of the decompression error
    */
  case CompressionError(message: String)

  /** Protocol violation - a protocol rule was violated.
    *
    * @param message
    *   description of the violation
    * @param errorCode
    *   the appropriate HTTP/2 error code
    */
  case ProtocolViolation(message: String, errorCode: H2ErrorCode)

  /** Stream error - error specific to a single stream.
    *
    * @param streamId
    *   the stream that encountered the error
    * @param errorCode
    *   the error code
    * @param message
    *   optional message
    */
  case StreamError(streamId: Int, errorCode: H2ErrorCode, message: Option[String])

  /** Connection error - error that affects the entire connection.
    *
    * @param errorCode
    *   the error code
    * @param message
    *   optional message
    */
  case ConnectionError(errorCode: H2ErrorCode, message: Option[String])

  /** Flow control violation - flow control limits exceeded.
    *
    * @param streamId
    *   the stream (0 for connection-level)
    * @param message
    *   description of the violation
    */
  case FlowControlViolation(streamId: Int, message: String)

  /** Stream state violation - invalid operation for current stream state.
    *
    * @param streamId
    *   the stream that is in an invalid state
    * @param message
    *   description of the violation
    */
  case StreamStateViolation(streamId: Int, message: String)

  /** Settings error - invalid settings value.
    *
    * @param message
    *   description of the settings error
    */
  case SettingsError(message: String)

  /** Network error - I/O error on underlying connection.
    *
    * @param message
    *   description of the network error
    * @param cause
    *   the underlying exception
    */
  case NetworkError(message: String, cause: Option[Throwable] = None)

  /** The message describing this error. */
  def errorMessage: String = this match {
    case InvalidPreface(msg) => s"HTTP/2 invalid preface: $msg"
    case InvalidFrame(msg, rfc) => s"HTTP/2 invalid frame: $msg ($rfc)"
    case CompressionError(msg) => s"HPACK compression error: $msg"
    case ProtocolViolation(msg, code) => s"HTTP/2 protocol violation: $msg (error code: ${code.value})"
    case StreamError(id, code, msg) =>
      val msgStr = msg.map(m => s": $m").getOrElse("")
      s"HTTP/2 stream error on stream $id (${code})$msgStr"
    case ConnectionError(code, msg) =>
      val msgStr = msg.map(m => s": $m").getOrElse("")
      s"HTTP/2 connection error (${code})$msgStr"
    case FlowControlViolation(id, msg) =>
      if id == 0 then s"HTTP/2 connection flow control violation: $msg"
      else s"HTTP/2 stream $id flow control violation: $msg"
    case StreamStateViolation(id, msg) => s"HTTP/2 stream $id state violation: $msg"
    case SettingsError(msg) => s"HTTP/2 settings error: $msg"
    case NetworkError(msg, _) => s"HTTP/2 network error: $msg"
  }

  /** Convert this error to an exception. */
  def toException: H2Exception = this match {
    case NetworkError(_, cause) => H2Exception(errorMessage, cause)
    case _ => H2Exception(errorMessage, None)
  }

  /** Get the H2 error code for this error, if applicable. */
  def h2ErrorCode: Option[H2ErrorCode] = this match {
    case ProtocolViolation(_, code) => Some(code)
    case StreamError(_, code, _) => Some(code)
    case ConnectionError(code, _) => Some(code)
    case FlowControlViolation(_, _) => Some(H2ErrorCode.FlowControlError)
    case StreamStateViolation(_, _) => Some(H2ErrorCode.StreamClosed)
    case CompressionError(_) => Some(H2ErrorCode.CompressionError)
    case InvalidFrame(_, _) => Some(H2ErrorCode.ProtocolError)
    case InvalidPreface(_) => Some(H2ErrorCode.ProtocolError)
    case SettingsError(_) => Some(H2ErrorCode.ProtocolError)
    case NetworkError(_, _) => Some(H2ErrorCode.InternalError)
  }
}

/** Exception wrapper for HTTP/2 errors. */
final case class H2Exception(message: String, cause: Option[Throwable] = None) extends Exception(message, cause.getOrElse(null))
