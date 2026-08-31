package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import scala.annotation.tailrec

import net.ghoula.eru.*

/** HPACK integer encoding and decoding as defined in RFC 7541 Section 5.1.
  *
  * Integers are represented with a variable-length encoding that uses an N-bit prefix. If the value
  * fits within the prefix (i.e., is less than 2^N - 1), it is encoded directly. Otherwise, the
  * prefix is filled with 1s and the remaining value is encoded using a series of octets where the
  * high bit indicates continuation.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#section-5.1 RFC 7541 Section 5.1]]
  */
object HpackInteger {

  /** Maximum prefix size in bits. */
  val MaxPrefixBits: Int = 8

  /** Minimum prefix size in bits. */
  val MinPrefixBits: Int = 1

  /** Encode an integer with an N-bit prefix.
    *
    * The first byte combines the prefix bits with any existing high bits. For example, with a 5-bit
    * prefix, the high 3 bits of the first byte are preserved from `firstByteMask`.
    *
    * Per RFC 7541 Section 5.1:
    * {{{
    * if I < 2^N - 1, encode I on N bits
    * else
    *     encode (2^N - 1) on N bits
    *     I = I - (2^N - 1)
    *     while I >= 128
    *         encode (I % 128 + 128) on 8 bits
    *         I = I / 128
    *     encode I on 8 bits
    * }}}
    *
    * @param value
    *   the non-negative integer to encode
    * @param prefixBits
    *   the number of bits available in the first byte (1-8)
    * @param firstByteMask
    *   the high bits to preserve in the first byte (bits above the prefix)
    * @param buffer
    *   the buffer to write to
    * @return
    *   Eru effect that succeeds with unit or fails with HpackError
    */
  def encode(value: Int, prefixBits: Int, firstByteMask: Int, buffer: ByteBuffer): Eru[HpackError, Unit] = {
    if value < 0 then {
      Eru.fail(HpackError.IntegerError(s"Value must be non-negative, got $value"))
    } else if prefixBits < MinPrefixBits || prefixBits > MaxPrefixBits then {
      Eru.fail(HpackError.IntegerError(s"Prefix bits must be $MinPrefixBits-$MaxPrefixBits, got $prefixBits"))
    } else if buffer.remaining < 1 then {
      Eru.fail(HpackError.BufferError("Buffer has no remaining capacity"))
    } else {
      val maxPrefix = (1 << prefixBits) - 1

      if value < maxPrefix then {
        buffer.put((firstByteMask | value).toByte): Unit
        Eru.unit
      } else {
        buffer.put((firstByteMask | maxPrefix).toByte): Unit
        encodeContinuation(value - maxPrefix, buffer)
      }
    }
  }

  /** Encode continuation bytes after the prefix is maxed out. */
  private def encodeContinuation(initialRemaining: Int, buffer: ByteBuffer): Eru[HpackError, Unit] = {
    @tailrec
    def loop(remaining: Int): Eru[HpackError, Unit] =
      if remaining < 128 then {
        if buffer.remaining < 1 then Eru.fail(HpackError.BufferError("Buffer overflow during final byte encoding"))
        else {
          buffer.put(remaining.toByte): Unit
          Eru.unit
        }
      } else if buffer.remaining < 1 then {
        Eru.fail(HpackError.BufferError("Buffer overflow during continuation encoding"))
      } else {
        buffer.put(((remaining % 128) + 128).toByte): Unit
        loop(remaining / 128)
      }

    loop(initialRemaining)
  }

  /** Calculate the number of bytes needed to encode an integer with an N-bit prefix.
    *
    * @param value
    *   the non-negative integer to encode
    * @param prefixBits
    *   the number of bits available in the first byte (1-8)
    * @return
    *   the number of bytes required
    */
  def encodedLength(value: Int, prefixBits: Int): Int = {
    require(value >= 0, s"HPACK integer must be non-negative, got $value")
    require(
      prefixBits >= MinPrefixBits && prefixBits <= MaxPrefixBits,
      s"Prefix bits must be $MinPrefixBits-$MaxPrefixBits, got $prefixBits"
    )

    val maxPrefix = (1 << prefixBits) - 1

    if value < maxPrefix then {
      1
    } else {
      var remaining = value - maxPrefix
      var length = 1
      while remaining >= 128 do {
        length += 1
        remaining = remaining / 128
      }
      length + 1
    }
  }

  /** Decode an integer with an N-bit prefix.
    *
    * Per RFC 7541 Section 5.1:
    * {{{
    * decode I from the next N bits
    * if I < 2^N - 1, return I
    * else
    *     M = 0
    *     repeat
    *         B = next octet
    *         I = I + (B & 127) * 2^M
    *         M = M + 7
    *     while B & 128 == 128
    *     return I
    * }}}
    *
    * @param firstByte
    *   the first byte containing the prefix (already read)
    * @param prefixBits
    *   the number of bits used for the prefix (1-8)
    * @param buffer
    *   the buffer to read continuation bytes from
    * @return
    *   Eru effect that succeeds with decoded value or fails with HpackError
    */
  def decode(firstByte: Byte, prefixBits: Int, buffer: ByteBuffer): Eru[HpackError, Int] = {
    if prefixBits < MinPrefixBits || prefixBits > MaxPrefixBits then {
      Eru.fail(HpackError.IntegerError(s"Prefix bits must be $MinPrefixBits-$MaxPrefixBits, got $prefixBits"))
    } else {
      val maxPrefix = (1 << prefixBits) - 1
      val prefixValue = (firstByte & 0xff) & maxPrefix

      if prefixValue < maxPrefix then {
        Eru.succeed(prefixValue)
      } else {
        decodeContinuation(prefixValue, buffer)
      }
    }
  }

  /** Decode continuation bytes after the prefix is maxed out. */
  private def decodeContinuation(initialValue: Int, buffer: ByteBuffer): Eru[HpackError, Int] = {
    @tailrec
    def loop(value: Int, shift: Int): Eru[HpackError, Int] =
      if buffer.remaining < 1 then Eru.fail(HpackError.IntegerError("Unexpected end of buffer during decoding"))
      else if shift > 28 then Eru.fail(HpackError.IntegerError("Integer overflow: value too large for 32-bit integer"))
      else {
        val b = buffer.get() & 0xff
        val contribution = (b & 127) << shift
        if value > Int.MaxValue - contribution then {
          Eru.fail(HpackError.IntegerError("Integer overflow: value too large for 32-bit integer"))
        } else {
          val next = value + contribution
          if (b & 128) == 0 then Eru.succeed(next)
          else loop(next, shift + 7)
        }
      }

    loop(initialValue, 0)
  }

  /** Decode an integer from a byte array with an N-bit prefix.
    *
    * Convenience method for testing and simple cases.
    *
    * @param bytes
    *   the bytes to decode from
    * @param prefixBits
    *   the number of bits used for the prefix (1-8)
    * @return
    *   Eru effect that succeeds with (decoded value, bytes consumed) or fails with HpackError
    */
  def decodeFromArray(bytes: Array[Byte], prefixBits: Int): Eru[HpackError, (Int, Int)] = {
    if bytes.isEmpty then {
      Eru.fail(HpackError.IntegerError("Empty byte array"))
    } else {
      val buffer = ByteBuffer.wrap(bytes, 1, bytes.length - 1)
      decode(bytes(0), prefixBits, buffer).map { value =>
        val consumed = 1 + (bytes.length - 1 - buffer.remaining)
        (value, consumed)
      }
    }
  }
}
