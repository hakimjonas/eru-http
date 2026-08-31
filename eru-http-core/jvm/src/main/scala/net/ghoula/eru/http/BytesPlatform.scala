package net.ghoula.eru.http

import scala.collection.immutable.ArraySeq

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

/** Opaque type for immutable byte sequences.
  *
  * Backed by `ArraySeq.ofByte`, the JVM-specialized immutable byte wrapper. `ArraySeq` gives us:
  *   - Structural `equals` / `hashCode` derived from content (no hand-rolled override on
  *     `Body.Binary` / `Chunk` required).
  *   - True immutability: `fromArray` defensive-copies inputs, `toArray` defensive-copies outputs.
  *     A `Bytes` value cannot be mutated through any public API.
  *   - Zero boxing on `Byte` access (`ofByte` is a primitive-specialized subclass).
  *
  * For hot-path writers that need to hand the underlying array to a `ByteBuffer.wrap` / `IO`
  * syscall without a defensive copy, `unsafeArray` (package-private) provides a named escape hatch.
  * Its contract: the caller MUST NOT mutate the returned array. Any mutation is a bug that breaks
  * the `Bytes` immutability invariant for all other holders of the same value.
  */
opaque type Bytes = ArraySeq.ofByte

object Bytes {
  import Charset.toJavaCharset

  /** Creates Bytes from an array. Defensive-copies the input so the caller can later mutate `arr`
    * without affecting the resulting `Bytes` value.
    */
  def fromArray(arr: Array[Byte]): Bytes =
    new ArraySeq.ofByte(arr.clone())

  /** Creates Bytes from a string with charset encoding.
    *
    * `String.getBytes(charset)` already returns a fresh array (JDK contract), so no additional
    * defensive copy is needed — wrapping directly is safe.
    */
  def fromString(s: String, charset: Charset): Bytes =
    new ArraySeq.ofByte(s.getBytes(charset.toJavaCharset))

  /** Empty bytes. Shared singleton — zero-length arrays are safe to alias. */
  val empty: Bytes = new ArraySeq.ofByte(Array.emptyByteArray)

  extension (b: Bytes) {

    /** Defensive copy of the underlying byte array.
      *
      * Use when the caller may mutate the returned array, or when handing it to an external API
      * whose mutation contract is unknown. For trusted hot-path writers inside `http.*` that will
      * NOT mutate (e.g. `ByteBuffer.wrap`, `GZIPOutputStream.write`), use `unsafeArray`.
      */
    def toArray: Array[Byte] = b.unsafeArray match {
      case arr: Array[Byte] => arr.clone()
    }

    /** Zero-copy access to the underlying byte array. Package-private to signal that callers inside
      * `net.ghoula.eru.http.*` must not mutate the returned array. Mutation breaks the `Bytes`
      * immutability invariant and is a bug.
      */
    private[http] def unsafeArray: Array[Byte] = b.unsafeArray match {
      case arr: Array[Byte] => arr
    }

    /** Length in bytes. */
    def length: Int = b.length

    /** Check if empty. */
    def isEmpty: Boolean = b.isEmpty

    /** Check if non-empty. */
    def nonEmpty: Boolean = b.nonEmpty

    /** Decode to string using charset. Defensive-copies internally — the String constructor that
      * takes `(byte[], String)` is JDK-documented to copy the input.
      */
    def asString(charset: Charset): String =
      new String(unsafeArray, charset.toJavaCharset)

    /** Concatenate with another Bytes. Returns a fresh Bytes with its own backing array. */
    def ++(that: Bytes): Bytes =
      new ArraySeq.ofByte(Array.concat(b.unsafeArray, that.unsafeArray))

    /** Value-based equality. Kept as an alias for `==` (which ArraySeq.ofByte already implements
      * structurally) so existing `a === b` call sites continue to compile.
      */
    def ===(that: Bytes): Boolean = b == that

    /** Value-based hash code. Goes directly to `java.util.Arrays.hashCode` rather than delegating
      * to `ArraySeq.ofByte.hashCode`, which is ~24× slower due to its more general iteration-based
      * implementation. Measured: `b.hashCode` @ 1KB = ~760 ns/op; `Arrays.hashCode(unsafeArray)` @
      * 1KB = ~32 ns/op.
      */
    def hash: Int = java.util.Arrays.hashCode(unsafeArray)
  }
}

/** Invalid charset error.
  */
final case class InvalidCharset(
  name: String,
  reason: String
) {
  def message: String = s"Invalid charset: $name ($reason)"
}
