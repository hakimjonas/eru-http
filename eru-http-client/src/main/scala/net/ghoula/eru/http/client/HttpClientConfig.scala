package net.ghoula.eru.http.client

import scala.concurrent.duration.*

import net.ghoula.eru.http.{ContentEncoding, TlsConfig}

/** Configuration for HTTP client behavior.
  *
  * @param connectTimeout
  *   Maximum time to wait for a connection to be established
  * @param requestTimeout
  *   Maximum time to wait for a complete request/response cycle
  * @param maxConnections
  *   Maximum number of concurrent connections
  * @param maxConnectionsPerHost
  *   Maximum number of connections per host
  * @param followRedirects
  *   Whether to automatically follow redirects (3xx responses)
  * @param maxRedirects
  *   Maximum number of redirects to follow (if followRedirects is true)
  * @param enableHttp2
  *   Whether to enable HTTP/2 support
  * @param userAgent
  *   Default User-Agent header value
  * @param cookieJar
  *   Optional cookie jar for automatic cookie handling
  * @param acceptEncoding
  *   List of acceptable content encodings for Accept-Encoding header (default: gzip, deflate)
  * @param automaticDecompression
  *   Whether to automatically decompress response bodies based on Content-Encoding header (default:
  *   true)
  * @param tlsConfig
  *   TLS/SSL configuration for HTTPS connections (default: TlsConfig.default with secure settings)
  */
final case class HttpClientConfig(
  connectTimeout: Duration = 30.seconds,
  requestTimeout: Duration = 60.seconds,
  maxConnections: Int = 100,
  maxConnectionsPerHost: Int = 10,
  followRedirects: Boolean = true,
  maxRedirects: Int = 5,
  enableHttp2: Boolean = true,
  userAgent: Option[String] = Some("eru-http/0.1.0"),
  cookieJar: Option[CookieJar] = None,
  acceptEncoding: List[ContentEncoding] = List(ContentEncoding.Gzip, ContentEncoding.Deflate),
  automaticDecompression: Boolean = true,
  tlsConfig: TlsConfig = TlsConfig.default
) {

  /** Creates a copy with modified connect timeout.
    */
  def withConnectTimeout(timeout: Duration): HttpClientConfig =
    copy(connectTimeout = timeout)

  /** Creates a copy with modified request timeout.
    */
  def withRequestTimeout(timeout: Duration): HttpClientConfig =
    copy(requestTimeout = timeout)

  /** Creates a copy with modified max connections.
    */
  def withMaxConnections(max: Int): HttpClientConfig =
    copy(maxConnections = max)

  /** Creates a copy with modified max connections per host.
    */
  def withMaxConnectionsPerHost(max: Int): HttpClientConfig =
    copy(maxConnectionsPerHost = max)

  /** Creates a copy with redirect following enabled/disabled.
    */
  def withFollowRedirects(follow: Boolean): HttpClientConfig =
    copy(followRedirects = follow)

  /** Creates a copy with modified max redirects.
    */
  def withMaxRedirects(max: Int): HttpClientConfig =
    copy(maxRedirects = max)

  /** Creates a copy with HTTP/2 enabled/disabled.
    */
  def withHttp2(enabled: Boolean): HttpClientConfig =
    copy(enableHttp2 = enabled)

  /** Creates a copy with modified User-Agent.
    */
  def withUserAgent(agent: String): HttpClientConfig =
    copy(userAgent = Some(agent))

  /** Creates a copy with no User-Agent header.
    */
  def withoutUserAgent: HttpClientConfig =
    copy(userAgent = None)

  /** Creates a copy with a cookie jar.
    */
  def withCookieJar(jar: CookieJar): HttpClientConfig =
    copy(cookieJar = Some(jar))

  /** Creates a copy without a cookie jar.
    */
  def withoutCookieJar: HttpClientConfig =
    copy(cookieJar = None)

  /** Creates a copy with modified accepted encodings.
    */
  def withAcceptEncoding(encodings: List[ContentEncoding]): HttpClientConfig =
    copy(acceptEncoding = encodings)

  /** Creates a copy with automatic decompression enabled/disabled.
    */
  def withAutomaticDecompression(enabled: Boolean): HttpClientConfig =
    copy(automaticDecompression = enabled)

  /** Creates a copy with custom TLS configuration.
    */
  def withTls(config: TlsConfig): HttpClientConfig =
    copy(tlsConfig = config)

  /** Creates a copy with insecure TLS settings (trust all certificates, no hostname verification).
    *
    * WARNING: This is insecure and should ONLY be used for testing with self-signed certificates.
    * NEVER use this in production.
    */
  def withInsecureTls: HttpClientConfig =
    copy(tlsConfig = TlsConfig.insecure)
}

object HttpClientConfig {

  /** Default HTTP client configuration.
    */
  val default: HttpClientConfig = HttpClientConfig()

  /** Configuration optimized for low-latency requests.
    */
  val lowLatency: HttpClientConfig = HttpClientConfig(
    connectTimeout = 10.seconds,
    requestTimeout = 30.seconds,
    maxConnections = 50,
    maxConnectionsPerHost = 5
  )

  /** Configuration optimized for high-throughput requests.
    */
  val highThroughput: HttpClientConfig = HttpClientConfig(
    connectTimeout = 60.seconds,
    requestTimeout = 120.seconds,
    maxConnections = 500,
    maxConnectionsPerHost = 50
  )
}
