package net.ghoula.eru.http

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.TestHelpers.*

class CompressionSpec extends FunSuite {

  def testString: String = "The quick brown fox jumps over the lazy dog. " * 100
  def testBytes: Bytes = Bytes.fromString(testString, Charset.UTF8)

  test("Compression - gzip compress and decompress text") {
    val original = testBytes

    val compressed = Compression.compress(original, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === original)

    assert(
      compressed.length < original.length,
      s"Compressed size ${compressed.length} should be less than original ${original.length}"
    )
  }

  test("Compression - gzip compress small data") {
    val small = Bytes.fromString("Hello, World!", Charset.UTF8)

    val compressed = Compression.compress(small, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === small)
  }

  test("Compression - gzip compress empty data") {
    val empty = Bytes.empty

    val compressed = Compression.compress(empty, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === empty)
    assert(decompressed.isEmpty)
  }

  test("Compression - gzip decompress fails on invalid data") {
    val invalid = Bytes.fromString("This is not gzipped data", Charset.UTF8)

    val result = Compression.decompress(invalid, ContentEncoding.Gzip)
    assert(result.isFailure)

    val error = result.assertFailure
    assert(error.message.contains("GZIP decompression failed"))
  }

  test("Compression - gzip handles binary data") {
    val binaryData = Bytes.fromArray((0 to 255).map(_.toByte).toArray ++ (0 to 255).map(_.toByte).toArray)

    val compressed = Compression.compress(binaryData, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === binaryData)

  }

  test("Compression - deflate compress and decompress text") {
    val original = testBytes

    val compressed = Compression.compress(original, ContentEncoding.Deflate).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess

    assert(decompressed === original)
    assert(compressed.length < original.length)

  }

  test("Compression - deflate compress small data") {
    val small = Bytes.fromString("Hello, World!", Charset.UTF8)

    val compressed = Compression.compress(small, ContentEncoding.Deflate).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess

    assert(decompressed === small)
  }

  test("Compression - deflate compress empty data") {
    val empty = Bytes.empty

    val compressed = Compression.compress(empty, ContentEncoding.Deflate).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess

    assert(decompressed === empty)
    assert(decompressed.isEmpty)
  }

  test("Compression - deflate decompress fails on invalid data") {
    val invalid = Bytes.fromString("This is not deflated data", Charset.UTF8)

    val result = Compression.decompress(invalid, ContentEncoding.Deflate)
    assert(result.isFailure)

    val error = result.assertFailure
    assert(error.message.contains("DEFLATE decompression failed"))
  }

  test("Compression - deflate handles binary data") {
    val binaryData = Bytes.fromArray((0 to 255).map(_.toByte).toArray ++ (0 to 255).map(_.toByte).toArray)

    val compressed = Compression.compress(binaryData, ContentEncoding.Deflate).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess

    assert(decompressed === binaryData)
  }

  test("Compression - identity encoding returns original data") {
    val original = testBytes

    val result = Compression.compress(original, ContentEncoding.Identity).assertSuccess
    assert(result === original)
  }

  test("Compression - identity decompression returns original data") {
    val original = testBytes

    val result = Compression.decompress(original, ContentEncoding.Identity).assertSuccess
    assert(result === original)
  }

  test("Compression - brotli compress and decompress text") {
    val original = testBytes

    val compressed = Compression.compress(original, ContentEncoding.Brotli).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess

    assert(decompressed === original)

    assert(
      compressed.length < original.length,
      s"Compressed size ${compressed.length} should be less than original ${original.length}"
    )

  }

  test("Compression - brotli compress small data") {
    val small = Bytes.fromString("Hello, World!", Charset.UTF8)

    val compressed = Compression.compress(small, ContentEncoding.Brotli).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess

    assert(decompressed === small)
  }

  test("Compression - brotli compress empty data") {
    val empty = Bytes.empty

    val compressed = Compression.compress(empty, ContentEncoding.Brotli).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess

    assert(decompressed === empty)
    assert(decompressed.isEmpty)
  }

  test("Compression - brotli handles binary data") {
    val binaryData = Bytes.fromArray((0 to 255).map(_.toByte).toArray ++ (0 to 255).map(_.toByte).toArray)

    val compressed = Compression.compress(binaryData, ContentEncoding.Brotli).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess

    assert(decompressed === binaryData)

  }

  test("Compression - brotli achieves better compression on repetitive data") {
    val repetitive = Bytes.fromString("A" * 10000, Charset.UTF8)

    val brotliCompressed = Compression.compress(repetitive, ContentEncoding.Brotli).assertSuccess
    val gzipCompressed = Compression.compress(repetitive, ContentEncoding.Gzip).assertSuccess

    val brotliRatio = (1.0 - brotliCompressed.length.toDouble / repetitive.length.toDouble) * 100

    assert(brotliRatio > 90.0, f"Expected >90%% compression, got $brotliRatio%.2f%%")

    assert(
      brotliCompressed.length <= gzipCompressed.length,
      "Brotli should compress as well or better than gzip on repetitive data"
    )
  }

  test("Compression - brotli round-trip multiple times") {
    var data = testBytes

    for (i <- 1 to 5) {
      val compressed = Compression.compress(data, ContentEncoding.Brotli).assertSuccess
      val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess
      assert(decompressed === data, s"Round-trip $i failed")
      data = decompressed
    }
  }

  test("Compression - brotli stream compression") {
    val original = testBytes
    val stream = ChunkStream.fromBytes(original)

    val compressedStream = Compression.compressStream(stream, ContentEncoding.Brotli).assertSuccess
    val compressedBytes = compressedStream.toBytes.assertSuccess

    val decompressed = Compression.decompress(compressedBytes, ContentEncoding.Brotli).assertSuccess
    assert(decompressed === original)
  }

  test("Compression - brotli stream decompression") {
    val original = testBytes
    val compressed = Compression.compress(original, ContentEncoding.Brotli).assertSuccess
    val stream = ChunkStream.fromBytes(compressed)

    val decompressedStream = Compression.decompressStream(stream, ContentEncoding.Brotli).assertSuccess
    val decompressed = decompressedStream.toBytes.assertSuccess

    assert(decompressed === original)
  }

  test("Compression - custom encoding fails") {
    val data = testBytes
    val custom = ContentEncoding.Custom("x-custom")

    val result = Compression.compress(data, custom)
    assert(result.isFailure)

    val error = result.assertFailure
    assert(error.message.contains("Unsupported"))
  }

  test("Compression - gzip compress stream") {
    val original = testBytes
    val stream = ChunkStream.fromBytes(original)

    val compressedStream = Compression.compressStream(stream, ContentEncoding.Gzip).assertSuccess
    val compressedBytes = compressedStream.toBytes.assertSuccess

    val decompressed = Compression.decompress(compressedBytes, ContentEncoding.Gzip).assertSuccess
    assert(decompressed === original)
  }

  test("Compression - deflate compress stream") {
    val original = testBytes
    val stream = ChunkStream.fromBytes(original)

    val compressedStream = Compression.compressStream(stream, ContentEncoding.Deflate).assertSuccess
    val compressedBytes = compressedStream.toBytes.assertSuccess

    val decompressed = Compression.decompress(compressedBytes, ContentEncoding.Deflate).assertSuccess
    assert(decompressed === original)
  }

  test("Compression - gzip decompress stream") {
    val original = testBytes
    val compressed = Compression.compress(original, ContentEncoding.Gzip).assertSuccess
    val stream = ChunkStream.fromBytes(compressed)

    val decompressedStream = Compression.decompressStream(stream, ContentEncoding.Gzip).assertSuccess
    val decompressed = decompressedStream.toBytes.assertSuccess

    assert(decompressed === original)
  }

  test("Compression - deflate decompress stream") {
    val original = testBytes
    val compressed = Compression.compress(original, ContentEncoding.Deflate).assertSuccess
    val stream = ChunkStream.fromBytes(compressed)

    val decompressedStream = Compression.decompressStream(stream, ContentEncoding.Deflate).assertSuccess
    val decompressed = decompressedStream.toBytes.assertSuccess

    assert(decompressed === original)
  }

  test("Compression - identity stream returns same stream") {
    val original = testBytes
    val stream = ChunkStream.fromBytes(original)

    val result = Compression.compressStream(stream, ContentEncoding.Identity).assertSuccess
    val resultBytes = result.toBytes.assertSuccess

    assert(resultBytes === original)
  }

  test("Compression - compare gzip vs deflate vs brotli compression ratios") {
    val original = testBytes

    val gzipCompressed = Compression.compress(original, ContentEncoding.Gzip).assertSuccess
    val deflateCompressed = Compression.compress(original, ContentEncoding.Deflate).assertSuccess
    val brotliCompressed = Compression.compress(original, ContentEncoding.Brotli).assertSuccess

    assert(gzipCompressed.length < original.length)
    assert(deflateCompressed.length < original.length)
    assert(brotliCompressed.length < original.length)
  }

  test("Compression - highly compressible data") {
    val repetitive = Bytes.fromString("A" * 10000, Charset.UTF8)

    val gzipCompressed = Compression.compress(repetitive, ContentEncoding.Gzip).assertSuccess
    val deflateCompressed = Compression.compress(repetitive, ContentEncoding.Deflate).assertSuccess

    val gzipRatio = (1.0 - gzipCompressed.length.toDouble / repetitive.length.toDouble) * 100
    val deflateRatio = (1.0 - deflateCompressed.length.toDouble / repetitive.length.toDouble) * 100

    assert(gzipRatio > 90.0, f"Expected >90%% compression, got $gzipRatio%.2f%%")
    assert(deflateRatio > 90.0, f"Expected >90%% compression, got $deflateRatio%.2f%%")
  }

  test("Compression - random-like data") {
    val random = Bytes.fromString(
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore.",
      Charset.UTF8
    )

    val compressed = Compression.compress(random, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === random)
  }

  test("Compression - round-trip gzip multiple times") {
    var data = testBytes

    for (i <- 1 to 5) {
      val compressed = Compression.compress(data, ContentEncoding.Gzip).assertSuccess
      val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess
      assert(decompressed === data, s"Round-trip $i failed")
      data = decompressed
    }
  }

  test("Compression - round-trip deflate multiple times") {
    var data = testBytes

    for (i <- 1 to 5) {
      val compressed = Compression.compress(data, ContentEncoding.Deflate).assertSuccess
      val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess
      assert(decompressed === data, s"Round-trip $i failed")
      data = decompressed
    }
  }

  test("Compression - large data (1MB)") {
    val largeData = Bytes.fromString("x" * 1024 * 1024, Charset.UTF8)

    val compressed = Compression.compress(largeData, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === largeData)
  }

  test("Compression - decompressing a truncated deflate stream fails fast instead of hanging") {
    val original = Bytes.fromString("payload that compresses well. " * 100, Charset.UTF8)
    val full = Compression.compress(original, ContentEncoding.Deflate).assertSuccess

    val truncated = Bytes.fromArray(full.unsafeArray.dropRight(5))
    Compression.decompress(truncated, ContentEncoding.Deflate).attempt.runTest match {
      case Result.Failure(_) => () // expected: truncated input must be reported, not spun on
      case Result.Success(_) => fail("a truncated DEFLATE stream decompressed without error")
    }
  }

  test("Compression - deflate round-trips large incompressible data (stored-block overhead)") {
    // 16 MiB of random bytes: raw deflate cannot shrink it, and the stored-block overhead
    // (5 bytes per 64 KiB block, plus block boundaries) far exceeds any fixed small margin, so
    // a fixed input-sized output buffer silently truncates the stream.
    val input = new Array[Byte](16 * 1024 * 1024)
    new java.util.Random(42).nextBytes(input)
    val original = Bytes.fromArray(input)

    val compressed = Compression.compress(original, ContentEncoding.Deflate).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess

    assertEquals(decompressed.unsafeArray.toSeq, input.toSeq)
  }
}
