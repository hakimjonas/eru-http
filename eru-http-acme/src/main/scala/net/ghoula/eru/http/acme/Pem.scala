package net.ghoula.eru.http.acme

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** PEM (RFC 7468) wrapping/unwrapping and JDK key/certificate decoding for the on-disk material. */
object Pem {

  private val nl = System.lineSeparator()

  /** Wraps DER bytes in a PEM block. */
  def encode(label: String, der: Array[Byte]): String = {
    val b64 = Base64.getMimeEncoder(64, nl.getBytes).encodeToString(der)
    s"-----BEGIN $label-----$nl$b64$nl-----END $label-----$nl"
  }

  /** Extracts the DER payload of the first PEM block carrying `label`. */
  def decode(content: String, label: String): Either[AcmeError, Array[Byte]] = {
    val begin = s"-----BEGIN $label-----"
    val end = s"-----END $label-----"
    val startIdx = content.indexOf(begin)
    val endIdx = content.indexOf(end)
    if startIdx < 0 || endIdx < 0 || endIdx < startIdx then Left(AcmeError.Storage(s"no PEM block '$label' found"))
    else {
      val b64 = content.substring(startIdx + begin.length, endIdx).replaceAll("\\s", "")
      scala.util
        .Try(Base64.getDecoder.decode(b64))
        .toEither
        .left
        .map(_ => AcmeError.Storage(s"malformed base64 in PEM block '$label'"))
    }
  }

  /** Encodes an EC keypair to PEM (`PRIVATE KEY` PKCS#8 + `PUBLIC KEY` X.509). */
  def encodeKeyPair(keyPair: KeyPair): (String, String) = {
    val privatePem = encode("PRIVATE KEY", keyPair.getPrivate.getEncoded)
    val publicPem = encode("PUBLIC KEY", keyPair.getPublic.getEncoded)
    (privatePem, publicPem)
  }

  /** Decodes a PKCS#8 PEM private key (EC). */
  def decodePrivateKey(pem: String): Either[AcmeError, PrivateKey] =
    decode(pem, "PRIVATE KEY").flatMap { der =>
      scala.util.Try {
        val kf = KeyFactory.getInstance("EC")
        kf.generatePrivate(new PKCS8EncodedKeySpec(der))
      }.toEither.left.map(e => AcmeError.Crypto(s"cannot decode private key: ${e.getMessage}"))
    }

  /** Decodes an X.509 PEM public key (EC). */
  def decodePublicKey(pem: String): Either[AcmeError, PublicKey] =
    decode(pem, "PUBLIC KEY").flatMap { der =>
      scala.util.Try {
        val kf = KeyFactory.getInstance("EC")
        kf.generatePublic(new X509EncodedKeySpec(der))
      }.toEither.left.map(e => AcmeError.Crypto(s"cannot decode public key: ${e.getMessage}"))
    }

  /** Encodes a certificate chain (leaf first) to a single PEM document. */
  def encodeChain(chain: List[X509Certificate]): Either[AcmeError, String] =
    scala.util.Try {
      chain.map(cert => encode("CERTIFICATE", cert.getEncoded)).mkString
    }.toEither.left.map(e => AcmeError.Crypto(s"cannot encode certificate: ${e.getMessage}"))

  /** Decodes a PEM certificate chain (leaf first). */
  def decodeChain(pemChain: String): Either[AcmeError, List[X509Certificate]] =
    scala.util.Try {
      val cf = CertificateFactory.getInstance("X.509")
      val stream = new ByteArrayInputStream(pemChain.getBytes("US-ASCII"))
      val result = scala.collection.mutable.ListBuffer.empty[X509Certificate]
      cf.generateCertificates(stream).forEach { certificate =>
        certificate match {
          case x509: X509Certificate => result += x509
          case _ => ()
        }
      }
      result.toList
    }.toEither.left
      .map(e => AcmeError.Download(s"cannot parse certificate chain: ${e.getMessage}"))
}
