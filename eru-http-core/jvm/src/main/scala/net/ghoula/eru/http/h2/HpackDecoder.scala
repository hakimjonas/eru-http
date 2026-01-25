package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import scala.collection.mutable.ArrayBuffer

import net.ghoula.eru.*

/** HPACK header block decoder as defined in RFC 7541.
  *
  * Decodes header field representations from HEADERS and CONTINUATION frames into a list of
  * name-value pairs. Maintains a dynamic table that persists across header blocks.
  *
  * Header field representations (RFC 7541 Section 6):
  *   - Indexed Header Field (6.1): References static or dynamic table entry
  *   - Literal Header Field with Incremental Indexing (6.2.1): New entry added to dynamic table
  *   - Literal Header Field without Indexing (6.2.2): Not added to dynamic table
  *   - Literal Header Field Never Indexed (6.2.3): Sensitive value, never indexed
  *   - Dynamic Table Size Update (6.3): Signals new maximum dynamic table size
  *
  * @param maxDynamicTableSize
  *   the maximum dynamic table size (from SETTINGS_HEADER_TABLE_SIZE)
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#section-6 RFC 7541 Section 6]]
  */
final class HpackDecoder(maxDynamicTableSize: Int = HpackDynamicTable.DefaultMaxSize) {

  /** The dynamic table for this decoder instance. */
  private val dynamicTable: HpackDynamicTable = HpackDynamicTable(maxDynamicTableSize)

  /** Decode a complete header block.
    *
    * @param buffer
    *   the buffer containing the encoded header block
    * @return
    *   Eru effect that succeeds with list of (name, value, sensitive) tuples or fails with
    *   HpackError. The sensitive flag indicates headers marked as "never indexed".
    */
  def decode(buffer: ByteBuffer): Eru[HpackError, List[(String, String, Boolean)]] = {
    val headers = ArrayBuffer[(String, String, Boolean)]()

    // Track whether we've seen any headers (for table size update validation)
    decodeLoop(buffer, headers, seenHeaders = false).map { _ =>
      headers.toList
    }
  }

  /** Main decode loop - processes representations until buffer is exhausted.
    *
    * @param seenHeaders
    *   whether any headers have been decoded yet (for RFC 7541 Section 4.2 validation)
    */
  private def decodeLoop(
    buffer: ByteBuffer,
    headers: ArrayBuffer[(String, String, Boolean)],
    seenHeaders: Boolean
  ): Eru[HpackError, Unit] = {
    if buffer.remaining == 0 then {
      Eru.unit
    } else {
      decodeRepresentation(buffer, headers, seenHeaders).flatMap { newSeenHeaders =>
        decodeLoop(buffer, headers, newSeenHeaders)
      }
    }
  }

  /** Decode a single header field representation.
    *
    * Per RFC 7541 Section 6, the first byte determines the representation type:
    *   - 1xxxxxxx: Indexed Header Field (Section 6.1)
    *   - 01xxxxxx: Literal with Incremental Indexing (Section 6.2.1)
    *   - 0000xxxx: Literal without Indexing (Section 6.2.2)
    *   - 0001xxxx: Literal Never Indexed (Section 6.2.3)
    *   - 001xxxxx: Dynamic Table Size Update (Section 6.3)
    *
    * @return
    *   Eru effect that succeeds with true if headers have been seen (including this one)
    */
  private def decodeRepresentation(
    buffer: ByteBuffer,
    headers: ArrayBuffer[(String, String, Boolean)],
    seenHeaders: Boolean
  ): Eru[HpackError, Boolean] = {
    if buffer.remaining < 1 then {
      Eru.fail(HpackError.HeaderError("Unexpected end of header block"))
    } else {
      val firstByte = buffer.get(buffer.position()) & 0xff // Peek without consuming

      if (firstByte & 0x80) != 0 then {
        // Indexed Header Field (Section 6.1): 1xxxxxxx
        decodeIndexed(buffer, headers).map(_ => true)
      } else if (firstByte & 0x40) != 0 then {
        // Literal with Incremental Indexing (Section 6.2.1): 01xxxxxx
        decodeLiteralIndexed(buffer, headers).map(_ => true)
      } else if (firstByte & 0x20) != 0 then {
        // Dynamic Table Size Update (Section 6.3): 001xxxxx
        // Per RFC 7541 Section 4.2, dynamic table size update MUST occur at the
        // beginning of the header block, before any header field representation.
        if seenHeaders then {
          Eru.fail(HpackError.TableError("Dynamic table size update must occur at the beginning of header block"))
        } else {
          decodeTableSizeUpdate(buffer).map(_ => false)
        }
      } else if (firstByte & 0x10) != 0 then {
        // Literal Never Indexed (Section 6.2.3): 0001xxxx
        decodeLiteralNeverIndexed(buffer, headers).map(_ => true)
      } else {
        // Literal without Indexing (Section 6.2.2): 0000xxxx
        decodeLiteralNoIndexing(buffer, headers).map(_ => true)
      }
    }
  }

  /** Decode an Indexed Header Field (RFC 7541 Section 6.1).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 1 |        Index (7+)         |
    *   +---+---------------------------+
    * }}}
    */
  private def decodeIndexed(
    buffer: ByteBuffer,
    headers: ArrayBuffer[(String, String, Boolean)]
  ): Eru[HpackError, Unit] = {
    val firstByte = buffer.get()
    HpackInteger.decode(firstByte, 7, buffer).flatMap { index =>
      if index == 0 then {
        Eru.fail(HpackError.HeaderError("Invalid index 0 in indexed header field"))
      } else {
        lookupIndex(index).map { case (name, value) =>
          headers += ((name, value, false))
        }
      }
    }
  }

  /** Decode a Literal Header Field with Incremental Indexing (RFC 7541 Section 6.2.1).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 1 |      Index (6+)       |
    *   +---+---+-----------------------+
    *   | H |     Value Length (7+)     |
    *   +---+---------------------------+
    *   | Value String (Length octets)  |
    *   +-------------------------------+
    *
    *   or
    *
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 1 |           0           |
    *   +---+---+-----------------------+
    *   | H |     Name Length (7+)      |
    *   +---+---------------------------+
    *   |  Name String (Length octets)  |
    *   +---+---------------------------+
    *   | H |     Value Length (7+)     |
    *   +---+---------------------------+
    *   | Value String (Length octets)  |
    *   +-------------------------------+
    * }}}
    */
  private def decodeLiteralIndexed(
    buffer: ByteBuffer,
    headers: ArrayBuffer[(String, String, Boolean)]
  ): Eru[HpackError, Unit] = {
    val firstByte = buffer.get()
    HpackInteger.decode(firstByte, 6, buffer).flatMap { index =>
      decodeNameValue(index, buffer).map { case (name, value) =>
        // Add to dynamic table
        dynamicTable.add(name, value)
        headers += ((name, value, false))
      }
    }
  }

  /** Decode a Literal Header Field without Indexing (RFC 7541 Section 6.2.2).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 0 | 0 | 0 |  Index (4+)   |
    *   +---+---+-----------------------+
    *   | H |     Value Length (7+)     |
    *   +---+---------------------------+
    *   | Value String (Length octets)  |
    *   +-------------------------------+
    * }}}
    */
  private def decodeLiteralNoIndexing(
    buffer: ByteBuffer,
    headers: ArrayBuffer[(String, String, Boolean)]
  ): Eru[HpackError, Unit] = {
    val firstByte = buffer.get()
    HpackInteger.decode(firstByte, 4, buffer).flatMap { index =>
      decodeNameValue(index, buffer).map { case (name, value) =>
        // Do NOT add to dynamic table
        headers += ((name, value, false))
      }
    }
  }

  /** Decode a Literal Header Field Never Indexed (RFC 7541 Section 6.2.3).
    *
    * Same format as without indexing but with sensitive flag.
    */
  private def decodeLiteralNeverIndexed(
    buffer: ByteBuffer,
    headers: ArrayBuffer[(String, String, Boolean)]
  ): Eru[HpackError, Unit] = {
    val firstByte = buffer.get()
    HpackInteger.decode(firstByte, 4, buffer).flatMap { index =>
      decodeNameValue(index, buffer).map { case (name, value) =>
        // Do NOT add to dynamic table, mark as sensitive
        headers += ((name, value, true))
      }
    }
  }

  /** Decode name and value based on index.
    *
    * @param index
    *   if > 0, use indexed name; if 0, decode literal name
    */
  private def decodeNameValue(index: Int, buffer: ByteBuffer): Eru[HpackError, (String, String)] = {
    if index > 0 then {
      // Name is indexed, decode only value
      lookupIndex(index).flatMap { case (name, _) =>
        HpackString.decode(buffer).map { value =>
          (name, value)
        }
      }
    } else {
      // Literal name and value
      HpackString.decode(buffer).flatMap { name =>
        HpackString.decode(buffer).map { value =>
          (name, value)
        }
      }
    }
  }

  /** Decode a Dynamic Table Size Update (RFC 7541 Section 6.3).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 0 | 1 |   Max Size (5+)   |
    *   +---+---------------------------+
    * }}}
    */
  private def decodeTableSizeUpdate(buffer: ByteBuffer): Eru[HpackError, Unit] = {
    val firstByte = buffer.get()
    HpackInteger.decode(firstByte, 5, buffer).flatMap { newMaxSize =>
      if newMaxSize > maxDynamicTableSize then {
        Eru.fail(
          HpackError.TableError(
            s"Dynamic table size update $newMaxSize exceeds maximum $maxDynamicTableSize"
          )
        )
      } else {
        dynamicTable.setMaxSize(newMaxSize)
        Eru.unit
      }
    }
  }

  /** Look up an entry by absolute HPACK index (1-based).
    *
    * Indices 1-61 reference the static table. Indices 62+ reference the dynamic table.
    */
  private def lookupIndex(index: Int): Eru[HpackError, (String, String)] = {
    if index <= 0 then {
      Eru.fail(HpackError.HeaderError(s"Invalid index $index (must be > 0)"))
    } else if index <= HpackStaticTable.size then {
      // Static table lookup
      HpackStaticTable.get(index) match {
        case Some(entry) => Eru.succeed((entry.name, entry.value))
        case None => Eru.fail(HpackError.HeaderError(s"Invalid static table index $index"))
      }
    } else {
      // Dynamic table lookup
      dynamicTable.getByAbsoluteIndex(index) match {
        case Some(entry) => Eru.succeed((entry.name, entry.value))
        case None => Eru.fail(HpackError.HeaderError(s"Invalid dynamic table index $index"))
      }
    }
  }

  /** Get the current dynamic table for inspection. */
  def getDynamicTable: HpackDynamicTable = dynamicTable

  /** Update the maximum dynamic table size (from SETTINGS frame).
    *
    * @param newMaxSize
    *   the new maximum size
    */
  def setMaxDynamicTableSize(newMaxSize: Int): Unit = {
    dynamicTable.setMaxSize(newMaxSize)
  }
}

object HpackDecoder {

  /** Create a new decoder with the default maximum dynamic table size. */
  def apply(): HpackDecoder = new HpackDecoder()

  /** Create a new decoder with a custom maximum dynamic table size.
    *
    * @param maxDynamicTableSize
    *   the maximum size for the dynamic table
    */
  def apply(maxDynamicTableSize: Int): HpackDecoder = new HpackDecoder(maxDynamicTableSize)
}
