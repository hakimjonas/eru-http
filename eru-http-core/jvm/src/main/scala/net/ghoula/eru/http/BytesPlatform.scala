package net.ghoula.eru.http

import net.ghoula.eru.*

/** JVM platform-specific implementation for Bytes and Charset.
  *
  * Uses java.lang.Array[Byte] and java.nio.charset as underlying implementations. Zero-cost
  * abstractions via opaque types.
  */

/** Character encoding for text/binary conversions. Hides java.nio.charset as an implementation
  * detail.
  */
opaque type Charset = String

object Charset {

  /** UTF-8 charset (recommended default).
    */
  val UTF8: Charset = "UTF-8"

  /** ISO-8859-1 (Latin-1) charset.
    */
  val ISO_8859_1: Charset = "ISO-8859-1"

  /** US-ASCII charset.
    */
  val US_ASCII: Charset = "US-ASCII"

  /** UTF-16 charset.
    */
  val UTF16: Charset = "UTF-16"

  /** Creates a Charset from a name with validation.
    */
  def fromName(name: String): Eru[InvalidCharset, Charset] = {
    Eru.effect {
      java.nio.charset.Charset.forName(name)
      name
    }.mapError(e => InvalidCharset(name, Option(e.getMessage).getOrElse("Invalid charset")))
  }

  extension (c: Charset) {

    /** The charset name.
      */
    def name: String = c

    /** Converts to java.nio.charset.Charset for interop.
      */
    def toJavaCharset: String = c
  }
}

/** Opaque type for byte arrays. Hides java.lang.Array[Byte] as an implementation detail.
  */
opaque type Bytes = Array[Byte]

object Bytes {
  import Charset.toJavaCharset

  /** Creates Bytes from an array.
    */
  def fromArray(arr: Array[Byte]): Bytes = arr

  /** Creates Bytes from a string with charset encoding.
    */
  def fromString(s: String, charset: Charset): Bytes =
    s.getBytes(charset.toJavaCharset)

  /** Empty bytes.
    */
  val empty: Bytes = Array.empty[Byte]

  extension (b: Bytes) {

    /** Converts to underlying array (for interop).
      */
    def toArray: Array[Byte] = b

    /** Length in bytes.
      */
    def length: Int = b.length

    /** Check if empty.
      */
    def isEmpty: Boolean = b.length == 0

    /** Check if non-empty.
      */
    def nonEmpty: Boolean = b.length != 0

    /** Decode to string using charset.
      */
    def asString(charset: Charset): String =
      new String(b, charset.toJavaCharset)

    /** Concatenate with another Bytes.
      */
    def ++(that: Bytes): Bytes =
      Array.concat(b, that)

    /** Value-based equality (compares bytes, not reference).
      */
    def ===(that: Bytes): Boolean =
      java.util.Arrays.equals(b, that)

    /** Value-based hash code.
      */
    def hash: Int = java.util.Arrays.hashCode(b)
  }
}

/** Invalid charset error.
  */
final case class InvalidCharset(
  name: String,
  reason: String
) extends Exception(s"Invalid charset: $name ($reason)")
