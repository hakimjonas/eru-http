package net.ghoula.eru.http.acme

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.HttpClient

/** RFC 8555 client state machine: directory → account → order → authorizations (http-01) → finalize
  * → certificate download.
  *
  * Protocol notes:
  *   - every POST is a signed JWS (ES256); the account JWK is embedded only for `newAccount`,
  *     everything after references the account via `kid` (RFC 8555 Section 6.2)
  *   - every response carries a fresh `Replay-Nonce`; it is captured for the next POST
  *   - a `badNonce` rejection is retried once with a fresh nonce (RFC 8555 Section 6.7)
  *   - POST-as-GET (empty payload) is used for authorization, order, and certificate polling
  *
  * The client is stateless between issues beyond the nonce cache: everything durable lives on disk
  * under [[AcmeConfig.storePath]].
  */
final class AcmeClient private[acme] (
  config: AcmeConfig,
  accountKey: KeyPair,
  responder: AcmeHttp01,
  client: HttpClient
)(using runtime: EruRuntime) {

  private val nonceRef = new AtomicReference[Option[String]](None)

  /** Runs the full issuance flow for `config.domains`. */
  def issue(serverKey: KeyPair): Eru[AcmeError, KeystoreWriter.IssuedCert] =
    for {
      dir <- directory()
      accountUrl <- account(dir)
      orderUrl <- newOrder(dir, accountUrl)
      order <- jwsGet(orderUrl, accountUrl)
      _ <- proveAll(order, accountUrl)
      finalizeUrl <- order.stringField("finalize").toRight(AcmeError.Order("order carries no finalize URL")).erum
      csr <- Csr.build(serverKey, config.domains).erum
      _ <- jwsPost(
        finalizeUrl,
        Json.obj("csr" -> Json.str(Jose.b64u(csr))),
        embeddedJwk = false,
        kid = Some(accountUrl)
      )
      finalOrder <- pollOrder(orderUrl, accountUrl)
      certUrl <- finalOrder
        .stringField("certificate")
        .toRight(AcmeError.Finalize("order is valid but carries no certificate URL"))
        .erum
      chainPem <- downloadCertificate(certUrl, accountUrl)
      chain <- Pem.decodeChain(chainPem).erum
      expiry <- KeystoreWriter.leafExpiry(chainPem).erum
      _ <- Eru.effect(responder.clear()).mapError(e => AcmeError.Storage(e.getMessage))
    } yield KeystoreWriter.IssuedCert(config.domains, chain, serverKey, expiry)

  // ------------------------------------------------------------------
  // Protocol steps
  // ------------------------------------------------------------------

  /** GET the directory document. */
  private def directory(): Eru[AcmeError, Json] =
    for {
      response <- httpGet(config.resolvedDirectoryUrl)
      _ <-
        if response.status == StatusCode.Ok then Eru.unit
        else Eru.fail(AcmeError.Directory(s"unexpected status ${response.status.value}"))
      body <- decodeJson(response, AcmeError.Directory.apply)
    } yield body

  /** Creates (or recovers) the account; returns its `kid` URL. */
  private def account(dir: Json): Eru[AcmeError, String] = {
    val url = dir.stringField("newAccount").toRight(AcmeError.Directory("no newAccount URL")).erum
    url.flatMap { accountUrl =>
      val payload = Json.obj("termsOfServiceAgreed" -> Json.bool(true))
      for {
        response <- jwsPost(accountUrl, payload, embeddedJwk = true, kid = None)
        _ <-
          if response.status == StatusCode.Created || response.status == StatusCode.Ok then Eru.unit
          else
            Eru.fail(
              AcmeError.Account(
                s"unexpected status ${response.status.value}: ${new String(response.body.unsafeArray, StandardCharsets.UTF_8).take(300)}"
              )
            )
        location <- response.headers
          .getFirst("Location")
          .map(_.value)
          .toRight(AcmeError.Account("newAccount response carries no Location"))
          .erum
      } yield location
    }
  }

  /** Creates a new order for the configured domains; returns the order URL. */
  private def newOrder(dir: Json, accountUrl: String): Eru[AcmeError, String] = {
    val url = dir.stringField("newOrder").toRight(AcmeError.Directory("no newOrder URL")).erum
    url.flatMap { orderUrl =>
      val identifiers =
        Json.Arr(config.domains.map(d => Json.obj("type" -> Json.str("dns"), "value" -> Json.str(d))))
      val payload = Json.obj("identifiers" -> identifiers)
      for {
        response <- jwsPost(orderUrl, payload, embeddedJwk = false, kid = Some(accountUrl))
        _ <-
          if response.status == StatusCode.Created then Eru.unit
          else Eru.fail(AcmeError.Order(s"unexpected status ${response.status.value}"))
        location <- response.headers
          .getFirst("Location")
          .map(_.value)
          .toRight(AcmeError.Order("newOrder response carries no Location"))
          .erum
      } yield location
    }
  }

  /** Proves every pending authorization of the order over http-01. */
  private def proveAll(order: Json, accountUrl: String): Eru[AcmeError, Unit] = {
    val urls = order.field("authorizations").flatMap(_.asArray).getOrElse(Nil).flatMap(_.asString)
    Eru.foreach(urls)(proveAuthorization(_, accountUrl)).map(_ => ())
  }

  /** Proves one authorization over http-01: registers the key authorization with the responder,
    * notifies the challenge, then polls the authorization until it turns valid or invalid.
    */
  private def proveAuthorization(authzUrl: String, accountUrl: String): Eru[AcmeError, Unit] =
    for {
      authz <- jwsGet(authzUrl, accountUrl)
      identifier = authz
        .field("identifiers")
        .flatMap(_.asArray)
        .getOrElse(Nil)
        .headOption
        .flatMap(_.stringField("value"))
        .getOrElse("unknown")
      challenge <- findHttp01Challenge(authz, identifier)
      keyAuthz = accountKey.getPublic match {
        case ec: ECPublicKey => Jose.keyAuthorization(challenge.token, ec)
        case other => throw new IllegalStateException(s"expected EC public key, got ${other.getClass.getSimpleName}")
      }
      _ <- Eru
        .effect(responder.register(challenge.token, keyAuthz))
        .mapError(e => AcmeError.Challenge(identifier, e.getMessage))
      notifyResponse <- jwsPost(challenge.url, Json.obj(), embeddedJwk = false, kid = Some(accountUrl))
      _ <-
        if notifyResponse.status == StatusCode.Ok then Eru.unit
        else Eru.fail(AcmeError.Challenge(identifier, s"challenge notify answered ${notifyResponse.status.value}"))
      _ <- pollAuthorization(identifier, authzUrl, accountUrl)
    } yield ()

  private final case class Http01Challenge(url: String, token: String)

  private def findHttp01Challenge(authz: Json, identifier: String): Eru[AcmeError, Http01Challenge] = {
    val challenges = authz.field("challenges").flatMap(_.asArray).getOrElse(Nil)
    challenges
      .flatMap(c =>
        c.stringField("type").filter(_ == "http-01").flatMap { _ =>
          for {
            url <- c.stringField("url")
            token <- c.stringField("token")
          } yield Http01Challenge(url, token)
        }
      )
      .headOption
      .toRight(AcmeError.Authorization(identifier, "authorization carries no http-01 challenge"))
      .erum
  }

  /** Polls an authorization until valid/invalid (bounded by 30s). */
  private def pollAuthorization(identifier: String, authzUrl: String, accountUrl: String): Eru[AcmeError, Unit] = {
    val deadline = System.nanoTime() + 30_000_000_000L
    def step(): Eru[AcmeError, Unit] =
      for {
        authz <- jwsGet(authzUrl, accountUrl)
        _ <- authz.stringField("status") match {
          case Some("valid") => Eru.unit
          case Some("invalid") =>
            Eru.fail(AcmeError.Authorization(identifier, "challenge validation failed"))
          case _ =>
            if System.nanoTime() > deadline then
              Eru.fail(AcmeError.Timeout(s"authorization for '$identifier' did not resolve in time"))
            else runtime.sleep(java.time.Duration.ofMillis(500)).flatMap(_ => step())
        }
      } yield ()
    step()
  }

  /** Polls the order until its status turns valid, then returns the final order document. */
  private def pollOrder(orderUrl: String, accountUrl: String): Eru[AcmeError, Json] = {
    val deadline = System.nanoTime() + 30_000_000_000L
    def step(): Eru[AcmeError, Json] =
      for {
        order <- jwsGet(orderUrl, accountUrl)
        result <- order.stringField("status") match {
          case Some("valid") => Eru.succeed(order)
          case Some("invalid") =>
            Eru.fail(AcmeError.Order("order became invalid"))
          case _ =>
            if System.nanoTime() > deadline then Eru.fail(AcmeError.Timeout("order did not become valid in time"))
            else runtime.sleep(java.time.Duration.ofMillis(500)).flatMap(_ => step())
        }
      } yield result
    step()
  }

  /** Downloads the issued chain (POST-as-GET). */
  private def downloadCertificate(certUrl: String, accountUrl: String): Eru[AcmeError, String] =
    for {
      response <- jwsPost(certUrl, Json.Null, embeddedJwk = false, kid = Some(accountUrl))
      _ <-
        if response.status == StatusCode.Ok then Eru.unit
        else Eru.fail(AcmeError.Download(s"unexpected status ${response.status.value}"))
      chain = new String(response.body.unsafeArray, StandardCharsets.UTF_8)
      _ <-
        if chain.contains("BEGIN CERTIFICATE") then Eru.unit
        else Eru.fail(AcmeError.Download("certificate response is not a PEM chain"))
    } yield chain

  // ------------------------------------------------------------------
  // HTTP + JWS plumbing
  // ------------------------------------------------------------------

  /** POST-as-GET returning the decoded JSON body (RFC 8555 Section 6.3). */
  private def jwsGet(url: String, accountUrl: String): Eru[AcmeError, Json] =
    jwsPost(url, Json.Null, embeddedJwk = false, kid = Some(accountUrl)).flatMap(response =>
      decodeJson(response, s => AcmeError.Protocol(s, Some(response.status.value)))
    )

  /** Signed POST with nonce management and the one-shot badNonce retry. */
  private def jwsPost(
    url: String,
    payload: Json,
    embeddedJwk: Boolean,
    kid: Option[String]
  ): Eru[AcmeError, Response[Bytes]] =
    ensureNonce.flatMap { nonce =>
      sendJws(url, payload, embeddedJwk, kid, nonce).flatMap { response =>
        if response.status == StatusCode.BadRequest then
          // badNonce (RFC 8555 Section 6.7): refresh and retry exactly once.
          problemTypeOf(response) match {
            case Some(t) if t.endsWith("badNonce") =>
              nonceRef.set(None)
              ensureNonce.flatMap(fresh => sendJws(url, payload, embeddedJwk, kid, fresh))
            case _ => Eru.succeed(response)
          }
        else Eru.succeed(response)
      }
    }

  private def problemTypeOf(response: Response[Bytes]): Option[String] = {
    val text = new String(response.body.unsafeArray, StandardCharsets.UTF_8)
    Json.parse(text).toOption.flatMap(_.stringField("type"))
  }

  private def sendJws(
    url: String,
    payload: Json,
    embeddedJwk: Boolean,
    kid: Option[String],
    nonce: String
  ): Eru[AcmeError, Response[Bytes]] =
    for {
      uri <- Uri.parse(url).mapError(e => AcmeError.Protocol(e.message))
      jws = Jose.flattenedJws(accountKey, embeddedJwk, kid, nonce, url, payload)
      request: Request[Body] = Request(
        Method.POST,
        uri,
        Headers.empty,
        Body.Text(jws.encode, Some(MediaType.applicationJson), Charset.UTF8)
      )
      response <- client.send(request).mapError(e => AcmeError.Protocol(e.message))
    } yield captureNonce(response)

  private def httpGet(url: String): Eru[AcmeError, Response[Bytes]] =
    for {
      uri <- Uri.parse(url).mapError(e => AcmeError.Protocol(e.message))
      response <- client
        .send(Request[Body](Method.GET, uri, Headers.empty, Body.Empty))
        .mapError(e => AcmeError.Protocol(e.message))
    } yield captureNonce(response)

  private def captureNonce(response: Response[Bytes]): Response[Bytes] = {
    response.headers.getFirst("Replay-Nonce").foreach(h => nonceRef.set(Some(h.value)))
    response
  }

  /** Returns a cached nonce, fetching a fresh one from `newNonce` when absent. */
  private def ensureNonce: Eru[AcmeError, String] =
    nonceRef.get() match {
      case Some(n) => Eru.succeed(n)
      case None =>
        for {
          dir <- directory()
          nonceUrl <- dir
            .stringField("newNonce")
            .toRight(AcmeError.Nonce("directory carries no newNonce URL"))
            .erum
          response <- httpGet(nonceUrl)
          nonce <- response.headers
            .getFirst("Replay-Nonce")
            .map(_.value)
            .toRight(AcmeError.Nonce("newNonce response carries no Replay-Nonce"))
            .erum
          _ <- Eru.effect(nonceRef.set(Some(nonce))).mapError(e => AcmeError.Nonce(e.getMessage))
        } yield nonce
    }

  private def decodeJson(response: Response[Bytes], onError: String => AcmeError): Eru[AcmeError, Json] = {
    val text = new String(response.body.unsafeArray, StandardCharsets.UTF_8)
    if text.trim.isEmpty then Eru.succeed(Json.Obj(Nil))
    else
      Json.parse(text) match {
        case Right(json) => Eru.succeed(json)
        case Left(err) => Eru.fail(onError(err))
      }
  }

}

object AcmeClient {

  /** Loads a P-256 keypair from `path` (`<name>.pem` + `<base>-public.pem`), or generates and
    * persists a new one.
    */
  def loadOrCreateKey(path: Path, description: String): Eru[AcmeError, KeyPair] = {
    val publicPath = path.resolveSibling(
      path.getFileName.toString.stripSuffix(".pem") + "-public.pem"
    )
    val attempt: Either[AcmeError, KeyPair] =
      try {
        if Files.exists(path) && Files.exists(publicPath) then
          for {
            privateKey <- Pem.decodePrivateKey(Files.readString(path))
            publicKey <- Pem.decodePublicKey(Files.readString(publicPath))
          } yield new KeyPair(publicKey, privateKey)
        else if Files.exists(path) || Files.exists(publicPath) then
          Left(
            AcmeError.Storage(
              s"$description storage is incomplete: ${path.getFileName} and ${publicPath.getFileName} must exist together"
            )
          )
        else {
          val keyPair = generateKeyPair()
          Files.createDirectories(path.getParent)
          val (privatePem, publicPem) = Pem.encodeKeyPair(keyPair)
          Files.writeString(path, privatePem)
          Files.writeString(publicPath, publicPem)
          Right(keyPair)
        }
      } catch {
        case err: AcmeError => Left(err)
        case e => Left(AcmeError.Storage(s"cannot load or create $description: ${e.getMessage}"))
      }
    attempt.erum
  }

  /** Fresh ES256-capable P-256 keypair. */
  def generateKeyPair(): KeyPair = {
    val kpg = java.security.KeyPairGenerator.getInstance("EC")
    kpg.initialize(256)
    kpg.generateKeyPair()
  }
}
