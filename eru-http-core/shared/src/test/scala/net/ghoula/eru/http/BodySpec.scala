package net.ghoula.eru.http

import munit.*

import net.ghoula.eru.*

import TestHelpers.*

class BodySpec extends FunSuite {

  // ===== Bytes Tests =====

  test("Bytes - fromString encodes string to bytes") {
    val bytes = Bytes.fromString("hello", Charset.UTF8)
    assert(bytes.length == 5)
    assert(!bytes.isEmpty)
    assert(bytes.nonEmpty)
  }

  test("Bytes - fromArray creates from byte array") {
    val arr = Array[Byte](1, 2, 3, 4, 5)
    val bytes = Bytes.fromArray(arr)
    assertEquals(bytes.length, 5)
  }

  test("Bytes - empty bytes") {
    val empty = Bytes.empty
    assert(empty.isEmpty)
    assert(!empty.nonEmpty)
    assertEquals(empty.length, 0)
  }

  test("Bytes - asString decodes bytes to string") {
    val original = "Hello, World!"
    val bytes = Bytes.fromString(original, Charset.UTF8)
    val decoded = bytes.asString(Charset.UTF8)
    assertEquals(decoded, original)
  }

  test("Bytes - concatenation") {
    val bytes1 = Bytes.fromString("Hello", Charset.UTF8)
    val bytes2 = Bytes.fromString(" World", Charset.UTF8)
    val combined = bytes1 ++ bytes2
    assertEquals(combined.asString(Charset.UTF8), "Hello World")
  }

  test("Bytes - value-based equality") {
    val bytes1 = Bytes.fromString("test", Charset.UTF8)
    val bytes2 = Bytes.fromString("test", Charset.UTF8)
    val bytes3 = Bytes.fromString("different", Charset.UTF8)

    assert(bytes1 === bytes2)
    assert(!(bytes1 === bytes3))
  }

  test("Bytes - hash code") {
    val bytes1 = Bytes.fromString("test", Charset.UTF8)
    val bytes2 = Bytes.fromString("test", Charset.UTF8)

    assertEquals(bytes1.hash, bytes2.hash)
  }

  // ===== Charset Tests =====

  test("Charset - predefined charsets") {
    assertEquals(Charset.UTF8.name, "UTF-8")
    assertEquals(Charset.ISO_8859_1.name, "ISO-8859-1")
    assertEquals(Charset.US_ASCII.name, "US-ASCII")
    assertEquals(Charset.UTF16.name, "UTF-16")
  }

  test("Charset - fromName validates charset") {
    val result = Charset.fromName("UTF-8")
    assert(result.isSuccess)
    assertEquals(result.assertSuccess.name, "UTF-8")
  }

  test("Charset - fromName fails on invalid charset") {
    val result = Charset.fromName("INVALID-CHARSET-12345")
    assert(result.isFailure)
  }

  // ===== Body.Empty Tests =====

  test("Body.Empty - has no content") {
    assertEquals(Body.Empty.mediaType, None)
    assertEquals(Body.Empty.contentLength, Some(0L))
    assert(Body.Empty.isEmpty)
  }

  test("Body.empty factory method") {
    val body = Body.empty
    assert(body.isEmpty)
    assertEquals(body, Body.Empty)
  }

  // ===== Body.Text Tests =====

  test("Body.Text - creates text body with default charset") {
    val body = Body.Text("Hello, World!")
    assertEquals(body.mediaType.map(_.value), Some("text/plain; charset=utf-8"))
    assert(!body.isEmpty)
  }

  test("Body.Text - empty string is empty body") {
    val body = Body.Text("")
    assert(body.isEmpty)
  }

  test("Body.Text - content length calculated correctly") {
    val text = "Hello"
    val body = Body.Text(text)
    assertEquals(body.contentLength, Some(5L))
  }

  test("Body.Text - bytes method returns encoded bytes") {
    val text = "test"
    val body = Body.Text(text, charset = Charset.UTF8)
    val bytes = body.bytes
    assertEquals(bytes.asString(Charset.UTF8), text)
  }

  test("Body.Text - custom charset") {
    val text = "Hello"
    val body = Body.Text(text, charset = Charset.ISO_8859_1)
    val bytes = body.bytes
    assertEquals(bytes.asString(Charset.ISO_8859_1), text)
  }

  test("Body.text factory method") {
    val body = Body.text("test")
    body match {
      case text: Body.Text => assertEquals(text.value, "test")
      case _ => fail("Expected Body.Text")
    }
  }

  // ===== Body.Binary Tests =====

  test("Body.Binary - creates binary body") {
    val bytes = Bytes.fromString("binary data", Charset.UTF8)
    val body = Body.Binary(bytes)
    assertEquals(body.mediaType.map(_.value), Some("application/octet-stream"))
    assert(!body.isEmpty)
  }

  test("Body.Binary - empty bytes is empty body") {
    val body = Body.Binary(Bytes.empty)
    assert(body.isEmpty)
  }

  test("Body.Binary - content length matches bytes") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val body = Body.Binary(bytes)
    assertEquals(body.contentLength, Some(bytes.length.toLong))
  }

  test("Body.Binary - asString decodes bytes") {
    val original = "test data"
    val bytes = Bytes.fromString(original, Charset.UTF8)
    val body = Body.Binary(bytes)
    assertEquals(body.asString(Charset.UTF8), original)
  }

  test("Body.Binary - equals uses value-based comparison") {
    val bytes1 = Bytes.fromString("test", Charset.UTF8)
    val bytes2 = Bytes.fromString("test", Charset.UTF8)
    val body1 = Body.Binary(bytes1)
    val body2 = Body.Binary(bytes2)

    assertEquals(body1, body2)
  }

  test("Body.binary factory method") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val body = Body.binary(bytes)
    body match {
      case _: Body.Binary => assert(true)
      case _ => fail("Expected Body.Binary")
    }
  }

  // ===== Chunk Tests =====

  test("Chunk - fromString creates chunk") {
    val chunk = Chunk.fromString("test", Charset.UTF8)
    assertEquals(chunk.size, 4)
    assert(!chunk.isEmpty)
    assert(chunk.nonEmpty)
  }

  test("Chunk - fromBytes creates chunk") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val chunk = Chunk.fromBytes(bytes)
    assertEquals(chunk.size, 4)
  }

  test("Chunk - empty chunk") {
    val chunk = Chunk.empty
    assert(chunk.isEmpty)
    assertEquals(chunk.size, 0)
  }

  test("Chunk - concatenation") {
    val chunk1 = Chunk.fromString("Hello", Charset.UTF8)
    val chunk2 = Chunk.fromString(" World", Charset.UTF8)
    val combined = chunk1 ++ chunk2
    assertEquals(combined.asString(Charset.UTF8), "Hello World")
  }

  test("Chunk - asString decodes") {
    val chunk = Chunk.fromString("test", Charset.UTF8)
    assertEquals(chunk.asString(Charset.UTF8), "test")
  }

  test("Chunk - equals uses value-based comparison") {
    val chunk1 = Chunk.fromString("test", Charset.UTF8)
    val chunk2 = Chunk.fromString("test", Charset.UTF8)

    assertEquals(chunk1, chunk2)
  }

  // ===== ChunkStream Tests =====

  test("ChunkStream - empty stream") {
    val stream = ChunkStream.empty
    val result = stream.pull.assertSuccess
    assertEquals(result, None)
  }

  test("ChunkStream - single chunk") {
    val chunk = Chunk.fromString("test", Charset.UTF8)
    val stream = ChunkStream.single(chunk)

    val pulled = stream.pull.assertSuccess
    assert(pulled.isDefined)
    val (head, _) = pulled.get
    assertEquals(head, chunk)
  }

  test("ChunkStream - fromChunks creates stream") {
    val chunk1 = Chunk.fromString("a", Charset.UTF8)
    val chunk2 = Chunk.fromString("b", Charset.UTF8)
    val chunk3 = Chunk.fromString("c", Charset.UTF8)

    val stream = ChunkStream.fromChunks(chunk1, chunk2, chunk3)
    val bytes = stream.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "abc")
  }

  test("ChunkStream - fromString creates stream") {
    val stream = ChunkStream.fromString("test", Charset.UTF8)
    val bytes = stream.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "test")
  }

  test("ChunkStream - fromBytes creates stream") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val stream = ChunkStream.fromBytes(bytes)
    val result = stream.toBytes.assertSuccess
    assertEquals(result.asString(Charset.UTF8), "test")
  }

  test("ChunkStream - fold accumulates") {
    val stream = ChunkStream.fromChunks(
      Chunk.fromString("a", Charset.UTF8),
      Chunk.fromString("b", Charset.UTF8),
      Chunk.fromString("c", Charset.UTF8)
    )

    val result = stream.fold(0)((acc, chunk) => acc + chunk.size).assertSuccess
    assertEquals(result, 3)
  }

  test("ChunkStream - map transforms chunks") {
    val stream = ChunkStream.fromString("test", Charset.UTF8)
    val mapped = stream.map(chunk => Chunk.fromString(chunk.asString(Charset.UTF8).toUpperCase, Charset.UTF8))
    val bytes = mapped.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "TEST")
  }

  test("ChunkStream - filter removes chunks") {
    val stream = ChunkStream.fromChunks(
      Chunk.fromString("a", Charset.UTF8),
      Chunk.fromString("", Charset.UTF8), // empty chunk
      Chunk.fromString("b", Charset.UTF8)
    )

    val filtered = stream.filter(_.nonEmpty)
    val bytes = filtered.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "ab")
  }

  test("ChunkStream - take limits chunks") {
    val stream = ChunkStream.fromChunks(
      Chunk.fromString("a", Charset.UTF8),
      Chunk.fromString("b", Charset.UTF8),
      Chunk.fromString("c", Charset.UTF8)
    )

    val taken = stream.take(2)
    val bytes = taken.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "ab")
  }

  test("ChunkStream - drop skips chunks") {
    val stream = ChunkStream.fromChunks(
      Chunk.fromString("a", Charset.UTF8),
      Chunk.fromString("b", Charset.UTF8),
      Chunk.fromString("c", Charset.UTF8)
    )

    val dropped = stream.drop(1)
    val bytes = dropped.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "bc")
  }

  test("ChunkStream - concatenation") {
    val stream1 = ChunkStream.fromString("Hello", Charset.UTF8)
    val stream2 = ChunkStream.fromString(" World", Charset.UTF8)
    val combined = stream1 ++ stream2
    val bytes = combined.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "Hello World")
  }

  // ===== Body.Stream Tests =====

  test("Body.Stream - creates from ChunkStream") {
    val chunkStream = ChunkStream.fromString("test", Charset.UTF8)
    val body = Body.Stream(Eru.succeed(chunkStream))

    assert(!body.isEmpty)
    assertEquals(body.mediaType, None)
    assertEquals(body.contentLength, None)
  }

  test("Body.Stream - toBytes collects stream") {
    val chunkStream = ChunkStream.fromString("test", Charset.UTF8)
    val body = Body.Stream(Eru.succeed(chunkStream))

    val bytes = body.toBytes.assertSuccess
    assertEquals(bytes.asString(Charset.UTF8), "test")
  }

  test("Body.Stream - asString decodes stream") {
    val chunkStream = ChunkStream.fromString("test data", Charset.UTF8)
    val body = Body.Stream(Eru.succeed(chunkStream))

    val text = body.asString(Charset.UTF8).assertSuccess
    assertEquals(text, "test data")
  }

  test("Body.stream factory method") {
    val chunkStream = ChunkStream.fromString("test", Charset.UTF8)
    val body = Body.stream(Eru.succeed(chunkStream))
    body match {
      case _: Body.Stream => assert(true)
      case _ => fail("Expected Body.Stream")
    }
  }

  test("Body.fromChunks creates streaming body") {
    val chunks = Eru.succeed(
      Iterator(
        Chunk.fromString("a", Charset.UTF8),
        Chunk.fromString("b", Charset.UTF8),
        Chunk.fromString("c", Charset.UTF8)
      )
    )

    val body = Body.fromChunks(chunks)
    body match {
      case _: Body.Stream => assert(true)
      case _ => fail("Expected Body.Stream")
    }
  }

  // ===== BodyEncoder Tests =====

  test("BodyEncoder[String] - encodes string") {
    val result = BodyEncoder[String].encode("test").assertSuccess
    result match {
      case text: Body.Text => assertEquals(text.value, "test")
      case _ => fail("Expected Body.Text")
    }
  }

  test("BodyEncoder[Bytes] - encodes bytes") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val result = BodyEncoder[Bytes].encode(bytes).assertSuccess
    result match {
      case binary: Body.Binary => assertEquals(binary.value, bytes)
      case _ => fail("Expected Body.Binary")
    }
  }

  test("BodyEncoder[Unit] - encodes to empty body") {
    val result = BodyEncoder[Unit].encode(()).assertSuccess
    assertEquals(result, Body.Empty)
  }

  test("BodyEncoder - contramap transforms input") {
    case class User(name: String)
    val userEncoder = BodyEncoder[String].contramap[User](_.name)

    val result = userEncoder.encode(User("Alice")).assertSuccess
    result match {
      case text: Body.Text => assertEquals(text.value, "Alice")
      case _ => fail("Expected Body.Text")
    }
  }

  // ===== BodyDecoder Tests =====

  test("BodyDecoder[String] - decodes empty body") {
    val result = BodyDecoder[String].decode(Body.Empty).assertSuccess
    assertEquals(result, "")
  }

  test("BodyDecoder[String] - decodes text body") {
    val body = Body.Text("test")
    val result = BodyDecoder[String].decode(body).assertSuccess
    assertEquals(result, "test")
  }

  test("BodyDecoder[String] - decodes binary body") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val body = Body.Binary(bytes)
    val result = BodyDecoder[String].decode(body).assertSuccess
    assertEquals(result, "test")
  }

  test("BodyDecoder[String] - decodes stream body") {
    val stream = ChunkStream.fromString("test", Charset.UTF8)
    val body = Body.Stream(Eru.succeed(stream))
    val result = BodyDecoder[String].decode(body).assertSuccess
    assertEquals(result, "test")
  }

  test("BodyDecoder[Bytes] - decodes empty body") {
    val result = BodyDecoder[Bytes].decode(Body.Empty).assertSuccess
    assert(result.isEmpty)
  }

  test("BodyDecoder[Bytes] - decodes text body") {
    val body = Body.Text("test")
    val result = BodyDecoder[Bytes].decode(body).assertSuccess
    assertEquals(result.asString(Charset.UTF8), "test")
  }

  test("BodyDecoder[Bytes] - decodes binary body") {
    val bytes = Bytes.fromString("test", Charset.UTF8)
    val body = Body.Binary(bytes)
    val result = BodyDecoder[Bytes].decode(body).assertSuccess
    assert(result === bytes)
  }

  test("BodyDecoder[Unit] - ignores body") {
    val body = Body.Text("ignored")
    val result = BodyDecoder[Unit].decode(body).assertSuccess
    assertEquals(result, ())
  }

  test("BodyDecoder - map transforms output") {
    val decoder = BodyDecoder[String].map(_.length)
    val body = Body.Text("test")
    val result = decoder.decode(body).assertSuccess
    assertEquals(result, 4)
  }

  test("BodyDecoder - flatMap chains decoding") {
    val decoder = BodyDecoder[String].flatMap { s =>
      if s.isEmpty then Eru.fail(DecodeError("Empty string not allowed"))
      else Eru.succeed(s.toUpperCase)
    }

    val body1 = Body.Text("test")
    val result1 = decoder.decode(body1).assertSuccess
    assertEquals(result1, "TEST")

    val body2 = Body.Text("")
    assert(decoder.decode(body2).isFailure)
  }
}
