package net.ghoula.eru.http

/** TLS/SSL protocol versions.
  *
  * Represents the supported TLS protocol versions for secure communication. TLS 1.3 is recommended
  * for enhanced security and performance.
  */
enum TlsVersion {

  /** TLS 1.2 protocol version.
    *
    * Widely supported but older protocol. Use TLS 1.3 when possible.
    */
  case TLSv1_2

  /** TLS 1.3 protocol version.
    *
    * Latest TLS protocol with improved security and performance. Recommended for all new
    * deployments.
    */
  case TLSv1_3

  /** Returns the protocol version string for use with SSL/TLS libraries.
    *
    * @return
    *   The protocol version string (e.g., "TLSv1.2", "TLSv1.3")
    */
  def value: String = this match {
    case TLSv1_2 => "TLSv1.2"
    case TLSv1_3 => "TLSv1.3"
  }
}

/** TLS/SSL configuration for HTTP clients and servers.
  *
  * Provides fine-grained control over TLS/SSL behavior including protocol versions, certificate
  * validation, and hostname verification.
  *
  * ==Security Considerations==
  *
  * The default configuration (`TlsConfig.default`) uses secure settings:
  *   - TLS enabled
  *   - Both TLS 1.3 and TLS 1.2 protocols supported (preferring 1.3)
  *   - Full certificate validation
  *   - Hostname verification enabled
  *
  * For production deployments:
  *   - Use `TlsConfig.default` or `TlsConfig.tls13Only` for maximum security
  *   - Never use `TlsConfig.insecure` in production
  *   - Ensure proper certificates are configured (not self-signed)
  *   - Keep TLS libraries up to date
  *
  * For development/testing only:
  *   - `TlsConfig.insecure` disables all certificate validation
  *   - Useful for self-signed certificates in local development
  *   - WARNING: Exposes you to man-in-the-middle attacks
  *
  * ==Custom Certificates==
  *
  * To use custom certificates (for client authentication or custom CA):
  *   - For clients: Configure your JVM's truststore or use Netty's SslContextBuilder
  *   - For servers: Provide certificate and private key files
  *   - See Netty SSL documentation for advanced certificate configuration
  *
  * @param enabled
  *   Whether TLS/SSL is enabled. Set to false for plain HTTP connections.
  * @param protocols
  *   List of TLS protocol versions to support, in order of preference. Default is TLS 1.3 followed
  *   by TLS 1.2 for maximum compatibility.
  * @param trustAll
  *   If true, accept all certificates without validation. WARNING: This is insecure and should only
  *   be used for testing with self-signed certificates. Default is false.
  * @param verifyHostname
  *   If true, verify that the certificate's hostname matches the requested hostname. This prevents
  *   man-in-the-middle attacks. Should only be disabled for testing. Default is true.
  */
final case class TlsConfig(
  enabled: Boolean = true,
  protocols: List[TlsVersion] = List(TlsVersion.TLSv1_3, TlsVersion.TLSv1_2),
  trustAll: Boolean = false,
  verifyHostname: Boolean = true
)

object TlsConfig {

  /** Default TLS configuration with secure settings.
    *
    * Uses:
    *   - TLS enabled
    *   - Both TLS 1.3 and TLS 1.2 (preferring 1.3)
    *   - Full certificate validation
    *   - Hostname verification enabled
    *
    * This is the recommended configuration for production use.
    */
  val default: TlsConfig = TlsConfig()

  /** Insecure TLS configuration that trusts all certificates and skips hostname verification.
    *
    * WARNING: This configuration is INSECURE and should ONLY be used for testing with self-signed
    * certificates in development environments.
    *
    * This configuration:
    *   - Accepts any certificate without validation
    *   - Skips hostname verification
    *   - Makes you vulnerable to man-in-the-middle attacks
    *
    * NEVER use this in production or when handling sensitive data.
    */
  val insecure: TlsConfig = TlsConfig(
    trustAll = true,
    verifyHostname = false
  )

  /** TLS 1.3 only configuration.
    *
    * Uses only TLS 1.3 protocol for maximum security and performance. Note that this may not be
    * compatible with older servers/clients that don't support TLS 1.3.
    *
    * Recommended when:
    *   - You control both client and server
    *   - You know the peer supports TLS 1.3
    *   - You need the latest security features
    */
  val tls13Only: TlsConfig = TlsConfig(
    protocols = List(TlsVersion.TLSv1_3)
  )

  /** Disabled TLS configuration (plain HTTP).
    *
    * Use this for unencrypted HTTP connections. Only appropriate for:
    *   - Local development
    *   - Internal networks behind a reverse proxy
    *   - Non-sensitive data
    *
    * For production internet-facing services, always use TLS.
    */
  val disabled: TlsConfig = TlsConfig(enabled = false)
}
