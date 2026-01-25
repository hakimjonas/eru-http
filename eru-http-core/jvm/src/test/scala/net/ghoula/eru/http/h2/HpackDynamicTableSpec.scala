package net.ghoula.eru.http.h2

import munit.FunSuite

/** Tests for HPACK dynamic table per RFC 7541 Section 2.3.2 and Section 4. */
class HpackDynamicTableSpec extends FunSuite {

  // ============================================================================
  // Basic Operations
  // ============================================================================

  test("Empty table has zero length and size") {
    val table = HpackDynamicTable()
    assertEquals(table.length, 0)
    assertEquals(table.size, 0)
  }

  test("Default max size is 4096") {
    val table = HpackDynamicTable()
    assertEquals(table.maxTableSize, 4096)
  }

  test("Custom max size is respected") {
    val table = HpackDynamicTable(1024)
    assertEquals(table.maxTableSize, 1024)
  }

  // ============================================================================
  // Entry Size Calculation (RFC 7541 Section 4.1)
  // ============================================================================

  test("Entry size is name + value + 32 overhead") {
    val table = HpackDynamicTable()
    // "custom-key" = 10 bytes, "custom-value" = 12 bytes
    // Total = 10 + 12 + 32 = 54
    assertEquals(table.entrySize("custom-key", "custom-value"), 54)
  }

  test("Entry size handles empty value") {
    val table = HpackDynamicTable()
    // ":authority" = 10 bytes, "" = 0 bytes
    // Total = 10 + 0 + 32 = 42
    assertEquals(table.entrySize(":authority", ""), 42)
  }

  test("Entry size handles UTF-8 multibyte characters") {
    val table = HpackDynamicTable()
    // "name" = 4 bytes, "日本語" = 9 bytes (3 chars × 3 bytes each)
    // Total = 4 + 9 + 32 = 45
    assertEquals(table.entrySize("name", "日本語"), 45)
  }

  // ============================================================================
  // Adding Entries
  // ============================================================================

  test("Add entry increases length and size") {
    val table = HpackDynamicTable()
    table.add("custom-key", "custom-value")

    assertEquals(table.length, 1)
    assertEquals(table.size, 54) // 10 + 12 + 32
  }

  test("Newest entry is at index 1 (FIFO)") {
    val table = HpackDynamicTable()
    table.add("first", "value1")
    table.add("second", "value2")

    // Second entry added, so "second" should be at index 1
    val entry1 = table.get(1)
    assert(entry1.isDefined)
    assertEquals(entry1.get.name, "second")

    // First entry should be at index 2
    val entry2 = table.get(2)
    assert(entry2.isDefined)
    assertEquals(entry2.get.name, "first")
  }

  // ============================================================================
  // Indexing
  // ============================================================================

  test("get returns None for invalid indices") {
    val table = HpackDynamicTable()
    table.add("test", "value")

    assertEquals(table.get(0), None)
    assertEquals(table.get(2), None)
    assertEquals(table.get(-1), None)
  }

  test("getByAbsoluteIndex accounts for static table offset") {
    val table = HpackDynamicTable()
    table.add("test", "value")

    // Dynamic table index 1 = absolute index 62 (after 61 static entries)
    val entry = table.getByAbsoluteIndex(62)
    assert(entry.isDefined)
    assertEquals(entry.get.name, "test")

    // Absolute index 61 is still in static table
    assertEquals(table.getByAbsoluteIndex(61), None)
  }

  // ============================================================================
  // Eviction on Add (RFC 7541 Section 4.4)
  // ============================================================================

  test("Adding entry evicts oldest entries when capacity exceeded") {
    // Small table: 100 bytes
    val table = HpackDynamicTable(100)

    // Add entry: "a" (1) + "b" (1) + 32 = 34 bytes
    table.add("a", "b")
    assertEquals(table.size, 34)

    // Add another: "c" (1) + "d" (1) + 32 = 34 bytes
    // Total would be 68, still fits
    table.add("c", "d")
    assertEquals(table.size, 68)
    assertEquals(table.length, 2)

    // Add another: "e" (1) + "f" (1) + 32 = 34 bytes
    // Total would be 102, exceeds 100
    // Must evict oldest ("a","b") to make room
    table.add("e", "f")
    assertEquals(table.length, 2)
    assertEquals(table.size, 68)

    // Verify oldest was evicted
    val entry1 = table.get(1)
    assertEquals(entry1.get.name, "e")
    val entry2 = table.get(2)
    assertEquals(entry2.get.name, "c")
  }

  test("Entry larger than max size empties table without adding") {
    val table = HpackDynamicTable(50)
    table.add("x", "y") // 34 bytes

    // Try to add entry larger than max size (50)
    // "verylongname" (12) + "verylongvalue" (13) + 32 = 57 bytes
    table.add("verylongname", "verylongvalue")

    // Table should be empty
    assertEquals(table.length, 0)
    assertEquals(table.size, 0)
  }

  // ============================================================================
  // Max Size Changes (RFC 7541 Section 4.3)
  // ============================================================================

  test("Reducing max size evicts entries") {
    val table = HpackDynamicTable(200)

    // Add entries totaling ~136 bytes
    table.add("a", "b") // 34
    table.add("c", "d") // 34
    table.add("e", "f") // 34
    table.add("g", "h") // 34
    assertEquals(table.length, 4)
    assertEquals(table.size, 136)

    // Reduce max size to 70 - should evict oldest entries
    table.setMaxSize(70)

    // Should keep only newest 2 entries (68 bytes)
    assertEquals(table.length, 2)
    assertEquals(table.size, 68)
    assertEquals(table.get(1).get.name, "g")
    assertEquals(table.get(2).get.name, "e")
  }

  test("Setting max size to 0 clears table") {
    val table = HpackDynamicTable()
    table.add("test", "value")

    table.setMaxSize(0)

    assertEquals(table.length, 0)
    assertEquals(table.size, 0)
    assertEquals(table.maxTableSize, 0)
  }

  test("Increasing max size does not affect existing entries") {
    val table = HpackDynamicTable(100)
    table.add("test", "value")
    val sizeBefore = table.size

    table.setMaxSize(200)

    assertEquals(table.size, sizeBefore)
    assertEquals(table.length, 1)
  }

  // ============================================================================
  // Find Operations
  // ============================================================================

  test("find returns exact match with exactMatch=true") {
    val table = HpackDynamicTable()
    table.add("content-type", "text/html")

    val result = table.find("content-type", "text/html")
    assert(result.isDefined)
    val (index, exactMatch) = result.get
    assertEquals(index, 62) // First dynamic entry
    assert(exactMatch)
  }

  test("find returns name-only match with exactMatch=false") {
    val table = HpackDynamicTable()
    table.add("content-type", "text/html")

    val result = table.find("content-type", "application/json")
    assert(result.isDefined)
    val (index, exactMatch) = result.get
    assertEquals(index, 62)
    assert(!exactMatch)
  }

  test("find returns None when name not found") {
    val table = HpackDynamicTable()
    table.add("content-type", "text/html")

    val result = table.find("accept", "text/html")
    assertEquals(result, None)
  }

  test("find prefers exact match over name-only match") {
    val table = HpackDynamicTable()
    table.add("content-type", "text/html") // index 63 (after next add)
    table.add("content-type", "application/json") // index 62

    // Looking for exact match on "text/html"
    val result = table.find("content-type", "text/html")
    assert(result.isDefined)
    val (index, exactMatch) = result.get
    assertEquals(index, 63) // The exact match
    assert(exactMatch)
  }

  test("findName returns first matching name") {
    val table = HpackDynamicTable()
    table.add("content-type", "text/html")
    table.add("content-type", "application/json")

    val result = table.findName("content-type")
    assert(result.isDefined)
    assertEquals(result.get, 62) // Newest entry
  }

  test("findName returns None when not found") {
    val table = HpackDynamicTable()
    table.add("content-type", "text/html")

    val result = table.findName("accept")
    assertEquals(result, None)
  }

  // ============================================================================
  // Clear
  // ============================================================================

  test("clear removes all entries") {
    val table = HpackDynamicTable()
    table.add("a", "b")
    table.add("c", "d")

    table.clear()

    assertEquals(table.length, 0)
    assertEquals(table.size, 0)
  }
}
