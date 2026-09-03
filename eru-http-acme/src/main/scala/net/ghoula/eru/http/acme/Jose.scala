package net.ghoula.eru.http.acme

import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.util.Base64

/** JOSE primitives for ACME (RFC 7515 / RFC 7517 / RFC 7638) restricted to what RFC 8555 requires:
  * ES256-signed flattened JWS with an embedded JWK (account creation) or a `kid` (everything else).
  */
object Jose {

  private val b64uEncoder = Base64.getUrlEncoder.withoutPadding
  private val b64uDecoder = Base64.getUrlDecoder

  /** Base64url without padding (RFC 7515 Section 2). */
  def b64u(bytes: Array[Byte]): String = b64uEncoder.encodeToString(bytes)

  /** Unsigned big-endian integer, base64url — the JOSE encoding for EC coordinates. */
  def b64uUint(value: BigInteger, length: Int): String =
    b64u(toFixedLength(value, length))

  def b64uDecode(value: String): Array[Byte] = b64uDecoder.decode(value)

  /** Unsigned big-endian, left-padded to `length` bytes (32 for P-256 coordinates). */
  private def toFixedLength(value: BigInteger, length: Int): Array[Byte] = {
    val raw = value.toByteArray // may carry a sign byte / leading zero
    val stripped =
      if raw.length > 1 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length) else raw
    if stripped.length == length then stripped
    else if stripped.length > length then throw AcmeError.Crypto(s"integer too large for $length-byte encoding")
    else {
      val out = new Array[Byte](length)
      System.arraycopy(stripped, 0, out, length - stripped.length, stripped.length)
      out
    }
  }

  /** The RFC 7638 JWK for an ES256 public key: exactly `{"crv","kty","x","y"}` in lexicographic
    * order — thumbprints hash this canonical form.
    */
  def ecJwk(publicKey: ECPublicKey): Json = {
    val point: ECPoint = publicKey.getW
    Json.obj(
      "crv" -> Json.str("P-256"),
      "kty" -> Json.str("EC"),
      "x" -> Json.str(b64uUint(point.getAffineX, 32)),
      "y" -> Json.str(b64uUint(point.getAffineY, 32))
    )
  }

  /** RFC 7638 thumbprint: SHA-256 over the canonical JWK member form, base64url. */
  def thumbprint(publicKey: ECPublicKey): String = {
    val canonical = ecJwk(publicKey).encode
    b64u(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes("US-ASCII")))
  }

  /** The RFC 8555 Section 8.1 key authorization: `<token>.<jwk-thumbprint>`. */
  def keyAuthorization(token: String, publicKey: ECPublicKey): String =
    s"$token.${thumbprint(publicKey)}"

  /** Converts a JDK `SHA256withECDSA` DER signature to the raw `r||s` (64-byte) form JOSE requires.
    * DER: SEQUENCE { INTEGER r, INTEGER s }.
    */
  def derSignatureToRaw(der: Array[Byte]): Array[Byte] = {
    // SEQUENCE tag
    requireByte(der, 0, 0x30)
    var pos = 1
    val (seqLen, pos1) = readLength(der, pos)
    pos = pos1
    val end = pos + seqLen
    requireByte(der, pos, 0x02)
    pos += 1
    val (rLen, pos2) = readLength(der, pos)
    pos = pos2
    val r = java.util.Arrays.copyOfRange(der, pos, pos + rLen)
    pos += rLen
    requireByte(der, pos, 0x02)
    pos += 1
    val (sLen, pos4) = readLength(der, pos)
    pos = pos4
    val s = java.util.Arrays.copyOfRange(der, pos, pos + sLen)
    pos += sLen
    if pos != end then throw AcmeError.Crypto("trailing bytes in DER signature")

    val out = new Array[Byte](64)
    copyLeftPadded(r, out, 0)
    copyLeftPadded(s, out, 32)
    out
  }

  private def copyLeftPadded(src: Array[Byte], dst: Array[Byte], dstOffset: Int): Unit = {
    var start = 0
    while start < src.length - 1 && src(start) == 0 do start += 1
    val len = src.length - start
    if len > 32 then throw AcmeError.Crypto("signature component exceeds 32 bytes")
    System.arraycopy(src, start, dst, dstOffset + 32 - len, len)
  }

  private def readLength(data: Array[Byte], pos: Int): (Int, Int) = {
    val first = data(pos) & 0xff
    if first < 0x80 then (first, pos + 1)
    else {
      val lenBytes = first & 0x7f
      var value = 0
      var i = 0
      while i < lenBytes do {
        value = (value << 8) | (data(pos + 1 + i) & 0xff)
        i += 1
      }
      (value, pos + 1 + lenBytes)
    }
  }

  private def requireByte(data: Array[Byte], pos: Int, expected: Int): Unit =
    if pos >= data.length || (data(pos) & 0xff) != expected then
      throw AcmeError.Crypto(s"malformed DER: expected 0x${expected.toHexString} at byte $pos")

  /** Signs `signingInput` with ES256 and returns the raw `r||s` signature. */
  def signEs256(keyPair: KeyPair, signingInput: String): Array[Byte] = {
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(keyPair.getPrivate)
    signer.update(signingInput.getBytes("US-ASCII"))
    derSignatureToRaw(signer.sign())
  }

  /** Builds a flattened JWS JSON (RFC 7515 Section 7.2.2) over `payload`.
    *
    * @param keyPair
    *   the ES256 account key
    * @param embeddedJwk
    *   when true the JWK is embedded in the protected header (newAccount); otherwise the account
    *   URL is referenced via `kid`
    * @param kid
    *   the account URL (required when `embeddedJwk` is false)
    * @param nonce
    *   the fresh Replay-Nonce
    * @param url
    *   the request URL
    * @param payload
    *   the payload JSON; [[Json.Null]] encodes an empty payload (POST-as-GET)
    */
  def flattenedJws(
    keyPair: KeyPair,
    embeddedJwk: Boolean,
    kid: Option[String],
    nonce: String,
    url: String,
    payload: Json
  ): Json = {
    val publicKey = keyPair.getPublic match {
      case ec: ECPublicKey => ec
      case other => throw AcmeError.Crypto(s"expected EC public key, got ${other.getClass.getSimpleName}")
    }
    val protectedHeader =
      if embeddedJwk then
        Json.obj(
          "alg" -> Json.str("ES256"),
          "jwk" -> ecJwk(publicKey),
          "nonce" -> Json.str(nonce),
          "url" -> Json.str(url)
        )
      else
        Json.obj(
          "alg" -> Json.str("ES256"),
          "kid" -> Json.str(kid.getOrElse(throw AcmeError.Crypto("kid required for non-embedded JWS"))),
          "nonce" -> Json.str(nonce),
          "url" -> Json.str(url)
        )

    val headerB64 = b64u(protectedHeader.encode.getBytes("US-ASCII"))
    val payloadB64 = payload match {
      case Json.Null => ""
      case p => b64u(p.encode.getBytes("US-ASCII"))
    }
    val signature = signEs256(keyPair, s"$headerB64.$payloadB64")

    Json.obj(
      "protected" -> Json.str(headerB64),
      "payload" -> Json.str(payloadB64),
      "signature" -> Json.str(b64u(signature))
    )
  }
}
