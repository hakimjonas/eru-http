package net.ghoula.eru.http.client.hostile

import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, SSLHandshakeException, SSLSocket, SSLSocketFactory}
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.TestHelpers.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Hostile test A.6: TLS protocol and cipher downgrade.
  *
  * `TlsHardeningSpec` (core, non-hostile) unit-tests that `TlsConfig.protocols` and `cipherSuites`
  * flow into the `SSLEngine` parameters. That's necessary but not sufficient: it doesn't prove an
  * actual TLS handshake rejects a downgraded client.
  *
  * This spec stands up a real HTTPS server with a restricted config and points a raw
  * `javax.net.ssl.SSLSocket` at it with `setEnabledProtocols(Array("TLSv1.1"))` / weak cipher
  * forced. The handshake MUST fail. If it succeeds, the restriction is cosmetic — which is exactly
  * the class of bug `TlsConfig.protocols`-was-dead-config was.
  *
  * The weak-cipher scenario probes the JDK for CBC-based RSA suites the hardened allowlist excludes
  * and forces one from the client; DH_anon suites are ignored because they fail for unrelated
  * reasons. If the JDK exposes no such weak cipher the scenario is skipped, since the JVM's own
  * defaults already rejected them. The control handshake uses a TLS_ECDHE_RSA_ suite because the
  * test keystore holds an RSA key (keytool `-keyalg RSA`); ECDHE_ECDSA would require an ECDSA key.
  * Weak ciphers are all TLS 1.2, so the client forces TLSv1.2.
  */
class TlsProtocolDowngradeSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  /** Build an SSLSocketFactory that trusts all certs (for self-signed test certs).
    *
    * SSLContext.init receives null key managers, which selects the JSSE default key managers.
    */
  private def trustAllSocketFactory: SSLSocketFactory = {
    val trustAll = new javax.net.ssl.X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      override def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    }
    val ctx = SSLContext.getInstance("TLS")
    // scalafix:off DisableSyntax.null
    ctx.init(null, Array(trustAll), SecureRandom.getInstanceStrong)
    // scalafix:on DisableSyntax.null
    ctx.getSocketFactory
  }

  /** Open a raw SSLSocket and attempt the handshake with the given protocol/cipher restrictions.
    * Returns Right(()) if the handshake succeeds, Left(errorClass) if it fails.
    *
    * startHandshake is called explicitly so the handshake runs synchronously and failures surface
    * here rather than lazily on the first I/O operation.
    */
  private def attemptHandshake(
    host: String,
    port: Int,
    enabledProtocols: Option[Array[String]],
    enabledCiphers: Option[Array[String]]
  ): Either[String, Unit] = {
    val factory = trustAllSocketFactory
    val rawSocket = factory.createSocket(host, port)
    val socket = rawSocket match {
      case ssl: SSLSocket => ssl
      case other => fail(s"Factory returned non-SSL socket: ${other.getClass.getName}")
    }
    try {
      socket.setSoTimeout(5_000)
      enabledProtocols.foreach(socket.setEnabledProtocols)
      enabledCiphers.foreach(socket.setEnabledCipherSuites)
      socket.startHandshake()
      Right(())
    } catch {
      case e: SSLHandshakeException => Left(s"SSLHandshakeException: ${e.getMessage}")
      case e: IOException => Left(s"IOException: ${e.getMessage}")
    } finally Try(socket.close()): Unit
  }

  test("TLS downgrade: server with tls13Only rejects TLS 1.1 handshake") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.tls13Only.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val serverConfig = HttpServerConfig.localhost.withPort(0).withTls(tlsConfig)

      val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("secret")))

      HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            addr <- server.start
            _ <- Eru.effect {
              val tls13Result = attemptHandshake(
                addr.host,
                addr.port,
                enabledProtocols = Some(Array("TLSv1.3")),
                enabledCiphers = None
              )
              tls13Result match {
                case Right(()) => ()
                case Left(err) => fail(s"TLS 1.3 handshake should succeed but failed: $err")
              }

              val downgradeResult = attemptHandshake(
                addr.host,
                addr.port,
                enabledProtocols = Some(Array("TLSv1.1")),
                enabledCiphers = None
              )
              downgradeResult match {
                case Left(_) => ()
                case Right(()) =>
                  fail(
                    "TLS 1.1 handshake SUCCEEDED against TLS 1.3-only server. " +
                      "TlsConfig.protocols restriction is not being enforced at handshake time."
                  )
              }

              val tls12Result = attemptHandshake(
                addr.host,
                addr.port,
                enabledProtocols = Some(Array("TLSv1.2")),
                enabledCiphers = None
              )
              tls12Result match {
                case Left(_) => ()
                case Right(()) =>
                  fail("TLS 1.2 handshake SUCCEEDED against TLS 1.3-only server")
              }
            }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
          } yield ()
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }

  test("TLS downgrade: server rejects client forcing a weak cipher suite") {
    requireHostileMode()

    val (keystorePath, password) = TestKeystores.generateSelfSignedKeystore()
    try {
      val tlsConfig = TlsConfig.default.copy(
        keyStorePath = Some(keystorePath.toString),
        keyStorePassword = Some(password)
      )
      val serverConfig = HttpServerConfig.localhost.withPort(0).withTls(tlsConfig)
      val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

      HttpServer
        .scoped(serverConfig)(handler) { server =>
          for {
            addr <- server.start
            _ <- Eru.effect {
              val supported = trustAllSocketFactory.getSupportedCipherSuites.toSet
              val weakCiphers = supported.filter { c =>
                c.contains("_CBC_") &&
                c.contains("_RSA_") &&
                !c.contains("DH_anon")
              }

              if weakCiphers.isEmpty then {
                ()
              } else {
                val weakCipher = weakCiphers.head
                val downgradeResult = attemptHandshake(
                  addr.host,
                  addr.port,
                  enabledProtocols = Some(Array("TLSv1.2")),
                  enabledCiphers = Some(Array(weakCipher))
                )
                downgradeResult match {
                  case Left(_) => ()
                  case Right(()) =>
                    fail(
                      s"Handshake SUCCEEDED with weak cipher $weakCipher. " +
                        "Server cipher allowlist is not being enforced at handshake time."
                    )
                }

                val strongCipher = TlsConfig.defaultCipherSuites
                  .find(c => supported.contains(c) && c.startsWith("TLS_ECDHE_RSA_"))
                  .getOrElse(fail("JDK must support at least one ECDHE_RSA cipher from our allowlist"))
                val strongResult = attemptHandshake(
                  addr.host,
                  addr.port,
                  enabledProtocols = Some(Array("TLSv1.2")),
                  enabledCiphers = Some(Array(strongCipher))
                )
                strongResult match {
                  case Right(()) => ()
                  case Left(err) => fail(s"Strong cipher $strongCipher handshake failed: $err")
                }
              }
            }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
          } yield ()
        }
        .assertSuccess
    } finally Files.deleteIfExists(keystorePath): Unit
  }
}
