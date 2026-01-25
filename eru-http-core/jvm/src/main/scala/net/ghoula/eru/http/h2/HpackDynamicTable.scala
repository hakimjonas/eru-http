package net.ghoula.eru.http.h2

import scala.collection.mutable.ArrayBuffer

/** HPACK dynamic table as defined in RFC 7541 Section 2.3.2.
  *
  * The dynamic table is a FIFO structure where:
  *   - New entries are inserted at the beginning (lowest index)
  *   - Old entries are evicted from the end (highest index) when capacity is exceeded
  *   - Indexing is 1-based and continues from the static table (index 62+)
  *
  * Entry size calculation per RFC 7541 Section 4.1: "The size of an entry is the sum of its name's
  * length in octets (as defined in Section 5.2), its value's length in octets, and 32."
  *
  * The 32-octet overhead accounts for estimated memory overhead of the entry structure.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc7541#section-2.3.2 RFC 7541 Section 2.3.2]]
  */
final class HpackDynamicTable(initialMaxSize: Int) {

  /** Overhead per entry in bytes per RFC 7541 Section 4.1. */
  private val EntryOverhead: Int = 32

  /** The entries in FIFO order (index 0 = newest, index n-1 = oldest). */
  private val entries: ArrayBuffer[HpackDynamicTable.Entry] = ArrayBuffer.empty

  /** Current size in bytes (sum of all entry sizes). */
  private var currentSize: Int = 0

  /** Maximum size in bytes. */
  private var maxSize: Int = initialMaxSize

  /** Get the current number of entries in the table. */
  def length: Int = entries.length

  /** Get the current size in bytes. */
  def size: Int = currentSize

  /** Get the maximum size in bytes. */
  def maxTableSize: Int = maxSize

  /** Calculate the size of an entry per RFC 7541 Section 4.1.
    *
    * @param name
    *   the header name
    * @param value
    *   the header value
    * @return
    *   the entry size in bytes
    */
  def entrySize(name: String, value: String): Int =
    name.getBytes("UTF-8").length + value.getBytes("UTF-8").length + EntryOverhead

  /** Get an entry by dynamic table index (1-based).
    *
    * Per RFC 7541 Section 2.3.3, the dynamic table indices start at 62 (after the 61 static
    * entries). However, this method uses 1-based indexing relative to the dynamic table itself.
    *
    * @param index
    *   the 1-based index within the dynamic table
    * @return
    *   the entry, or None if index is out of range
    */
  def get(index: Int): Option[HpackDynamicTable.Entry] = {
    if index >= 1 && index <= entries.length then {
      Some(entries(index - 1))
    } else {
      None
    }
  }

  /** Get an entry by absolute HPACK index (62+).
    *
    * The absolute index includes both static table (1-61) and dynamic table (62+).
    *
    * @param absoluteIndex
    *   the absolute HPACK index (must be >= 62)
    * @return
    *   the entry, or None if index is out of range
    */
  def getByAbsoluteIndex(absoluteIndex: Int): Option[HpackDynamicTable.Entry] = {
    val dynamicIndex = absoluteIndex - HpackStaticTable.size
    get(dynamicIndex)
  }

  /** Add an entry to the dynamic table.
    *
    * Per RFC 7541 Section 4.4: "Before a new entry is added to the dynamic table, entries are
    * evicted from the end of the dynamic table until the size of the dynamic table is less than or
    * equal to (maximum size - new entry size) or until the table is empty."
    *
    * If the entry is larger than the maximum table size, the table is emptied but the entry is not
    * added.
    *
    * @param name
    *   the header name
    * @param value
    *   the header value
    */
  // scalafix:off DisableSyntax.return
  // Return used for early exit when entry exceeds max size per RFC 7541 Section 4.4
  def add(name: String, value: String): Unit = {
    val newEntrySize = entrySize(name, value)

    // If entry is larger than max size, clear the table and don't add
    // Per RFC 7541 Section 4.4: "an entry larger than the maximum size causes the table to be
    // emptied of all existing entries and results in an empty table"
    if newEntrySize > maxSize then {
      clear()
      return
    }

    // Evict entries until there's room for the new entry
    evictToFit(newEntrySize)

    // Insert at the beginning (newest entry at lowest index)
    entries.prepend(HpackDynamicTable.Entry(name, value))
    currentSize = currentSize + newEntrySize
  }
  // scalafix:on DisableSyntax.return

  /** Set the maximum table size.
    *
    * Per RFC 7541 Section 4.3: "Whenever the maximum size for the dynamic table is reduced, entries
    * are evicted from the end of the dynamic table until the size of the dynamic table is less than
    * or equal to the maximum size."
    *
    * @param newMaxSize
    *   the new maximum size in bytes
    */
  def setMaxSize(newMaxSize: Int): Unit = {
    require(newMaxSize >= 0, s"Maximum size must be non-negative, got $newMaxSize")
    maxSize = newMaxSize
    evictToFit(0)
  }

  /** Clear all entries from the table. */
  def clear(): Unit = {
    entries.clear()
    currentSize = 0
  }

  /** Find an entry matching the given name and value.
    *
    * @param name
    *   the header name to match
    * @param value
    *   the header value to match
    * @return
    *   Some((absoluteIndex, exactMatch)) if found, None otherwise. exactMatch is true if both name
    *   and value match, false if only name matches.
    */
  // scalafix:off DisableSyntax.return
  // Return used for early exit on exact match - idiomatic for search loops
  def find(name: String, value: String): Option[(Int, Boolean)] = {
    var nameMatchIndex: Option[Int] = None

    var i = 0
    while i < entries.length do {
      val entry = entries(i)
      if entry.name == name then {
        if entry.value == value then {
          // Exact match - return immediately
          return Some((HpackStaticTable.size + i + 1, true))
        } else if nameMatchIndex.isEmpty then {
          // First name-only match
          nameMatchIndex = Some(HpackStaticTable.size + i + 1)
        }
      }
      i += 1
    }

    nameMatchIndex.map((_, false))
  }
  // scalafix:on DisableSyntax.return

  /** Find an entry matching only the name.
    *
    * @param name
    *   the header name to match
    * @return
    *   Some(absoluteIndex) if found, None otherwise
    */
  // scalafix:off DisableSyntax.return
  // Return used for early exit on match - idiomatic for search loops
  def findName(name: String): Option[Int] = {
    var i = 0
    while i < entries.length do {
      if entries(i).name == name then {
        return Some(HpackStaticTable.size + i + 1)
      }
      i += 1
    }
    None
  }
  // scalafix:on DisableSyntax.return

  /** Evict entries from the end until the table can accommodate additionalBytes.
    *
    * @param additionalBytes
    *   bytes that need to fit after eviction
    */
  private def evictToFit(additionalBytes: Int): Unit = {
    val targetSize = maxSize - additionalBytes
    while currentSize > targetSize && entries.nonEmpty do {
      val evicted = entries.remove(entries.length - 1)
      currentSize = currentSize - entrySize(evicted.name, evicted.value)
    }
  }
}

object HpackDynamicTable {

  /** A dynamic table entry with name and value. */
  final case class Entry(name: String, value: String)

  /** Default maximum table size per RFC 7541 Section 4.2: "The default value is 4,096 octets." */
  val DefaultMaxSize: Int = 4096

  /** Create a new dynamic table with the default maximum size. */
  def apply(): HpackDynamicTable = new HpackDynamicTable(DefaultMaxSize)

  /** Create a new dynamic table with a custom maximum size.
    *
    * @param maxSize
    *   the maximum size in bytes
    */
  def apply(maxSize: Int): HpackDynamicTable = new HpackDynamicTable(maxSize)
}
