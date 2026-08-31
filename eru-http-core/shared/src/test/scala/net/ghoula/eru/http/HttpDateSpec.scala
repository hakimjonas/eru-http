package net.ghoula.eru.http

import munit.*

import java.time.*

import TestHelpers.*

/** Tests for HTTP date parsing and formatting.
  *
  * Caveats relied upon by the assertions:
  *   - HTTP dates carry no sub-second precision, so `isModifiedSince` truncates to seconds.
  *   - `now()` is formatted and re-parsed; it may rarely fail under a clock race, and it is
  *     expected to be within 1 second of `Instant.now()`.
  *   - The month-name test uses the 15th of each 2022 month to avoid month-end day-of-week edge
  *     cases.
  */
class HttpDateSpec extends FunSuite {

  test("parse IMF-fixdate format") {
    val result = HttpDate.parse("Sun, 06 Nov 1994 08:49:37 GMT").assertSuccess
    val expected = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse IMF-fixdate with different day") {
    val result = HttpDate.parse("Mon, 15 Aug 2022 14:30:00 GMT").assertSuccess
    val expected = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse IMF-fixdate midnight") {
    val result = HttpDate.parse("Sat, 01 Jan 2000 00:00:00 GMT").assertSuccess
    val expected = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse IMF-fixdate end of day") {
    val result = HttpDate.parse("Fri, 31 Dec 1999 23:59:59 GMT").assertSuccess
    val expected = ZonedDateTime.of(1999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse RFC 850 format") {
    val result = HttpDate.parse("Sunday, 06-Nov-94 08:49:37 GMT").assertSuccess
    val expected = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse RFC 850 with 2-digit year in 2000s") {
    val result = HttpDate.parse("Monday, 15-Aug-22 14:30:00 GMT").assertSuccess
    val expected = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse RFC 850 with 2-digit year pivot (50+)") {
    val result = HttpDate.parse("Sunday, 01-Jan-50 00:00:00 GMT").assertSuccess
    val expected = ZonedDateTime.of(1950, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse RFC 850 with 2-digit year pivot (49 and below)") {
    val result = HttpDate.parse("Friday, 01-Jan-49 00:00:00 GMT").assertSuccess
    val expected = ZonedDateTime.of(2049, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse ANSI C asctime format") {
    val result = HttpDate.parse("Sun Nov  6 08:49:37 1994").assertSuccess
    val expected = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse asctime with single-digit day") {
    val result = HttpDate.parse("Mon Aug  1 14:30:00 2022").assertSuccess
    val expected = ZonedDateTime.of(2022, 8, 1, 14, 30, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parse asctime with double-digit day") {
    val result = HttpDate.parse("Mon Dec 25 12:00:00 2000").assertSuccess
    val expected = ZonedDateTime.of(2000, 12, 25, 12, 0, 0, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("format instant as IMF-fixdate") {
    val instant = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC).toInstant
    val result = HttpDate.format(instant)
    assertEquals(result, "Sun, 06 Nov 1994 08:49:37 GMT")
  }

  test("format epoch") {
    val instant = Instant.EPOCH
    val result = HttpDate.format(instant)
    assertEquals(result, "Thu, 01 Jan 1970 00:00:00 GMT")
  }

  test("format future date") {
    val instant = ZonedDateTime.of(2050, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant
    val result = HttpDate.format(instant)
    assertEquals(result, "Sat, 31 Dec 2050 23:59:59 GMT")
  }

  test("round-trip: format and parse IMF-fixdate") {
    val original = ZonedDateTime.of(2022, 8, 15, 14, 30, 45, 0, ZoneOffset.UTC).toInstant
    val formatted = HttpDate.format(original)
    val parsed = HttpDate.parse(formatted).assertSuccess
    assertEquals(parsed, original)
  }

  test("round-trip: parse and format IMF-fixdate") {
    val original = "Mon, 15 Aug 2022 14:30:45 GMT"
    val parsed = HttpDate.parse(original).assertSuccess
    val formatted = HttpDate.format(parsed)
    assertEquals(formatted, original)
  }

  test("parse fails on invalid format") {
    assert(HttpDate.parse("Invalid Date").isFailure)
  }

  test("parse fails on empty string") {
    assert(HttpDate.parse("").isFailure)
  }

  test("parse handles whitespace") {
    val result = HttpDate.parse("  Sun, 06 Nov 1994 08:49:37 GMT  ").assertSuccess
    val expected = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC).toInstant
    assertEquals(result, expected)
  }

  test("parseNotFuture accepts past date") {
    val pastDate = "Sun, 06 Nov 1994 08:49:37 GMT"
    val result = HttpDate.parseNotFuture(pastDate).assertSuccess
    assert(result.isBefore(Instant.now()))
  }

  test("parseNotFuture accepts current date") {
    val now = Instant.now()
    val formatted = HttpDate.format(now)
    val result = HttpDate.parseNotFuture(formatted).assertSuccess
    assert(!result.isAfter(Instant.now()))
  }

  test("parseNotFuture rejects future date") {
    val futureInstant = Instant.now().plus(Duration.ofDays(365))
    val futureDate = HttpDate.format(futureInstant)
    assert(HttpDate.parseNotFuture(futureDate).isFailure)
  }

  test("isModifiedSince returns true when modified after") {
    val lastModified = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant
    val ifModifiedSince = ZonedDateTime.of(2022, 8, 15, 14, 0, 0, 0, ZoneOffset.UTC).toInstant

    assert(HttpDate.isModifiedSince(lastModified, ifModifiedSince))
  }

  test("isModifiedSince returns false when not modified") {
    val lastModified = ZonedDateTime.of(2022, 8, 15, 14, 0, 0, 0, ZoneOffset.UTC).toInstant
    val ifModifiedSince = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant

    assert(!HttpDate.isModifiedSince(lastModified, ifModifiedSince))
  }

  test("isModifiedSince returns false when equal") {
    val instant = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant

    assert(!HttpDate.isModifiedSince(instant, instant))
  }

  test("isModifiedSince truncates to seconds") {
    val lastModified = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 500_000_000, ZoneOffset.UTC).toInstant
    val ifModifiedSince = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 999_999_999, ZoneOffset.UTC).toInstant

    assert(!HttpDate.isModifiedSince(lastModified, ifModifiedSince))
  }

  test("isUnmodifiedSince returns true when not modified") {
    val lastModified = ZonedDateTime.of(2022, 8, 15, 14, 0, 0, 0, ZoneOffset.UTC).toInstant
    val ifUnmodifiedSince = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant

    assert(HttpDate.isUnmodifiedSince(lastModified, ifUnmodifiedSince))
  }

  test("isUnmodifiedSince returns false when modified") {
    val lastModified = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant
    val ifUnmodifiedSince = ZonedDateTime.of(2022, 8, 15, 14, 0, 0, 0, ZoneOffset.UTC).toInstant

    assert(!HttpDate.isUnmodifiedSince(lastModified, ifUnmodifiedSince))
  }

  test("isUnmodifiedSince returns true when equal") {
    val instant = ZonedDateTime.of(2022, 8, 15, 14, 30, 0, 0, ZoneOffset.UTC).toInstant

    assert(HttpDate.isUnmodifiedSince(instant, instant))
  }

  test("all three formats parse to same instant") {
    val imf = HttpDate.parse("Sun, 06 Nov 1994 08:49:37 GMT").assertSuccess
    val rfc850 = HttpDate.parse("Sunday, 06-Nov-94 08:49:37 GMT").assertSuccess
    val asctime = HttpDate.parse("Sun Nov  6 08:49:37 1994").assertSuccess

    assertEquals(imf, rfc850)
    assertEquals(imf, asctime)
  }

  test("now() returns current time in HTTP format") {
    val formatted = HttpDate.now()
    val parsed = HttpDate.parse(formatted).assertSuccess

    val now = Instant.now()
    val diff = Duration.between(parsed, now).abs()
    assert(diff.getSeconds <= 1)
  }

  test("format preserves day of week") {
    val monday = ZonedDateTime.of(2022, 8, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant
    val formatted = HttpDate.format(monday)
    assert(formatted.startsWith("Mon, "))
  }

  test("format preserves month name") {
    val august = ZonedDateTime.of(2022, 8, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant
    val formatted = HttpDate.format(august)
    assert(formatted.contains(" Aug "))
  }

  test("format always ends with GMT") {
    val instant = Instant.now()
    val formatted = HttpDate.format(instant)
    assert(formatted.endsWith(" GMT"))
  }

  test("parse handles different month names") {
    val months = List(
      ("Jan", 1),
      ("Feb", 2),
      ("Mar", 3),
      ("Apr", 4),
      ("May", 5),
      ("Jun", 6),
      ("Jul", 7),
      ("Aug", 8),
      ("Sep", 9),
      ("Oct", 10),
      ("Nov", 11),
      ("Dec", 12)
    )

    months.foreach { case (name, month) =>
      val date = LocalDate.of(2022, month, 15)
      val dayOfWeek = date.getDayOfWeek.toString.take(3).capitalize
      val dateStr = s"$dayOfWeek, 15 $name 2022 12:00:00 GMT"
      val result = HttpDate.parse(dateStr).assertSuccess
      val expected = ZonedDateTime.of(2022, month, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant
      assertEquals(result, expected, s"Failed for month $name")
    }
  }
}
