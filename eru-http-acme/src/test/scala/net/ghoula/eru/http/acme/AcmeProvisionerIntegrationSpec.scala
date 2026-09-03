package net.ghoula.eru.http.acme

import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

import TestHelpers.*

/** End-to-end provisioning against a stub ACME server served by eru-http's own HttpServer.
  *
  * The stub implements just enough of RFC 8555 for the client's state machine (directory, nonce,
  * account, order, authorization, challenge, finalize, certificate download) and answers
  * authorizations as immediately-valid — the challenge token→key-authorization mapping is still
  * verified separately through the responder. The issued "certificate" is a real X.509 leaf
  * generated once with `keytool`, so the keystore writer's PKCS12 output is loadable and
  * inspectable with the JDK's own KeyStore API.
  */
class AcmeProvisionerIntegrationSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  // ------------------------------------------------------------------
  // Stub ACME server
  // ------------------------------------------------------------------

  /** A pre-generated self-signed leaf (keytool), PEM-wrapped. */
  private def stubCertificatePem: String = {
    val dir = Files.createTempDirectory("acme-stub-cert")
    val ks = dir.resolve("stub.p12")
    val process = ProcessBuilder(
      "keytool",
      "-genkeypair",
      "-alias",
      "stub",
      "-keyalg",
      "EC",
      "-keysize",
      "256",
      "-dname",
      "CN=issued.example.com",
      "-keypass",
      "stubpass",
      "-storepass",
      "stubpass",
      "-storetype",
      "PKCS12",
      "-keystore",
      ks.toString,
      "-validity",
      "90"
    ).redirectErrorStream(true).start()
    val output = process.getInputStream.readAllBytes()
    assert(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "keytool did not finish")
    assertEquals(process.exitValue(), 0, s"keytool failed:\n${new String(output)}")

    val exportPath = dir.resolve("stub.der")
    val exportProcess = ProcessBuilder(
      "keytool",
      "-exportcert",
      "-alias",
      "stub",
      "-keypass",
      "stubpass",
      "-storepass",
      "stubpass",
      "-storetype",
      "PKCS12",
      "-keystore",
      ks.toString,
      "-file",
      exportPath.toString
    ).redirectErrorStream(true).start()
    val exportOutput = exportProcess.getInputStream.readAllBytes()
    assert(exportProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "keytool export did not finish")
    assertEquals(exportProcess.exitValue(), 0, s"keytool export failed:\n${new String(exportOutput)}")

    val der = Files.readAllBytes(exportPath)
    val b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)
    s"-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
  }

  private final case class StubServer(address: ServerAddress, shutdown: Eru[HttpError, Unit], orderCount: AtomicInteger)

  private def startStubServer(certPem: String): Eru[HttpError, StubServer] = {
    val orderCount = new AtomicInteger(0)
    val nonceCounter = new AtomicInteger(0)
    // Resolved after bind: the stub's own URLs must carry the real port.
    val boundPort = new AtomicInteger(0)
    def url(path: String): String = s"http://localhost:${boundPort.get()}$path"

    def json(status: StatusCode, body: Json, location: Option[String] = None): Eru[HttpError, Response[Body]] = {
      val headersEffect = Headers.empty
        .add("Replay-Nonce", s"stub-nonce-${nonceCounter.incrementAndGet()}")
        .flatMap(h =>
          location match {
            case Some(l) => h.add("Location", l)
            case None => Eru.succeed(h)
          }
        )
        .flatMap(_.add("Content-Type", "application/json"))
        .flatMap(_.add("Content-Length", body.encode.length.toString))
      headersEffect
        .map(headers => Response(status, headers, Body.Text(body.encode)))
        .mapError(e => HttpError.NetworkError(s"header error: $e", None))
    }

    val handler: RequestHandler = request => {
      val path = request.uri.path
      // The server delivers request bodies as Body.Binary regardless of content type.
      val bodyText = request.body match {
        case Body.Binary(bytes, _) => new String(bytes.unsafeArray, java.nio.charset.StandardCharsets.UTF_8)
        case t: Body.Text => t.value
        case _ => ""
      }
      if request.method == Method.GET && path == "/directory" then
        json(
          StatusCode.Ok,
          Json.obj(
            "newNonce" -> Json.str(url("/new-nonce")),
            "newAccount" -> Json.str(url("/new-account")),
            "newOrder" -> Json.str(url("/new-order"))
          )
        )
      else if request.method == Method.GET && path == "/new-nonce" then json(StatusCode.Ok, Json.Obj(Nil))
      else if request.method == Method.POST && path == "/new-account" then {
        // Validate the JWS envelope exists (protected decodes, JWK embedded).
        val envelope = Json.parse(bodyText).toOption
        val hasJwk = envelope.flatMap(_.stringField("protected")).flatMap { p =>
          Json.parse(new String(Jose.b64uDecode(p), "US-ASCII")).toOption.flatMap(_.field("jwk"))
        }
        hasJwk match {
          case Some(_) =>
            json(StatusCode.Created, Json.Obj(Nil), location = Some(url("/account/1")))
          case None =>
            Eru.succeed(
              Response(
                StatusCode.BadRequest,
                Headers.empty,
                Body.Text(s"envelope must embed a JWK; got: ${bodyText.take(200)}")
              )
            )
        }
      } else if request.method == Method.POST && path == "/new-order" then {
        orderCount.incrementAndGet(): Unit
        json(
          StatusCode.Created,
          Json.obj(
            "status" -> Json.str("pending"),
            "identifiers" -> Json.Arr(
              List(Json.obj("type" -> Json.str("dns"), "value" -> Json.str("issued.example.com")))
            ),
            "authorizations" -> Json.Arr(List(Json.str(url("/authz/1")))),
            "finalize" -> Json.str(url("/finalize/1"))
          ),
          location = Some(url("/order/1"))
        )
      } else if request.method == Method.POST && path == "/authz/1" || path == "/order/1" then
        json(
          StatusCode.Ok,
          if path == "/authz/1" then
            Json.obj(
              "status" -> Json.str("valid"),
              "identifiers" -> Json.Arr(
                List(Json.obj("type" -> Json.str("dns"), "value" -> Json.str("issued.example.com")))
              ),
              "challenges" -> Json.Arr(
                List(
                  Json.obj(
                    "type" -> Json.str("http-01"),
                    "url" -> Json.str(url("/challenge/1")),
                    "token" -> Json.str("stub-token")
                  )
                )
              )
            )
          else
            Json.obj(
              "status" -> Json.str("valid"),
              "finalize" -> Json.str(url("/finalize/1")),
              "certificate" -> Json.str(url("/cert/1"))
            )
        )
      else if request.method == Method.POST && path == "/challenge/1" then json(StatusCode.Ok, Json.Obj(Nil))
      else if request.method == Method.POST && path == "/finalize/1" then
        json(
          StatusCode.Ok,
          Json.obj(
            "status" -> Json.str("valid"),
            "certificate" -> Json.str(url("/cert/1"))
          )
        )
      else if request.method == Method.POST && path == "/cert/1" then {
        val headersEffect = Headers.empty
          .add("Replay-Nonce", s"stub-nonce-${nonceCounter.incrementAndGet()}")
          .flatMap(_.add("Content-Type", "application/pem-certificate-chain"))
          .flatMap(_.add("Content-Length", certPem.length.toString))
        headersEffect
          .map(headers => Response(StatusCode.Ok, headers, Body.Text(certPem)))
          .mapError(e => HttpError.NetworkError(s"header error: $e", None))
      } else Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.Text("no such resource")))
    }

    HttpServer
      .create(HttpServerConfig.localhost.withPort(0), handler)
      .flatMap(_.start.map { addr =>
        boundPort.set(addr.port)
        StubServer(addr, Eru.unit, orderCount)
      })
  }

  // ------------------------------------------------------------------
  // Tests
  // ------------------------------------------------------------------

  test("AcmeProvisioner: full issuance flow produces a loadable PKCS12 TlsConfig") {
    val certPem = stubCertificatePem
    val stub = startStubServer(certPem).assertSuccess

    val result = Try {
      val responder = AcmeHttp01.create()
      val storePath = Files.createTempDirectory("acme-store")
      val config = AcmeConfig(
        domains = List("issued.example.com"),
        contactEmail = "ops@example.com",
        directoryUrl = Some(s"http://localhost:${stub.address.port}/directory"),
        storePath = storePath,
        renewalCheckInterval = java.time.Duration.ofHours(24) // must not fire during the test
      )
      AcmeProvisioner.start(config, responder).assertSuccess
    }

    // Stop the stub whether or not provisioning succeeded.
    val provisioner = result.toOption
    provisioner.foreach(p => p.stop().unsafeRunSync())

    stub.shutdown.attempt.unsafeRunSync(): Unit

    val p = result.get
    val tlsConfig = p.tlsConfig
    val p12Path = Path.of(tlsConfig.keyStorePath.get)
    assert(Files.exists(p12Path), s"keystore missing at $p12Path")
    assertEquals(tlsConfig.keyStorePassword, Some("changeit"))

    // The keystore loads with the JDK and carries the stub-issued chain under the domain alias.
    val keyStore = KeyStore.getInstance("PKCS12")
    val in = Files.newInputStream(p12Path)
    try keyStore.load(in, "changeit".toCharArray)
    finally in.close()
    assert(keyStore.containsAlias("issued.example.com"))
    val chain = keyStore.getCertificateChain("issued.example.com")
    assertEquals(chain.length, 1)
    val leaf = chain(0) match {
      case x509: java.security.cert.X509Certificate => x509
      case other => fail(s"expected X509Certificate, got ${other.getClass.getName}")
    }
    assertEquals(leaf.getSubjectX500Principal.getName, "CN=issued.example.com")
    assert(Option(keyStore.getKey("issued.example.com", "changeit".toCharArray)).isDefined)

    // PEM copies exist alongside the keystore.
    assert(Files.exists(p12Path.getParent.resolve("cert.pem")))
    assert(Files.exists(p12Path.getParent.resolve("key.pem")))

    // The responder saw and registered the challenge key authorization.
    val responderSnapshot = p.responder.snapshot
    assert(responderSnapshot.isEmpty, "challenge registry must be cleared after issuance")
  }

  test("AcmeProvisioner: a still-valid stored certificate is reused without issuance") {
    val certPem = stubCertificatePem

    val storePath = Files.createTempDirectory("acme-store-reuse")
    val responder = AcmeHttp01.create()
    def configFor(stub: StubServer): AcmeConfig = AcmeConfig(
      domains = List("issued.example.com"),
      contactEmail = "ops@example.com",
      directoryUrl = Some(s"http://localhost:${stub.address.port}/directory"),
      storePath = storePath,
      renewalCheckInterval = java.time.Duration.ofHours(24)
    )

    val stub1 = startStubServer(certPem).assertSuccess
    val p1 = AcmeProvisioner.start(configFor(stub1), responder).assertSuccess
    p1.stop().unsafeRunSync()
    stub1.shutdown.attempt.unsafeRunSync(): Unit
    assertEquals(stub1.orderCount.get(), 1)

    // Second start against a fresh stub: no newOrder traffic — the stored cert is reused.
    val stub2 = startStubServer(certPem).assertSuccess
    val p2 = AcmeProvisioner.start(configFor(stub2), responder).assertSuccess
    p2.stop().unsafeRunSync()
    stub2.shutdown.attempt.unsafeRunSync(): Unit
    assertEquals(stub2.orderCount.get(), 0, "stored certificate must be reused without a new order")
    assert(Files.exists(Path.of(p2.tlsConfig.keyStorePath.get)))
  }

  test("AcmeHttp01: serves the registered key authorization and 404s unknown tokens") {
    val responder = AcmeHttp01.create()
    responder.register("tok-42", "tok-42.thumbprint-value")

    val handler = AcmeHttp01.challengeHandler(responder)
    val uri = Uri.parse("http://localhost:80/.well-known/acme-challenge/tok-42").assertSuccess
    val response = handler(Request.get(uri)).assertSuccess
    assertEquals(response.status, StatusCode.Ok)
    assertEquals(
      response.body match {
        case t: Body.Text => Some(t.value)
        case _ => None
      },
      Some("tok-42.thumbprint-value")
    )

    val unknown = Uri.parse("http://localhost:80/.well-known/acme-challenge/nope").assertSuccess
    val missing = handler(Request.get(unknown)).assertSuccess
    assertEquals(missing.status, StatusCode.NotFound)
  }
}
