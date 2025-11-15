package net.ghoula.eru.http.server

import scala.concurrent.duration.*

import net.ghoula.eru.http.{ContentEncoding, TlsConfig}

/** Configuration for HTTP server.
  *
  * @param host
  *   The host to bind to (e.g., "0.0.0.0" for all interfaces, "localhost" for local only)
  * @param port
  *   The port to listen on (0 for random available port)
  * @param backlog
  *   Maximum queue length for incoming connections
  * @param maxConnections
  *   Maximum number of concurrent connections to handle. Additional connections will wait in
  *   backlog. Default 1024. Helps prevent resource exhaustion under extreme load.
  * @param idleTimeout
  *   Maximum time a connection can be idle before being closed
  * @param maxRequestSize
  *   Maximum size of incoming HTTP requests in bytes
  * @param enableHttp2
  *   Whether to enable HTTP/2 support (if available)
  * @param gracefulShutdownTimeout
  *   Time to wait for in-flight requests during graceful shutdown
  * @param compressionEnabled
  *   Whether to enable response compression (default: true)
  * @param compressionLevel
  *   Compression level 1-9, where 1=fastest, 9=best compression (default: 6)
  * @param compressionMinSize
  *   Minimum response body size in bytes to compress (default: 1024 bytes)
  * @param compressionEncodings
  *   List of supported compression encodings in order of preference (default: gzip, deflate)
  * @param tlsConfig
  *   Optional TLS/SSL configuration. None = plain HTTP, Some(config) = HTTPS with specified TLS
  *   settings. For HTTPS, you must provide proper certificates (development mode uses self-signed).
  */
final case class HttpServerConfig(
  host: String = "0.0.0.0",
  port: Int = 8080,
  backlog: Int = 128,
  maxConnections: Int = 1024,
  idleTimeout: Duration = 60.seconds,
  maxRequestSize: Int = 10 * 1024 * 1024, // 10 MB
  enableHttp2: Boolean = false,
  gracefulShutdownTimeout: Duration = 10.seconds,
  compressionEnabled: Boolean = true,
  compressionLevel: Int = 6,
  compressionMinSize: Int = 1024,
  compressionEncodings: List[ContentEncoding] = List(ContentEncoding.Gzip, ContentEncoding.Deflate),
  tlsConfig: Option[TlsConfig] = None
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

  /** Creates a copy with compression enabled/disabled.
    */
  def withCompression(enabled: Boolean): HttpServerConfig =
    copy(compressionEnabled = enabled)

  /** Creates a copy with modified compression level (1-9).
    */
  def withCompressionLevel(level: Int): HttpServerConfig = {
    require(level >= 1 && level <= 9, "Compression level must be between 1 and 9")
    copy(compressionLevel = level)
  }

  /** Creates a copy with modified minimum compression size.
    */
  def withCompressionMinSize(size: Int): HttpServerConfig = {
    require(size >= 0, "Compression minimum size must be non-negative")
    copy(compressionMinSize = size)
  }

  /** Creates a copy with modified compression encodings.
    */
  def withCompressionEncodings(encodings: List[ContentEncoding]): HttpServerConfig =
    copy(compressionEncodings = encodings)

  /** Creates a copy with TLS/SSL enabled using the provided configuration.
    *
    * This enables HTTPS on the server. In development, self-signed certificates will be used. In
    * production, you should configure proper certificates.
    */
  def withTls(config: TlsConfig): HttpServerConfig =
    copy(tlsConfig = Some(config))

  /** Creates a copy with TLS/SSL disabled (plain HTTP).
    */
  def withoutTls: HttpServerConfig =
    copy(tlsConfig = None)
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
    * Larger backlog and request size limits for handling many concurrent connections.
    */
  val highThroughput: HttpServerConfig = HttpServerConfig(
    backlog = 1024,
    maxRequestSize = 50 * 1024 * 1024, // 50 MB
    gracefulShutdownTimeout = 30.seconds
  )

  /** Configuration for microservices.
    *
    * Quick shutdown and smaller limits for containerized environments.
    */
  val microservice: HttpServerConfig = HttpServerConfig(
    port = 8080,
    backlog = 256,
    idleTimeout = 30.seconds,
    maxRequestSize = 5 * 1024 * 1024, // 5 MB
    gracefulShutdownTimeout = 5.seconds
  )
}
