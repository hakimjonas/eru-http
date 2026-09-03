package net.ghoula.eru.http.acme

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.X509Certificate

/** Builds and persists the PKCS12 keystore `TlsConfig` consumes.
  *
  * Eru servers take `keyStorePath` + `keyStorePassword`; this module therefore materializes the
  * issued chain plus the certificate key into a PKCS12 file (and, alongside it, PEM files for
  * observability and external tooling).
  */
object KeystoreWriter {

  /** One issued certificate: the chain (leaf first), the key that matches the leaf, and the leaf's
    * expiry.
    */
  final case class IssuedCert(
    domains: List[String],
    chain: List[X509Certificate],
    keyPair: KeyPair,
    notAfter: java.time.Instant
  ) derives CanEqual

  /** Builds a PKCS12 keystore containing `cert.chain` under alias `domains.head`, keyed by
    * `cert.keyPair`.
    */
  def buildPkcs12(cert: IssuedCert, password: String): Array[Byte] = {
    val keyStore = KeyStore.getInstance("PKCS12")
    // JDK API requirement: load(null, null) initializes an empty in-memory keystore.
    keyStore.load(null, null) // scalafix:ok DisableSyntax.null
    val alias = cert.domains.headOption.getOrElse("eru-http-acme")
    // Build the chain as the JDK's expected Array[Certificate] (arrays are invariant).
    val chainArray = new Array[java.security.cert.Certificate](cert.chain.size)
    cert.chain.zipWithIndex.foreach { case (certificate, index) => chainArray(index) = certificate }
    keyStore.setKeyEntry(
      alias,
      cert.keyPair.getPrivate,
      password.toCharArray,
      chainArray
    )
    val out = new java.io.ByteArrayOutputStream()
    keyStore.store(out, password.toCharArray)
    out.toByteArray
  }

  /** Persists the issued material under `storePath/<primary-domain>/`:
    *   - `<primary>.p12` — the keystore `TlsConfig` points at
    *   - `cert.pem` / `chain.pem` / `key.pem` — observability + external tooling
    *
    * Returns the keystore path.
    */
  def persist(storePath: Path, cert: IssuedCert, password: String): Either[AcmeError, Path] =
    scala.util.Try {
      val primary = cert.domains.headOption.getOrElse("server")
      val dir = storePath.resolve(primary)
      Files.createDirectories(dir)

      val p12Path = dir.resolve(s"$primary.p12")
      val p12 = buildPkcs12(cert, password)
      val out = new FileOutputStream(p12Path.toFile)
      try out.write(p12)
      finally out.close()

      val chainPem = Pem.encodeChain(cert.chain) match {
        case Right(pem) => pem
        case Left(error) => throw error
      }
      val (keyPem, _) = Pem.encodeKeyPair(cert.keyPair)
      Files.writeString(dir.resolve("cert.pem"), chainPem)
      Files.writeString(dir.resolve("chain.pem"), chainPem)
      Files.writeString(dir.resolve("key.pem"), keyPem)
      p12Path
    }.toEither.left.map {
      case err: AcmeError => err
      case e => AcmeError.Storage(s"cannot persist issued material: ${e.getMessage}")
    }

  /** Reads a persisted PEM certificate chain and returns the leaf's expiry. */
  def leafExpiry(chainPem: String): Either[AcmeError, java.time.Instant] =
    Pem.decodeChain(chainPem).flatMap {
      case Nil => Left(AcmeError.Download("empty certificate chain"))
      case leaf :: _ => Right(leaf.getNotAfter.toInstant)
    }
}
