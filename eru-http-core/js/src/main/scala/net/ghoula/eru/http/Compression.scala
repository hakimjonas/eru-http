package net.ghoula.eru.http

import net.ghoula.eru.*

/** Scala.js implementation of compression and decompression for HTTP content encodings.
  *
  * TODO: Implement using Node.js zlib or browser CompressionStream API.
  *
  * For now, this is a stub implementation that returns errors for all compression operations. Full
  * implementation requires:
  *   - Node.js: zlib module facade
  *   - Browser: CompressionStream/DecompressionStream API (Chrome 80+, Firefox 113+)
  */
object Compression {

  /** Compresses bytes using the specified encoding.
    *
    * @param bytes
    *   the uncompressed data
    * @param encoding
    *   the compression encoding to use
    * @return
    *   compressed bytes or an error
    */
  def compress(bytes: Bytes, encoding: ContentEncoding): Eru[CompressionError, Bytes] = {
    encoding match {
      case ContentEncoding.Identity => Eru.succeed(bytes)
      case _ =>
        Eru.fail(
          CompressionError(
            s"Compression not yet implemented for Scala.js: ${encoding.value}",
            Some("Requires Node.js zlib or browser CompressionStream API")
          )
        )
    }
  }

  /** Decompresses bytes using the specified encoding.
    *
    * @param bytes
    *   the compressed data
    * @param encoding
    *   the compression encoding used
    * @return
    *   decompressed bytes or an error
    */
  def decompress(bytes: Bytes, encoding: ContentEncoding): Eru[CompressionError, Bytes] = {
    encoding match {
      case ContentEncoding.Identity => Eru.succeed(bytes)
      case _ =>
        Eru.fail(
          CompressionError(
            s"Decompression not yet implemented for Scala.js: ${encoding.value}",
            Some("Requires Node.js zlib or browser DecompressionStream API")
          )
        )
    }
  }

  /** Compresses a chunk stream using the specified encoding.
    *
    * @param stream
    *   the uncompressed chunk stream
    * @param encoding
    *   the compression encoding to use
    * @return
    *   compressed chunk stream or an error
    */
  def compressStream(
    stream: ChunkStream,
    encoding: ContentEncoding
  ): Eru[CompressionError, ChunkStream] = {
    encoding match {
      case ContentEncoding.Identity => Eru.succeed(stream)
      case _ =>
        Eru.fail(
          CompressionError(
            s"Stream compression not yet implemented for Scala.js: ${encoding.value}",
            Some("Requires Node.js zlib or browser CompressionStream API")
          )
        )
    }
  }

  /** Decompresses a chunk stream using the specified encoding.
    *
    * @param stream
    *   the compressed chunk stream
    * @param encoding
    *   the compression encoding used
    * @return
    *   decompressed chunk stream or an error
    */
  def decompressStream(
    stream: ChunkStream,
    encoding: ContentEncoding
  ): Eru[CompressionError, ChunkStream] = {
    encoding match {
      case ContentEncoding.Identity => Eru.succeed(stream)
      case _ =>
        Eru.fail(
          CompressionError(
            s"Stream decompression not yet implemented for Scala.js: ${encoding.value}",
            Some("Requires Node.js zlib or browser DecompressionStream API")
          )
        )
    }
  }

  /** Error that occurs during compression or decompression.
    *
    * @param message
    *   the error message
    * @param hint
    *   optional hint for resolving the error
    * @param cause
    *   optional underlying exception that caused this error
    */
  final case class CompressionError(
    message: String,
    hint: Option[String] = None,
    cause: Option[Throwable] = None
  )
}
