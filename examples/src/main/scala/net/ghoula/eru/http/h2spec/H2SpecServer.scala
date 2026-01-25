package net.ghoula.eru.http.h2spec

import java.nio.file.Files
import scala.sys.process.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** HTTP/2 server for h2spec conformance testing.
  *
  * This server provides a minimal HTTP/2 endpoint for testing with h2spec. It:
  *   - Generates a self-signed certificate on startup
  *   - Responds to all requests with a simple 200 OK
  *   - Supports HTTP/2 via ALPN (TLS required)
  *
  * Run with: sbt "examples/runMain net.ghoula.eru.http.h2spec.H2SpecServer"
  *
  * Then run h2spec against it: /tmp/h2spec -h localhost -p 8443 -t -k
  *
  * h2spec flags: -t: Use TLS (required for h2) -k: Skip certificate verification (for self-signed
  * certs) -v: Verbose output --strict: Show all tests including passed
  */
object H2SpecServer {

  def main(args: Array[String]): Unit = {
    given runtime: EruRuntime = EruRuntime.shared

    val port = args.headOption.flatMap(_.toIntOption).getOrElse(8443)

    println(s"Starting HTTP/2 h2spec test server on port $port...")
    println()

    // Generate self-signed certificate
    val (keystorePath, password) = generateSelfSignedKeystore()
    println(s"Generated self-signed certificate: $keystorePath")
    println()
    println("Run h2spec with:")
    println(s"  /tmp/h2spec -h localhost -p $port -t -k")
    println()
    println("Or for verbose output:")
    println(s"  /tmp/h2spec -h localhost -p $port -t -k -v")
    println()
    println("Press Ctrl+C to stop")
    println()

    // Simple handler that responds to all requests
    val httpHandler: RequestHandler = request => {
      val path = if request.uri.path.isEmpty then "/" else request.uri.path
      val body = s"HTTP/2 Test Server\nMethod: ${request.method}\nPath: $path"
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.Text(body, None, Charset.UTF8)
        )
      )
    }

    // Configure server with TLS
    val tlsConfig = TlsConfig(
      enabled = true,
      keyStorePath = Some(keystorePath.toString),
      keyStorePassword = Some(password)
    )

    val serverConfig = HttpServerConfig.default.copy(
      port = port,
      tlsConfig = Some(tlsConfig)
    )

    val server = HttpServer
      .create(serverConfig, httpHandler)
      .flatMap { server =>
        server.start.flatMap { addr =>
          println(s"Server started on https://${addr.host}:${addr.port}")
          // Block forever until interrupted
          Eru.effect {
            Runtime.getRuntime.addShutdownHook(new Thread(() => {
              println("\nShutting down...")
              Files.deleteIfExists(keystorePath): Unit
              println("Cleaned up temporary keystore")
            }))
            Thread.currentThread().join()
          }
        }
      }

    server.attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(err) =>
        System.err.println(s"Server failed: $err")
        System.exit(1)
    }
  }

  /** Generate a self-signed certificate and keystore for testing using keytool. */
  private def generateSelfSignedKeystore(): (java.nio.file.Path, String) = {
    val password = "h2spec-test"
    val tempDir = Files.createTempDirectory("h2spec-server-")
    val tempFile = tempDir.resolve("keystore.p12")

    val cmd = Seq(
      "keytool",
      "-genkeypair",
      "-alias",
      "server",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "1",
      "-keystore",
      tempFile.toString,
      "-storepass",
      password,
      "-keypass",
      password,
      "-dname",
      "CN=localhost,O=h2spec-test,L=Test,C=US",
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
