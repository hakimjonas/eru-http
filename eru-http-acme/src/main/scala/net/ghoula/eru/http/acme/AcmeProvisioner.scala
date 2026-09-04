package net.ghoula.eru.http.acme

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.HttpClient

/** ACME provisioning: obtain (or reuse) a certificate, persist it as a PKCS12 keystore, hand back a
  * [[TlsConfig]], and keep both current.
  *
  * Usage:
  * {{{
  *   given runtime: EruRuntime = EruRuntime.shared
  *   val accountKey = AcmeClient.generateKeyPair()
  *   val responder  = AcmeHttp01.create(accountKey)
  *   val provisioner = AcmeProvisioner
  *     .start(AcmeConfig(List("api.example.com"), "ops@example.com", storePath = Path.of("/var/lib/acme")), responder)
  *     .assertSuccess
  *
  *   // port 80: serve the http-01 challenges
  *   HttpServer.create(HttpServerConfig.any.withPort(80), AcmeHttp01.challengeHandler(responder))
  *
  *   // the TLS server consumes the produced config
  *   HttpServer.create(HttpServerConfig.any.withTls(provisioner.tlsConfig), handler)
  * }}}
  *
  * Renewal: a daemon fiber wakes on `renewalCheckInterval`; when the leaf's remaining validity
  * drops below `renewBefore`, it re-runs issuance, rewrites the keystore, and swaps the config
  * returned by [[tlsConfig]]. Servers that already built their SSLContext pick up the renewed
  * material on their next start; live context swapping is left to the operator for now.
  */
final class AcmeProvisioner private[acme] (
  val config: AcmeConfig,
  private[acme] val responder: AcmeHttp01,
  runtime: EruRuntime
) {

  private val currentTlsConfig = new AtomicReference[Option[TlsConfig]](None)
  private val renewalFiber = new AtomicReference[Option[Fiber[?, ?]]](None)
  private val httpClientRef = new AtomicReference[Option[HttpClient]](None)
  private val stopped = new AtomicBoolean(false)

  /** The current TLS configuration; ready as soon as [[AcmeProvisioner.start]] returns. */
  def tlsConfig: TlsConfig =
    currentTlsConfig.get().getOrElse(throw AcmeError.Storage("provisioning has not completed"))

  /** Stops the renewal loop and the underlying ACME HTTP client (idempotent). Issued material stays
    * on disk.
    */
  def stop(): Eru[Nothing, Unit] =
    Eru.effect {
      stopped.set(true)
      renewalFiber.getAndSet(None).foreach { fiber =>
        val cause = InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(()))
        fiber.interrupt(cause).attempt.unsafeRunSync(): Unit
      }
      httpClientRef.getAndSet(None).foreach { client =>
        client.shutdown.attempt.unsafeRunSync(): Unit
      }
    }.attempt.map(_ => ())

  private[acme] def installTlsConfig(p12Path: Path): Unit =
    currentTlsConfig.set(
      Some(
        TlsConfig(
          keyStorePath = Some(p12Path.toString),
          keyStorePassword = Some(config.keyStorePassword)
        )
      )
    )

  private[acme] def setHttpClient(client: HttpClient): Unit = httpClientRef.set(Some(client))

  private[acme] def startRenewalLoop(
    accountKey: java.security.KeyPair,
    serverKey: java.security.KeyPair
  ): Eru[AcmeError, Unit] =
    httpClientRef.get() match {
      case None => Eru.fail(AcmeError.Storage("renewal loop requires the provisioner's HTTP client"))
      case Some(client) =>
        val acmeClient = new AcmeClient(config, accountKey, responder, client)(using runtime)
        val loop = renewalLoop(acmeClient, serverKey)
        runtime.forkDaemon(loop).map { fiber =>
          renewalFiber.set(Some(fiber))
          ()
        }
    }

  private def renewalLoop(client: AcmeClient, serverKey: java.security.KeyPair): Eru[Nothing, Unit] = {
    def step(): Eru[Nothing, Unit] =
      runtime.sleep(config.renewalCheckInterval).flatMap { _ =>
        if stopped.get() then Eru.unit
        else
          checkAndRenew(client, serverKey).attempt.flatMap {
            case Result.Success(_) => step()
            case Result.Failure(_) => step() // a failed renewal retries on the next tick
          }
      }
    step()
  }

  /** Re-issues when the leaf's remaining validity drops below `config.renewBefore`. */
  private def checkAndRenew(client: AcmeClient, serverKey: java.security.KeyPair): Eru[AcmeError, Unit] =
    storedChainPem match {
      case None => Eru.unit
      case Some(pemPath) =>
        Eru
          .effect(KeystoreWriter.leafExpiry(Files.readString(pemPath)))
          .mapError {
            case err: AcmeError => err
            case e => AcmeError.Storage(e.getMessage)
          }
          .flatMap {
            case Right(notAfter) if Duration.between(Instant.now(), notAfter).compareTo(config.renewBefore) > 0 =>
              Eru.unit
            case _ => renew(client, serverKey)
          }
    }

  private def renew(client: AcmeClient, serverKey: java.security.KeyPair): Eru[AcmeError, Unit] =
    for {
      cert <- client.issue(serverKey)
      p12Path <- KeystoreWriter.persist(config.storePath, cert, config.keyStorePassword).erum
      _ <- Eru.effect(installTlsConfig(p12Path)).mapError(e => AcmeError.Storage(e.getMessage))
    } yield ()

  private def storedChainPem: Option[Path] = {
    val primary = config.domains.headOption.getOrElse("server")
    val pem = config.storePath.resolve(primary).resolve("cert.pem")
    if Files.exists(pem) then Some(pem) else None
  }

}

object AcmeProvisioner {

  /** Provisions (or loads) certificates and starts the renewal loop.
    *
    * When a previously-issued certificate is still valid beyond `renewBefore`, the stored keystore
    * is reused without any network traffic; otherwise a full issuance runs. Either way the returned
    * provisioner exposes a ready [[AcmeProvisioner.tlsConfig]].
    */
  def start(config: AcmeConfig, responder: AcmeHttp01)(using runtime: EruRuntime): Eru[AcmeError, AcmeProvisioner] =
    for {
      _ <- Eru
        .effect(Files.createDirectories(config.storePath))
        .mapError(e => AcmeError.Storage(s"cannot create store path: ${e.getMessage}"))
      accountKey <- AcmeClient.loadOrCreateKey(config.storePath.resolve("account-key.pem"), "ACME account key")
      serverKey <- AcmeClient.loadOrCreateKey(config.storePath.resolve("server-key.pem"), "certificate key")
      provisioner = new AcmeProvisioner(config, responder, runtime)
      client <- HttpClient
        .create(net.ghoula.eru.http.client.HttpClientConfig.default)
        .mapError(e => AcmeError.Protocol(e.message))
      _ <- Eru.effect(provisioner.setHttpClient(client)).mapError(e => AcmeError.Storage(e.getMessage))
      reused <- loadReusable(config)
      _ <- reused match {
        case Some(p12Path) => install(provisioner, p12Path)
        case None =>
          for {
            cert <- new AcmeClient(config, accountKey, responder, client)(using runtime).issue(serverKey)
            p12Path <- KeystoreWriter.persist(config.storePath, cert, config.keyStorePassword).erum
            _ <- install(provisioner, p12Path)
          } yield ()
      }
      _ <- provisioner.startRenewalLoop(accountKey, serverKey)
    } yield provisioner

  private def install(provisioner: AcmeProvisioner, p12Path: Path): Eru[AcmeError, Path] =
    Eru.effect {
      provisioner.installTlsConfig(p12Path)
      p12Path
    }.mapError(e => AcmeError.Storage(e.getMessage))

  /** Loads the stored keystore if the leaf still has `renewBefore` of validity left. */
  private def loadReusable(config: AcmeConfig): Eru[AcmeError, Option[Path]] = {
    val primary = config.domains.headOption.getOrElse("server")
    val dir = config.storePath.resolve(primary)
    val pem = dir.resolve("cert.pem")
    val p12 = dir.resolve(s"$primary.p12")
    Eru.effect {
      if Files.exists(pem) && Files.exists(p12) then
        KeystoreWriter.leafExpiry(Files.readString(pem)) match {
          case Right(notAfter) if Duration.between(Instant.now(), notAfter).compareTo(config.renewBefore) > 0 =>
            Some(p12)
          case _ => None
        }
      else None
    }.mapError(e => AcmeError.Storage(e.getMessage))
  }

}
