package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Request and response interceptors: bearer auth, User-Agent, and logging composed onto one
  * client, exercised against a local server that rejects requests without credentials.
  *
  * Run it: sbt "examples/runMain examples.ClientWithAuth"
  */
object ClientWithAuth {

  given runtime: EruRuntime = EruRuntime.create()

  val Token: String = "Bearer demo-token"

  val handler: RequestHandler = req =>
    req.headers.getFirst(HeaderNames.Authorization).map(_.value) match {
      case Some(value) if value == Token =>
        Eru.succeed(Response.ok(Body.text(s"secrets for ${req.uri.path}")))
      case _ =>
        Response
          .unauthorized("Bearer", Body.text("missing or invalid token"))
          .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid 401 response: $e", "RFC 9110")))
    }

  val program: Eru[HttpError, Unit] =
    HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
      for
        address <- server.start
        uri <- Uri
          .parse(s"http://${address.host}:${address.port}/articles")
          .mapError(HttpError.InvalidUri.apply)
        _ <- HttpClient.scoped { plain =>
          for
            rejected <- plain.send(Request.get(uri))
            _ <- Eru
              .effect(println(s"without token: ${rejected.status.value}"))
              .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
          yield ()
        }
        _ <- HttpClient.scoped { base =>
          val client = base
            .withRequestInterceptor(Interceptor.userAgent("examples-client/1.0"))
            .withRequestInterceptor(Interceptor.logRequest(line => println(s"-> $line")))
            .withRequestInterceptor(Interceptor.bearerAuth("demo-token"))
            .withResponseInterceptor(Interceptor.logResponse(line => println(s"<- $line")))
          for
            accepted <- client.send(Request.get(uri))
            _ <- Eru
              .effect(
                println(s"with token: ${accepted.status.value} ${accepted.body.asString(Charset.UTF8)}")
              )
              .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
          yield ()
        }
      yield ()
    }

  def main(args: Array[String]): Unit =
    program.unsafeRunSync()
}
