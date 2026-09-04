package net.ghoula.eru.http

import java.net.InetAddress

/** The client address a request arrived from, as resolved by the server.
  *
  * Resolution follows the same policy per-IP rate limiting uses, so a `RateLimit`-style consumer
  * keying on this address and the governor's own accounting can never disagree:
  *   1. The connection-level address captured at accept time — the TCP peer, or the address a PROXY
  *      protocol v2 preamble carried when the peer speaks PROXY.
  *   2. When that address falls inside the configured trusted-proxy CIDRs and the request carries
  *      `X-Forwarded-For`, the leftmost untrusted XFF entry.
  *
  * Servers attach it to every incoming request; client-constructed requests carry `None`.
  *
  * @param address
  *   the resolved client address (no DNS semantics — a literal address)
  * @param source
  *   how the address was determined
  */
final case class ClientAddress(
  address: InetAddress,
  source: ClientAddress.Source
) derives CanEqual {

  /** The address in its canonical textual form (IPv4 dotted-quad or IPv6 textual). */
  def hostAddress: String = address.getHostAddress
}

object ClientAddress {

  /** How the server determined the client address. */
  enum Source derives CanEqual {

    /** The TCP peer address (`SocketChannel.getRemoteAddress`). */
    case TcpPeer

    /** The address carried by a PROXY protocol v2 preamble spoken by the peer. */
    case ProxyProtocol

    /** The leftmost untrusted `X-Forwarded-For` entry, after the connection-level address proved to
      * be a configured trusted proxy.
      */
    case ForwardedFor
  }
}
