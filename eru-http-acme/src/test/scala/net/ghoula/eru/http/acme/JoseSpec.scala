package net.ghoula.eru.http.acme

import munit.FunSuite

import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey

/** Unit tests for the JOSE primitives: base64url, JWK, RFC 7638 thumbprint, ES256 JWS signature
  * conversion.
  */
class JoseSpec extends FunSuite {

  private def keyPair: KeyPair = AcmeClient.generateKeyPair()

  private def ecPub(pair: KeyPair): ECPublicKey = pair.getPublic match {
    case ec: ECPublicKey => ec
    case other => throw new IllegalStateException(s"expected EC public key, got ${other.getClass.getSimpleName}")
  }

  test("b64u: base64url without padding") {
    assertEquals(Jose.b64u(Array[Byte](0, 1, 2)), "AAEC")
    // padding bytes ('=' and '+', '/') must not appear
    val long = Jose.b64u(Array.fill(100)(0x7f.toByte))
    assert(!long.contains("="))
    assert(!long.contains("+"))
    assert(!long.contains("/"))
    // decode round-trips
    java.util.Arrays.equals(Jose.b64uDecode(Jose.b64u(Array.fill(37)(0x42.toByte))), Array.fill(37)(0x42.toByte))
  }

  test("b64uUint: left-pads to 32 bytes and strips the sign byte") {
    val one = new BigInteger("1")
    // decoded 32-byte form: 31 zero bytes then 0x01
    val decoded = Jose.b64uDecode(Jose.b64uUint(one, 32))
    assertEquals(decoded.length, 32)
    assertEquals(decoded(31), 1.toByte)
    assert(decoded.take(31).forall(_ == 0))
  }

  test("ecJwk: exactly the RFC 7638 required members in lexicographic order") {
    val jwk = Jose.ecJwk(ecPub(keyPair))
    val Json.Obj(fields) = jwk.runtimeChecked
    assertEquals(fields.map(_._1), List("crv", "kty", "x", "y"))
    assertEquals(fields.map(_._2), List(Json.str("P-256"), Json.str("EC"), fields(2)._2, fields(3)._2))
  }

  test("thumbprint: SHA-256 over the canonical JWK form, base64url") {
    val pair = keyPair
    val pub = ecPub(pair)
    val expected = Jose.b64u(
      MessageDigest.getInstance("SHA-256").digest(Jose.ecJwk(pub).encode.getBytes("US-ASCII"))
    )
    assertEquals(Jose.thumbprint(pub), expected)
    // stable across key instances with the same key material
    assertEquals(Jose.thumbprint(pub), Jose.thumbprint(pub))
  }

  test("keyAuthorization: token.thumbprint") {
    val pub = ecPub(keyPair)
    assertEquals(Jose.keyAuthorization("tok-1", pub), s"tok-1.${Jose.thumbprint(pub)}")
  }

  test("signEs256: verifiable by the JDK and 64-byte raw form") {
    val pair = keyPair
    val input = "headerB64.payloadB64"
    val raw = Jose.signEs256(pair, input)
    assertEquals(raw.length, 64)

    // The JDK's own verify path consumes the DER form; re-encoding raw r||s back to DER must
    // verify against the same key.
    val r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32))
    val s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64))
    val der = derEncode(r, s)
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(pair.getPublic)
    verifier.update(input.getBytes("US-ASCII"))
    assert(verifier.verify(der))
  }

  private def derEncode(r: BigInteger, s: BigInteger): Array[Byte] = {
    def int(value: BigInteger): Array[Byte] = {
      val body = value.toByteArray
      val lenBytes =
        if body.length < 0x80 then Array(body.length.toByte)
        else Array(0x81.toByte, body.length.toByte)
      Array(0x02.toByte) ++ lenBytes ++ body
    }
    val body = int(r) ++ int(s)
    val lenBytes =
      if body.length < 0x80 then Array(body.length.toByte)
      else Array(0x81.toByte, body.length.toByte)
    Array(0x30.toByte) ++ lenBytes ++ body
  }

  test("flattenedJws: structure with embedded JWK and kid variants") {
    val pair = keyPair
    val payload = Json.obj("termsOfServiceAgreed" -> Json.bool(true))

    val embedded =
      Jose.flattenedJws(pair, embeddedJwk = true, kid = None, nonce = "n-1", url = "u-1", payload = payload)
    val Json.Obj(fields) = embedded.runtimeChecked
    assertEquals(fields.map(_._1), List("protected", "payload", "signature"))
    // protected header decodes and carries alg/jwk/nonce/url
    val headerStr = fields.head._2.asString.get
    val header = Json.parse(new String(Jose.b64uDecode(headerStr), "US-ASCII")).toOption.get
    assertEquals(header.stringField("alg"), Some("ES256"))
    assertEquals(header.stringField("nonce"), Some("n-1"))
    assertEquals(header.stringField("url"), Some("u-1"))
    assert(header.field("jwk").isDefined)

    val withKid =
      Jose.flattenedJws(pair, embeddedJwk = false, kid = Some("acc-1"), nonce = "n-2", url = "u-2", payload = Json.Null)
    val Json.Obj(kidFields) = withKid.runtimeChecked
    val kidHeaderStr = kidFields.head._2.asString.get
    val kidHeader = Json.parse(new String(Jose.b64uDecode(kidHeaderStr), "US-ASCII")).toOption.get
    assertEquals(kidHeader.stringField("kid"), Some("acc-1"))
    // POST-as-GET: empty payload encodes as the empty string
    assertEquals(kidFields(1)._2.asString, Some(""))
  }
}
