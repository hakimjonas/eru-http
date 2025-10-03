package net.ghoula.eru.http

import net.ghoula.eru.*

/** Platform-specific types imported from BytesPlatform.scala:
  *   - Bytes: opaque type wrapping platform byte array (Array[Byte] on JVM, Uint8Array on JS)
  *   - Charset: opaque type for character encoding
  *   - InvalidCharset: charset validation error
  *
  * These types are defined in:
  *   - jvm/src/main/scala/net/ghoula/eru/http/BytesPlatform.scala (JVM implementation)
  *   - js/src/main/scala/net/ghoula/eru/http/BytesPlatform.scala (Scala.js implementation)
  */

/** HTTP message body representation with support for in-memory and streaming content.
  *
  * Body is the foundational abstraction for HTTP request and response payloads. It supports both
  * in-memory representations (for small payloads) and streaming representations (for large payloads
  * or when backpressure is needed).
  *
  * All body operations integrate with Eru effects for proper resource management and error
  * handling.
  */
sealed trait Body {

  /** The media type of this body content.
    */
  def mediaType: Option[MediaType]

  /** The content length if known ahead of time. None for streaming bodies where length is unknown.
    */
  def contentLength: Option[Long]

  /** Returns true if this body has no content.
    */
  def isEmpty: Boolean
}

object Body {

  /** Empty body with no content. Used for requests/responses that don't have a body (GET, HEAD, 204
    * No Content, etc.).
    */
  case object Empty extends Body {
    val mediaType: Option[MediaType] = None
    val contentLength: Option[Long] = Some(0L)
    val isEmpty: Boolean = true
  }

  /** In-memory string body.
    *
    * Suitable for small text-based payloads like JSON, XML, HTML, or plain text. The entire content
    * is held in memory as a String.
    *
    * @param value
    *   the string content
    * @param mediaType
    *   the media type (defaults to text/plain; charset=utf-8)
    * @param charset
    *   the character encoding (defaults to UTF-8)
    */
  final case class Text(
    value: String,
    mediaType: Option[MediaType] = Some(MediaType.textPlain.withCharset("utf-8")),
    charset: Charset = Charset.UTF8
  ) extends Body {
    lazy val contentLength: Option[Long] = Some(Bytes.fromString(value, charset).length.toLong)
    val isEmpty: Boolean = value.isEmpty

    /** Returns the body content as bytes.
      */
    def bytes: Bytes = Bytes.fromString(value, charset)
  }

  /** In-memory binary body.
    *
    * Suitable for small binary payloads like images, PDFs, or arbitrary binary data. The entire
    * content is held in memory as bytes.
    *
    * @param value
    *   the binary content
    * @param mediaType
    *   the media type (defaults to application/octet-stream)
    */
  final case class Binary(
    value: Bytes,
    mediaType: Option[MediaType] = Some(MediaType.applicationOctetStream)
  ) extends Body {
    lazy val contentLength: Option[Long] = Some(value.length.toLong)
    val isEmpty: Boolean = value.isEmpty

    /** Returns the body content as a string using the specified charset.
      */
    def asString(charset: Charset = Charset.UTF8): String = value.asString(charset)

    // Override equals/hashCode for value-based comparison
    @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
    override def equals(that: Any): Boolean = that match {
      case other: Binary =>
        this.value === other.value &&
        this.mediaType == other.mediaType
      case _ => false
    }

    override def hashCode(): Int = {
      val prime = 31
      var result = 1
      result = prime * result + value.hash
      result = prime * result + mediaType.hashCode
      result
    }
  }

  /** Streaming body for large or unknown-size content.
    *
    * The body content is provided as a stream of chunks, allowing for:
    *   - Handling large files without loading into memory
    *   - Streaming responses as they're generated
    *   - Backpressure support via Eru effects
    *   - Progress tracking via chunk consumption
    *
    * @param chunks
    *   an Eru effect producing a stream of byte chunks
    * @param contentLength
    *   the total content length if known ahead of time
    * @param mediaType
    *   the media type
    */
  final case class Stream(
    chunks: Eru[Nothing, ChunkStream],
    contentLength: Option[Long] = None,
    mediaType: Option[MediaType] = None
  ) extends Body {
    val isEmpty: Boolean = false

    /** Consumes the entire stream into memory. Use with caution - only for streams known to be
      * small.
      */
    def toBytes: Eru[Nothing, Bytes] = {
      chunks.flatMap { stream =>
        stream.toBytes
      }
    }

    /** Consumes the entire stream into a string. Use with caution - only for streams known to be
      * small.
      */
    def asString(charset: Charset = Charset.UTF8): Eru[Nothing, String] = {
      toBytes.map(bytes => bytes.asString(charset))
    }
  }

  /** Creates an empty body.
    *
    * @return
    *   an empty Body
    */
  def empty: Body = Empty

  /** Creates a text body from a string.
    *
    * @param value
    *   the text content
    * @param mediaType
    *   the media type (defaults to text/plain with UTF-8)
    * @return
    *   a Text body
    */
  def text(value: String, mediaType: MediaType = MediaType.textPlain.withCharset("utf-8")): Body =
    Text(value, Some(mediaType))

  /** Creates a binary body from bytes.
    *
    * @param value
    *   the binary content
    * @param mediaType
    *   the media type (defaults to application/octet-stream)
    * @return
    *   a Binary body
    */
  def binary(value: Bytes, mediaType: MediaType = MediaType.applicationOctetStream): Body =
    Binary(value, Some(mediaType))

  /** Creates a streaming body from a chunk stream.
    *
    * @param chunks
    *   an Eru effect producing a chunk stream
    * @param contentLength
    *   optional known content length
    * @param mediaType
    *   optional media type
    * @return
    *   a Stream body
    */
  def stream(
    chunks: Eru[Nothing, ChunkStream],
    contentLength: Option[Long] = None,
    mediaType: Option[MediaType] = None
  ): Body = Stream(chunks, contentLength, mediaType)

  /** Creates a streaming body from a stream of byte chunks.
    *
    * @param chunks
    *   an Eru effect producing an iterator of chunks
    * @param contentLength
    *   optional known content length
    * @param mediaType
    *   optional media type
    * @return
    *   a Stream body
    */
  def fromChunks(
    chunks: Eru[Nothing, Iterator[Chunk]],
    contentLength: Option[Long] = None,
    mediaType: Option[MediaType] = None
  ): Body = {
    val chunkStream = chunks.map(ChunkStream.fromIterator)
    Stream(chunkStream, contentLength, mediaType)
  }
}

/** A chunk of bytes in a streaming body.
  *
  * Chunks are the unit of streaming - bodies are transmitted as sequences of chunks. This allows
  * for efficient memory usage and backpressure control.
  */
final case class Chunk(bytes: Bytes) {
  def size: Int = bytes.length
  def isEmpty: Boolean = bytes.isEmpty
  def nonEmpty: Boolean = bytes.nonEmpty

  /** Combines this chunk with another.
    */
  def ++(that: Chunk): Chunk = Chunk(bytes ++ that.bytes)

  /** Returns the bytes as a string using the specified charset.
    */
  def asString(charset: Charset = Charset.UTF8): String = bytes.asString(charset)

  // Override equals/hashCode for value-based comparison
  @scala.annotation.nowarn("msg=pattern selector should be an instance of Matchable")
  override def equals(that: Any): Boolean = that match {
    case other: Chunk => this.bytes === other.bytes
    case _ => false
  }

  override def hashCode(): Int = bytes.hash
}

object Chunk {

  /** Empty chunk with no bytes.
    *
    * @return
    *   an empty Chunk
    */
  val empty: Chunk = Chunk(Bytes.empty)

  /** Creates a chunk from a string.
    *
    * @param s
    *   the string to convert
    * @param charset
    *   the character encoding (defaults to UTF-8)
    * @return
    *   a Chunk containing the string's bytes
    */
  def fromString(s: String, charset: Charset = Charset.UTF8): Chunk =
    Chunk(Bytes.fromString(s, charset))

  /** Creates a chunk from bytes.
    *
    * @param bytes
    *   the bytes to wrap
    * @return
    *   a Chunk containing the bytes
    */
  def fromBytes(bytes: Bytes): Chunk = Chunk(bytes)
}

/** A stream of chunks with Eru-based backpressure.
  *
  * ChunkStream represents a potentially infinite stream of chunks. It integrates with Eru effects
  * for proper resource management and backpressure.
  */
sealed trait ChunkStream {

  /** Pulls the next chunk from the stream. Returns None when the stream is exhausted.
    */
  def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]]

  /** Folds over all chunks in the stream.
    */
  def fold[A](initial: A)(f: (A, Chunk) => A): Eru[Nothing, A] = {
    def loop(current: A, stream: ChunkStream): Eru[Nothing, A] = {
      stream.pull.flatMap {
        case None => Eru.succeed(current)
        case Some((chunk, rest)) => loop(f(current, chunk), rest)
      }
    }
    loop(initial, this)
  }

  /** Collects all chunks into a single Bytes. Use with caution - only for streams known to be
    * small.
    */
  def toBytes: Eru[Nothing, Bytes] = {
    fold(Bytes.empty)((acc, chunk) => acc ++ chunk.bytes)
  }

  /** Maps over each chunk.
    */
  def map(f: Chunk => Chunk): ChunkStream = ChunkStream.Map(this, f)

  /** Filters chunks based on a predicate.
    */
  def filter(p: Chunk => Boolean): ChunkStream = ChunkStream.Filter(this, p)

  /** Takes the first n chunks.
    */
  def take(n: Int): ChunkStream = ChunkStream.Take(this, n)

  /** Drops the first n chunks.
    */
  def drop(n: Int): ChunkStream = ChunkStream.Drop(this, n)

  /** Appends another stream to this one.
    */
  def ++(that: ChunkStream): ChunkStream = ChunkStream.Append(this, that)
}

object ChunkStream {

  /** Empty stream.
    */
  case object Empty extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = Eru.succeed(None)
  }

  /** A stream consisting of a single chunk followed by another stream.
    */
  private final case class Cons(head: Chunk, tail: Eru[Nothing, ChunkStream]) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      tail.map(t => Some((head, t)))
    }
  }

  /** A stream that lazily evaluates to another stream.
    */
  private final case class Suspend(thunk: Eru[Nothing, ChunkStream]) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      thunk.flatMap(_.pull)
    }
  }

  /** A mapped stream.
    */
  private final case class Map(source: ChunkStream, f: Chunk => Chunk) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      source.pull.map {
        case None => None
        case Some((chunk, rest)) => Some((f(chunk), rest.map(f)))
      }
    }
  }

  /** A filtered stream.
    */
  private final case class Filter(source: ChunkStream, p: Chunk => Boolean) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      source.pull.flatMap {
        case None => Eru.succeed(None)
        case Some((chunk, rest)) =>
          if p(chunk) then Eru.succeed(Some((chunk, rest.filter(p))))
          else rest.filter(p).pull
      }
    }
  }

  /** A stream that takes the first n chunks.
    */
  private final case class Take(source: ChunkStream, n: Int) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      if n <= 0 then Eru.succeed(None)
      else {
        source.pull.map {
          case None => None
          case Some((chunk, rest)) => Some((chunk, rest.take(n - 1)))
        }
      }
    }
  }

  /** A stream that drops the first n chunks.
    */
  private final case class Drop(source: ChunkStream, n: Int) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      if n <= 0 then source.pull
      else {
        source.pull.flatMap {
          case None => Eru.succeed(None)
          case Some((_, rest)) => rest.drop(n - 1).pull
        }
      }
    }
  }

  /** A stream that appends another stream.
    */
  private final case class Append(first: ChunkStream, second: ChunkStream) extends ChunkStream {
    def pull: Eru[Nothing, Option[(Chunk, ChunkStream)]] = {
      first.pull.flatMap {
        case None => second.pull
        case Some((chunk, rest)) => Eru.succeed(Some((chunk, rest ++ second)))
      }
    }
  }

  /** Creates an empty stream.
    */
  def empty: ChunkStream = Empty

  /** Creates a stream from a single chunk.
    */
  def single(chunk: Chunk): ChunkStream =
    Cons(chunk, Eru.succeed(Empty))

  /** Creates a stream from multiple chunks.
    */
  def fromChunks(chunks: Chunk*): ChunkStream =
    chunks.foldRight(empty: ChunkStream)((chunk, rest) => Cons(chunk, Eru.succeed(rest)))

  /** Creates a stream from an iterator of chunks.
    */
  def fromIterator(iter: Iterator[Chunk]): ChunkStream = {
    if !iter.hasNext then Empty
    else Cons(iter.next(), Eru.succeed(fromIterator(iter)))
  }

  /** Creates a stream from bytes.
    */
  def fromBytes(bytes: Bytes): ChunkStream =
    single(Chunk.fromBytes(bytes))

  /** Creates a stream from a string.
    */
  def fromString(s: String, charset: Charset = Charset.UTF8): ChunkStream =
    single(Chunk.fromString(s, charset))

  /** Creates a stream that lazily evaluates.
    */
  def suspend(thunk: => ChunkStream): ChunkStream =
    Suspend(Eru.effectTotal(thunk))

  /** Creates a stream from an Eru effect.
    */
  def eval(effect: Eru[Nothing, ChunkStream]): ChunkStream =
    Suspend(effect)
}

/** Error that can occur during body encoding.
  */
final case class EncodeError(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull)

/** Error that can occur during body decoding.
  */
final case class DecodeError(
  message: String,
  cause: Option[Throwable] = None,
  expected: Option[MediaType] = None,
  actual: Option[MediaType] = None
) extends Exception(message, cause.orNull)

/** Type class for encoding values to HTTP bodies.
  *
  * BodyEncoder defines how to convert a value of type A into an HTTP body. Implementations handle
  * serialization and media type determination.
  *
  * @tparam A
  *   the type being encoded
  */
trait BodyEncoder[A] {

  /** Encodes a value into a body.
    *
    * @param value
    *   the value to encode
    * @param mediaType
    *   optional media type override (uses default if None)
    * @return
    *   an Eru effect producing the encoded body or an error
    */
  def encode(value: A, mediaType: Option[MediaType] = None): Eru[EncodeError, Body]

  /** The default media type for this encoder.
    */
  def defaultMediaType: MediaType

  /** Maps this encoder to encode a different type.
    *
    * @param f
    *   function to convert from B to A
    * @tparam B
    *   the new input type
    * @return
    *   a new BodyEncoder for type B
    */
  def contramap[B](f: B => A): BodyEncoder[B] = {
    val self = this
    new BodyEncoder[B] {
      def encode(value: B, mediaType: Option[MediaType]): Eru[EncodeError, Body] =
        self.encode(f(value), mediaType)
      def defaultMediaType: MediaType = self.defaultMediaType
    }
  }
}

object BodyEncoder {

  /** Summons an implicit encoder.
    *
    * @tparam A
    *   the type to encode
    * @return
    *   the implicit BodyEncoder for type A
    */
  def apply[A](using encoder: BodyEncoder[A]): BodyEncoder[A] = encoder

  /** Encoder for String values (text/plain).
    */
  given BodyEncoder[String] = new BodyEncoder[String] {
    def encode(value: String, mediaType: Option[MediaType]): Eru[EncodeError, Body] = {
      val mt = mediaType.getOrElse(defaultMediaType)
      Eru.succeed(Body.Text(value, Some(mt)))
    }
    def defaultMediaType: MediaType = MediaType.textPlain.withCharset("utf-8")
  }

  /** Encoder for Bytes (application/octet-stream).
    */
  given BodyEncoder[Bytes] = new BodyEncoder[Bytes] {
    def encode(value: Bytes, mediaType: Option[MediaType]): Eru[EncodeError, Body] = {
      val mt = mediaType.getOrElse(defaultMediaType)
      Eru.succeed(Body.Binary(value, Some(mt)))
    }
    def defaultMediaType: MediaType = MediaType.applicationOctetStream
  }

  /** Encoder for Unit (empty body).
    */
  given BodyEncoder[Unit] = new BodyEncoder[Unit] {
    def encode(value: Unit, mediaType: Option[MediaType]): Eru[EncodeError, Body] =
      Eru.succeed(Body.Empty)
    def defaultMediaType: MediaType = MediaType.textPlain
  }

  /** Encoder for Body (identity encoder).
    */
  given BodyEncoder[Body] = new BodyEncoder[Body] {
    def encode(value: Body, mediaType: Option[MediaType]): Eru[EncodeError, Body] =
      Eru.succeed(value)
    def defaultMediaType: MediaType = MediaType.applicationOctetStream
  }
}

/** Type class for decoding HTTP bodies into values.
  *
  * BodyDecoder defines how to convert an HTTP body into a value of type A. Implementations handle
  * deserialization and media type validation.
  *
  * @tparam A
  *   the type being decoded to
  */
trait BodyDecoder[A] {

  /** Decodes a body into a value.
    *
    * @param body
    *   the body to decode
    * @return
    *   an Eru effect producing the decoded value or an error
    */
  def decode(body: Body): Eru[DecodeError, A]

  /** The media types this decoder can handle. Empty list means it can handle any media type.
    */
  def supportedMediaTypes: List[MediaType]

  /** Maps this decoder to decode to a different type.
    *
    * @param f
    *   function to transform the decoded value
    * @tparam B
    *   the new output type
    * @return
    *   a new BodyDecoder for type B
    */
  def map[B](f: A => B): BodyDecoder[B] = {
    val self = this
    new BodyDecoder[B] {
      def decode(body: Body): Eru[DecodeError, B] =
        self.decode(body).map(f)
      def supportedMediaTypes: List[MediaType] = self.supportedMediaTypes
    }
  }

  /** FlatMaps this decoder.
    *
    * @param f
    *   function to transform the decoded value into another Eru effect
    * @tparam B
    *   the new output type
    * @return
    *   a new BodyDecoder for type B
    */
  def flatMap[B](f: A => Eru[DecodeError, B]): BodyDecoder[B] = {
    val self = this
    new BodyDecoder[B] {
      def decode(body: Body): Eru[DecodeError, B] =
        self.decode(body).flatMap(f)
      def supportedMediaTypes: List[MediaType] = self.supportedMediaTypes
    }
  }
}

object BodyDecoder {

  /** Summons an implicit decoder.
    *
    * @tparam A
    *   the type to decode to
    * @return
    *   the implicit BodyDecoder for type A
    */
  def apply[A](using decoder: BodyDecoder[A]): BodyDecoder[A] = decoder

  /** Decoder for String values.
    */
  given BodyDecoder[String] = new BodyDecoder[String] {
    def decode(body: Body): Eru[DecodeError, String] = body match {
      case Body.Empty => Eru.succeed("")
      case Body.Text(value, _, _) => Eru.succeed(value)
      case Body.Binary(bytes, _) => Eru.succeed(bytes.asString(Charset.UTF8))
      case stream: Body.Stream =>
        stream
          .asString()
          .mapError(_ => DecodeError("Failed to decode stream to string", None))
    }
    def supportedMediaTypes: List[MediaType] = List.empty // Accept any
  }

  /** Decoder for Bytes.
    */
  given BodyDecoder[Bytes] = new BodyDecoder[Bytes] {
    def decode(body: Body): Eru[DecodeError, Bytes] = body match {
      case Body.Empty => Eru.succeed(Bytes.empty)
      case Body.Text(value, _, charset) => Eru.succeed(Bytes.fromString(value, charset))
      case Body.Binary(bytes, _) => Eru.succeed(bytes)
      case stream: Body.Stream =>
        stream.toBytes.mapError(_ => DecodeError("Failed to decode stream to bytes", None))
    }
    def supportedMediaTypes: List[MediaType] = List.empty // Accept any
  }

  /** Decoder for Unit (ignores body).
    */
  given BodyDecoder[Unit] = new BodyDecoder[Unit] {
    def decode(body: Body): Eru[DecodeError, Unit] = Eru.succeed(())
    def supportedMediaTypes: List[MediaType] = List.empty // Accept any
  }
}
