package net.ghoula.eru.http.h2spec

import java.nio.file.Files
import scala.annotation.unused

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.h2.{H2ServerConnection, H2Settings}
import net.ghoula.eru.prelude.*

/** Test server that intentionally sends duplicate :status via H2ServerConnection.sendResponse. This
  * reproduces the bug where passing :status in headers causes duplicate pseudo-headers.
  */
object DuplicateStatusTest {
  def main(args: Array[String]): Unit = {
    @unused given runtime: EruRuntime = EruRuntime.shared
    val port = 8444

    println(s"BUGGY SERVER: Starting on port $port with duplicate :status...")

    val (keystorePath, password) = generateKeystore()
    val sslContext = createSslContext(keystorePath, password)
    val h2Settings = H2Settings.create().attempt.unsafeRunSync() match {
      case Result.Success(s) => s
      case Result.Failure(_) => throw new RuntimeException("Failed to create settings")
    }

    val serverSocket = java.nio.channels.ServerSocketChannel.open()
    serverSocket.bind(new java.net.InetSocketAddress(port))
    println(s"Listening on port $port")

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      serverSocket.close()
      Files.deleteIfExists(keystorePath): Unit
    }))

    while true do {
      try {
        val clientSocket = serverSocket.accept()
        Thread.startVirtualThread { () =>
          try handleClient(clientSocket, sslContext, h2Settings)
          catch { case _: Exception => }
          finally {
            try clientSocket.close()
            catch { case _: Exception => }
          }
        }: Unit
      } catch { case _: java.nio.channels.ClosedChannelException => }
    }
  }

  private def handleClient(
    socket: java.nio.channels.SocketChannel,
    sslContext: javax.net.ssl.SSLContext,
    h2Settings: H2Settings
  ): Unit = {
    given runtime: EruRuntime = EruRuntime.shared
    val sslChannel = SSLSocketChannel.server(socket, sslContext, Array("h2"))

    if scala.util.Try(sslChannel.doHandshake()).isSuccess then {
      H2ServerConnection.accept(sslChannel, h2Settings).attempt.unsafeRunSync() match {
        case Result.Failure(_) => ()
        case Result.Success(h2conn) =>
          h2conn.receiveRequest().attempt.unsafeRunSync() match {
            case Result.Failure(_) => ()
            case Result.Success((streamId, _, _)) =>
              // BUG: Include :status in headers - sendResponse also adds it!
              val buggyHeaders = List(
                (":status", "200"), // DUPLICATE - sendResponse adds this too!
                ("content-type", "text/plain")
              )
              println(s"Sending with BUGGY headers: $buggyHeaders")
              h2conn.sendResponse(streamId, 200, buggyHeaders, Some("OK".getBytes)).attempt.unsafeRunSync(): Unit
          }
      }
    }
  }

  private def generateKeystore(): (java.nio.file.Path, String) = {
    import scala.sys.process.*
    val password = "testpassword"
    val tempDir = Files.createTempDirectory("duptest-")
    val path = tempDir.resolve("keystore.p12")
    Seq(
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
      path.toString,
      "-storepass",
      password,
      "-keypass",
      password,
      "-dname",
      "CN=localhost,O=test,L=Test,C=US",
      "-storetype",
      "PKCS12"
    ).!
    (path, password)
  }

  private def createSslContext(path: java.nio.file.Path, password: String): javax.net.ssl.SSLContext = {
    val ks = java.security.KeyStore.getInstance("PKCS12")
    val fis = new java.io.FileInputStream(path.toFile)
    try ks.load(fis, password.toCharArray)
    finally fis.close()
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, password.toCharArray)
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    // scalafix:off DisableSyntax.null
    ctx.init(kmf.getKeyManagers, null, null)
    // scalafix:on DisableSyntax.null
    ctx
  }
}
