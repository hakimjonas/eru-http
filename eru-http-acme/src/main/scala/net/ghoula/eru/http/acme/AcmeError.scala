package net.ghoula.eru.http.acme

/** Errors the ACME machinery can produce, across the protocol, crypto, and storage layers.
  *
  * Extends [[RuntimeException]] so crypto/IO blocks evaluated inside `Try` can carry a typed
  * failure through to the `Eru` error channel. `getMessage` carries the human-readable detail.
  * Protocol errors also carry the HTTP status and, when the server sent one, the ACME problem
  * `type` (RFC 8555 Section 8.6 / RFC 7807).
  */
enum AcmeError(message: String) extends RuntimeException(message) derives CanEqual {
  case Directory(detail: String) extends AcmeError(s"ACME directory: $detail")
  case Nonce(detail: String) extends AcmeError(s"ACME nonce: $detail")
  case Account(detail: String) extends AcmeError(s"ACME account: $detail")
  case Order(detail: String) extends AcmeError(s"ACME order: $detail")
  case Authorization(identifier: String, detail: String)
      extends AcmeError(s"ACME authorization for '$identifier': $detail")
  case Challenge(identifier: String, detail: String) extends AcmeError(s"ACME challenge for '$identifier': $detail")
  case Finalize(detail: String) extends AcmeError(s"ACME finalize: $detail")
  case Download(detail: String) extends AcmeError(s"ACME certificate download: $detail")
  case Keystore(detail: String) extends AcmeError(s"ACME keystore: $detail")
  case Crypto(detail: String) extends AcmeError(s"ACME crypto: $detail")
  case Storage(detail: String) extends AcmeError(s"ACME storage: $detail")
  case Protocol(detail: String, status: Option[Int] = None, problemType: Option[String] = None)
      extends AcmeError(
        s"ACME protocol: $detail${status.map(s => s" (HTTP $s)").getOrElse("")}${problemType.map(t => s" [$t]").getOrElse("")}"
      )
  case Timeout(detail: String) extends AcmeError(s"ACME timeout: $detail")
}
