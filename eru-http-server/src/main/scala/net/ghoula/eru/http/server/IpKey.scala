package net.ghoula.eru.http.server

import java.net.InetAddress
import scala.collection.immutable.ArraySeq

/** Compact binary representation of a client IP address for use as a map key in `PerIpGovernor`.
  *
  * Mirrors nginx's `$binary_remote_addr` pattern: store IPs as raw bytes (4 for IPv4, 16 for IPv6)
  * rather than as `InetAddress` objects or strings. This minimizes per-entry memory footprint in
  * the bounded tracking map, letting the 100k-entry default cap fit comfortably in ~12MB of heap.
  *
  * Backed by `ArraySeq[Byte]` so content-based `equals` / `hashCode` fall out of the case class
  * derivation — there is no hand-rolled `equals` override to keep in sync with the data field.
  */
private[server] final case class IpKey private (private val data: ArraySeq[Byte]) {
  require(data.length == 4 || data.length == 16, s"IP bytes must be 4 (IPv4) or 16 (IPv6), got ${data.length}")

  /** Whether this is an IPv4 address (4 bytes). */
  def isIpv4: Boolean = data.length == 4

  /** Defensive copy of the raw bytes. */
  def bytes: Array[Byte] = data.toArray

  /** The address as a JDK `InetAddress` — raw bytes, no DNS resolution. */
  def toInetAddress: InetAddress = InetAddress.getByAddress(bytes)

  override def toString: String = {
    // Render as a parseable address string for logs and test output.
    try InetAddress.getByAddress(data.toArray).getHostAddress
    catch { case _: Exception => s"IpKey(${data.length} bytes)" }
  }
}

private[server] object IpKey {

  /** Build an IpKey from an InetAddress (IPv4 or IPv6). */
  def fromInetAddress(addr: InetAddress): IpKey =
    new IpKey(ArraySeq.unsafeWrapArray(addr.getAddress.clone()))

  /** Build an IpKey from raw bytes. Length must be 4 or 16. */
  def fromBytes(bytes: Array[Byte]): IpKey =
    new IpKey(ArraySeq.unsafeWrapArray(bytes.clone()))

  /** Build an IpKey from a String. Fails with None on anything that isn't a literal IP.
    *
    * Uses `InetAddress.ofLiteral` (JDK 22+) to avoid DNS resolution. Returns None for empty
    * strings, hostnames, and malformed input — `InetAddress.getByName("")` would silently resolve
    * to localhost, which is exactly the wrong behavior for a security-critical client-IP parser.
    */
  def parse(s: String): Option[IpKey] = {
    if s.isEmpty then None
    else {
      try Some(fromInetAddress(InetAddress.ofLiteral(s)))
      catch { case _: Exception => None }
    }
  }
}
