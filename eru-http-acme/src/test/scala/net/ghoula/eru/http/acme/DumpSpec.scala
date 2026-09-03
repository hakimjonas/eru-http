package net.ghoula.eru.http.acme

import munit.FunSuite

class DumpSpec extends FunSuite {
  test("dump") {
    val pair = AcmeClient.generateKeyPair()
    val csr = Csr.build(pair, List("api.example.com", "www.example.com")).toOption.get
    println("MINE: " + csr.map(b => f"$b%02x").mkString(" ").take(480))
    println("LEN: " + csr.length)
    import java.nio.file.{Files, Paths}
    Files.write(Paths.get("/tmp/opencode/csrdbg/mine.csr"), Pem.encode("CERTIFICATE REQUEST", csr).getBytes)
    println("WROTE /tmp/opencode/csrdbg/mine.csr")
  }
}
