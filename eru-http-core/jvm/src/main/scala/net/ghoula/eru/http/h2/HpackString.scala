package net.ghoula.eru.http.h2

import java.nio.ByteBuffer

import net.ghoula.eru.*

/** HPACK string literal encoding and decoding as defined in RFC 7541 Section 5.2.
  *
  * String literals can be encoded in two forms:
  *   - Raw (literal): The string is encoded as-is
  *   - Huffman: The string is compressed using the Huffman code defined in Appendix B
  *
  * Format:
  * {{{
  *   +---+---+---+---+---+---+---+---+
  *   | H |    String Length (7+)     |
  *   +---+---------------------------+
  *   |  String Data (Length octets)  |
  *   +-------------------------------+
  * }}}
  *
  * Where H (bit 7) indicates Huffman encoding: 1 = Huffman, 0 = raw.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#section-5.2 RFC 7541 Section 5.2]]
  */
object HpackString {

  /** Huffman encoding flag (bit 7 of first byte). */
  private val HuffmanFlag: Int = 0x80

  /** Prefix bits for string length (7 bits, with bit 7 for Huffman flag). */
  private val LengthPrefixBits: Int = 7

  /** Encode a string literal.
    *
    * The encoder automatically chooses between Huffman and raw encoding based on which produces the
    * smaller output, as recommended by RFC 7541.
    *
    * @param value
    *   the string to encode
    * @param buffer
    *   the buffer to write to
    * @return
    *   Eru effect that succeeds with bytes written or fails with HpackError
    */
  def encode(value: String, buffer: ByteBuffer): Eru[HpackError, Int] = {
    val bytes = value.getBytes("UTF-8")
    val huffmanLength = HpackHuffman.encodedLength(bytes)

    // Use Huffman encoding if it's smaller
    if huffmanLength < bytes.length then {
      encodeHuffman(bytes, huffmanLength, buffer)
    } else {
      encodeLiteral(bytes, buffer)
    }
  }

  /** Encode a string literal with forced Huffman encoding.
    *
    * @param value
    *   the string to encode
    * @param buffer
    *   the buffer to write to
    * @return
    *   Eru effect that succeeds with bytes written or fails with HpackError
    */
  def encodeHuffmanForced(value: String, buffer: ByteBuffer): Eru[HpackError, Int] = {
    val bytes = value.getBytes("UTF-8")
    val huffmanLength = HpackHuffman.encodedLength(bytes)
    encodeHuffman(bytes, huffmanLength, buffer)
  }

  /** Encode a string literal with forced raw encoding (no Huffman).
    *
    * @param value
    *   the string to encode
    * @param buffer
    *   the buffer to write to
    * @return
    *   Eru effect that succeeds with bytes written or fails with HpackError
    */
  def encodeLiteralForced(value: String, buffer: ByteBuffer): Eru[HpackError, Int] = {
    val bytes = value.getBytes("UTF-8")
    encodeLiteral(bytes, buffer)
  }

  /** Encode using Huffman compression. */
  private def encodeHuffman(bytes: Array[Byte], huffmanLength: Int, buffer: ByteBuffer): Eru[HpackError, Int] = {
    // Encode length with Huffman flag set (H=1)
    HpackInteger.encode(huffmanLength, LengthPrefixBits, HuffmanFlag, buffer).flatMap { _ =>
      val lengthBytes = HpackInteger.encodedLength(huffmanLength, LengthPrefixBits)

      // Encode the Huffman-compressed data
      HpackHuffman.encode(bytes, buffer).map { huffmanBytes =>
        lengthBytes + huffmanBytes
      }
    }
  }

  /** Encode as raw literal (no Huffman). */
  private def encodeLiteral(bytes: Array[Byte], buffer: ByteBuffer): Eru[HpackError, Int] = {
    // Encode length without Huffman flag (H=0)
    HpackInteger.encode(bytes.length, LengthPrefixBits, 0, buffer).flatMap { _ =>
      val lengthBytes = HpackInteger.encodedLength(bytes.length, LengthPrefixBits)

      // Write raw bytes
      if buffer.remaining < bytes.length then {
        Eru.fail(HpackError.BufferError(s"Buffer overflow: need ${bytes.length} bytes, have ${buffer.remaining}"))
      } else {
        buffer.put(bytes): Unit
        Eru.succeed(lengthBytes + bytes.length)
      }
    }
  }

  /** Calculate the encoded length of a string.
    *
    * @param value
    *   the string to encode
    * @return
    *   the number of bytes required (using automatic Huffman selection)
    */
  def encodedLength(value: String): Int = {
    val bytes = value.getBytes("UTF-8")
    val huffmanLength = HpackHuffman.encodedLength(bytes)

    if huffmanLength < bytes.length then {
      // Huffman encoding
      HpackInteger.encodedLength(huffmanLength, LengthPrefixBits) + huffmanLength
    } else {
      // Literal encoding
      HpackInteger.encodedLength(bytes.length, LengthPrefixBits) + bytes.length
    }
  }

  /** Decode a string literal.
    *
    * @param buffer
    *   the buffer to read from
    * @return
    *   Eru effect that succeeds with decoded string or fails with HpackError
    */
  def decode(buffer: ByteBuffer): Eru[HpackError, String] = {
    if buffer.remaining < 1 then {
      Eru.fail(HpackError.StringError("Buffer underflow: no bytes available for string"))
    } else {
      val firstByte = buffer.get()
      val isHuffman = (firstByte & HuffmanFlag) != 0

      // Decode the length using 7-bit prefix
      HpackInteger.decode(firstByte, LengthPrefixBits, buffer).flatMap { length =>
        if buffer.remaining < length then {
          Eru.fail(HpackError.StringError(s"Buffer underflow: need $length bytes for string, have ${buffer.remaining}"))
        } else if isHuffman then {
          decodeHuffman(buffer, length)
        } else {
          decodeLiteral(buffer, length)
        }
      }
    }
  }

  /** Decode Huffman-encoded string. */
  private def decodeHuffman(buffer: ByteBuffer, length: Int): Eru[HpackError, String] = {
    HpackHuffman.decode(buffer, length).flatMap { bytes =>
      Eru.succeed(new String(bytes, "UTF-8"))
    }
  }

  /** Decode raw literal string. */
  private def decodeLiteral(buffer: ByteBuffer, length: Int): Eru[HpackError, String] = {
    val bytes = new Array[Byte](length)
    buffer.get(bytes)
    Eru.succeed(new String(bytes, "UTF-8"))
  }

  /** Decode a string literal from a byte array.
    *
    * Convenience method for testing.
    *
    * @param bytes
    *   the bytes to decode from
    * @return
    *   Eru effect that succeeds with (decoded string, bytes consumed) or fails with HpackError
    */
  def decodeFromArray(bytes: Array[Byte]): Eru[HpackError, (String, Int)] = {
    val buffer = ByteBuffer.wrap(bytes)
    decode(buffer).map { value =>
      val consumed = buffer.position()
      (value, consumed)
    }
  }
}
