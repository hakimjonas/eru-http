package net.ghoula.eru.http

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.Decoder
import com.aayushatharva.brotli4j.encoder.Encoder

import java.io.*
import java.util.zip.*

import net.ghoula.eru.*

/** JVM implementation of compression and decompression for HTTP content encodings.
  *
  * Supports gzip, deflate, and brotli compression. Brotli support uses brotli4j library which
  * includes native libraries for major platforms (Linux, macOS, Windows).
  */
object Compression {

  // Load Brotli native library at initialization
  private val brotliAvailable: Boolean = {
    try {
      Brotli4jLoader.ensureAvailability()
      true
    } catch {
      case e: Exception =>
        // Brotli not available on this platform
        System.err.println(s"Warning: Brotli compression not available: ${e.getMessage}")
        false
    }
  }

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
      case ContentEncoding.Gzip => compressGzip(bytes)
      case ContentEncoding.Deflate => compressDeflate(bytes)
      case ContentEncoding.Identity => Eru.succeed(bytes)
      case ContentEncoding.Brotli => compressBrotli(bytes)
      case other =>
        Eru.fail(CompressionError(s"Unsupported compression encoding: ${other.value}", None))
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
      case ContentEncoding.Gzip => decompressGzip(bytes)
      case ContentEncoding.Deflate => decompressDeflate(bytes)
      case ContentEncoding.Identity => Eru.succeed(bytes)
      case ContentEncoding.Brotli => decompressBrotli(bytes)
      case other =>
        Eru.fail(CompressionError(s"Unsupported decompression encoding: ${other.value}", None))
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
        // For streaming compression, we collect the stream and compress as a whole
        // A more sophisticated implementation would use streaming compression
        stream.toBytes.flatMap { bytes =>
          compress(bytes, encoding).map(compressed => ChunkStream.fromBytes(compressed))
        }
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
        // For streaming decompression, we collect the stream and decompress as a whole
        // A more sophisticated implementation would use streaming decompression
        stream.toBytes.flatMap { bytes =>
          decompress(bytes, encoding).map(decompressed => ChunkStream.fromBytes(decompressed))
        }
    }
  }

  // ===== GZIP Implementation =====

  private def compressGzip(bytes: Bytes): Eru[CompressionError, Bytes] = {
    Eru.effect {
      val baos = new ByteArrayOutputStream()
      val gzipOut = new GZIPOutputStream(baos)
      try {
        gzipOut.write(bytes.toArray)
        gzipOut.finish()
        Bytes.fromArray(baos.toByteArray)
      } finally {
        gzipOut.close()
      }
    }.mapError(e =>
      CompressionError(
        s"GZIP compression failed: ${e.getMessage}",
        Some(e.getClass.getSimpleName),
        Some(e)
      )
    )
  }

  private def decompressGzip(bytes: Bytes): Eru[CompressionError, Bytes] = {
    Eru.effect {
      val bais = new ByteArrayInputStream(bytes.toArray)
      val gzipIn = new GZIPInputStream(bais)
      try {
        val baos = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        var len = gzipIn.read(buffer)
        while len > 0 do {
          baos.write(buffer, 0, len)
          len = gzipIn.read(buffer)
        }
        Bytes.fromArray(baos.toByteArray)
      } finally {
        gzipIn.close()
      }
    }.mapError(e =>
      CompressionError(
        s"GZIP decompression failed: ${e.getMessage}",
        Some(e.getClass.getSimpleName),
        Some(e)
      )
    )
  }

  // ===== DEFLATE Implementation =====

  private def compressDeflate(bytes: Bytes): Eru[CompressionError, Bytes] = {
    Eru.effect {
      val deflater = new Deflater()
      try {
        deflater.setInput(bytes.toArray)
        deflater.finish()

        val buffer = new Array[Byte](bytes.length + 1024) // Extra space for compression overhead
        val compressedLength = deflater.deflate(buffer)
        Bytes.fromArray(buffer.take(compressedLength))
      } finally {
        deflater.end()
      }
    }.mapError(e =>
      CompressionError(
        s"DEFLATE compression failed: ${e.getMessage}",
        Some(e.getClass.getSimpleName),
        Some(e)
      )
    )
  }

  private def decompressDeflate(bytes: Bytes): Eru[CompressionError, Bytes] = {
    Eru.effect {
      val inflater = new Inflater()
      try {
        inflater.setInput(bytes.toArray)

        val baos = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        while !inflater.finished() do {
          val len = inflater.inflate(buffer)
          if len > 0 then {
            baos.write(buffer, 0, len)
          }
        }
        Bytes.fromArray(baos.toByteArray)
      } finally {
        inflater.end()
      }
    }.mapError(e =>
      CompressionError(
        s"DEFLATE decompression failed: ${e.getMessage}",
        Some(e.getClass.getSimpleName),
        Some(e)
      )
    )
  }

  // ===== BROTLI Implementation =====

  private def compressBrotli(bytes: Bytes): Eru[CompressionError, Bytes] = {
    if !brotliAvailable then {
      Eru.fail(
        CompressionError(
          "Brotli compression not available on this platform",
          Some("Native library failed to load")
        )
      )
    } else {
      Eru.effect {
        val compressed = Encoder.compress(bytes.toArray)
        Bytes.fromArray(compressed)
      }.mapError(e =>
        CompressionError(
          s"Brotli compression failed: ${e.getMessage}",
          Some(e.getClass.getSimpleName),
          Some(e)
        )
      )
    }
  }

  private def decompressBrotli(bytes: Bytes): Eru[CompressionError, Bytes] = {
    if !brotliAvailable then {
      Eru.fail(
        CompressionError(
          "Brotli decompression not available on this platform",
          Some("Native library failed to load")
        )
      )
    } else {
      Eru.effect {
        val result = Decoder.decompress(bytes.toArray)
        Bytes.fromArray(result.getDecompressedData)
      }.mapError(e =>
        CompressionError(
          s"Brotli decompression failed: ${e.getMessage}",
          Some(e.getClass.getSimpleName),
          Some(e)
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
