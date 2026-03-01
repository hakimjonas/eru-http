package net.ghoula.eru.http

import net.ghoula.eru.*
import scala.scalajs.js
import scala.scalajs.js.typedarray._

/**
 * Scala.js platform-specific implementation for Bytes and Charset.
 *
 * Uses js.typedarray.Uint8Array as underlying implementation for Bytes.
 * Zero-cost abstractions via opaque types.
 *
 * TODO: Implement full Scala.js support for:
 * - TextEncoder/TextDecoder for charset operations
 * - Uint8Array operations
 * - Browser and Node.js compatibility
 */

/**
 * Character encoding for text/binary conversions.
 * For Scala.js, charset names are used with TextEncoder/TextDecoder.
 */
opaque type Charset = String

object Charset {
  /**
   * UTF-8 charset (recommended default).
   */
  val UTF8: Charset = "utf-8"

  /**
   * ISO-8859-1 (Latin-1) charset.
   */
  val ISO_8859_1: Charset = "iso-8859-1"

  /**
   * US-ASCII charset.
   */
  val US_ASCII: Charset = "us-ascii"

  /**
   * UTF-16 charset.
   */
  val UTF16: Charset = "utf-16"

  /**
   * Creates a Charset from a name with validation.
   * TODO: Validate against TextEncoder supported encodings.
   */
  def fromName(name: String): Eru[InvalidCharset, Charset] = {
    // For now, accept any charset name
    // TODO: Validate using TextEncoder in browser/Node.js
    Eru.succeed(name)
  }

  extension (c: Charset) {
    /**
     * The charset name.
     */
    def name: String = c

    /**
     * Returns charset name for use with TextEncoder/TextDecoder.
     */
    def toJavaCharset: String = c  // Name kept for API compatibility
  }
}

/**
 * Opaque type for byte arrays.
 * Uses Uint8Array on Scala.js platform.
 *
 * TODO: Full implementation using Uint8Array operations.
 */
opaque type Bytes = Array[Byte]  // Temporary - should be Uint8Array

object Bytes {
  import Charset.toJavaCharset

  /**
   * Creates Bytes from an array.
   * TODO: Convert to Uint8Array.
   */
  def fromArray(arr: Array[Byte]): Bytes = arr

  /**
   * Creates Bytes from a string with charset encoding.
   * TODO: Use TextEncoder.
   */
  def fromString(s: String, charset: Charset): Bytes = {
    // Temporary implementation
    s.getBytes(java.nio.charset.Charset.forName(charset.toJavaCharset))
  }

  /**
   * Empty bytes.
   */
  val empty: Bytes = Array.empty[Byte]

  extension (b: Bytes) {
    /**
     * Converts to underlying array.
     */
    def toArray: Array[Byte] = b

    /**
     * Length in bytes.
     */
    def length: Int = b.length

    /**
     * Check if empty.
     */
    def isEmpty: Boolean = b.length == 0

    /**
     * Check if non-empty.
     */
    def nonEmpty: Boolean = b.length != 0

    /**
     * Decode to string using charset.
     * TODO: Use TextDecoder.
     */
    def asString(charset: Charset): String = {
      // Temporary implementation
      new String(b, java.nio.charset.Charset.forName(charset.toJavaCharset))
    }

    /**
     * Concatenate with another Bytes.
     */
    def ++(that: Bytes): Bytes =
      Array.concat(b, that)

    /**
     * Value-based equality.
     */
    def ===(that: Bytes): Boolean = {
      if (b.length != that.length) false
      else {
        var i = 0
        while (i < b.length) {
          if (b(i) != that(i)) return false
          i += 1
        }
        true
      }
    }

    /**
     * Value-based hash code.
     */
    def hash: Int = {
      var h = 1
      var i = 0
      while (i < b.length) {
        h = 31 * h + b(i)
        i += 1
      }
      h
    }
  }
}

/**
 * Invalid charset error.
 */
final case class InvalidCharset(
  name: String,
  reason: String
) {
  def message: String = s"Invalid charset: $name ($reason)"
}
