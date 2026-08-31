package net.ghoula.eru.http.server

import scala.concurrent.duration.*

import net.ghoula.eru.EruObserver
import net.ghoula.eru.http.TlsConfig

/** PROXY protocol v2 detection mode (HAProxy spec).
  *
  *   - [[ProxyProtocolMode.Off]] — never expect a PROXY preamble. Every connection's TCP peer is
  *     treated as the client.
  *   - [[ProxyProtocolMode.Optional]] — if the first 12 bytes look like the PROXY v2 signature,
  *     parse it; otherwise treat the stream as regular HTTP. Suitable for dual-mode deployments.
  *   - [[ProxyProtocolMode.Required]] — every connection MUST start with a valid PROXY v2 preamble;
  *     missing or malformed frames cause an immediate connection drop. Harshest option, correct
  *     when you know every inbound connection is via a PROXY-aware LB.
  */
enum ProxyProtocolMode {
  case Off
  case Optional
  case Required
}

/** Configuration for HTTP server.
  *
  * @param host
  *   The host to bind to (e.g., "0.0.0.0" for all interfaces, "localhost" for local only)
  * @param port
  *   The port to listen on (0 for random available port)
  * @param backlog
  *   Maximum queue length for incoming connections
  * @param maxConnections
  *   Maximum number of concurrent connections to handle. Connections beyond this cap block the
  *   accept loop on a permit; further connects queue in the kernel backlog. Default 1024. Helps
  *   prevent resource exhaustion under extreme load.
  * @param idleTimeout
  *   Maximum silent gap a keep-alive connection may sit in between the previous response and the
  *   first byte of the next request. Header reading itself is bounded by `readHeaderTimeout` for
  *   every request. Analogous to nginx's `keepalive_timeout`. Default 60s matches typical client
  *   connection-pool lifetimes.
  * @param readHeaderTimeout
  *   Socket-level per-read deadline for the header phase of EVERY request (first or keep-alive): if
  *   a client sends nothing for this long while its headers are being read, the server answers 408
  *   and closes. A client that keeps sending bytes faster than the deadline is making legitimate
  *   progress. Defeats Slowloris. Analogous to nginx's `client_header_timeout`. Default 15s.
  * @param maxRequestSize
  *   Maximum size of incoming HTTP requests in bytes. Default 10 MB.
  * @param enableHttp2
  *   Whether to enable HTTP/2 support (if available)
  * @param gracefulShutdownTimeout
  *   Bound on the shutdown wait for handler-fiber cleanup; in-flight requests are interrupted
  *   rather than completed
  * @param tlsConfig
  *   Optional TLS/SSL configuration. None = plain HTTP, Some(config) = HTTPS with specified TLS
  *   settings. For HTTPS, you must provide proper certificates (development mode uses self-signed).
  * @param acceptorThreads
  *   Number of acceptor threads for SO_REUSEPORT multi-threaded accept (Linux 3.9+). Default is
  *   number of available processors. Each acceptor runs its own accept loop, enabling kernel-level
  *   load balancing across cores. Set to 1 to disable SO_REUSEPORT.
  * @param proxyProtocolMode
  *   How the server handles PROXY protocol v2 preambles. See [[ProxyProtocolMode]]. Default Off.
  * @param trustedProxies
  *   CIDR blocks from which an `X-Forwarded-For` header is trusted. Empty by default — XFF is
  *   ignored unless the TCP peer is on this list. Do NOT set to `0.0.0.0/0`; that lets any client
  *   spoof their IP via a header and defeats per-IP limits.
  * @param maxConnectionsPerIp
  *   Concurrent connections permitted per client IP. Connections beyond this cap are dropped with
  *   TCP close (no HTTP response). Default 10 — covers HTTP/1.1 browser parallelism (6) with
  *   margin.
  * @param acceptRatePerIp
  *   Sustained rate of new-connection accepts per client IP (conn/sec). Default 20. Together with
  *   `acceptBurstPerIp` forms a token bucket on connection acceptance — closes the open/close storm
  *   bypass of `maxConnectionsPerIp`.
  * @param acceptBurstPerIp
  *   Burst budget for `acceptRatePerIp`. Default 20.
  * @param requestsPerSecondPerIp
  *   Sustained request rate per client IP (req/sec). Default 10. Over-budget requests get 429 Too
  *   Many Requests with `Retry-After`.
  * @param burstSizePerIp
  *   Burst budget for `requestsPerSecondPerIp`. Default 20.
  * @param trackedIpCap
  *   Hard upper bound on the number of client IPs tracked by `PerIpGovernor`. When the map is full,
  *   new IPs are REJECTED (fail-closed) rather than evicting existing entries. This is deliberate:
  *   an attacker rotating through fresh IPs must not be able to evict legit clients. Default
  *   100_000. Budget on the order of 12MB of heap for the map — a rough figure, since each IpEntry
  *   carries atomics and token buckets on top of Caffeine's per-entry overhead.
  * @param perIpGovernanceEnabled
  *   Master switch for per-IP governance. Default `false` — the feature is opt-in because it
  *   changes connection semantics. Turn on when you expose the server directly to untrusted traffic
  *   (no proxy in front, or PROXY/XFF correctly configured).
  * @param tlsHandshakeTimeout
  *   Maximum wall time allowed for a TLS handshake to complete. Beyond this the accept-loop VT is
  *   unparked by closing the socket, defeating a Slowloris-over-TLS that would otherwise hold
  *   handler VTs forever. Applied by wrapping the handshake in Eru `.timeout` (see
  *   `NativeHttpServer.wrapWithTLS`): `SocketChannel.read` in blocking mode does not honor
  *   `SO_TIMEOUT`, so the fiber is interrupted instead, which closes the channel. Once the
  *   handshake succeeds, request-phase reads are controlled by `readHeaderTimeout` / `idleTimeout`.
  *   Default 10s.
  * @param proxyHandshakeTimeout
  *   Maximum wall time for reading a PROXY v2 preamble. The preamble read runs on the accept-loop
  *   virtual thread with no socket read timeout, so a peer that stalls mid-preamble would otherwise
  *   park an acceptor forever. On timeout the connection is closed and the acceptor moves on. Only
  *   applies when `proxyProtocolMode` is not `Off`. Default 10s.
  * @param bodyReadTimeout
  *   Socket-level per-read deadline for request BODY reads: if a client sends nothing for this long
  *   while its body is being read, the server answers 408 (or closes for an already-committed
  *   response) and moves on. Analogous to nginx's `client_body_timeout`. Without it, a client
  *   trickling a body at one byte per half minute holds a fiber and buffer indefinitely. Default
  *   60s.
  * @param strictPathValidation
  *   When enabled, reject requests whose path contains control characters (0x00–0x1F or 0x7F).
  *   eru-http's default contract is byte-faithful: paths are delivered to handlers as-parsed but
  *   un-decoded and un-normalized. Handlers that do file-system or DB lookups from a path MUST
  *   normalize themselves (`Path.of(p).normalize().startsWith(base)`). Strict mode rejects byte
  *   sequences that can never appear in a well-formed request target per RFC 9110 §4 / RFC 3986
  *   §3.3 — NUL, raw CR, etc. It does NOT percent-decode: `..%2f` is still accepted. Default true
  *   as of 1.0.0 (flipped from opt-in; byte-faithful delivery is unchanged). Opt out with
  *   withStrictPathValidation(false) for deployments that need the old lenient behavior.
  * @param serverObserver
  *   Optional observer attached to server-side fibers (accept loops, connection handlers). When
  *   set, each fiber emits `FiberStarted`/`FiberCompleted` events through this observer. Set to
  *   `None` (the default) for no observer overhead. Use together with `DiagnosticsObserver` (from
  *   `eru-diagnostics` or your own implementation) to track active-connection counts and detect
  *   stuck fibers.
  * @param watchdogInterval
  *   When set, the server sends `WATCHDOG=1` heartbeats to systemd at half this interval via the
  *   `NOTIFY_SOCKET` Unix datagram socket. Requirement: `Type=notify` and a matching `WatchdogSec=`
  *   in the systemd service unit. A dedicated daemon fiber runs independently of request handlers,
  *   so a stuck handler cannot prevent the watchdog heartbeat. When `None` (default), no watchdog
  *   activity occurs — safe for non-systemd environments.
  */
final case class HttpServerConfig(
  host: String = "0.0.0.0",
  port: Int = 8080,
  backlog: Int = 128,
  maxConnections: Int = 1024,
  idleTimeout: Duration = 60.seconds,
  readHeaderTimeout: Duration = 15.seconds,
  bodyReadTimeout: Duration = 60.seconds,
  maxRequestSize: Int = 10 * 1024 * 1024,
  enableHttp2: Boolean = true,
  gracefulShutdownTimeout: Duration = 10.seconds,
  tlsConfig: Option[TlsConfig] = None,
  acceptorThreads: Int = Runtime.getRuntime.availableProcessors(),
  proxyProtocolMode: ProxyProtocolMode = ProxyProtocolMode.Off,
  trustedProxies: List[Cidr] = Nil,
  maxConnectionsPerIp: Int = 10,
  acceptRatePerIp: Int = 20,
  acceptBurstPerIp: Int = 20,
  requestsPerSecondPerIp: Int = 10,
  burstSizePerIp: Int = 20,
  trackedIpCap: Int = 100_000,
  perIpGovernanceEnabled: Boolean = false,
  tlsHandshakeTimeout: Duration = 10.seconds,
  proxyHandshakeTimeout: Duration = 10.seconds,
  strictPathValidation: Boolean = true,
  serverObserver: Option[EruObserver] = None,
  watchdogInterval: Option[Duration] = None
) {

  /** Creates a copy with modified host.
    */
  def withHost(host: String): HttpServerConfig =
    copy(host = host)

  /** Creates a copy with modified port.
    */
  def withPort(port: Int): HttpServerConfig =
    copy(port = port)

  /** Creates a copy with modified backlog.
    */
  def withBacklog(backlog: Int): HttpServerConfig =
    copy(backlog = backlog)

  /** Creates a copy with modified idle timeout.
    */
  def withIdleTimeout(timeout: Duration): HttpServerConfig =
    copy(idleTimeout = timeout)

  /** Creates a copy with modified read-header timeout (Slowloris defense).
    */
  def withReadHeaderTimeout(timeout: Duration): HttpServerConfig =
    copy(readHeaderTimeout = timeout)

  /** Creates a copy with modified max request size.
    */
  def withMaxRequestSize(size: Int): HttpServerConfig =
    copy(maxRequestSize = size)

  /** Creates a copy with modified HTTP/2 setting.
    */
  def withHttp2(enabled: Boolean): HttpServerConfig =
    copy(enableHttp2 = enabled)

  /** Creates a copy with modified graceful shutdown timeout.
    */
  def withGracefulShutdownTimeout(timeout: Duration): HttpServerConfig =
    copy(gracefulShutdownTimeout = timeout)

  /** Creates a copy with TLS/SSL enabled using the provided configuration.
    *
    * This enables HTTPS on the server. In development, self-signed certificates will be used. In
    * production, you should configure proper certificates.
    */
  def withTls(config: TlsConfig): HttpServerConfig =
    copy(tlsConfig = Some(config))

  /** Creates a copy with a modified TLS handshake timeout. */
  def withTlsHandshakeTimeout(timeout: Duration): HttpServerConfig =
    copy(tlsHandshakeTimeout = timeout)

  /** Creates a copy with strict path validation enabled/disabled. */
  def withStrictPathValidation(enabled: Boolean = true): HttpServerConfig =
    copy(strictPathValidation = enabled)

  /** Creates a copy with modified PROXY protocol v2 mode. */
  def withProxyProtocolMode(mode: ProxyProtocolMode): HttpServerConfig =
    copy(proxyProtocolMode = mode)

  /** Creates a copy with modified trusted-proxies CIDR list.
    *
    * Only when the TCP peer falls inside one of these CIDRs will `X-Forwarded-For` be trusted.
    * Setting `0.0.0.0/0` or `::/0` defeats the entire purpose — any client can spoof its IP.
    */
  def withTrustedProxies(cidrs: List[Cidr]): HttpServerConfig =
    copy(trustedProxies = cidrs)

  /** Enables per-IP governance (connection caps + rate limiting). */
  def withPerIpGovernance(enabled: Boolean = true): HttpServerConfig =
    copy(perIpGovernanceEnabled = enabled)

  /** Creates a copy with modified per-IP concurrent-connection cap. */
  def withMaxConnectionsPerIp(n: Int): HttpServerConfig =
    copy(maxConnectionsPerIp = n)

  /** Creates a copy with modified per-IP accept-rate limits. */
  def withAcceptRatePerIp(ratePerSec: Int, burst: Int): HttpServerConfig =
    copy(acceptRatePerIp = ratePerSec, acceptBurstPerIp = burst)

  /** Creates a copy with modified per-IP request-rate limits. */
  def withRequestRatePerIp(ratePerSec: Int, burst: Int): HttpServerConfig =
    copy(requestsPerSecondPerIp = ratePerSec, burstSizePerIp = burst)

  /** Creates a copy with modified tracked-IP cap. */
  def withTrackedIpCap(cap: Int): HttpServerConfig =
    copy(trackedIpCap = cap)
}

object HttpServerConfig {

  /** Default server configuration.
    *
    * Binds to all interfaces on port 8080 with reasonable defaults.
    */
  val default: HttpServerConfig = HttpServerConfig()

  /** Configuration for local development.
    *
    * Binds only to localhost for security.
    */
  val localhost: HttpServerConfig = HttpServerConfig(
    host = "localhost",
    port = 8080
  )

  /** Configuration optimized for high throughput.
    *
    * Larger backlog and request size limits for handling many concurrent connections. Uses a
    * 1024-deep backlog and a 50 MB request-size limit.
    */
  val highThroughput: HttpServerConfig = HttpServerConfig(
    backlog = 1024,
    maxRequestSize = 50 * 1024 * 1024,
    gracefulShutdownTimeout = 30.seconds
  )

  /** Configuration for microservices.
    *
    * Quick shutdown and smaller limits for containerized environments. Uses a 5 MB request-size
    * limit and a 256-deep backlog.
    */
  val microservice: HttpServerConfig = HttpServerConfig(
    port = 8080,
    backlog = 256,
    idleTimeout = 30.seconds,
    maxRequestSize = 5 * 1024 * 1024,
    gracefulShutdownTimeout = 5.seconds
  )

  /** Configuration for edge-exposed deployments (the server faces untrusted traffic directly, no
    * trusted LB in front).
    *
    * Per-IP governance is ON: per-IP connection caps, accept-rate and request-rate token buckets,
    * and the fail-closed tracked-IP cap, all at their documented defaults (10 concurrent
    * connections per IP, 20/20 accept bucket, 10/20 request bucket, 100k tracked IPs). Strict path
    * validation stays on (the default). The limits are conservative starting points — tune them to
    * your traffic.
    */
  val edge: HttpServerConfig = HttpServerConfig(
    port = 8080,
    maxConnections = 4096,
    strictPathValidation = true,
    perIpGovernanceEnabled = true
  )
}
