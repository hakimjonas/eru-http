package examples

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** A small REST API with in-memory storage: list, get, create, update, and delete users. Bodies use
  * a simple "name,email" text format, since eru-http deliberately ships no JSON codec (see
  * MANIFESTO.md); BodyEncoder/BodyDecoder or a library of your choice plugs in here.
  *
  * Run it: sbt "examples/runMain examples.RestApi" Try it: curl http://localhost:8080/users curl -X
  * POST http://localhost:8080/users -d 'David Brown,david@example.com'
  */
object RestApi {

  given runtime: EruRuntime = EruRuntime.create()

  final case class User(id: Int, name: String, email: String)

  val nextId = new AtomicInteger(1)
  val users = new ConcurrentHashMap[Int, User]()

  def createUser(name: String, email: String): User = {
    val user = User(nextId.getAndIncrement(), name, email)
    users.put(user.id, user)
    user
  }

  createUser("Alice Johnson", "alice@example.com")
  createUser("Bob Smith", "bob@example.com")

  /** Decodes the body as "name,email" and answers 400 for anything else. */
  def withUserBody(
    body: Body
  )(use: (String, String) => Eru[HttpError, Response[Body]]): Eru[HttpError, Response[Body]] =
    BodyDecoder[String]
      .decode(body)
      .mapError(HttpError.BodyDecodeError.apply)
      .flatMap { raw =>
        raw.trim.split(",", 2).toList match {
          case name :: email :: Nil if name.nonEmpty => use(name, email)
          case _ =>
            Eru.succeed(Response.badRequest(Body.text("expected body format: name,email")))
        }
      }

  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      case (Method.GET, "/users") =>
        val text = users
          .values()
          .asScala
          .toList
          .sortBy(_.id)
          .map { u =>
            s"${u.id}: ${u.name} <${u.email}>"
          }
          .mkString("\n")
        Eru.succeed(Response.ok(Body.text(text)))

      case (Method.POST, "/users") =>
        withUserBody(req.body) { (name, email) =>
          val user = createUser(name, email)
          for
            location <- Uri
              .parse(s"http://localhost:8080/users/${user.id}")
              .mapError(HttpError.InvalidUri.apply)
            created <- Response
              .created(location, Body.text(s"user ${user.id} created"))
              .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid 201 response: $e", "RFC 9110")))
          yield created
        }

      case (Method.GET, path) if path.startsWith("/users/") =>
        val found = path.drop("/users/".length).toIntOption.flatMap(id => Option(users.get(id)))
        found match {
          case Some(user) =>
            Eru.succeed(Response.ok(Body.text(s"${user.name} <${user.email}>")))
          case None =>
            Eru.succeed(Response.badRequest(Body.text("unknown user id")))
        }

      case (Method.PUT, path) if path.startsWith("/users/") =>
        path.drop("/users/".length).toIntOption match {
          case Some(uid) =>
            withUserBody(req.body) { (name, email) =>
              Option(users.replace(uid, User(uid, name, email))) match {
                case Some(_) =>
                  Eru.succeed(Response.ok(Body.text(s"user $uid updated")))
                case None =>
                  Eru.succeed(Response.badRequest(Body.text("unknown user id")))
              }
            }
          case None =>
            Eru.succeed(Response.badRequest(Body.text("unknown user id")))
        }

      case (Method.DELETE, path) if path.startsWith("/users/") =>
        path.drop("/users/".length).toIntOption match {
          case Some(uid) if Option(users.remove(uid)).isDefined =>
            Eru.succeed(Response.noContent)
          case _ =>
            Eru.succeed(Response.badRequest(Body.text("unknown user id")))
        }

      case (_, path) if path.startsWith("/users") =>
        Response
          .methodNotAllowed(Set(Method.GET, Method.POST, Method.PUT, Method.DELETE))
          .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid 405 response: $e", "RFC 9110")))

      case _ =>
        Eru.succeed(
          Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not found"))
        )
    }

  def main(args: Array[String]): Unit = {
    val server = HttpServer
      .create(HttpServerConfig.localhost.withPort(8080), handler)
      .unsafeRunSync()
    val address = server.start.unsafeRunSync()
    println(s"listening on ${address.host}:${address.port} - Ctrl+C to stop")
    new java.util.concurrent.CountDownLatch(1).await() // block so the server keeps serving; Ctrl+C to stop
  }
}
