package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import scala.collection.mutable.ArrayBuffer

import net.ghoula.eru.*

/** HPACK Huffman encoding and decoding as defined in RFC 7541 Section 5.2 and Appendix B.
  *
  * The Huffman code was generated from statistics obtained on a large sample of HTTP headers. It is
  * a canonical Huffman code with code lengths ranging from 5 to 30 bits.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#section-5.2 RFC 7541 Section 5.2]]
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#appendix-B RFC 7541 Appendix B]]
  */
object HpackHuffman {

  /** End-of-string symbol (256) used for padding per RFC 7541. */
  val EOS: Int = 256

  /** Huffman code entry: (code, length in bits). */
  private case class Code(bits: Int, length: Int)

  /** Huffman codes for all 257 symbols (0-255 + EOS) per RFC 7541 Appendix B.
    *
    * Format: (hex code aligned on LSB, number of bits)
    */
  private val codes: Array[Code] = Array(
    // @formatter:off
    Code(0x1ff8, 13),     // 0
    Code(0x7fffd8, 23),   // 1
    Code(0xfffffe2, 28),  // 2
    Code(0xfffffe3, 28),  // 3
    Code(0xfffffe4, 28),  // 4
    Code(0xfffffe5, 28),  // 5
    Code(0xfffffe6, 28),  // 6
    Code(0xfffffe7, 28),  // 7
    Code(0xfffffe8, 28),  // 8
    Code(0xffffea, 24),   // 9
    Code(0x3ffffffc, 30), // 10
    Code(0xfffffe9, 28),  // 11
    Code(0xfffffea, 28),  // 12
    Code(0x3ffffffd, 30), // 13
    Code(0xfffffeb, 28),  // 14
    Code(0xfffffec, 28),  // 15
    Code(0xfffffed, 28),  // 16
    Code(0xfffffee, 28),  // 17
    Code(0xfffffef, 28),  // 18
    Code(0xffffff0, 28),  // 19
    Code(0xffffff1, 28),  // 20
    Code(0xffffff2, 28),  // 21
    Code(0x3ffffffe, 30), // 22
    Code(0xffffff3, 28),  // 23
    Code(0xffffff4, 28),  // 24
    Code(0xffffff5, 28),  // 25
    Code(0xffffff6, 28),  // 26
    Code(0xffffff7, 28),  // 27
    Code(0xffffff8, 28),  // 28
    Code(0xffffff9, 28),  // 29
    Code(0xffffffa, 28),  // 30
    Code(0xffffffb, 28),  // 31
    Code(0x14, 6),        // 32 ' '
    Code(0x3f8, 10),      // 33 '!'
    Code(0x3f9, 10),      // 34 '"'
    Code(0xffa, 12),      // 35 '#'
    Code(0x1ff9, 13),     // 36 '$'
    Code(0x15, 6),        // 37 '%'
    Code(0xf8, 8),        // 38 '&'
    Code(0x7fa, 11),      // 39 '''
    Code(0x3fa, 10),      // 40 '('
    Code(0x3fb, 10),      // 41 ')'
    Code(0xf9, 8),        // 42 '*'
    Code(0x7fb, 11),      // 43 '+'
    Code(0xfa, 8),        // 44 ','
    Code(0x16, 6),        // 45 '-'
    Code(0x17, 6),        // 46 '.'
    Code(0x18, 6),        // 47 '/'
    Code(0x0, 5),         // 48 '0'
    Code(0x1, 5),         // 49 '1'
    Code(0x2, 5),         // 50 '2'
    Code(0x19, 6),        // 51 '3'
    Code(0x1a, 6),        // 52 '4'
    Code(0x1b, 6),        // 53 '5'
    Code(0x1c, 6),        // 54 '6'
    Code(0x1d, 6),        // 55 '7'
    Code(0x1e, 6),        // 56 '8'
    Code(0x1f, 6),        // 57 '9'
    Code(0x5c, 7),        // 58 ':'
    Code(0xfb, 8),        // 59 ';'
    Code(0x7ffc, 15),     // 60 '<'
    Code(0x20, 6),        // 61 '='
    Code(0xffb, 12),      // 62 '>'
    Code(0x3fc, 10),      // 63 '?'
    Code(0x1ffa, 13),     // 64 '@'
    Code(0x21, 6),        // 65 'A'
    Code(0x5d, 7),        // 66 'B'
    Code(0x5e, 7),        // 67 'C'
    Code(0x5f, 7),        // 68 'D'
    Code(0x60, 7),        // 69 'E'
    Code(0x61, 7),        // 70 'F'
    Code(0x62, 7),        // 71 'G'
    Code(0x63, 7),        // 72 'H'
    Code(0x64, 7),        // 73 'I'
    Code(0x65, 7),        // 74 'J'
    Code(0x66, 7),        // 75 'K'
    Code(0x67, 7),        // 76 'L'
    Code(0x68, 7),        // 77 'M'
    Code(0x69, 7),        // 78 'N'
    Code(0x6a, 7),        // 79 'O'
    Code(0x6b, 7),        // 80 'P'
    Code(0x6c, 7),        // 81 'Q'
    Code(0x6d, 7),        // 82 'R'
    Code(0x6e, 7),        // 83 'S'
    Code(0x6f, 7),        // 84 'T'
    Code(0x70, 7),        // 85 'U'
    Code(0x71, 7),        // 86 'V'
    Code(0x72, 7),        // 87 'W'
    Code(0xfc, 8),        // 88 'X'
    Code(0x73, 7),        // 89 'Y'
    Code(0xfd, 8),        // 90 'Z'
    Code(0x1ffb, 13),     // 91 '['
    Code(0x7fff0, 19),    // 92 '\'
    Code(0x1ffc, 13),     // 93 ']'
    Code(0x3ffc, 14),     // 94 '^'
    Code(0x22, 6),        // 95 '_'
    Code(0x7ffd, 15),     // 96 '`'
    Code(0x3, 5),         // 97 'a'
    Code(0x23, 6),        // 98 'b'
    Code(0x4, 5),         // 99 'c'
    Code(0x24, 6),        // 100 'd'
    Code(0x5, 5),         // 101 'e'
    Code(0x25, 6),        // 102 'f'
    Code(0x26, 6),        // 103 'g'
    Code(0x27, 6),        // 104 'h'
    Code(0x6, 5),         // 105 'i'
    Code(0x74, 7),        // 106 'j'
    Code(0x75, 7),        // 107 'k'
    Code(0x28, 6),        // 108 'l'
    Code(0x29, 6),        // 109 'm'
    Code(0x2a, 6),        // 110 'n'
    Code(0x7, 5),         // 111 'o'
    Code(0x2b, 6),        // 112 'p'
    Code(0x76, 7),        // 113 'q'
    Code(0x2c, 6),        // 114 'r'
    Code(0x8, 5),         // 115 's'
    Code(0x9, 5),         // 116 't'
    Code(0x2d, 6),        // 117 'u'
    Code(0x77, 7),        // 118 'v'
    Code(0x78, 7),        // 119 'w'
    Code(0x79, 7),        // 120 'x'
    Code(0x7a, 7),        // 121 'y'
    Code(0x7b, 7),        // 122 'z'
    Code(0x7ffe, 15),     // 123 '{'
    Code(0x7fc, 11),      // 124 '|'
    Code(0x3ffd, 14),     // 125 '}'
    Code(0x1ffd, 13),     // 126 '~'
    Code(0xffffffc, 28),  // 127
    Code(0xfffe6, 20),    // 128
    Code(0x3fffd2, 22),   // 129
    Code(0xfffe7, 20),    // 130
    Code(0xfffe8, 20),    // 131
    Code(0x3fffd3, 22),   // 132
    Code(0x3fffd4, 22),   // 133
    Code(0x3fffd5, 22),   // 134
    Code(0x7fffd9, 23),   // 135
    Code(0x3fffd6, 22),   // 136
    Code(0x7fffda, 23),   // 137
    Code(0x7fffdb, 23),   // 138
    Code(0x7fffdc, 23),   // 139
    Code(0x7fffdd, 23),   // 140
    Code(0x7fffde, 23),   // 141
    Code(0xffffeb, 24),   // 142
    Code(0x7fffdf, 23),   // 143
    Code(0xffffec, 24),   // 144
    Code(0xffffed, 24),   // 145
    Code(0x3fffd7, 22),   // 146
    Code(0x7fffe0, 23),   // 147
    Code(0xffffee, 24),   // 148
    Code(0x7fffe1, 23),   // 149
    Code(0x7fffe2, 23),   // 150
    Code(0x7fffe3, 23),   // 151
    Code(0x7fffe4, 23),   // 152
    Code(0x1fffdc, 21),   // 153
    Code(0x3fffd8, 22),   // 154
    Code(0x7fffe5, 23),   // 155
    Code(0x3fffd9, 22),   // 156
    Code(0x7fffe6, 23),   // 157
    Code(0x7fffe7, 23),   // 158
    Code(0xffffef, 24),   // 159
    Code(0x3fffda, 22),   // 160
    Code(0x1fffdd, 21),   // 161
    Code(0xfffe9, 20),    // 162
    Code(0x3fffdb, 22),   // 163
    Code(0x3fffdc, 22),   // 164
    Code(0x7fffe8, 23),   // 165
    Code(0x7fffe9, 23),   // 166
    Code(0x1fffde, 21),   // 167
    Code(0x7fffea, 23),   // 168
    Code(0x3fffdd, 22),   // 169
    Code(0x3fffde, 22),   // 170
    Code(0xfffff0, 24),   // 171
    Code(0x1fffdf, 21),   // 172
    Code(0x3fffdf, 22),   // 173
    Code(0x7fffeb, 23),   // 174
    Code(0x7fffec, 23),   // 175
    Code(0x1fffe0, 21),   // 176
    Code(0x1fffe1, 21),   // 177
    Code(0x3fffe0, 22),   // 178
    Code(0x1fffe2, 21),   // 179
    Code(0x7fffed, 23),   // 180
    Code(0x3fffe1, 22),   // 181
    Code(0x7fffee, 23),   // 182
    Code(0x7fffef, 23),   // 183
    Code(0xfffea, 20),    // 184
    Code(0x3fffe2, 22),   // 185
    Code(0x3fffe3, 22),   // 186
    Code(0x3fffe4, 22),   // 187
    Code(0x7ffff0, 23),   // 188
    Code(0x3fffe5, 22),   // 189
    Code(0x3fffe6, 22),   // 190
    Code(0x7ffff1, 23),   // 191
    Code(0x3ffffe0, 26),  // 192
    Code(0x3ffffe1, 26),  // 193
    Code(0xfffeb, 20),    // 194
    Code(0x7fff1, 19),    // 195
    Code(0x3fffe7, 22),   // 196
    Code(0x7ffff2, 23),   // 197
    Code(0x3fffe8, 22),   // 198
    Code(0x1ffffec, 25),  // 199
    Code(0x3ffffe2, 26),  // 200
    Code(0x3ffffe3, 26),  // 201
    Code(0x3ffffe4, 26),  // 202
    Code(0x7ffffde, 27),  // 203
    Code(0x7ffffdf, 27),  // 204
    Code(0x3ffffe5, 26),  // 205
    Code(0xfffff1, 24),   // 206
    Code(0x1ffffed, 25),  // 207
    Code(0x7fff2, 19),    // 208
    Code(0x1fffe3, 21),   // 209
    Code(0x3ffffe6, 26),  // 210
    Code(0x7ffffe0, 27),  // 211
    Code(0x7ffffe1, 27),  // 212
    Code(0x3ffffe7, 26),  // 213
    Code(0x7ffffe2, 27),  // 214
    Code(0xfffff2, 24),   // 215
    Code(0x1fffe4, 21),   // 216
    Code(0x1fffe5, 21),   // 217
    Code(0x3ffffe8, 26),  // 218
    Code(0x3ffffe9, 26),  // 219
    Code(0xffffffd, 28),  // 220
    Code(0x7ffffe3, 27),  // 221
    Code(0x7ffffe4, 27),  // 222
    Code(0x7ffffe5, 27),  // 223
    Code(0xfffec, 20),    // 224
    Code(0xfffff3, 24),   // 225
    Code(0xfffed, 20),    // 226
    Code(0x1fffe6, 21),   // 227
    Code(0x3fffe9, 22),   // 228
    Code(0x1fffe7, 21),   // 229
    Code(0x1fffe8, 21),   // 230
    Code(0x7ffff3, 23),   // 231
    Code(0x3fffea, 22),   // 232
    Code(0x3fffeb, 22),   // 233
    Code(0x1ffffee, 25),  // 234
    Code(0x1ffffef, 25),  // 235
    Code(0xfffff4, 24),   // 236
    Code(0xfffff5, 24),   // 237
    Code(0x3ffffea, 26),  // 238
    Code(0x7ffff4, 23),   // 239
    Code(0x3ffffeb, 26),  // 240
    Code(0x7ffffe6, 27),  // 241
    Code(0x3ffffec, 26),  // 242
    Code(0x3ffffed, 26),  // 243
    Code(0x7ffffe7, 27),  // 244
    Code(0x7ffffe8, 27),  // 245
    Code(0x7ffffe9, 27),  // 246
    Code(0x7ffffea, 27),  // 247
    Code(0x7ffffeb, 27),  // 248
    Code(0xffffffe, 28),  // 249
    Code(0x7ffffec, 27),  // 250
    Code(0x7ffffed, 27),  // 251
    Code(0x7ffffee, 27),  // 252
    Code(0x7ffffef, 27),  // 253
    Code(0x7fffff0, 27),  // 254
    Code(0x3ffffee, 26),  // 255
    Code(0x3fffffff, 30)  // 256 EOS
    // @formatter:on
  )

  /** Encode a string using Huffman coding.
    *
    * Per RFC 7541 Section 5.2: "As the Huffman-encoded data doesn't always end at an octet
    * boundary, some padding is inserted after it, up to the next octet boundary. To prevent this
    * padding from being misinterpreted as part of the string literal, the most significant bits of
    * the code corresponding to the EOS (end-of-string) symbol are used."
    *
    * The encoder is bit-level: variable-length codes are packed into whole bytes. A sentinel option
    * records the first buffer overflow so the loop exits cleanly without `return`, and padding is
    * the most significant bits of the EOS code.
    *
    * @param input
    *   the string to encode (as UTF-8 bytes)
    * @param buffer
    *   the buffer to write encoded bytes to
    * @return
    *   Eru effect that succeeds with number of bytes written or fails with HpackError
    */
  def encode(input: Array[Byte], buffer: ByteBuffer): Eru[HpackError, Int] = {
    var currentByte = 0
    var bitsInCurrentByte = 0
    var bytesWritten = 0
    var err: Option[HpackError] = None
    var i = 0

    while err.isEmpty && i < input.length do {
      val symbol = input(i) & 0xff
      val code = codes(symbol)
      var bitsRemaining = code.length
      val codeValue = code.bits

      while err.isEmpty && bitsRemaining > 0 do {
        val bitsToWrite = math.min(bitsRemaining, 8 - bitsInCurrentByte)
        val shift = bitsRemaining - bitsToWrite
        val mask = (1 << bitsToWrite) - 1
        val bits = (codeValue >> shift) & mask

        currentByte = (currentByte << bitsToWrite) | bits
        bitsInCurrentByte += bitsToWrite
        bitsRemaining -= bitsToWrite

        if bitsInCurrentByte == 8 then {
          if buffer.remaining < 1 then {
            err = Some(HpackError.BufferError("Buffer overflow during Huffman encoding"))
          } else {
            buffer.put(currentByte.toByte): Unit
            bytesWritten += 1
            currentByte = 0
            bitsInCurrentByte = 0
          }
        }
      }
      i += 1
    }

    err match {
      case Some(e) => Eru.fail(e)
      case None =>
        if bitsInCurrentByte == 0 then Eru.succeed(bytesWritten)
        else {
          val paddingBits = 8 - bitsInCurrentByte
          val eosMsb = codes(EOS).bits >> (codes(EOS).length - paddingBits)
          val finalByte = (currentByte << paddingBits) | eosMsb
          if buffer.remaining < 1 then Eru.fail(HpackError.BufferError("Buffer overflow during Huffman padding"))
          else {
            buffer.put(finalByte.toByte): Unit
            Eru.succeed(bytesWritten + 1)
          }
        }
    }
  }

  /** Calculate the encoded length of a string in bytes.
    *
    * @param input
    *   the bytes to encode
    * @return
    *   the number of bytes the Huffman-encoded output will require
    */
  def encodedLength(input: Array[Byte]): Int = {
    var totalBits = 0
    for (b <- input) {
      totalBits += codes(b & 0xff).length
    }
    (totalBits + 7) / 8
  }

  /** Decode Huffman-encoded bytes.
    *
    * Symbols are decoded by a linear search over the 257 codes (0–255 plus the EOS symbol); the
    * minimum code length is 5 bits. An EOS symbol anywhere in the stream is a decoding error.
    * Trailing padding must be at most 7 bits and must equal the most significant bits of the EOS
    * code, per RFC 7541 Section 5.2.
    *
    * @param buffer
    *   the buffer to read from
    * @param length
    *   the number of bytes to decode
    * @return
    *   Eru effect that succeeds with decoded bytes or fails with HpackError
    */
  def decode(buffer: ByteBuffer, length: Int): Eru[HpackError, Array[Byte]] = {
    if buffer.remaining < length then {
      Eru.fail(HpackError.BufferError(s"Buffer has ${buffer.remaining} bytes but need $length"))
    } else {
      val output = ArrayBuffer[Byte]()
      var accumulator = 0
      var accumulatorBits = 0
      var bytesRead = 0
      var eosFound = false

      while bytesRead < length && !eosFound do {
        val b = buffer.get() & 0xff
        bytesRead += 1

        accumulator = (accumulator << 8) | b
        accumulatorBits += 8

        var decoded = true
        while decoded && accumulatorBits >= 5 && !eosFound do {
          decoded = false

          var symbol = 0
          while symbol <= 256 && !decoded do {
            val code = codes(symbol)
            if accumulatorBits >= code.length then {
              val shift = accumulatorBits - code.length
              val candidate = accumulator >> shift
              val mask = (1 << code.length) - 1

              if (candidate & mask) == code.bits then {
                if symbol == EOS then {
                  eosFound = true
                  decoded = true
                } else {
                  output += symbol.toByte
                  accumulator = accumulator & ((1 << shift) - 1)
                  accumulatorBits = shift
                  decoded = true
                }
              }
            }
            symbol += 1
          }
        }
      }

      if eosFound then {
        Eru.fail(HpackError.HuffmanError("EOS symbol must not appear in Huffman-encoded string"))
      } else if accumulatorBits > 7 then {
        Eru.fail(HpackError.HuffmanError(s"Huffman padding longer than 7 bits: $accumulatorBits bits"))
      } else if accumulatorBits > 0 then {
        val remainingBits = accumulator & ((1 << accumulatorBits) - 1)
        val eosPrefix = codes(EOS).bits >> (codes(EOS).length - accumulatorBits)

        if remainingBits != eosPrefix then {
          Eru.fail(
            HpackError.HuffmanError(s"Invalid Huffman padding: got $remainingBits, expected $eosPrefix (EOS prefix)")
          )
        } else {
          Eru.succeed(output.toArray)
        }
      } else {
        Eru.succeed(output.toArray)
      }
    }
  }

  /** Decode Huffman-encoded bytes from an array.
    *
    * Convenience method for testing.
    *
    * @param encoded
    *   the Huffman-encoded bytes
    * @return
    *   Eru effect that succeeds with decoded bytes or fails with HpackError
    */
  def decodeFromArray(encoded: Array[Byte]): Eru[HpackError, Array[Byte]] = {
    decode(ByteBuffer.wrap(encoded), encoded.length)
  }
}
