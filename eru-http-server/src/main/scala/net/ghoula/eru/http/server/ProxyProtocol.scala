package net.ghoula.eru.http.server

import java.io.{EOFException, InputStream}
import java.net.{Inet4Address, Inet6Address, InetAddress}

/** PROXY protocol v2 peek-parser (HAProxy spec, March 2020 revision).
  *
  * Used by [[NativeHttpServer]] to recover the original client IP when the server is fronted by a
  * PROXY-aware L4 load balancer (HAProxy, envoy, AWS NLB). Unlike `X-Forwarded-For`, the PROXY
  * preamble is prepended to the raw TCP stream before HTTP begins, so a plain HTTP client cannot
  * forge it. That guarantee only holds when the listener accepts connections exclusively from
  * trusted load balancers; in `Optional` mode any TCP peer can send a preamble (see the PROXY
  * protocol's security considerations), so restrict access at the network layer.
  *
  * Frame format (all integers big-endian):
  * {{{
  *   +---------------------------------------------------------------+
  *   |  12-byte signature: \r\n\r\n\0\r\nQUIT\n                      |
  *   +---+---+-------+-------+-------+-------+-------+-------+-------+
  *   |ver|cmd| fam   | proto |        length (2 bytes)               |
  *   +---+---+-------+-------+-------+-------+-------+-------+-------+
  *   |              address payload (length bytes)                  ...
  *   +---------------------------------------------------------------+
  * }}}
  *
  * Supported commands:
  *   - LOCAL (0x0): health check, no addresses, use TCP peer.
  *   - PROXY (0x1): real client/server addresses follow.
  *
  * Supported address families:
  *   - AF_INET (0x1): 4+4+2+2 = 12 bytes.
  *   - AF_INET6 (0x2): 16+16+2+2 = 36 bytes.
  *   - AF_UNIX (0x3): accepted, payload skipped, returns NoAddr (caller uses TCP peer).
  *   - AF_UNSPEC (0x0): payload skipped, returns NoAddr.
  *
  * @see
  *   [[https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt HAProxy PROXY Protocol spec]]
  */
private[server] object ProxyProtocol {

  /** The 12-byte PROXY v2 signature. */
  val Signature: Array[Byte] =
    Array(0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a).map(_.toByte)

  /** Maximum length field value we'll honor — protects against a hostile peer declaring e.g.
    * length=65535 with AF_UNIX and tying us up reading garbage. AF_INET6 is the largest valid
    * inline payload at 36 bytes; TLV extensions can add more. The 1KB bound accommodates the spec's
    * own examples plus room for TLV chains such as PP2_TYPE_SSL; a declared payload larger than
    * that is treated as malformed.
    */
  private val MaxPayloadLen = 1024

  /** Decoded PROXY v2 preamble. */
  sealed trait Header {
    def clientAddr: Option[InetAddress]
  }

  /** No address information available (LOCAL command, AF_UNSPEC, AF_UNIX). Caller falls back to TCP
    * peer for client IP.
    */
  case object NoAddr extends Header {
    def clientAddr: Option[InetAddress] = None
  }

  /** Real IPv4 client address extracted from the PROXY preamble. */
  final case class Ipv4(src: Inet4Address, dst: Inet4Address, srcPort: Int, dstPort: Int) extends Header {
    def clientAddr: Option[InetAddress] = Some(src)
  }

  /** Real IPv6 client address extracted from the PROXY preamble. */
  final case class Ipv6(src: Inet6Address, dst: Inet6Address, srcPort: Int, dstPort: Int) extends Header {
    def clientAddr: Option[InetAddress] = Some(src)
  }

  /** Parse outcomes. */
  sealed trait ParseResult
  object ParseResult {

    /** Successful parse. `consumedBytes` is the total number of bytes the preamble occupied on the
      * wire (16 header + payload). The caller should ensure the parsing stream is positioned
      * immediately after those bytes before HTTP parsing begins.
      */
    final case class Parsed(header: Header, consumedBytes: Int) extends ParseResult

    /** The first 12 bytes do not match the PROXY signature. `consumedBytes` contains exactly those
      * 12 bytes, which the caller must replay to the downstream parser (since the underlying stream
      * position has advanced past them).
      */
    final case class NotProxyProtocol(consumedBytes: Array[Byte]) extends ParseResult

    /** The stream starts with the signature but the frame is malformed (bad version, oversized
      * length, etc.). The caller must close the connection — the stream state is unusable.
      */
    final case class Invalid(reason: String) extends ParseResult
  }

  /** Attempt to parse a PROXY v2 preamble from the given byte source.
    *
    * The `peekBytes` parameter is the first chunk already read from the socket (if the caller
    * needed to peek for protocol detection). Any additional bytes are read from `source`.
    *
    * Reads fail with `EOFException` if the source closes, and all sizes are bounded by
    * `MaxPayloadLen + 16`. The read itself has no socket timeout, so the caller bounds the stall:
    * `NativeHttpServer` wraps this parse in `proxyHandshakeTimeout` (a peer that sends part of the
    * signature and stalls would otherwise park its thread).
    *
    * Exactly 12 bytes are read first to check the signature; on mismatch `NotProxyProtocol` is
    * returned with exactly those 12 bytes so the caller can replay them as HTTP without further
    * consumption (required for `Optional` mode). On match, 4 more bytes
    * (version/command/family/proto/length) are read and validated before the declared payload.
    */
  def parse(peekBytes: Array[Byte], source: InputStream): ParseResult = {
    readExactly(peekBytes, source, 12) match {
      case Left(msg) => ParseResult.Invalid(msg)
      case Right(sig) =>
        if !java.util.Arrays.equals(sig, 0, 12, Signature, 0, 12) then ParseResult.NotProxyProtocol(sig)
        else
          readExactly(Array.empty, source, 4) match {
            case Left(msg) => ParseResult.Invalid(msg)
            case Right(rest) =>
              val header = new Array[Byte](16)
              System.arraycopy(sig, 0, header, 0, 12)
              System.arraycopy(rest, 0, header, 12, 4)
              decodeHeader(header, source)
          }
    }
  }

  /** Signature confirmed. Decode the remaining bytes of the fixed header and dispatch on command /
    * family.
    */
  private def decodeHeader(header: Array[Byte], source: InputStream): ParseResult = {
    val versionCommand = header(12) & 0xff
    val version = (versionCommand >>> 4) & 0x0f
    val command = versionCommand & 0x0f
    val familyProto = header(13) & 0xff
    val family = (familyProto >>> 4) & 0x0f
    val length = ((header(14) & 0xff) << 8) | (header(15) & 0xff)

    val validated: Either[ParseResult, Int] = for {
      _ <- Either.cond(
        version == 0x2,
        (),
        ParseResult.Invalid(s"Unsupported PROXY protocol version: $version (only v2 supported)")
      )
      _ <- Either.cond(command == 0x0 || command == 0x1, (), ParseResult.Invalid(s"Unknown PROXY command: $command"))
      _ <- Either.cond(
        length <= MaxPayloadLen,
        (),
        ParseResult.Invalid(s"PROXY payload length $length exceeds maximum $MaxPayloadLen")
      )
    } yield length

    validated match {
      case Left(err) => err
      case Right(_) =>
        val payloadRead: Either[String, Array[Byte]] =
          if length == 0 then Right(Array.emptyByteArray) else readExactly(Array.empty, source, length)
        payloadRead match {
          case Left(msg) => ParseResult.Invalid(msg)
          case Right(payload) =>
            val consumed = 16 + length
            if command == 0x0 then ParseResult.Parsed(NoAddr, consumed)
            else decodeAddress(family, length, payload, consumed)
        }
    }
  }

  /** PROXY command: decode the per-family address payload. */
  private def decodeAddress(family: Int, length: Int, payload: Array[Byte], consumed: Int): ParseResult = family match {
    case 0x0 => ParseResult.Parsed(NoAddr, consumed)
    case 0x1 => decodeAfInet(length, payload, consumed)
    case 0x2 => decodeAfInet6(length, payload, consumed)
    case 0x3 => ParseResult.Parsed(NoAddr, consumed)
    case other => ParseResult.Invalid(s"Unknown PROXY address family: $other")
  }

  /** Decode an AF_INET payload into an [[Ipv4]] header.
    *
    * `InetAddress.getByAddress` returns the concrete subtype keyed on byte length — 4 bytes →
    * `Inet4Address` (JDK contract) — so the pattern match in `inet4` preserves that invariant
    * without relying on `asInstanceOf`.
    */
  private def decodeAfInet(length: Int, payload: Array[Byte], consumed: Int): ParseResult = {
    if length < 12 then ParseResult.Invalid(s"AF_INET payload too short: $length (need 12)")
    else {
      val srcPort = ((payload(8) & 0xff) << 8) | (payload(9) & 0xff)
      val dstPort = ((payload(10) & 0xff) << 8) | (payload(11) & 0xff)
      val result = for {
        src <- inet4(payload.slice(0, 4))
        dst <- inet4(payload.slice(4, 8))
      } yield Ipv4(src, dst, srcPort, dstPort)
      result match {
        case Right(h) => ParseResult.Parsed(h, consumed)
        case Left(msg) => ParseResult.Invalid(s"Invalid AF_INET payload: $msg")
      }
    }
  }

  private def decodeAfInet6(length: Int, payload: Array[Byte], consumed: Int): ParseResult = {
    if length < 36 then ParseResult.Invalid(s"AF_INET6 payload too short: $length (need 36)")
    else {
      val srcPort = ((payload(32) & 0xff) << 8) | (payload(33) & 0xff)
      val dstPort = ((payload(34) & 0xff) << 8) | (payload(35) & 0xff)
      val result = for {
        src <- inet6(payload.slice(0, 16))
        dst <- inet6(payload.slice(16, 32))
      } yield Ipv6(src, dst, srcPort, dstPort)
      result match {
        case Right(h) => ParseResult.Parsed(h, consumed)
        case Left(msg) => ParseResult.Invalid(s"Invalid AF_INET6 payload: $msg")
      }
    }
  }

  private def inet4(bytes: Array[Byte]): Either[String, Inet4Address] =
    try
      InetAddress.getByAddress(bytes) match {
        case a: Inet4Address => Right(a)
        case other => Left(s"expected Inet4Address, got ${other.getClass.getSimpleName}")
      }
    catch { case e: Exception => Left(e.getMessage) }

  private def inet6(bytes: Array[Byte]): Either[String, Inet6Address] =
    try
      InetAddress.getByAddress(bytes) match {
        case a: Inet6Address => Right(a)
        case other => Left(s"expected Inet6Address, got ${other.getClass.getSimpleName}")
      }
    catch { case e: Exception => Left(e.getMessage) }

  /** Read `n` bytes total: prepend `already` (bytes the caller has already pulled from the wire),
    * read remaining from `source`. Fails on EOF.
    */
  private def readExactly(already: Array[Byte], source: InputStream, n: Int): Either[String, Array[Byte]] = {
    if already.length >= n then Right(already.take(n))
    else {
      val buf = new Array[Byte](n)
      System.arraycopy(already, 0, buf, 0, already.length)
      var filled = already.length
      try {
        while filled < n do {
          val read = source.read(buf, filled, n - filled)
          if read < 0 then throw new EOFException(s"PROXY preamble truncated: wanted $n, got $filled")
          filled += read
        }
        Right(buf)
      } catch { case e: Exception => Left(s"PROXY preamble read failed: ${e.getMessage}") }
    }
  }
}
