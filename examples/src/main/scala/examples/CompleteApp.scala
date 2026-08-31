package examples

import java.util.Collections
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** A complete application sketch: bearer auth protecting /articles, ETag-based conditional GET
  * (If-None-Match answers 304), an SSE stream, logging, request IDs, and a default error handler.
  * Runs until interrupted (Ctrl+C).
  *
  * Run it: sbt "examples/runMain examples.CompleteApp" Try it: curl http://localhost:8080/health
  * curl -H "Authorization: Bearer demo-token" http://localhost:8080/articles
  */
object CompleteApp {

  given runtime: EruRuntime = EruRuntime.create()

  val Token: String = "Bearer demo-token"
  val articles: java.util.List[String] =
    Collections.synchronizedList(new java.util.ArrayList[String](List("First article", "Second article").asJava))

  def listing: String =
    articles.asScala.zipWithIndex.map((a, i) => s"${i + 1}. $a").mkString("\n")

  /** Public paths skip the auth check. */
  def isPublic(path: String): Boolean = path == "/health" || path == "/events"

  val authMiddleware: Middleware = handler =>
    req =>
      if isPublic(req.uri.path) then handler(req)
      else
        req.headers.getFirst(HeaderNames.Authorization).map(_.value) match {
          case Some(value) if value == Token => handler(req)
          case _ =>
            Response
              .unauthorized("Bearer", Body.text("missing or invalid token"))
              .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid 401 response: $e", "RFC 9110")))
        }

  val handler: RequestHandler = req =>
    (req.method, req.uri.path) match {
      case (Method.GET, "/health") =>
        Eru.succeed(Response.ok(Body.text("ok")))

      case (Method.GET, "/events") =>
        val events = List(
          ServerSentEvent.data("article feed online").copy(id = Some("1")),
          ServerSentEvent.event("article", "First article").copy(id = Some("2"))
        )
        Response
          .sse(ServerSentEvent.toChunkStream(events))
          .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid SSE response: $e", "WHATWG HTML")))

      case (Method.GET, "/articles") =>
        for
          etag <- ETag.fromContent(Body.text(listing))
          response <-
            req.headers.getFirst(HeaderNames.IfNoneMatch).map(_.value) match {
              case Some(value) if value == etag.headerValue =>
                Eru.succeed(
                  Response(status = StatusCode.NotModified, headers = Headers.empty, body = Body.Empty)
                )
              case _ =>
                Eru.succeed(Response.ok(Body.text(listing)))
            }
          withEtag <- response
            .setHeader(HeaderNames.ETag, etag.headerValue)
            .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid response: $e", "RFC 9110")))
        yield withEtag

      case (Method.POST, "/articles") =>
        BodyDecoder[String]
          .decode(req.body)
          .mapError(HttpError.BodyDecodeError.apply)
          .flatMap { text =>
            if text.trim.isEmpty then {
              Eru.succeed(Response.badRequest(Body.text("article text is required")))
            } else {
              articles.add(text.trim)
              Eru.succeed(
                Response(status = StatusCode.Created, headers = Headers.empty, body = Body.text(s"stored: $text"))
              )
            }
          }

      case _ =>
        Eru.succeed(
          Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not found"))
        )
    }

  val app: RequestHandler = authMiddleware
    .andThen(Middleware.logging(line => println(s"[log] $line")))
    .andThen(Middleware.requestId())
    .andThen(Middleware.errorHandlerDefault)
    .apply(handler)

  def main(args: Array[String]): Unit = {
    val server = HttpServer
      .create(HttpServerConfig.localhost.withPort(8080), app)
      .unsafeRunSync()
    val address = server.start.unsafeRunSync()
    println(s"listening on ${address.host}:${address.port} - Ctrl+C to stop")
    new java.util.concurrent.CountDownLatch(1).await() // block so the server keeps serving; Ctrl+C to stop
  }
}
