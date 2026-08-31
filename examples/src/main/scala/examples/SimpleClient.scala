package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Minimal client round trip: start a server on an ephemeral port, send a GET and a POST with the
  * client, print both responses.
  *
  * Run it: sbt "examples/runMain examples.SimpleClient"
  */
object SimpleClient {

  given runtime: EruRuntime = EruRuntime.create()

  val handler: RequestHandler = req =>
    req.method match {
      case Method.GET =>
        Eru.succeed(Response.ok(Body.text(s"hello from ${req.uri.path}")))
      case Method.POST =>
        for body <- BodyDecoder[String]
            .decode(req.body)
            .mapError(HttpError.BodyDecodeError.apply)
        yield Response.ok(Body.text(s"echo: $body"))
      case _ =>
        Eru.succeed(Response.ok(Body.text("try GET / or POST /echo")))
    }

  val program: Eru[HttpError, Unit] =
    HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
      for
        address <- server.start
        base = s"http://${address.host}:${address.port}"
        _ <- HttpClient.scoped { client =>
          for
            root <- Uri.parse(s"$base/").mapError(HttpError.InvalidUri.apply)
            echoed <- Uri.parse(s"$base/echo").mapError(HttpError.InvalidUri.apply)
            got <- client.send(Request.get(root))
            _ <- Eru
              .effect(println(s"GET / -> ${got.status.value} ${got.body.asString(Charset.UTF8)}"))
              .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
            sent <- client.send(Request.post(echoed, Body.text("Hello, World!")))
            _ <- Eru
              .effect(
                println(s"POST /echo -> ${sent.status.value} ${sent.body.asString(Charset.UTF8)}")
              )
              .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
          yield ()
        }
      yield ()
    }

  def main(args: Array[String]): Unit =
    program.unsafeRunSync()
}
