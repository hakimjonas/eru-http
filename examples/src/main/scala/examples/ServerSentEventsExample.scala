package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Server-Sent Events: GET /events streams a few SSE events (with event types and reconnection
  * ids), GET / explains the demo. Runs until interrupted (Ctrl+C).
  *
  * Run it: sbt "examples/runMain examples.ServerSentEventsExample" Try it: curl -N
  * http://localhost:8080/events
  */
object ServerSentEventsExample {

  given runtime: EruRuntime = EruRuntime.create()

  val events: List[ServerSentEvent] = List(
    ServerSentEvent.data("Welcome!").copy(id = Some("1")),
    ServerSentEvent.event("update", "New message").copy(id = Some("2")),
    ServerSentEvent.event("update", "Another message").copy(id = Some("3")),
    ServerSentEvent.data("bye").copy(id = Some("4"))
  )

  val handler: RequestHandler = req =>
    req.uri.path match {
      case "/events" =>
        Response
          .sse(ServerSentEvent.toChunkStream(events))
          .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"invalid SSE response: $e", "WHATWG HTML")))
      case _ =>
        Eru.succeed(
          Response.ok(Body.text("SSE demo: curl -N http://localhost:8080/events"))
        )
    }

  def main(args: Array[String]): Unit = {
    val server = HttpServer
      .create(HttpServerConfig.localhost.withPort(8080), handler)
      .unsafeRunSync()
    val address = server.start.unsafeRunSync()
    println(s"listening on ${address.host}:${address.port} - open http://${address.host}:8080/events - Ctrl+C to stop")
    new java.util.concurrent.CountDownLatch(1).await() // block so the server keeps serving; Ctrl+C to stop
  }
}
