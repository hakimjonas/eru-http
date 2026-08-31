package net.ghoula.eru.http

import java.time.*
import java.time.format.*
import java.time.temporal.ChronoField
import java.util.Locale

import net.ghoula.eru.*

/** HTTP date parsing and formatting per RFC 9110 Section 5.6.7.
  *
  * HTTP uses three date formats:
  *   1. IMF-fixdate (preferred): `Sun, 06 Nov 1994 08:49:37 GMT`
  *   1. RFC 850 (obsolete): `Sunday, 06-Nov-94 08:49:37 GMT`
  *   1. ANSI C asctime: `Sun Nov 6 08:49:37 1994`
  *
  * All HTTP dates are in GMT/UTC timezone.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9110.html#section-5.6.7 RFC 9110 Section 5.6.7 - Date/Time Formats]]
  */
object HttpDate {

  /** Parses an HTTP date from a string.
    *
    * Attempts to parse using all three HTTP date formats in order:
    *   1. IMF-fixdate (preferred format)
    *   1. RFC 850 (obsolete format)
    *   1. ANSI C asctime format
    *
    * @param value
    *   the date string to parse
    * @return
    *   the parsed Instant or a ParseError
    */
  def parse(value: String): Eru[ParseError, Instant] = {
    val trimmed = value.trim

    parseImfFixdate(trimmed)
      .orElse(parseRfc850(trimmed))
      .orElse(parseAsctime(trimmed))
      .getOrElse {
        Eru.fail(
          ParseError(
            value,
            "Not a valid HTTP date format (expected IMF-fixdate, RFC 850, or asctime)",
            "RFC 9110 Section 5.6.7"
          )
        )
      }
  }

  /** Formats an Instant as an HTTP date using IMF-fixdate format (preferred).
    *
    * Output format: `Sun, 06 Nov 1994 08:49:37 GMT`
    *
    * @param instant
    *   the instant to format
    * @return
    *   the formatted HTTP date string
    */
  def format(instant: Instant): String = {
    imfFixdateFormatter.format(instant.atZone(ZoneOffset.UTC))
  }

  /** Formats current time as an HTTP date.
    *
    * @return
    *   the current time formatted as an HTTP date
    */
  def now(): String = {
    format(Instant.now())
  }

  /** IMF-fixdate formatter (preferred): `Sun, 06 Nov 1994 08:49:37 GMT`. */
  private val imfFixdateFormatter: DateTimeFormatter = {
    new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
      .toFormatter(Locale.US)
      .withZone(ZoneOffset.UTC)
  }

  /** Attempts to parse an IMF-fixdate format date.
    *
    * @param value
    *   the date string to parse
    * @return
    *   Some(Eru) if parsing succeeds, None if format doesn't match
    */
  private def parseImfFixdate(value: String): Option[Eru[ParseError, Instant]] = {
    try {
      val zdt = ZonedDateTime.parse(value, imfFixdateFormatter)
      Some(Eru.succeed(zdt.toInstant))
    } catch {
      case _: DateTimeParseException => None
    }
  }

  /** RFC 850 formatter (obsolete): `Sunday, 06-Nov-94 08:49:37 GMT`.
    *
    * 2-digit years are ambiguous; a pivot year of 1950 maps 00-49 to 2000-2049 and 50-99 to
    * 1950-1999.
    */
  private val rfc850Formatter: DateTimeFormatter = {
    new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendPattern("EEEE, dd-MMM-")
      .appendValueReduced(ChronoField.YEAR, 2, 2, 1950)
      .appendPattern(" HH:mm:ss 'GMT'")
      .toFormatter(Locale.US)
      .withZone(ZoneOffset.UTC)
  }

  /** Attempts to parse an RFC 850 format date.
    *
    * @param value
    *   the date string to parse
    * @return
    *   Some(Eru) if parsing succeeds, None if format doesn't match
    */
  private def parseRfc850(value: String): Option[Eru[ParseError, Instant]] = {
    try {
      val zdt = ZonedDateTime.parse(value, rfc850Formatter)
      Some(Eru.succeed(zdt.toInstant))
    } catch {
      case _: DateTimeParseException => None
    }
  }

  /** ANSI C asctime formatter: `Sun Nov 6 08:49:37 1994`.
    *
    * Single-digit days are preceded by a single space (no padding).
    */
  private val asctimeFormatter: DateTimeFormatter = {
    new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendPattern("EEE MMM ")
      .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
      .appendPattern(" HH:mm:ss yyyy")
      .toFormatter(Locale.US)
      .withZone(ZoneOffset.UTC)
  }

  /** Alternative asctime formatter for inputs where single-digit days carry two leading spaces. */
  private val asctimeFormatterAlt: DateTimeFormatter = {
    new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendPattern("EEE MMM  ")
      .appendValue(ChronoField.DAY_OF_MONTH, 1, 1, SignStyle.NOT_NEGATIVE)
      .appendPattern(" HH:mm:ss yyyy")
      .toFormatter(Locale.US)
      .withZone(ZoneOffset.UTC)
  }

  /** Attempts to parse an ANSI C asctime format date.
    *
    * @param value
    *   the date string to parse
    * @return
    *   Some(Eru) if parsing succeeds, None if format doesn't match
    */
  private def parseAsctime(value: String): Option[Eru[ParseError, Instant]] = {
    try {
      val zdt = ZonedDateTime.parse(value, asctimeFormatter)
      Some(Eru.succeed(zdt.toInstant))
    } catch {
      case _: DateTimeParseException =>
        try {
          val zdt = ZonedDateTime.parse(value, asctimeFormatterAlt)
          Some(Eru.succeed(zdt.toInstant))
        } catch {
          case _: DateTimeParseException => None
        }
    }
  }

  /** Parses a date and validates it's not in the future (useful for Last-Modified).
    *
    * @param value
    *   the date string to parse
    * @return
    *   the parsed Instant or an error if invalid or in the future
    */
  def parseNotFuture(value: String): Eru[ParseError, Instant] = {
    parse(value).flatMap { instant =>
      if instant.isAfter(Instant.now()) then {
        Eru.fail(
          ParseError(
            value,
            "Date cannot be in the future",
            "RFC 9110 Section 8.8.2"
          )
        )
      } else {
        Eru.succeed(instant)
      }
    }
  }

  /** Checks if a resource has been modified since a given date.
    *
    * Used for implementing If-Modified-Since logic. Per RFC 9110, both instants are truncated to
    * whole seconds before comparison, since HTTP dates carry no sub-second precision.
    *
    * @param lastModified
    *   the last modified instant of the resource
    * @param ifModifiedSince
    *   the instant to compare against
    * @return
    *   true if the resource has been modified since the given instant
    */
  def isModifiedSince(lastModified: Instant, ifModifiedSince: Instant): Boolean = {
    val lastModifiedSeconds = lastModified.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    val ifModifiedSinceSeconds = ifModifiedSince.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

    lastModifiedSeconds.isAfter(ifModifiedSinceSeconds)
  }

  /** Checks if a resource has not been modified since a given date.
    *
    * Used for implementing If-Unmodified-Since logic.
    *
    * @param lastModified
    *   the last modified instant of the resource
    * @param ifUnmodifiedSince
    *   the instant to compare against
    * @return
    *   true if the resource has not been modified since the given instant
    */
  def isUnmodifiedSince(lastModified: Instant, ifUnmodifiedSince: Instant): Boolean = {
    !isModifiedSince(lastModified, ifUnmodifiedSince)
  }
}
