package net.ghoula.eru.http.server

import java.net.{Inet4Address, Inet6Address, InetAddress}

/** An IPv4 or IPv6 CIDR block (e.g. `10.0.0.0/8`, `2001:db8::/32`).
  *
  * Used by the server's trusted-proxies allowlist: an `X-Forwarded-For` header is trusted only when
  * the connection's TCP peer falls inside one of the configured CIDRs.
  *
  * Parsing is strict:
  *   - Prefix must match the address family of the base address (0–32 for IPv4, 0–128 for IPv6).
  *   - Host bits outside the prefix are NOT ignored — they must already be zero. `10.0.0.1/8` is
  *     rejected because bit 8+ is set. This prevents silent misconfiguration where an operator
  *     writes `10.1.2.3/24` intending `10.1.2.0/24`.
  */
private[server] final case class Cidr private (base: InetAddress, prefixBits: Int) {

  /** Raw bytes of the base address (4 for IPv4, 16 for IPv6). Copy-on-read. */
  private val baseBytes: Array[Byte] = base.getAddress.clone()

  /** True if `addr` falls inside this CIDR. Address family must match. */
  def contains(addr: InetAddress): Boolean = {
    val addrBytes = addr.getAddress
    if addrBytes.length != baseBytes.length then false
    else matches(addrBytes, baseBytes, prefixBits)
  }

  /** True if `key` falls inside this CIDR. Address family must match. */
  def contains(key: IpKey): Boolean = {
    val raw = key.bytes
    if raw.length != baseBytes.length then false
    else matches(raw, baseBytes, prefixBits)
  }

  override def toString: String = s"${base.getHostAddress}/$prefixBits"

  private def matches(a: Array[Byte], b: Array[Byte], bits: Int): Boolean = {
    val fullBytes = bits / 8
    val tailBits = bits % 8
    val fullEqual = java.util.Arrays.equals(a, 0, fullBytes, b, 0, fullBytes)
    if !fullEqual then false
    else if tailBits == 0 then true
    else {
      val mask = (0xff << (8 - tailBits)) & 0xff
      (a(fullBytes) & mask) == (b(fullBytes) & mask)
    }
  }
}

private[server] object Cidr {

  /** Parse a CIDR string. Returns Left(message) on malformed input or host-bits-set violation. */
  def parse(s: String): Either[String, Cidr] = {
    val idx = s.indexOf('/')
    if idx <= 0 || idx == s.length - 1 then Left(s"Missing prefix: $s")
    else {
      val addrPart = s.substring(0, idx)
      val prefixPart = s.substring(idx + 1)

      for {
        prefix <- prefixPart.toIntOption.toRight(s"Invalid prefix: $prefixPart")
        // Use ofLiteral to reject hostnames / DNS lookups / empty string.
        addr <- scala.util
          .Try(InetAddress.ofLiteral(addrPart))
          .toEither
          .left
          .map(_ => s"Invalid IP address: $addrPart")
        maxBits <- (addr: Matchable) match {
          case _: Inet4Address => Right(32)
          case _: Inet6Address => Right(128)
          case _ => Left(s"Unsupported address family: $addr")
        }
        _ <- Either.cond(
          prefix >= 0 && prefix <= maxBits,
          (),
          s"Prefix $prefix out of range for ${if maxBits == 32 then "IPv4" else "IPv6"} (0–$maxBits)"
        )
        _ <- Either.cond(
          !hasHostBitsSet(addr.getAddress, prefix),
          (),
          s"Host bits set in $s — use the network address (e.g. 10.0.0.0/8 rather than 10.0.0.1/8)"
        )
      } yield new Cidr(addr, prefix)
    }
  }

  /** Parse or throw. Only suitable for trusted constants (tests, hardcoded defaults). */
  def unsafeParse(s: String): Cidr = parse(s) match {
    case Right(c) => c
    case Left(msg) => throw new IllegalArgumentException(s"Invalid CIDR '$s': $msg")
  }

  private def hasHostBitsSet(bytes: Array[Byte], prefixBits: Int): Boolean = {
    val fullBytes = prefixBits / 8
    val tailBits = prefixBits % 8
    val boundaryByteDirty = tailBits != 0 && fullBytes < bytes.length && {
      val hostMask = (0xff >>> tailBits) & 0xff
      (bytes(fullBytes) & hostMask) != 0
    }
    val tailStart = if tailBits == 0 then fullBytes else fullBytes + 1
    val tailDirty = bytes.iterator.drop(tailStart).exists(_ != 0)
    boundaryByteDirty || tailDirty
  }
}
