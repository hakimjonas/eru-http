package net.ghoula.eru.http.acme

import java.nio.file.Path

/** Configuration for ACME provisioning.
  *
  * @param domains
  *   the DNS identifiers to certify. The first entry becomes the certificate's Common Name; all
  *   entries land in the Subject Alternative Name extension (Let's Encrypt requires SAN).
  * @param contactEmail
  *   account contact (RFC 8555 `contact` entry, `mailto:` scheme)
  * @param staging
  *   when true, use the Let's Encrypt **staging** directory — rate limits are generous and certs
  *   are issued by a test CA (not browser-trusted). Default true: the safe default is the one that
  *   cannot burn production rate limits.
  * @param directoryUrl
  *   explicit ACME directory URL; overrides `staging` when set (e.g. a local Pebble instance)
  * @param storePath
  *   directory where the account key, issued material, and PKCS12 keystores are persisted
  * @param keyStorePassword
  *   password for the generated PKCS12 keystores
  * @param renewBefore
  *   renew a certificate when its remaining validity drops below this duration (default 30 days;
  *   Let's Encrypt certificates live 90 days)
  * @param renewalCheckInterval
  *   how often the renewal loop checks expiry
  * @param http01Port
  *   port the HTTP-01 challenge responder must be reachable on. ACME validates http-01 on port 80
  *   (RFC 8555 Section 8.3); deployments typically front this with a redirect from :80. The
  *   responder itself is a middleware — wiring it into a listener on this port is the operator's
  *   (or [[AcmeHttp01.redirectTarget]]'s) job.
  */
final case class AcmeConfig(
  domains: List[String],
  contactEmail: String,
  staging: Boolean = true,
  directoryUrl: Option[String] = None,
  storePath: Path,
  keyStorePassword: String = "changeit",
  renewBefore: java.time.Duration = java.time.Duration.ofDays(30),
  renewalCheckInterval: java.time.Duration = java.time.Duration.ofHours(12),
  http01Port: Int = 80
) derives CanEqual {

  require(domains.nonEmpty, "at least one domain is required")

  /** The ACME directory URL this config resolves to. */
  def resolvedDirectoryUrl: String = directoryUrl.getOrElse {
    if staging then "https://acme-staging-v02.api.letsencrypt.org/directory"
    else "https://acme-v02.api.letsencrypt.org/directory"
  }
}

object AcmeConfig {

  /** Let's Encrypt production directory. */
  val LetsEncryptProduction: String = "https://acme-v02.api.letsencrypt.org/directory"

  /** Let's Encrypt staging directory. */
  val LetsEncryptStaging: String = "https://acme-staging-v02.api.letsencrypt.org/directory"
}
