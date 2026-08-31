package net.ghoula.eru.http

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*

/** Test helper that generates self-signed PKCS12 keystores for TLS test suites.
  *
  * `keytool` is resolved from `java.home` — the JDK running the test JVM — rather than from `PATH`,
  * because IDEs launch test JVMs with a minimal environment where the JDK `bin` directory is not on
  * `PATH`. Resolving from `java.home` works everywhere a JDK is present (compilation requires one).
  */
object TestKeystores {

  private def keytoolExecutable: String = {
    val jdkBin: Path = Paths.get(Option(System.getProperty("java.home")).getOrElse(""), "bin")
    val candidates: Seq[Path] = Seq(jdkBin.resolve("keytool"), jdkBin.resolve("keytool.exe"))
    candidates.find(Files.isExecutable).map(_.toString).getOrElse("keytool")
  }

  /** Generate a self-signed certificate and keystore for testing.
    *
    * Returns the path to a temporary PKCS12 keystore file and its password. The caller owns cleanup
    * of the returned file.
    */
  def generateSelfSignedKeystore(): (Path, String) = {
    val password = "testpassword"
    val tempDir = Files.createTempDirectory("tls-test-")
    val tempFile = tempDir.resolve("keystore.p12")

    val cmd = Seq(
      keytoolExecutable,
      "-genkeypair",
      "-alias",
      "server",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "365",
      "-keystore",
      tempFile.toString,
      "-storepass",
      password,
      "-keypass",
      password,
      "-dname",
      "CN=localhost,O=Test,L=Test,C=US",
      "-storetype",
      "PKCS12"
    )

    val exitCode = cmd.!
    if exitCode != 0 then {
      throw new RuntimeException(s"keytool failed with exit code $exitCode")
    }

    (tempFile, password)
  }
}
