package net.ghoula.eru.http.h2

/** HPACK codec errors for internal encoder/decoder operations per RFC 7541.
  *
  * These errors represent codec-level failures (invalid encoding, buffer overflow, etc.) as opposed
  * to protocol-level errors (H2Error.CompressionError) that are sent to peers.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541 RFC 7541]]
  */
enum HpackError {

  /** Integer encoding/decoding error.
    *
    * @param reason
    *   description of the error
    * @param rfc
    *   relevant RFC section
    */
  case IntegerError(reason: String, rfc: String = "RFC 7541 Section 5.1")

  /** Huffman encoding/decoding error.
    *
    * @param reason
    *   description of the error
    * @param rfc
    *   relevant RFC section
    */
  case HuffmanError(reason: String, rfc: String = "RFC 7541 Section 5.2")

  /** String literal encoding/decoding error.
    *
    * @param reason
    *   description of the error
    * @param rfc
    *   relevant RFC section
    */
  case StringError(reason: String, rfc: String = "RFC 7541 Section 5.2")

  /** Dynamic table error (invalid index, size violation).
    *
    * @param reason
    *   description of the error
    * @param rfc
    *   relevant RFC section
    */
  case TableError(reason: String, rfc: String = "RFC 7541 Section 2.3")

  /** Header field representation error.
    *
    * @param reason
    *   description of the error
    * @param rfc
    *   relevant RFC section
    */
  case HeaderError(reason: String, rfc: String = "RFC 7541 Section 6")

  /** Buffer error (overflow, underflow, insufficient capacity).
    *
    * @param reason
    *   description of the error
    */
  case BufferError(reason: String)

  /** Get a formatted error message. */
  def message: String = this match {
    case IntegerError(reason, rfc) => s"HPACK integer error: $reason ($rfc)"
    case HuffmanError(reason, rfc) => s"HPACK Huffman error: $reason ($rfc)"
    case StringError(reason, rfc) => s"HPACK string error: $reason ($rfc)"
    case TableError(reason, rfc) => s"HPACK table error: $reason ($rfc)"
    case HeaderError(reason, rfc) => s"HPACK header error: $reason ($rfc)"
    case BufferError(reason) => s"HPACK buffer error: $reason"
  }

  /** Convert this error to an H2Error for protocol-level handling.
    *
    * HPACK decompression failures are COMPRESSION_ERROR per RFC 9113 Section 5.4.3.
    */
  def toH2Error: H2Error = H2Error.CompressionError(message)
}
