package net.ghoula.eru.http.acme

import munit.FunSuite

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Unit tests for the hand-rolled PKCS#10 builder. The strongest available check: `keytool
  * -printcertreq` parses the request and reports the SAN entries we encoded.
  */
class CsrSpec extends FunSuite {

  test("CSR builds, is valid DER from the JDK's perspective, and keytool parses it") {
    val pair = AcmeClient.generateKeyPair()
    val csr = Csr.build(pair, List("api.example.com", "www.example.com"))
    assert(csr.isRight)
    val der = csr.toOption.get
    // SEQUENCE tag + definite length
    assertEquals(der(0), 0x30.toByte)

    val pem = Pem.encode("CERTIFICATE REQUEST", der)
    val dir = Files.createTempDirectory("csr-spec")
    val reqPath = dir.resolve("req.pem")
    Files.writeString(reqPath, pem)

    val process = ProcessBuilder("keytool", "-printcertreq", "-file", reqPath.toString)
      .redirectErrorStream(true)
      .start()
    val output = process.getInputStream.readAllBytes()
    val finished = process.waitFor(30, TimeUnit.SECONDS)
    assert(finished, "keytool did not finish in time")
    assertEquals(process.exitValue(), 0, s"keytool rejected the CSR:\n${new String(output)}")

    val text = new String(output)
    assert(text.contains("api.example.com"), s"SAN missing from keytool output:\n$text")
    assert(text.contains("www.example.com"), s"second SAN missing from keytool output:\n$text")
    assert(text.contains("CN=api.example.com"), s"CN missing from keytool output:\n$text")
  }

  test("CSR requires at least one domain") {
    val pair = AcmeClient.generateKeyPair()
    assert(Csr.build(pair, List.empty).isLeft)
  }

  test("PEM encode/decode round-trips DER") {
    val der = Csr.build(AcmeClient.generateKeyPair(), List("roundtrip.example.com")).toOption.get
    val pem = Pem.encode("CERTIFICATE REQUEST", der)
    val decoded = Pem.decode(pem, "CERTIFICATE REQUEST")
    assert(decoded.isRight)
    assert(java.util.Arrays.equals(decoded.toOption.get, der), "PEM round-trip must preserve the DER bytes")
  }
}
