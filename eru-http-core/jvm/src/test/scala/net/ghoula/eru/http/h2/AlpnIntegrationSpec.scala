package net.ghoula.eru.http.h2

import munit.FunSuite

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.file.Files
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import net.ghoula.eru.http.{SSLSocketChannel, TestKeystores}

/** End-to-end ALPN integration tests.
  *
  * These tests verify that ALPN negotiation works correctly with real TLS handshakes, not mocks.
  *
  * ==Why Raw Threads Instead of Eru==
  *
  * Unlike TlsIntegrationSpec which tests HTTP-over-TLS via HttpServer/HttpClient, these tests need
  * direct access to SSLSocketChannel's `getApplicationProtocol()` method which isn't exposed
  * through the higher-level APIs. The HttpServer abstracts away the TLS layer, making it impossible
  * to verify ALPN negotiation at the protocol level.
  *
  * ==Synchronization Pattern==
  *
  * Uses simple volatile vars for error capture with thread.join() for synchronization. This is the
  * minimal pattern needed for server-client socket tests. The data exchange test additionally uses
  * CountDownLatch to prevent premature connection close.
  */
class AlpnIntegrationSpec extends FunSuite {

  /** Create an SSLContext from a keystore file.
    *
    * Uses SecureRandom.getInstanceStrong per Java security best practices.
    */
  private def createSSLContext(keystorePath: java.nio.file.Path, password: String): SSLContext = {
    val keyStore = KeyStore.getInstance("PKCS12")
    val fis = Files.newInputStream(keystorePath)
    try {
      keyStore.load(fis, password.toCharArray)
    } finally {
      fis.close()
    }

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, password.toCharArray)

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(keyStore)

    val ctx = SSLContext.getInstance("TLSv1.3")
    ctx.init(kmf.getKeyManagers, tmf.getTrustManagers, SecureRandom.getInstanceStrong)
    ctx
  }

  test("ALPN - client and server both support h2, negotiates h2") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.bind(new InetSocketAddress("localhost", 0)): Unit
      val port = serverSocket.socket().getLocalPort
      val sslContext = createSSLContext(keystorePath, password)

      @volatile var serverProtocol: String = ""
      @volatile var serverError: Option[Throwable] = None

      val serverThread = new Thread(() => {
        try {
          val clientSocket = serverSocket.accept()
          val serverSsl = SSLSocketChannel.server(clientSocket, sslContext, SSLSocketChannel.Http2Protocols)
          serverSsl.doHandshake()
          serverProtocol = serverSsl.getApplicationProtocol
          serverSsl.close()
        } catch {
          case e: Throwable => serverError = Some(e)
        }
      })
      serverThread.start()

      val clientSocket = SocketChannel.open(new InetSocketAddress("localhost", port))
      val clientSsl = SSLSocketChannel.client(
        clientSocket,
        sslContext,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = SSLSocketChannel.Http2Protocols
      )
      clientSsl.doHandshake()
      val clientProtocol = clientSsl.getApplicationProtocol

      serverThread.join(5000)
      clientSsl.close()
      serverSocket.close()

      serverError.foreach(e => throw e)
      assertEquals(clientProtocol, "h2", "Client should negotiate h2")
      assertEquals(serverProtocol, "h2", "Server should negotiate h2")
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("ALPN - client only supports http/1.1, negotiates http/1.1") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.bind(new InetSocketAddress("localhost", 0)): Unit
      val port = serverSocket.socket().getLocalPort
      val sslContext = createSSLContext(keystorePath, password)

      @volatile var serverProtocol: String = ""
      @volatile var serverError: Option[Throwable] = None

      val serverThread = new Thread(() => {
        try {
          val clientSocket = serverSocket.accept()
          val serverSsl = SSLSocketChannel.server(clientSocket, sslContext, SSLSocketChannel.Http2Protocols)
          serverSsl.doHandshake()
          serverProtocol = serverSsl.getApplicationProtocol
          serverSsl.close()
        } catch {
          case e: Throwable => serverError = Some(e)
        }
      })
      serverThread.start()

      val clientSocket = SocketChannel.open(new InetSocketAddress("localhost", port))
      val clientSsl = SSLSocketChannel.client(
        clientSocket,
        sslContext,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = SSLSocketChannel.Http1Protocols
      )
      clientSsl.doHandshake()
      val clientProtocol = clientSsl.getApplicationProtocol

      serverThread.join(5000)
      clientSsl.close()
      serverSocket.close()

      serverError.foreach(e => throw e)
      assertEquals(clientProtocol, "http/1.1", "Client should negotiate http/1.1")
      assertEquals(serverProtocol, "http/1.1", "Server should negotiate http/1.1")
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("ALPN - server only supports http/1.1, negotiates http/1.1") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.bind(new InetSocketAddress("localhost", 0)): Unit
      val port = serverSocket.socket().getLocalPort
      val sslContext = createSSLContext(keystorePath, password)

      @volatile var serverProtocol: String = ""
      @volatile var serverError: Option[Throwable] = None

      val serverThread = new Thread(() => {
        try {
          val clientSocket = serverSocket.accept()
          val serverSsl = SSLSocketChannel.server(clientSocket, sslContext, SSLSocketChannel.Http1Protocols)
          serverSsl.doHandshake()
          serverProtocol = serverSsl.getApplicationProtocol
          serverSsl.close()
        } catch {
          case e: Throwable => serverError = Some(e)
        }
      })
      serverThread.start()

      val clientSocket = SocketChannel.open(new InetSocketAddress("localhost", port))
      val clientSsl = SSLSocketChannel.client(
        clientSocket,
        sslContext,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = SSLSocketChannel.Http2Protocols
      )
      clientSsl.doHandshake()
      val clientProtocol = clientSsl.getApplicationProtocol

      serverThread.join(5000)
      clientSsl.close()
      serverSocket.close()

      serverError.foreach(e => throw e)
      assertEquals(clientProtocol, "http/1.1", "Client should negotiate http/1.1")
      assertEquals(serverProtocol, "http/1.1", "Server should negotiate http/1.1")
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("ALPN - no ALPN configured, returns empty string") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.bind(new InetSocketAddress("localhost", 0)): Unit
      val port = serverSocket.socket().getLocalPort
      val sslContext = createSSLContext(keystorePath, password)

      @volatile var serverProtocol: String = ""
      @volatile var serverError: Option[Throwable] = None

      val serverThread = new Thread(() => {
        try {
          val clientSocket = serverSocket.accept()
          val serverSsl = SSLSocketChannel.server(clientSocket, sslContext, Array.empty[String])
          serverSsl.doHandshake()
          serverProtocol = serverSsl.getApplicationProtocol
          serverSsl.close()
        } catch {
          case e: Throwable => serverError = Some(e)
        }
      })
      serverThread.start()

      val clientSocket = SocketChannel.open(new InetSocketAddress("localhost", port))
      val clientSsl = SSLSocketChannel.client(
        clientSocket,
        sslContext,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = Array.empty
      )
      clientSsl.doHandshake()
      val clientProtocol = clientSsl.getApplicationProtocol

      serverThread.join(5000)
      clientSsl.close()
      serverSocket.close()

      serverError.foreach(e => throw e)
      assertEquals(clientProtocol, "", "Client should have empty protocol (no ALPN)")
      assertEquals(serverProtocol, "", "Server should have empty protocol (no ALPN)")
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("ALPN - isHttp2 helper method works correctly") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.bind(new InetSocketAddress("localhost", 0)): Unit
      val port = serverSocket.socket().getLocalPort
      val sslContext = createSSLContext(keystorePath, password)

      @volatile var serverIsHttp2: Boolean = false
      @volatile var serverError: Option[Throwable] = None

      val serverThread = new Thread(() => {
        try {
          val clientSocket = serverSocket.accept()
          val serverSsl = SSLSocketChannel.server(clientSocket, sslContext, SSLSocketChannel.Http2Protocols)
          serverSsl.doHandshake()
          serverIsHttp2 = serverSsl.isHttp2
          serverSsl.close()
        } catch {
          case e: Throwable => serverError = Some(e)
        }
      })
      serverThread.start()

      val clientSocket = SocketChannel.open(new InetSocketAddress("localhost", port))
      val clientSsl = SSLSocketChannel.client(
        clientSocket,
        sslContext,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = SSLSocketChannel.Http2Protocols
      )
      clientSsl.doHandshake()
      val clientIsHttp2 = clientSsl.isHttp2

      serverThread.join(5000)
      clientSsl.close()
      serverSocket.close()

      serverError.foreach(e => throw e)
      assert(clientIsHttp2, "Client isHttp2 should be true")
      assert(serverIsHttp2, "Server isHttp2 should be true")
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }

  test("ALPN - can exchange data after h2 negotiation") {
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    try {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.bind(new InetSocketAddress("localhost", 0)): Unit
      val port = serverSocket.socket().getLocalPort
      val sslContext = createSSLContext(keystorePath, password)

      @volatile var receivedData: Array[Byte] = Array.empty
      @volatile var serverError: Option[Throwable] = None

      val responseSentLatch = new java.util.concurrent.CountDownLatch(1)
      val clientDoneLatch = new java.util.concurrent.CountDownLatch(1)

      val serverThread = new Thread(() => {
        try {
          val clientSocket = serverSocket.accept()
          val serverSsl = SSLSocketChannel.server(clientSocket, sslContext, SSLSocketChannel.Http2Protocols)
          serverSsl.doHandshake()

          val buffer = ByteBuffer.allocate(1024)
          val bytesRead = serverSsl.read(buffer)
          if bytesRead > 0 then {
            buffer.flip(): Unit
            receivedData = new Array[Byte](buffer.remaining)
            buffer.get(receivedData): Unit
          }

          val response = "Server received your message".getBytes("UTF-8")
          serverSsl.write(ByteBuffer.wrap(response)): Unit
          responseSentLatch.countDown()

          clientDoneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS): Unit
          serverSsl.close()
        } catch {
          case e: Throwable => serverError = Some(e)
        }
      })
      serverThread.start()

      val clientSocket = SocketChannel.open(new InetSocketAddress("localhost", port))
      val clientSsl = SSLSocketChannel.client(
        clientSocket,
        sslContext,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = SSLSocketChannel.Http2Protocols
      )
      clientSsl.doHandshake()

      assertEquals(clientSsl.getApplicationProtocol, "h2")

      val message = "Hello from HTTP/2 client".getBytes("UTF-8")
      clientSsl.write(ByteBuffer.wrap(message)): Unit

      responseSentLatch.await(5, java.util.concurrent.TimeUnit.SECONDS): Unit

      val responseBuffer = ByteBuffer.allocate(1024)
      clientSsl.read(responseBuffer): Unit
      responseBuffer.flip(): Unit
      val responseData = new Array[Byte](responseBuffer.remaining)
      responseBuffer.get(responseData): Unit
      val responseText = new String(responseData, "UTF-8")

      clientDoneLatch.countDown()

      serverThread.join(5000)
      clientSsl.close()
      serverSocket.close()

      serverError.foreach(e => throw e)
      assertEquals(new String(receivedData, "UTF-8"), "Hello from HTTP/2 client")
      assertEquals(responseText, "Server received your message")
    } finally {
      Files.deleteIfExists(keystorePath): Unit
    }
  }
}
