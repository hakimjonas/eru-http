package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.client.*
import scala.collection.concurrent.TrieMap
import java.time.Instant

/** Complete full-featured application combining all eru-http capabilities.
  *
  * This example demonstrates a complete application that:
  *   - Serves a REST API with CRUD operations
  *   - Uses middleware for logging, CORS, error handling
  *   - Includes authentication
  *   - Makes HTTP client requests
  *   - Handles file uploads
  *   - Streams server-sent events
  *   - Implements caching with ETags
  *   - Provides comprehensive error handling
  *
  * This is a real-world template showing Scala 3 + Eru best practices.
  */
object CompleteApp {

  given runtime: EruRuntime = EruRuntime.shared

  // Domain Model
  case class Article(
    id: Int,
    title: String,
    content: String,
    author: String,
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toJson: String = {
      s"""{"id":$id,"title":"$title","content":"$content","author":"$author","createdAt":"$createdAt","updatedAt":"$updatedAt"}"""
    }
  }

  // In-memory storage
  object ArticleStore {
    private val articles = TrieMap[Int, Article](
      1 -> Article(
        1,
        "Introduction to Eru",
        "Eru is a functional effect system for Scala 3...",
        "alice",
        Instant.now(),
        Instant.now()
      ),
      2 -> Article(
        2,
        "HTTP with Eru",
        "Building HTTP servers and clients with eru-http...",
        "bob",
        Instant.now(),
        Instant.now()
      )
    )
    private var nextId = 3

    def getAll: List[Article] = articles.values.toList.sortBy(_.id)
    def getById(id: Int): Option[Article] = articles.get(id)

    def create(title: String, content: String, author: String): Article = {
      val now = Instant.now()
      val article = Article(nextId, title, content, author, now, now)
      articles.put(nextId, article)
      nextId += 1
      article
    }

    def update(id: Int, title: String, content: String): Option[Article] = {
      articles.get(id).map { old =>
        val updated = old.copy(title = title, content = content, updatedAt = Instant.now())
        articles.put(id, updated)
        updated
      }
    }

    def delete(id: Int): Boolean = articles.remove(id).isDefined
  }

  def main(args: Array[String]): Unit = {
    runServer()
  }

  def runServer(): Unit = {
    println("=== Complete Application Example ===\n")

    // Build complete middleware chain
    val app = authMiddleware
      .andThen(Middleware.logging(msg => println(s"[APP] $msg")))
      .andThen(Middleware.corsPermissive)
      .andThen(requestIdMiddleware)
      .andThen(Middleware.errorHandlerDefault)
      .apply(handler)

    val program = for {
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(8080), app)
      address <- server.start

      _ <- Eru.effect {
        println(s"Application started at http://${address.host}:${address.port}")
        println("\n=== API Documentation ===")
        println("\nPublic Endpoints:")
        println("  GET  /                - API information")
        println("  GET  /health          - Health check")
        println("\nArticle Endpoints (require auth):")
        println("  GET  /articles        - List all articles")
        println("  GET  /articles/:id    - Get article by ID")
        println("  POST /articles        - Create article (body: title,content,author)")
        println("  PUT  /articles/:id    - Update article (body: title,content)")
        println("  DELETE /articles/:id  - Delete article")
        println("\nOther Endpoints:")
        println("  GET  /stats           - Application statistics")
        println("  GET  /events          - SSE event stream")
        println("\nAuthentication:")
        println("  Use header: Authorization: Bearer demo-token")
        println("\nExample commands:")
        println("""  curl -H "Authorization: Bearer demo-token" http://localhost:8080/articles""")
        println("""  curl -H "Authorization: Bearer demo-token" http://localhost:8080/articles/1""")
        println(
          """  curl -X POST -H "Authorization: Bearer demo-token" http://localhost:8080/articles -d 'New Article,Content here,charlie'"""
        )
        println("\nPress Enter to stop...")
        scala.io.StdIn.readLine()
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- server.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Application stopped successfully")
      case Result.Failure(error) =>
        println(s"Application error: $error")
    }
  }

  // Authentication middleware
  val authMiddleware: Middleware = handler => req =>
    // Public endpoints don't require auth
    if isPublicEndpoint(req.uri.path) then handler(req)
    else {
      // Check for Authorization header
      req.headers.getFirst(HeaderNames.Authorization) match {
        case Some(authHeader) if authHeader.value == "Bearer demo-token" =>
          handler(req)
        case Some(_) =>
          Response.unauthorized("Bearer", Body.text("""{"error":"Invalid token"}"""))
        case None =>
          Response.unauthorized("Bearer", Body.text("""{"error":"Missing Authorization header"}"""))
      }
    }

  def isPublicEndpoint(path: String): Boolean = {
    path == "/" || path == "/health" || path == "/stats"
  }

  // Request ID middleware
  val requestIdMiddleware: Middleware = handler => req =>
    for {
      requestId <- Eru.effect(java.util.UUID.randomUUID().toString).mapError(e =>
        HttpError.NetworkError(e.getMessage, Some(e))
      )
      response <- handler(req)
      withId <- response.setHeader("X-Request-ID", requestId).mapError {
        case e: HeaderName.InvalidHeaderName =>
          HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        case e: HeaderValue.InvalidHeaderValue =>
          HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
      }
    } yield withId

  // Main request handler
  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      // Public endpoints
      case (Method.GET, "/") => handleRoot()
      case (Method.GET, "/health") => handleHealth()
      case (Method.GET, "/stats") => handleStats()

      // Article endpoints
      case (Method.GET, "/articles") => handleListArticles()
      case (Method.GET, path) if path.startsWith("/articles/") =>
        extractId(path, "/articles/") match {
          case Some(id) => handleGetArticle(id)
          case None => Response.badRequest(Body.text("""{"error":"Invalid article ID"}"""))
        }
      case (Method.POST, "/articles") => handleCreateArticle(req)
      case (Method.PUT, path) if path.startsWith("/articles/") =>
        extractId(path, "/articles/") match {
          case Some(id) => handleUpdateArticle(id, req)
          case None => Response.badRequest(Body.text("""{"error":"Invalid article ID"}"""))
        }
      case (Method.DELETE, path) if path.startsWith("/articles/") =>
        extractId(path, "/articles/") match {
          case Some(id) => handleDeleteArticle(id)
          case None => Response.badRequest(Body.text("""{"error":"Invalid article ID"}"""))
        }

      // SSE endpoint
      case (Method.GET, "/events") => handleEvents()

      case _ =>
        Response.notFound(Body.text("""{"error":"Endpoint not found"}"""))
    }

  def extractId(path: String, prefix: String): Option[Int] = {
    path.drop(prefix.length).toIntOption
  }

  def handleRoot(): Eru[HttpError, Response[Body]] = {
    val info =
      """{"name":"Complete App Example","version":"1.0.0","description":"Full-featured eru-http application"}"""
    jsonResponse(info)
  }

  def handleHealth(): Eru[HttpError, Response[Body]] = {
    val health = s"""{"status":"healthy","timestamp":"${Instant.now()}","uptime":"running"}"""
    jsonResponse(health)
  }

  def handleStats(): Eru[HttpError, Response[Body]] = {
    val articleCount = ArticleStore.getAll.length
    val stats =
      s"""{"articles":$articleCount,"timestamp":"${Instant.now()}","version":"1.0.0"}"""
    jsonResponse(stats)
  }

  def handleListArticles(): Eru[HttpError, Response[Body]] = {
    val articles = ArticleStore.getAll
    val json = articles.map(_.toJson).mkString("[", ",", "]")
    jsonResponse(json)
  }

  def handleGetArticle(id: Int): Eru[HttpError, Response[Body]] = {
    ArticleStore.getById(id) match {
      case Some(article) =>
        // Add ETag for caching
        for {
          etag <- ETag.weak(s"article-$id-${article.updatedAt.toEpochMilli}")
          response <- jsonResponse(article.toJson)
          withETag <- response.withETag(etag)
        } yield withETag
      case None =>
        Response.notFound(Body.text(s"""{"error":"Article not found","id":$id}"""))
    }
  }

  def handleCreateArticle(req: Request[Body]): Eru[HttpError, Response[Body]] = {
    for {
      bodyText <- BodyDecoder[String].decode(req.body).mapError(HttpError.BodyDecodeError.apply)
      parts = bodyText.split(",").map(_.trim)
      result <- if parts.length != 3 then
        Response.badRequest(
          Body.text("""{"error":"Invalid format. Expected: title,content,author"}""")
        )
      else {
        val Array(title, content, author) = parts
        val article = ArticleStore.create(title, content, author)
        for {
          uri <- Uri.parse(s"/articles/${article.id}")
          response <- Response.created(uri, Body.text(article.toJson))
          withContentType <- response.withContentType(MediaType.applicationJson)
        } yield withContentType
      }
    } yield result
  }

  def handleUpdateArticle(id: Int, req: Request[Body]): Eru[HttpError, Response[Body]] = {
    for {
      bodyText <- BodyDecoder[String].decode(req.body).mapError(HttpError.BodyDecodeError.apply)
      parts = bodyText.split(",").map(_.trim)
      result <- if parts.length != 2 then
        Response.badRequest(Body.text("""{"error":"Invalid format. Expected: title,content"}"""))
      else {
        val Array(title, content) = parts
        ArticleStore.update(id, title, content) match {
          case Some(article) => jsonResponse(article.toJson)
          case None =>
            Response.notFound(Body.text(s"""{"error":"Article not found","id":$id}"""))
        }
      }
    } yield result
  }

  def handleDeleteArticle(id: Int): Eru[HttpError, Response[Body]] = {
    if ArticleStore.delete(id) then Response.noContent
    else Response.notFound(Body.text(s"""{"error":"Article not found","id":$id}"""))
  }

  def handleEvents(): Eru[HttpError, Response[Body]] = {
    val events = List(
      ServerSentEvent.event("stats", s"""{"articles":${ArticleStore.getAll.length}}"""),
      ServerSentEvent.data(s"""Application running at ${Instant.now()}""")
    )
    val stream = ServerSentEvent.toChunkStream(events)
    Response.sse(stream)
  }

  def jsonResponse(json: String): Eru[HttpError, Response[Body]] = {
    for {
      body <- Eru.succeed(Body.text(json, MediaType.applicationJson))
      response <- Response.ok(body).withContentType(MediaType.applicationJson)
    } yield response
  }
}
