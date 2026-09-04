package net.ghoula.eru.http.acme

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.RequestHandler

/** HTTP-01 challenge responder (RFC 8555 Sections 8.3 and 8.4).
  *
  * ACME validates http-01 by requesting `http://<domain>/.well-known/acme-challenge/<token>` on
  * port 80 and expecting the key authorization as the response body (`text/plain`). This wrapper
  * serves those paths for the challenges currently being proven and delegates everything else to
  * `next`.
  *
  * Wire it into the listener ACME will reach — the simplest deployments run a dedicated
  * `HttpServer` on port 80 (see [[AcmeProvisioner.challengeHandler]]), or redirect :80 → the TLS
  * server while this middleware answers the challenge paths on the plain listener.
  */
final class AcmeHttp01 private[acme] (
  challenges: ConcurrentHashMap[String, String]
) extends (RequestHandler => RequestHandler) {

  import AcmeHttp01.ChallengePrefix

  /** The current challenge registry: token → key authorization. */
  def snapshot: Map[String, String] = challenges.asScala.toMap

  /** Wraps `next` so ACME challenge paths are answered locally. */
  def apply(next: RequestHandler): RequestHandler = { request =>
    if request.method == Method.GET && request.uri.path.startsWith(ChallengePrefix) then challengeResponse(request)
    else next(request)
  }

  private def challengeResponse(request: Request[Body]): Eru[HttpError, Response[Body]] = {
    val token = request.uri.path.stripPrefix(ChallengePrefix)
    Option(challenges.get(token)) match {
      case Some(keyAuthz) =>
        Eru.succeed(
          Response(
            StatusCode.Ok,
            Headers.empty,
            Body.Text(keyAuthz, Some(MediaType.textPlain), Charset.UTF8)
          )
        )
      case None =>
        Eru.succeed(
          Response(
            StatusCode.NotFound,
            Headers.empty,
            Body.Text("no such ACME challenge", Some(MediaType.textPlain), Charset.UTF8)
          )
        )
    }
  }

  private[acme] def register(token: String, keyAuthz: String): Unit =
    challenges.put(token, keyAuthz): Unit

  private[acme] def clear(): Unit = challenges.clear()
}

object AcmeHttp01 {

  private val ChallengePrefix = "/.well-known/acme-challenge/"

  /** Builds a responder. Key authorizations (`<token>.<thumbprint>`) are registered by the
    * [[AcmeClient]] during issuance; the same instance must be handed to [[AcmeProvisioner.start]].
    */
  def create(): AcmeHttp01 = new AcmeHttp01(new ConcurrentHashMap[String, String]())

  /** A ready-made plain-HTTP handler chain for the challenge port: responder only. */
  def challengeHandler(responder: AcmeHttp01): RequestHandler = responder { _ =>
    Eru.succeed(
      Response(
        StatusCode.NotFound,
        Headers.empty,
        Body.Text("not an ACME challenge path", Some(MediaType.textPlain), Charset.UTF8)
      )
    )
  }

  /** Convenience redirect handler for a TLS-fronting listener: answers ACME challenge paths with a
    * 301 to `challengeHost`'s responder. Most ACME validators follow redirects, so serving
    * challenges through the TLS server via redirect is a common pattern.
    */
  def redirectTarget(challengeHost: String, port: Int): RequestHandler => RequestHandler = next =>
    request => {
      if request.uri.path.startsWith(ChallengePrefix) then {
        val location = s"http://$challengeHost${if port == 80 then "" else s":$port"}${request.uri.path}"
        Response(StatusCode.MovedPermanently, Headers.empty, Body.Empty)
          .setHeader(HeaderNames.Location, location)
          .attempt
          .flatMap {
            case Result.Success(r) => Eru.succeed(r)
            case Result.Failure(error) =>
              Eru.fail(HttpError.NetworkError(s"cannot build challenge redirect: $error", None))
          }
      } else next(request)
    }
}
