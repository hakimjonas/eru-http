package net.ghoula.eru.http

import munit.FunSuite

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*
import net.ghoula.eru.http.h2.{H2ClientConnection, H2Error}
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Request-level validation on the HTTP/2 path.
  *
  * The HTTP/1.1 path validates every request (Host, bodyless-method rules, forbidden header
  * combinations, QUERY's Content-Type requirement). These tests pin the same validation on the
  * HTTP/2 path using raw H2 frames, which the client API cannot produce because it validates before
  * sending.
  */
class H2ValidationSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def munitTimeout: Duration = 30.seconds

  private def sendRawH2(port: Int)(
    send: H2ClientConnection => Eru[Throwable | H2Error, (Int, (List[(String, String)], Option[Array[Byte]]))]
  ): (Int, List[(String, String)], Option[Array[Byte]]) = {
    val socket = SocketChannel.open()
    socket.configureBlocking(true)
    socket.connect(new InetSocketAddress("localhost", port))

    try {
      val ctx = SSLContextFactory.createClientContext(TlsConfig.insecure)
      val ssl = SSLSocketChannel.client(
        socket,
        ctx,
        "localhost",
        port,
        verifyHostname = false,
        alpnProtocols = SSLSocketChannel.Http2Protocols,
        protocols = TlsConfig.insecure.protocols,
        cipherSuites = TlsConfig.insecure.cipherSuites
      )
      ssl.doHandshake()

      val conn = H2ClientConnection.connect(ssl).assertSuccess
      try {
        val (_, response) = send(conn).attempt.unsafeRunSync() match {
          case Result.Success(v) => v
          case Result.Failure(e) => fail(s"h2 exchange failed: $e")
        }
        val (headers, body) = response
        val status = headers.find(_._1 == ":status").flatMap(_._2.toIntOption).getOrElse(0)
        (status, headers, body)
      } finally {
        conn.shutdown().attempt.unsafeRunSync(): Unit
      }
    } finally {
      try socket.close()
      catch { case _: Exception => () }
    }
  }

  test("HTTP/2 - QUERY without Content-Type returns 400 (RFC 10008 Section 2)") {
    val testPort = 18770
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    val handler: RequestHandler =
      _ => Eru.succeed(Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text("ok")))

    val serverTls = TlsConfig.default
      .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
    val serverConfig = HttpServerConfig.default.copy(port = testPort).withTls(serverTls)

    try {
      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.map { _ =>
            val authority = s"localhost:$testPort"
            val (status, _, _) = sendRawH2(testPort) { conn =>
              conn
                .sendRequest("QUERY", "/search", authority, headers = Nil, body = Some("hello".getBytes("US-ASCII")))
                .flatMap(id => conn.receiveResponse(id).map((id, _)))
            }
            assertEquals(status, 400, s"QUERY without Content-Type must be rejected with 400, got $status")
          }
        }
        .assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - GET with a request body returns 400 (RFC 9110 Section 9.3.1)") {
    val testPort = 18771
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    val handler: RequestHandler =
      _ => Eru.succeed(Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text("ok")))

    val serverTls = TlsConfig.default
      .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
    val serverConfig = HttpServerConfig.default.copy(port = testPort).withTls(serverTls)

    try {
      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.map { _ =>
            val authority = s"localhost:$testPort"
            val (status, _, _) = sendRawH2(testPort) { conn =>
              conn
                .sendRequest("GET", "/x", authority, headers = Nil, body = Some("boom".getBytes("US-ASCII")))
                .flatMap(id => conn.receiveResponse(id).map((id, _)))
            }
            assertEquals(status, 400, s"GET with a body must be rejected with 400, got $status")
          }
        }
        .assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - valid QUERY with Content-Type still returns 200") {
    val testPort = 18772
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    val handler: RequestHandler =
      _ => Eru.succeed(Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text("ok")))

    val serverTls = TlsConfig.default
      .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
    val serverConfig = HttpServerConfig.default.copy(port = testPort).withTls(serverTls)

    try {
      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.map { _ =>
            val authority = s"localhost:$testPort"
            val (status, _, _) = sendRawH2(testPort) { conn =>
              conn
                .sendRequest(
                  "QUERY",
                  "/search",
                  authority,
                  headers = List(("content-type", "text/plain")),
                  body = Some("hello".getBytes("US-ASCII"))
                )
                .flatMap(id => conn.receiveResponse(id).map((id, _)))
            }
            assertEquals(status, 200, s"valid QUERY must still work, got $status")
          }
        }
        .assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - control characters in :path are rejected with 400") {
    val testPort = 18773
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    val handler: RequestHandler =
      _ => Eru.succeed(Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text("ok")))

    val serverTls = TlsConfig.default
      .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
    val serverConfig = HttpServerConfig.default.copy(port = testPort).withTls(serverTls)

    try {
      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.map { _ =>
            val authority = s"localhost:$testPort"
            val maliciousPath = "/x\r\nInjected: y"
            val (status, _, _) = sendRawH2(testPort) { conn =>
              conn
                .sendRequest("GET", maliciousPath, authority)
                .flatMap(id => conn.receiveResponse(id).map((id, _)))
            }
            assertEquals(status, 400, s"control characters in :path must be rejected, got $status")
          }
        }
        .assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }

  test("HTTP/2 - invalid :scheme is rejected with 400") {
    val testPort = 18774
    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()

    val handler: RequestHandler =
      _ => Eru.succeed(Response(status = StatusCode.Ok, headers = Headers.empty, body = Body.text("ok")))

    val serverTls = TlsConfig.default
      .copy(keyStorePath = Some(keystorePath.toString), keyStorePassword = Some(password))
    val serverConfig = HttpServerConfig.default.copy(port = testPort).withTls(serverTls)

    try {
      HttpServer
        .scoped(serverConfig)(handler) { server =>
          server.start.map { _ =>
            val authority = s"localhost:$testPort"
            val (status, _, _) = sendRawH2(testPort) { conn =>
              conn
                .sendRequest("GET", "/x", authority, scheme = "ftp")
                .flatMap(id => conn.receiveResponse(id).map((id, _)))
            }
            assertEquals(status, 400, s"invalid :scheme must be rejected, got $status")
          }
        }
        .assertSuccess
    } finally {
      Files.deleteIfExists(keystorePath): Unit
      Files.deleteIfExists(keystorePath.getParent): Unit
    }
  }
}
