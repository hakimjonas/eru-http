package net.ghoula.eru.http

import munit.FunSuite

import net.ghoula.eru.http.TestHelpers.*

class CompressionSpec extends FunSuite {

  // Helper to create test data
  def testString: String = "The quick brown fox jumps over the lazy dog. " * 100
  def testBytes: Bytes = Bytes.fromString(testString, Charset.UTF8)

  // ===== GZIP Compression Tests =====

  test("Compression - gzip compress and decompress text") {
    val original = testBytes

    val compressed = Compression.compress(original, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    // Verify decompressed matches original
    assert(decompressed === original)

    // Verify compression actually reduced size
    assert(
      compressed.length < original.length,
      s"Compressed size ${compressed.length} should be less than original ${original.length}"
    )

    // Log compression ratio
    val ratio = (1.0 - compressed.length.toDouble / original.length.toDouble) * 100
    println(f"GZIP compression ratio: $ratio%.2f%% reduction")
  }

  test("Compression - gzip compress small data") {
    val small = Bytes.fromString("Hello, World!", Charset.UTF8)

    val compressed = Compression.compress(small, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === small)

    // Small data might not compress well, but should still work
    println(
      s"GZIP small data: original=${small.length}, compressed=${compressed.length}"
    )
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
    // Create binary data with various byte values
    val binaryData = Bytes.fromArray((0 to 255).map(_.toByte).toArray ++ (0 to 255).map(_.toByte).toArray)

    val compressed = Compression.compress(binaryData, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === binaryData)

    val ratio = (1.0 - compressed.length.toDouble / binaryData.length.toDouble) * 100
    println(f"GZIP binary compression ratio: $ratio%.2f%% reduction")
  }

  // ===== DEFLATE Compression Tests =====

  test("Compression - deflate compress and decompress text") {
    val original = testBytes

    val compressed = Compression.compress(original, ContentEncoding.Deflate).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess

    assert(decompressed === original)
    assert(compressed.length < original.length)

    val ratio = (1.0 - compressed.length.toDouble / original.length.toDouble) * 100
    println(f"DEFLATE compression ratio: $ratio%.2f%% reduction")
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

  // ===== Identity (No-Op) Tests =====

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

  // ===== BROTLI Compression Tests =====

  test("Compression - brotli compress and decompress text") {
    val original = testBytes

    val compressed = Compression.compress(original, ContentEncoding.Brotli).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess

    // Verify decompressed matches original
    assert(decompressed === original)

    // Verify compression actually reduced size
    assert(
      compressed.length < original.length,
      s"Compressed size ${compressed.length} should be less than original ${original.length}"
    )

    // Log compression ratio
    val ratio = (1.0 - compressed.length.toDouble / original.length.toDouble) * 100
    println(f"BROTLI compression ratio: $ratio%.2f%% reduction")
  }

  test("Compression - brotli compress small data") {
    val small = Bytes.fromString("Hello, World!", Charset.UTF8)

    val compressed = Compression.compress(small, ContentEncoding.Brotli).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Brotli).assertSuccess

    assert(decompressed === small)

    println(
      s"BROTLI small data: original=${small.length}, compressed=${compressed.length}"
    )
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

    val ratio = (1.0 - compressed.length.toDouble / binaryData.length.toDouble) * 100
    println(f"BROTLI binary compression ratio: $ratio%.2f%% reduction")
  }

  test("Compression - brotli achieves better compression on repetitive data") {
    // Highly repetitive data - Brotli should excel here
    val repetitive = Bytes.fromString("A" * 10000, Charset.UTF8)

    val brotliCompressed = Compression.compress(repetitive, ContentEncoding.Brotli).assertSuccess
    val gzipCompressed = Compression.compress(repetitive, ContentEncoding.Gzip).assertSuccess

    val brotliRatio = (1.0 - brotliCompressed.length.toDouble / repetitive.length.toDouble) * 100
    val gzipRatio = (1.0 - gzipCompressed.length.toDouble / repetitive.length.toDouble) * 100

    println("Compression comparison on highly repetitive data (10000 'A's):")
    println(f"  BROTLI: $brotliRatio%.2f%% reduction (${brotliCompressed.length} bytes)")
    println(f"  GZIP:   $gzipRatio%.2f%% reduction (${gzipCompressed.length} bytes)")

    // Brotli should achieve high compression
    assert(brotliRatio > 90.0, f"Expected >90%% compression, got $brotliRatio%.2f%%")

    // Brotli should be equal or better than gzip for repetitive data
    assert(
      brotliCompressed.length <= gzipCompressed.length,
      "Brotli should compress as well or better than gzip on repetitive data"
    )
  }

  test("Compression - brotli round-trip multiple times") {
    var data = testBytes

    // Compress and decompress 5 times
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

    // Decompress to verify
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

  // ===== Unsupported Encodings Tests =====

  test("Compression - custom encoding fails") {
    val data = testBytes
    val custom = ContentEncoding.Custom("x-custom")

    val result = Compression.compress(data, custom)
    assert(result.isFailure)

    val error = result.assertFailure
    assert(error.message.contains("Unsupported"))
  }

  // ===== Stream Compression Tests =====

  test("Compression - gzip compress stream") {
    val original = testBytes
    val stream = ChunkStream.fromBytes(original)

    val compressedStream = Compression.compressStream(stream, ContentEncoding.Gzip).assertSuccess
    val compressedBytes = compressedStream.toBytes.assertSuccess

    // Decompress to verify
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

  // ===== Compression Comparison Tests =====

  test("Compression - compare gzip vs deflate vs brotli compression ratios") {
    val original = testBytes

    val gzipCompressed = Compression.compress(original, ContentEncoding.Gzip).assertSuccess
    val deflateCompressed = Compression.compress(original, ContentEncoding.Deflate).assertSuccess
    val brotliCompressed = Compression.compress(original, ContentEncoding.Brotli).assertSuccess

    val gzipRatio = (1.0 - gzipCompressed.length.toDouble / original.length.toDouble) * 100
    val deflateRatio = (1.0 - deflateCompressed.length.toDouble / original.length.toDouble) * 100
    val brotliRatio = (1.0 - brotliCompressed.length.toDouble / original.length.toDouble) * 100

    println(s"Original size: ${original.length} bytes")
    println(f"GZIP compressed: ${gzipCompressed.length} bytes ($gzipRatio%.2f%% reduction)")
    println(f"DEFLATE compressed: ${deflateCompressed.length} bytes ($deflateRatio%.2f%% reduction)")
    println(f"BROTLI compressed: ${brotliCompressed.length} bytes ($brotliRatio%.2f%% reduction)")

    // All should achieve some compression
    assert(gzipCompressed.length < original.length)
    assert(deflateCompressed.length < original.length)
    assert(brotliCompressed.length < original.length)

    // Brotli should typically achieve better or equal compression
    println(
      f"Brotli improvement over gzip: ${((gzipCompressed.length - brotliCompressed.length).toDouble / gzipCompressed.length.toDouble) * 100}%.2f%%"
    )
  }

  test("Compression - highly compressible data") {
    // Repeated pattern - should compress very well
    val repetitive = Bytes.fromString("A" * 10000, Charset.UTF8)

    val gzipCompressed = Compression.compress(repetitive, ContentEncoding.Gzip).assertSuccess
    val deflateCompressed = Compression.compress(repetitive, ContentEncoding.Deflate).assertSuccess

    val gzipRatio = (1.0 - gzipCompressed.length.toDouble / repetitive.length.toDouble) * 100
    val deflateRatio = (1.0 - deflateCompressed.length.toDouble / repetitive.length.toDouble) * 100

    println("Highly compressible data (10000 'A's):")
    println(f"  GZIP: $gzipRatio%.2f%% reduction")
    println(f"  DEFLATE: $deflateRatio%.2f%% reduction")

    // Should achieve very high compression (>95%)
    assert(gzipRatio > 90.0, f"Expected >90%% compression, got $gzipRatio%.2f%%")
    assert(deflateRatio > 90.0, f"Expected >90%% compression, got $deflateRatio%.2f%%")
  }

  test("Compression - random-like data") {
    // Less compressible data
    val random = Bytes.fromString(
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore.",
      Charset.UTF8
    )

    val compressed = Compression.compress(random, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === random)

    val ratio = (1.0 - compressed.length.toDouble / random.length.toDouble) * 100
    println(f"Random-like data compression ratio: $ratio%.2f%% reduction")
  }

  // ===== Round-Trip Tests =====

  test("Compression - round-trip gzip multiple times") {
    var data = testBytes

    // Compress and decompress 5 times
    for (i <- 1 to 5) {
      val compressed = Compression.compress(data, ContentEncoding.Gzip).assertSuccess
      val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess
      assert(decompressed === data, s"Round-trip $i failed")
      data = decompressed
    }
  }

  test("Compression - round-trip deflate multiple times") {
    var data = testBytes

    // Compress and decompress 5 times
    for (i <- 1 to 5) {
      val compressed = Compression.compress(data, ContentEncoding.Deflate).assertSuccess
      val decompressed = Compression.decompress(compressed, ContentEncoding.Deflate).assertSuccess
      assert(decompressed === data, s"Round-trip $i failed")
      data = decompressed
    }
  }

  // ===== Edge Cases =====

  test("Compression - large data (1MB)") {
    val largeData = Bytes.fromString("x" * 1024 * 1024, Charset.UTF8) // 1MB

    val compressed = Compression.compress(largeData, ContentEncoding.Gzip).assertSuccess
    val decompressed = Compression.decompress(compressed, ContentEncoding.Gzip).assertSuccess

    assert(decompressed === largeData)

    val ratio = (1.0 - compressed.length.toDouble / largeData.length.toDouble) * 100
    println(f"Large data (1MB) compression ratio: $ratio%.2f%% reduction")
  }
}
