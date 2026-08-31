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

  private lazy val brotliAvailable: Boolean =
    probeExecSupport
      .flatMap(_ => probeBrotli)
      .fold(
        msg => { System.err.println(s"Warning: $msg"); false },
        _ => true
      )

  private def probeExecSupport: Either[String, Unit] = {
    val dir = Option(System.getProperty("eru.native.workdir"))
      .orElse(Option(System.getProperty("java.io.tmpdir")))
      .map(java.nio.file.Path.of(_))
      .toRight("No tmpdir available")
    dir.flatMap { path =>
      val probe = java.nio.file.Files.createTempFile(path, "eru-", ".probe")
      val executable = probe.toFile.nn.setExecutable(true) && probe.toFile.nn.canExecute()
      java.nio.file.Files.deleteIfExists(probe)
      Either.cond(
        executable,
        (),
        s"Brotli not available (${path} is noexec). Set -Deru.native.workdir to an executable directory."
      )
    }
  }

  private def probeBrotli: Either[String, Unit] =
    scala.util
      .Try(Brotli4jLoader.ensureAvailability())
      .toEither
      .left
      .map(e => s"Brotli native library failed to load: ${e.getMessage}")

  /** Whether a codec for this encoding is available on this platform.
    *
    * Gzip, deflate, and identity are always available via java.util.zip; brotli requires the
    * brotli4j native library, so availability is probed once at load time.
    */
  def isSupported(encoding: ContentEncoding): Boolean = encoding match {
    case ContentEncoding.Gzip | ContentEncoding.Deflate | ContentEncoding.Identity => true
    case ContentEncoding.Brotli => brotliAvailable
    case _ => false
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
    * The stream is collected and compressed as a whole, rather than with incremental streaming
    * compression.
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
        // A failure in the source stream (framing error, decompression failure) is carried into
        // the compressed stream as ChunkStream.Fail so the response write observes it instead of
        // silently compressing a truncated body.
        stream.toBytes.attempt.flatMap {
          case Result.Success(bytes) =>
            compress(bytes, encoding).map(compressed => ChunkStream.fromBytes(compressed))
          case Result.Failure(e) =>
            Eru.succeed(ChunkStream.fail(e)) // HttpError only: toBytes cannot fail with CompressionError
        }
    }
  }

  /** Decompresses a chunk stream using the specified encoding.
    *
    * The stream is collected and decompressed as a whole, rather than with incremental streaming
    * decompression.
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
        // See compressStream: source failures ride into the output stream as ChunkStream.Fail.
        stream.toBytes.attempt.flatMap {
          case Result.Success(bytes) =>
            decompress(bytes, encoding).map(decompressed => ChunkStream.fromBytes(decompressed))
          case Result.Failure(e) =>
            Eru.succeed(ChunkStream.fail(e)) // HttpError only: toBytes cannot fail with CompressionError
        }
    }
  }

  private def compressGzip(bytes: Bytes): Eru[CompressionError, Bytes] = {
    Eru.effect {
      val baos = new ByteArrayOutputStream()
      val gzipOut = new GZIPOutputStream(baos)
      try {
        gzipOut.write(bytes.unsafeArray)
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
      val bais = new ByteArrayInputStream(bytes.unsafeArray)
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

  /** DEFLATE compression. Drains the deflater in a loop until it reports the stream finished: raw
    * deflate output can exceed the input for incompressible data (stored blocks add header overhead
    * per 64 KiB block), so a single call into a fixed input-sized buffer would silently truncate
    * the stream.
    */
  private def compressDeflate(bytes: Bytes): Eru[CompressionError, Bytes] = {
    Eru.effect {
      val deflater = new Deflater()
      try {
        deflater.setInput(bytes.unsafeArray)
        deflater.finish()

        val out = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        while !deflater.finished() do {
          val n = deflater.deflate(buffer)
          if n > 0 then {
            out.write(buffer, 0, n)
          } else if !deflater.finished() then {
            throw new IllegalStateException("DEFLATE compression made no progress")
          }
        }
        Bytes.fromArray(out.toByteArray)
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
        inflater.setInput(bytes.unsafeArray)

        val baos = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        while !inflater.finished() do {
          val len = inflater.inflate(buffer)
          if len > 0 then {
            baos.write(buffer, 0, len)
          } else if !inflater.finished() then {
            // No progress and not finished: either the input ran out (truncated stream) or the
            // inflater is wedged. Bail out instead of spinning forever.
            if inflater.needsInput() then
              throw new java.util.zip.DataFormatException(
                "Truncated DEFLATE stream: input exhausted before the final block"
              )
            else
              throw new java.util.zip.DataFormatException(
                "DEFLATE decompression made no progress"
              )
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
        val compressed = Encoder.compress(bytes.unsafeArray)
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
        val result = Decoder.decompress(bytes.unsafeArray)
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
