package net.ghoula.eru.http.h2spec

import java.nio.file.Files
import scala.annotation.unused
import scala.sys.process.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.h2.{H2ServerConnection, H2Settings}
import net.ghoula.eru.prelude.*

/** HTTP/2 server configured for full h2spec compliance testing.
  *
  * This server is specifically configured to enable ALL h2spec tests:
  *   - SETTINGS_MAX_CONCURRENT_STREAMS = 100 (enables test 5.1.2.1)
  *
  * Run with: sbt "examples/runMain net.ghoula.eru.http.h2spec.H2ComplianceServer"
  *
  * Then run h2spec: /tmp/h2spec -h localhost -p 8443 -t -k
  */
object H2ComplianceServer {

  def main(args: Array[String]): Unit = {
    @unused given runtime: EruRuntime = EruRuntime.shared

    val port = args.headOption.flatMap(_.toIntOption).getOrElse(8443)

    println(s"Starting HTTP/2 COMPLIANCE test server on port $port...")
    println("Configured with SETTINGS_MAX_CONCURRENT_STREAMS=100 for test 5.1.2.1")
    println()

    // Generate self-signed certificate
    val (keystorePath, password) = generateSelfSignedKeystore()
    println(s"Generated self-signed certificate: $keystorePath")
    println()
    println("Run h2spec with:")
    println(s"  /tmp/h2spec -h localhost -p $port -t -k")
    println()
    println("Press Ctrl+C to stop")
    println()

    // Create H2Settings with maxConcurrentStreams=100 for test 5.1.2.1
    val h2Settings = H2Settings.create(maxConcurrentStreams = 100).attempt.unsafeRunSync() match {
      case Result.Success(s) => s
      case Result.Failure(err) =>
        System.err.println(s"Failed to create H2Settings: $err")
        System.exit(1)
        throw new RuntimeException("unreachable")
    }

    // Print settings for verification
    val entries = h2Settings.toEntries()
    println(s"H2 Settings entries to send: $entries")
    entries.foreach { e =>
      println(s"  0x${e.id.toHexString} = ${e.value}")
    }
    println()

    // Create SSL context
    val sslContext = {
      val ks = java.security.KeyStore.getInstance("PKCS12")
      val fis = new java.io.FileInputStream(keystorePath.toFile)
      try ks.load(fis, password.toCharArray)
      finally fis.close()

      val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(ks, password.toCharArray)

      val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
      // scalafix:off DisableSyntax.null
      // Java SSLContext.init() API accepts null to use system defaults for trust managers and secure random
      ctx.init(kmf.getKeyManagers, null, null)
      // scalafix:on DisableSyntax.null
      ctx
    }

    // Create server socket
    val serverSocket = java.nio.channels.ServerSocketChannel.open()
    serverSocket.bind(new java.net.InetSocketAddress(port))
    println(s"Server listening on port $port")

    // Accept loop
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("\nShutting down...")
      serverSocket.close()
      Files.deleteIfExists(keystorePath): Unit
      println("Cleaned up")
    }))

    while true do {
      try {
        val clientSocket = serverSocket.accept()
        Thread.startVirtualThread { () =>
          try {
            handleClient(clientSocket, sslContext, h2Settings)
          } catch {
            case _: Exception =>
            // Silently ignore connection errors (normal during h2spec testing)
          } finally {
            try clientSocket.close()
            catch { case _: Exception => }
          }
        }: Unit
      } catch {
        case _: java.nio.channels.ClosedChannelException => // Server shutting down
      }
    }
  }

  private def handleClient(
    socket: java.nio.channels.SocketChannel,
    sslContext: javax.net.ssl.SSLContext,
    h2Settings: H2Settings
  ): Unit = {
    given runtime: EruRuntime = EruRuntime.shared

    // Wrap in SSL using the proper factory method
    val sslChannel = SSLSocketChannel.server(socket, sslContext, Array("h2"))

    // Perform TLS handshake (blocking, throws on failure)
    val handshakeSuccess = scala.util.Try(sslChannel.doHandshake()).isSuccess

    if handshakeSuccess then {
      // Accept HTTP/2 connection with custom settings
      H2ServerConnection.accept(sslChannel, h2Settings).attempt.unsafeRunSync() match {
        case Result.Failure(_) =>
          () // H2 preface failed, nothing more to do
        case Result.Success(h2conn) =>
          // Handle requests using runtime.fork for proper concurrent stream handling.
          // This allows streams to accumulate naturally for limit testing (5.1.2.1).
          //
          // SETTINGS changes are handled correctly because H2ServerConnection.sendResponse
          // calls processPendingControlFrames() before computing window sizes, ensuring
          // RFC 9113 Section 6.9.2 compliance.
          var continue = true
          while continue do {
            h2conn.receiveRequest().attempt.unsafeRunSync() match {
              case Result.Failure(_) =>
                continue = false
              case Result.Success((streamId, _, _)) =>
                // Fork response handler - runs on separate VT, main loop continues
                val responseEffect = {
                  // Note: sendResponse adds :status pseudo-header automatically from status parameter
                  // Don't include :status in headers list to avoid duplicate pseudo-header error
                  val responseHeaders = List(
                    ("content-type", "text/plain")
                  )
                  val responseBody = Some("OK".getBytes("UTF-8"))
                  h2conn.sendResponse(streamId, 200, responseHeaders, responseBody)
                }
                runtime.fork(responseEffect).attempt.unsafeRunSync(): Unit

                // Check if connection is going away
                h2conn.connection.isGoingAway.attempt.unsafeRunSync() match {
                  case Result.Success(true) => continue = false
                  case _ => // Continue
                }
            }
          }
      }
    }
    // else: TLS handshake failed, nothing more to do
  }

  private def generateSelfSignedKeystore(): (java.nio.file.Path, String) = {
    val password = "h2spec-test"
    val tempDir = Files.createTempDirectory("h2compliance-server-")
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
