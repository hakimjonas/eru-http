package net.ghoula.eru.http.acme

import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey

/** A minimal PKCS#10 (RFC 2986) certification-request builder.
  *
  * The JDK exposes no public PKCS#10 API, so this builds the DER by hand — the structure is small
  * and fixed:
  * {{{
  * CertificationRequest ::= SEQUENCE {
  *   certificationRequestInfo SEQUENCE {
  *     version INTEGER (0),
  *     subject Name,                        -- single RDN: CN=<cn>
  *     subjectPKInfo SubjectPublicKeyInfo,  -- taken from the JDK's X.509 encoding
  *     attributes [0] IMPLICIT SET          -- one attribute: extensionRequest (SAN, dNSName)
  *   },
  *   signatureAlgorithm SEQUENCE { ecdsa-with-SHA256 },
  *   signature BIT STRING                   -- DER ECDSA signature over the CSRInfo DER
  * }}
  * }}}
  *
  * The SAN extension (RFC 5280 Section 4.2.1.6, OID 2.5.29.17) is mandatory for ACME CAs: Let's
  * Encrypt issues only from SAN, ignoring the CN.
  */
object Csr {

  private val OidCommonName = "2.5.4.3"
  private val OidEcdsaWithSha256 = "1.2.840.10045.4.3.2"
  private val OidExtensionRequest = "1.2.840.113549.1.9.14"
  private val OidSubjectAltName = "2.5.29.17"

  /** Builds a signed CSR for `domains` (SAN covers all; the first is the CN). */
  def build(keyPair: KeyPair, domains: List[String]): Either[AcmeError, Array[Byte]] =
    scala.util.Try {
      val publicKey = keyPair.getPublic match {
        case ec: ECPublicKey => ec
        case other => throw AcmeError.Crypto(s"expected EC public key, got ${other.getClass.getSimpleName}")
      }
      val cn = domains.headOption.getOrElse(throw AcmeError.Crypto("CSR requires at least one domain"))

      // The JDK's X.509 encoding of an EC public key IS the SubjectPublicKeyInfo structure.
      val subjectPkInfo = publicKey.getEncoded

      val sanEntries = domains.map(d => Der.contextPrimitive(2, Der.ia5String(d)))
      val sanExtension = Der.sequence(
        Der.oid(OidSubjectAltName) ++ Der.octetString(Der.sequence(sanEntries*))
      )
      // [0] is the IMPLICIT SET OF attributes per RFC 2986 — the SET tag is replaced by the
      // context tag, so the Attribute SEQUENCEs go directly under [0], not inside a 0x31 SET.
      // The extensionRequest VALUE is the JDK/Let's Encrypt convention: SET { SEQUENCE {
      // Extension... } } — CertificateExtensions encodes its extensions wrapped in one SEQUENCE.
      val attributes = Der.contextTagged(
        0,
        Der.sequence(
          Der.oid(OidExtensionRequest) ++ Der.set(Der.sequence(sanExtension))
        )
      )

      val csrInfo = Der.sequence(
        Der.integer(BigInteger.ZERO) ++
          Der.sequence(Der.set(Der.sequence(Der.oid(OidCommonName) ++ Der.utf8String(cn)))) ++
          subjectPkInfo ++
          attributes
      )

      val signer = Signature.getInstance("SHA256withECDSA")
      signer.initSign(keyPair.getPrivate)
      signer.update(csrInfo)
      val signature = signer.sign()

      Der.sequence(
        csrInfo ++
          Der.sequence(Der.oid(OidEcdsaWithSha256)) ++
          Der.bitString(signature)
      )
    }.toEither.left.map {
      case err: AcmeError => err
      case e => AcmeError.Crypto(s"CSR generation failed: ${e.getMessage}")
    }
}

/** Minimal DER (X.690) writer: tag-length-value primitives with definite lengths. */
object Der {

  def sequence(content: Array[Byte]*): Array[Byte] = tlv(0x30, concat(content*))
  def set(content: Array[Byte]*): Array[Byte] = tlv(0x31, concat(content*))

  def integer(value: BigInteger): Array[Byte] = tlv(0x02, value.toByteArray)
  def bitString(content: Array[Byte]): Array[Byte] = tlv(0x03, Array(0.toByte) ++ content)
  def octetString(content: Array[Byte]): Array[Byte] = tlv(0x04, content)
  def utf8String(value: String): Array[Byte] = tlv(0x0c, value.getBytes("UTF-8"))
  def ia5String(value: String): Array[Byte] = tlv(0x16, value.getBytes("US-ASCII"))

  /** Context-specific constructed tag number `n` (e.g. `[0]` attributes). */
  def contextTagged(n: Int, content: Array[Byte]): Array[Byte] = tlv(0xa0 | n, content)

  /** Context-specific primitive tag number `n` — IMPLICIT tagging keeps the underlying type's
    * primitive form (e.g. `[2] IMPLICIT IA5String` for dNSName is 0x82, not 0xa2).
    */
  def contextPrimitive(n: Int, content: Array[Byte]): Array[Byte] = tlv(0x80 | n, content)

  /** OBJECT IDENTIFIER in base-128 (X.690 Section 8.19). */
  def oid(dotted: String): Array[Byte] = {
    val parts = dotted.split('.').map(_.toInt)
    if parts.length < 2 then throw AcmeError.Crypto(s"malformed OID '$dotted'")
    val first = 40 * parts(0) + parts(1)
    val body = concat((encodeBase128(first) +: parts.drop(2).map(encodeBase128))*)
    tlv(0x06, body)
  }

  private def encodeBase128(value: Int): Array[Byte] = {
    var v = value
    var bytes = List.empty[Byte]
    bytes = (v & 0x7f).toByte :: bytes
    v >>= 7
    while v > 0 do {
      bytes = (((v & 0x7f) | 0x80).toByte) :: bytes
      v >>= 7
    }
    bytes.toArray
  }

  private def tlv(tag: Int, content: Array[Byte]): Array[Byte] = {
    val len = content.length
    val lengthBytes: Array[Byte] =
      if len < 0x80 then Array(len.toByte)
      else {
        val significant: List[Byte] = {
          var rest = len
          var out = List.empty[Byte]
          while rest > 0 do {
            out = (rest & 0xff).toByte :: out
            rest >>= 8
          }
          out
        }
        Array((0x80 | significant.length).toByte) ++ significant.toArray
      }
    Array(tag.toByte) ++ lengthBytes ++ content
  }

  private def concat(parts: Array[Byte]*): Array[Byte] = parts.foldLeft(Array.emptyByteArray)(_ ++ _)
}
