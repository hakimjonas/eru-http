package net.ghoula.eru.http.h2

import java.nio.ByteBuffer

import net.ghoula.eru.*

/** HPACK header block encoder as defined in RFC 7541.
  *
  * Encodes header name-value pairs into the wire format. Maintains a dynamic table that persists
  * across header blocks for compression.
  *
  * Encoding strategies:
  *   - Indexed Header Field: Use when exact name-value match exists in tables
  *   - Literal with Incremental Indexing: Use for headers likely to repeat
  *   - Literal without Indexing: Use for headers unlikely to repeat
  *   - Literal Never Indexed: Use for sensitive headers (e.g., cookies, auth)
  *
  * @param maxDynamicTableSize
  *   the maximum dynamic table size (from SETTINGS_HEADER_TABLE_SIZE)
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#section-6 RFC 7541 Section 6]]
  */
final class HpackEncoder(maxDynamicTableSize: Int = HpackDynamicTable.DefaultMaxSize) {

  /** The dynamic table for this encoder instance. */
  private val dynamicTable: HpackDynamicTable = HpackDynamicTable(maxDynamicTableSize)

  /** Encode a list of headers into an HPACK header block.
    *
    * @param headers
    *   list of (name, value) pairs to encode
    * @param buffer
    *   the buffer to write to
    * @param sensitive
    *   set of header names that should be marked as never-indexed
    * @return
    *   Eru effect that succeeds with bytes written or fails with HpackError
    */
  def encode(
    headers: List[(String, String)],
    buffer: ByteBuffer,
    sensitive: Set[String] = Set.empty
  ): Eru[HpackError, Int] = {
    encodeLoop(headers, buffer, sensitive, 0)
  }

  /** Encode headers one by one. */
  private def encodeLoop(
    headers: List[(String, String)],
    buffer: ByteBuffer,
    sensitive: Set[String],
    totalBytes: Int
  ): Eru[HpackError, Int] = {
    headers match {
      case Nil => Eru.succeed(totalBytes)
      case (name, value) :: rest =>
        val isSensitive = sensitive.contains(name.toLowerCase)
        encodeHeader(name, value, isSensitive, buffer).flatMap { bytes =>
          encodeLoop(rest, buffer, sensitive, totalBytes + bytes)
        }
    }
  }

  /** Encode a single header field.
    *
    * Strategy:
    *   1. Check for exact match in static/dynamic table -> Indexed
    *   2. Check for name match -> Literal with name index
    *   3. No match -> Literal with new name
    *
    * For non-sensitive headers, add to dynamic table if not already present.
    */
  private def encodeHeader(
    name: String,
    value: String,
    sensitive: Boolean,
    buffer: ByteBuffer
  ): Eru[HpackError, Int] = {
    // Normalize name to lowercase per HTTP/2 spec
    val normalizedName = name.toLowerCase

    if sensitive then {
      // Never indexed for sensitive headers
      encodeLiteralNeverIndexed(normalizedName, value, buffer)
    } else {
      // Look for matches in tables
      findMatch(normalizedName, value) match {
        case MatchResult.ExactMatch(index) =>
          // Perfect - use indexed representation
          encodeIndexed(index, buffer)

        case MatchResult.NameMatch(index) =>
          // Name found - literal with incremental indexing
          encodeLiteralIndexed(index, normalizedName, value, buffer)

        case MatchResult.NoMatch =>
          // Nothing found - literal with incremental indexing and new name
          encodeLiteralIndexedNewName(normalizedName, value, buffer)
      }
    }
  }

  /** Find a match in static and dynamic tables. */
  private def findMatch(name: String, value: String): MatchResult = {
    // Check static table for exact match first
    HpackStaticTable.findIndex(name, value) match {
      case Some(index) => MatchResult.ExactMatch(index)
      case None =>
        // Check dynamic table for exact match
        dynamicTable.find(name, value) match {
          case Some((dynIndex, true)) => MatchResult.ExactMatch(dynIndex)
          case Some((dynIndex, false)) =>
            // Dynamic has name match - but check if static has name too
            HpackStaticTable.findNameIndex(name) match {
              case Some(staticIndex) => MatchResult.NameMatch(staticIndex) // Prefer static
              case None => MatchResult.NameMatch(dynIndex)
            }
          case None =>
            // No dynamic match - check static for name-only match
            HpackStaticTable.findNameIndex(name) match {
              case Some(index) => MatchResult.NameMatch(index)
              case None => MatchResult.NoMatch
            }
        }
    }
  }

  /** Encode an Indexed Header Field (RFC 7541 Section 6.1).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 1 |        Index (7+)         |
    *   +---+---------------------------+
    * }}}
    */
  private def encodeIndexed(index: Int, buffer: ByteBuffer): Eru[HpackError, Int] = {
    HpackInteger.encode(index, 7, 0x80, buffer).map { _ =>
      HpackInteger.encodedLength(index, 7)
    }
  }

  /** Encode a Literal Header Field with Incremental Indexing, indexed name (RFC 7541 Section
    * 6.2.1).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 1 |      Index (6+)       |
    *   +---+---+-----------------------+
    *   | H |     Value Length (7+)     |
    *   +---+---------------------------+
    *   | Value String (Length octets)  |
    *   +-------------------------------+
    * }}}
    */
  private def encodeLiteralIndexed(
    nameIndex: Int,
    name: String,
    value: String,
    buffer: ByteBuffer
  ): Eru[HpackError, Int] = {
    // First, add to dynamic table
    dynamicTable.add(name, value)

    // Encode index with 0x40 prefix
    HpackInteger.encode(nameIndex, 6, 0x40, buffer).flatMap { _ =>
      val indexBytes = HpackInteger.encodedLength(nameIndex, 6)

      // Encode value
      HpackString.encode(value, buffer).map { valueBytes =>
        indexBytes + valueBytes
      }
    }
  }

  /** Encode a Literal Header Field with Incremental Indexing, new name (RFC 7541 Section 6.2.1).
    *
    * {{{
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
  private def encodeLiteralIndexedNewName(
    name: String,
    value: String,
    buffer: ByteBuffer
  ): Eru[HpackError, Int] = {
    // First, add to dynamic table
    dynamicTable.add(name, value)

    // Encode 0x40 (incremental indexing, index=0)
    HpackInteger.encode(0, 6, 0x40, buffer).flatMap { _ =>
      val prefixBytes = HpackInteger.encodedLength(0, 6)

      // Encode name
      HpackString.encode(name, buffer).flatMap { nameBytes =>
        // Encode value
        HpackString.encode(value, buffer).map { valueBytes =>
          prefixBytes + nameBytes + valueBytes
        }
      }
    }
  }

  /** Encode a Literal Header Field Never Indexed (RFC 7541 Section 6.2.3).
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 0 | 0 | 1 |  Index (4+)   |
    *   +---+---+-----------------------+
    *   | H |     Value Length (7+)     |
    *   +---+---------------------------+
    *   | Value String (Length octets)  |
    *   +-------------------------------+
    * }}}
    */
  private def encodeLiteralNeverIndexed(
    name: String,
    value: String,
    buffer: ByteBuffer
  ): Eru[HpackError, Int] = {
    // Check for name match (don't add to table)
    val nameIndex = HpackStaticTable
      .findNameIndex(name)
      .orElse(dynamicTable.findName(name))
      .getOrElse(0)

    if nameIndex > 0 then {
      // Name found - use indexed name
      HpackInteger.encode(nameIndex, 4, 0x10, buffer).flatMap { _ =>
        val indexBytes = HpackInteger.encodedLength(nameIndex, 4)

        HpackString.encode(value, buffer).map { valueBytes =>
          indexBytes + valueBytes
        }
      }
    } else {
      // New name
      HpackInteger.encode(0, 4, 0x10, buffer).flatMap { _ =>
        val prefixBytes = HpackInteger.encodedLength(0, 4)

        HpackString.encode(name, buffer).flatMap { nameBytes =>
          HpackString.encode(value, buffer).map { valueBytes =>
            prefixBytes + nameBytes + valueBytes
          }
        }
      }
    }
  }

  /** Encode a Dynamic Table Size Update (RFC 7541 Section 6.3).
    *
    * Must be sent at the beginning of a header block if the dynamic table size has changed.
    *
    * {{{
    *   +---+---+---+---+---+---+---+---+
    *   | 0 | 0 | 1 |   Max Size (5+)   |
    *   +---+---------------------------+
    * }}}
    */
  def encodeTableSizeUpdate(newMaxSize: Int, buffer: ByteBuffer): Eru[HpackError, Int] = {
    dynamicTable.setMaxSize(newMaxSize)
    HpackInteger.encode(newMaxSize, 5, 0x20, buffer).map { _ =>
      HpackInteger.encodedLength(newMaxSize, 5)
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

object HpackEncoder {

  /** Create a new encoder with the default maximum dynamic table size. */
  def apply(): HpackEncoder = new HpackEncoder()

  /** Create a new encoder with a custom maximum dynamic table size.
    *
    * @param maxDynamicTableSize
    *   the maximum size for the dynamic table
    */
  def apply(maxDynamicTableSize: Int): HpackEncoder = new HpackEncoder(maxDynamicTableSize)
}

/** Result of searching for a header in static and dynamic tables. */
private enum MatchResult {
  case ExactMatch(index: Int)
  case NameMatch(index: Int)
  case NoMatch
}
