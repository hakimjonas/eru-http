package net.ghoula.eru.http.h2

/** HPACK static table as defined in RFC 7541 Appendix A.
  *
  * The static table consists of 61 predefined header fields. Index 0 is unused. Index 1-61 map to
  * the predefined entries. The table never changes.
  *
  * Per RFC 7541 Section 2.3.1: "The static table is ordered by a unique index address space shared
  * with the dynamic table. Indices from 1 to the length of the static table (inclusive) refer to
  * elements in the static table."
  */
object HpackStaticTable {

  /** A static table entry with name and optional value. */
  final case class Entry(name: String, value: String)

  /** The 61 predefined static table entries per RFC 7541 Appendix A.
    *
    * Index 0 is not used in HPACK. Entries are stored at indices 1-61.
    */
  val entries: Array[Entry] = Array(
    Entry(":authority", ""), // 1
    Entry(":method", "GET"), // 2
    Entry(":method", "POST"), // 3
    Entry(":path", "/"), // 4
    Entry(":path", "/index.html"), // 5
    Entry(":scheme", "http"), // 6
    Entry(":scheme", "https"), // 7
    Entry(":status", "200"), // 8
    Entry(":status", "204"), // 9
    Entry(":status", "206"), // 10
    Entry(":status", "304"), // 11
    Entry(":status", "400"), // 12
    Entry(":status", "404"), // 13
    Entry(":status", "500"), // 14
    Entry("accept-charset", ""), // 15
    Entry("accept-encoding", "gzip, deflate"), // 16
    Entry("accept-language", ""), // 17
    Entry("accept-ranges", ""), // 18
    Entry("accept", ""), // 19
    Entry("access-control-allow-origin", ""), // 20
    Entry("age", ""), // 21
    Entry("allow", ""), // 22
    Entry("authorization", ""), // 23
    Entry("cache-control", ""), // 24
    Entry("content-disposition", ""), // 25
    Entry("content-encoding", ""), // 26
    Entry("content-language", ""), // 27
    Entry("content-length", ""), // 28
    Entry("content-location", ""), // 29
    Entry("content-range", ""), // 30
    Entry("content-type", ""), // 31
    Entry("cookie", ""), // 32
    Entry("date", ""), // 33
    Entry("etag", ""), // 34
    Entry("expect", ""), // 35
    Entry("expires", ""), // 36
    Entry("from", ""), // 37
    Entry("host", ""), // 38
    Entry("if-match", ""), // 39
    Entry("if-modified-since", ""), // 40
    Entry("if-none-match", ""), // 41
    Entry("if-range", ""), // 42
    Entry("if-unmodified-since", ""), // 43
    Entry("last-modified", ""), // 44
    Entry("link", ""), // 45
    Entry("location", ""), // 46
    Entry("max-forwards", ""), // 47
    Entry("proxy-authenticate", ""), // 48
    Entry("proxy-authorization", ""), // 49
    Entry("range", ""), // 50
    Entry("referer", ""), // 51
    Entry("refresh", ""), // 52
    Entry("retry-after", ""), // 53
    Entry("server", ""), // 54
    Entry("set-cookie", ""), // 55
    Entry("strict-transport-security", ""), // 56
    Entry("transfer-encoding", ""), // 57
    Entry("user-agent", ""), // 58
    Entry("vary", ""), // 59
    Entry("via", ""), // 60
    Entry("www-authenticate", "") // 61
  )

  /** Number of entries in the static table. */
  val size: Int = entries.length

  /** Get an entry by index (1-based per RFC 7541).
    *
    * @param index
    *   the 1-based index
    * @return
    *   the entry, or None if index is out of range
    */
  def get(index: Int): Option[Entry] = {
    if index >= 1 && index <= size then Some(entries(index - 1))
    else None
  }

  // Pre-computed lookup maps for efficient encoding

  /** Map from (name, value) pair to static table index. */
  private val nameValueIndex: Map[(String, String), Int] = {
    entries.zipWithIndex.collect {
      case (entry, idx) if entry.value.nonEmpty =>
        (entry.name, entry.value) -> (idx + 1)
    }.toMap
  }

  /** Map from name to static table index (first occurrence). */
  private val nameOnlyIndex: Map[String, Int] = {
    entries.zipWithIndex
      .groupBy(_._1.name)
      .map { case (name, pairs) => name -> (pairs.head._2 + 1) }
  }

  /** Find the index for a name-value pair.
    *
    * @param name
    *   the header name (lowercase)
    * @param value
    *   the header value
    * @return
    *   Some(index) if exact match found, None otherwise
    */
  def findIndex(name: String, value: String): Option[Int] = {
    nameValueIndex.get((name, value))
  }

  /** Find the index for a name only (first occurrence).
    *
    * @param name
    *   the header name (lowercase)
    * @return
    *   Some(index) if name found, None otherwise
    */
  def findNameIndex(name: String): Option[Int] = {
    nameOnlyIndex.get(name)
  }
}
