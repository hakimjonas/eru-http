package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import scala.collection.concurrent.TrieMap

/** Complete REST API with CRUD operations.
  *
  * Demonstrates:
  *   - RESTful resource design
  *   - CRUD operations (Create, Read, Update, Delete)
  *   - JSON response handling
  *   - Path parameter extraction
  *   - Proper HTTP status codes
  *   - Resource validation
  *   - Concurrent safe in-memory storage
  */
object RestApi {

  given runtime: EruRuntime = EruRuntime.shared

  // Domain model
  case class User(id: Int, name: String, email: String) {
    def toJson: String = s"""{"id":$id,"name":"$name","email":"$email"}"""
  }

  object UserStore {
    // Thread-safe in-memory store
    private val users = TrieMap[Int, User](
      1 -> User(1, "Alice Smith", "alice@example.com"),
      2 -> User(2, "Bob Johnson", "bob@example.com"),
      3 -> User(3, "Carol Williams", "carol@example.com")
    )
    private var nextId = 4

    def getAll: List[User] = users.values.toList.sortBy(_.id)

    def getById(id: Int): Option[User] = users.get(id)

    def create(name: String, email: String): User = {
      val user = User(nextId, name, email)
      users.put(nextId, user)
      nextId += 1
      user
    }

    def update(id: Int, name: String, email: String): Option[User] = {
      users.get(id).map { _ =>
        val user = User(id, name, email)
        users.put(id, user)
        user
      }
    }

    def delete(id: Int): Boolean = {
      users.remove(id).isDefined
    }
  }

  def main(args: Array[String]): Unit = {
    runServer()
  }

  def runServer(): Unit = {
    println("=== REST API Example ===\n")

    // Apply middleware to handler
    val app = Middleware
      .logging(msg => println(s"[API] $msg"))
      .andThen(Middleware.corsPermissive)
      .apply(handler)

    val program = for {
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(8080), app)
      address <- server.start

      _ <- Eru.effect {
        println(s"REST API started at http://${address.host}:${address.port}")
        println("\nAvailable endpoints:")
        println("  GET    /users          - List all users")
        println("  GET    /users/:id      - Get user by ID")
        println("  POST   /users          - Create new user (body: name,email)")
        println("  PUT    /users/:id      - Update user (body: name,email)")
        println("  DELETE /users/:id      - Delete user")
        println("\nTry these commands:")
        println("  curl http://localhost:8080/users")
        println("  curl http://localhost:8080/users/1")
        println("""  curl -X POST http://localhost:8080/users -d 'David Brown,david@example.com'""")
        println("""  curl -X PUT http://localhost:8080/users/1 -d 'Alice Johnson,alice.j@example.com'""")
        println("  curl -X DELETE http://localhost:8080/users/2")
        println("\nPress Enter to stop...")
        scala.io.StdIn.readLine()
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- server.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Server stopped successfully")
      case Result.Failure(error) =>
        println(s"Server error: $error")
    }
  }

  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      // List all users
      case (Method.GET, "/users") =>
        handleListUsers()

      // Get user by ID
      case (Method.GET, path) if path.startsWith("/users/") =>
        val idStr = path.drop("/users/".length)
        idStr.toIntOption match {
          case Some(id) => handleGetUser(id)
          case None => Response.badRequest(Body.text(s"""{"error":"Invalid user ID: $idStr"}"""))
        }

      // Create user
      case (Method.POST, "/users") =>
        handleCreateUser(req)

      // Update user
      case (Method.PUT, path) if path.startsWith("/users/") =>
        val idStr = path.drop("/users/".length)
        idStr.toIntOption match {
          case Some(id) => handleUpdateUser(id, req)
          case None => Response.badRequest(Body.text(s"""{"error":"Invalid user ID: $idStr"}"""))
        }

      // Delete user
      case (Method.DELETE, path) if path.startsWith("/users/") =>
        val idStr = path.drop("/users/".length)
        idStr.toIntOption match {
          case Some(id) => handleDeleteUser(id)
          case None => Response.badRequest(Body.text(s"""{"error":"Invalid user ID: $idStr"}"""))
        }

      // Root endpoint
      case (Method.GET, "/") =>
        val info = """{"service":"REST API Example","version":"1.0","endpoints":["/users"]}"""
        jsonResponse(info)

      case _ =>
        Response.notFound(Body.text("""{"error":"Not found"}"""))
    }

  /** Lists all users.
    */
  def handleListUsers(): Eru[HttpError, Response[Body]] = {
    val users = UserStore.getAll
    val json = users.map(_.toJson).mkString("[", ",", "]")
    jsonResponse(json)
  }

  /** Gets a single user by ID.
    */
  def handleGetUser(id: Int): Eru[HttpError, Response[Body]] = {
    UserStore.getById(id) match {
      case Some(user) =>
        jsonResponse(user.toJson)
      case None =>
        Response.notFound(Body.text(s"""{"error":"User not found","id":$id}"""))
    }
  }

  /** Creates a new user.
    *
    * Expects body format: "name,email"
    */
  def handleCreateUser(req: Request[Body]): Eru[HttpError, Response[Body]] = {
    for {
      // Decode request body
      bodyText <- BodyDecoder[String]
        .decode(req.body)
        .mapError(HttpError.BodyDecodeError.apply)

      // Parse name and email (simple CSV format)
      parts = bodyText.split(",").map(_.trim)

      // Validate
      result <- if parts.length != 2 then
        Response.badRequest(
          Body.text("""{"error":"Invalid format. Expected: name,email"}""")
        )
      else {
        val Array(name, email) = parts
        if name.isEmpty || email.isEmpty then
          Response.badRequest(Body.text("""{"error":"Name and email cannot be empty"}"""))
        else {
          // Create user
          val user = UserStore.create(name, email)
          for {
            uri <- Uri.parse(s"/users/${user.id}")
            response <- Response.created(uri, Body.text(user.toJson))
            withContentType <- response.withContentType(MediaType.applicationJson)
          } yield withContentType
        }
      }
    } yield result
  }

  /** Updates an existing user.
    *
    * Expects body format: "name,email"
    */
  def handleUpdateUser(id: Int, req: Request[Body]): Eru[HttpError, Response[Body]] = {
    for {
      // Decode request body
      bodyText <- BodyDecoder[String]
        .decode(req.body)
        .mapError(HttpError.BodyDecodeError.apply)

      // Parse name and email
      parts = bodyText.split(",").map(_.trim)

      result <- if parts.length != 2 then
        Response.badRequest(
          Body.text("""{"error":"Invalid format. Expected: name,email"}""")
        )
      else {
        val Array(name, email) = parts
        UserStore.update(id, name, email) match {
          case Some(user) =>
            jsonResponse(user.toJson)
          case None =>
            Response.notFound(Body.text(s"""{"error":"User not found","id":$id}"""))
        }
      }
    } yield result
  }

  /** Deletes a user.
    */
  def handleDeleteUser(id: Int): Eru[HttpError, Response[Body]] = {
    if UserStore.delete(id) then Response.noContent
    else Response.notFound(Body.text(s"""{"error":"User not found","id":$id}"""))
  }

  /** Helper to create JSON responses.
    */
  def jsonResponse(json: String): Eru[HttpError, Response[Body]] = {
    for {
      body <- Eru.succeed(Body.text(json, MediaType.applicationJson))
      response <- Response.ok(body).withContentType(MediaType.applicationJson)
    } yield response
  }
}
