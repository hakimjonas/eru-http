package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*

/** Server-Sent Events (SSE) example.
  *
  * Demonstrates:
  *   - Creating SSE streams
  *   - Sending events to clients
  *   - Different event types
  *   - Event IDs for reconnection
  *   - HTML client page
  *   - Real-time updates
  */
object ServerSentEventsExample {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    runServer()
  }

  def runServer(): Unit = {
    println("=== Server-Sent Events Example ===\n")

    val program = for {
      server <- HttpServer.create(HttpServerConfig.localhost.withPort(8080), handler)
      address <- server.start

      _ <- Eru.effect {
        println(s"SSE server started at http://${address.host}:${address.port}")
        println("\nEndpoints:")
        println("  GET /              - HTML page with SSE client")
        println("  GET /events        - SSE event stream")
        println("  GET /time          - Time updates stream")
        println("  GET /counter       - Counter stream")
        println("\nOpen http://localhost:8080 in your browser to see SSE in action")
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
      case (Method.GET, "/") =>
        handleIndexPage()

      case (Method.GET, "/events") =>
        handleEventsStream()

      case (Method.GET, "/time") =>
        handleTimeStream()

      case (Method.GET, "/counter") =>
        handleCounterStream()

      case _ =>
        Response.notFound(Body.text("Not found"))
    }

  /** Serves the HTML page with SSE client.
    */
  def handleIndexPage(): Eru[HttpError, Response[Body]] = {
    val html = """
      |<!DOCTYPE html>
      |<html>
      |<head>
      |  <title>Server-Sent Events Demo</title>
      |  <style>
      |    body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
      |    h1 { color: #333; }
      |    .container { background: white; padding: 20px; border-radius: 8px; margin-bottom: 20px; }
      |    .event { padding: 10px; margin: 5px 0; background: #e3f2fd; border-left: 4px solid #2196f3; }
      |    .event-type { font-weight: bold; color: #1976d2; }
      |    .timestamp { color: #666; font-size: 0.9em; }
      |    button { padding: 10px 20px; margin: 5px; cursor: pointer; }
      |  </style>
      |</head>
      |<body>
      |  <h1>Server-Sent Events Demo</h1>
      |
      |  <div class="container">
      |    <h2>Static Events Stream</h2>
      |    <button onclick="startEvents()">Start Events</button>
      |    <button onclick="stopEvents()">Stop Events</button>
      |    <div id="events"></div>
      |  </div>
      |
      |  <div class="container">
      |    <h2>Live Time Stream</h2>
      |    <button onclick="startTime()">Start Time</button>
      |    <button onclick="stopTime()">Stop Time</button>
      |    <div id="time"></div>
      |  </div>
      |
      |  <div class="container">
      |    <h2>Counter Stream</h2>
      |    <button onclick="startCounter()">Start Counter</button>
      |    <button onclick="stopCounter()">Stop Counter</button>
      |    <div id="counter"></div>
      |  </div>
      |
      |  <script>
      |    let eventsSource = null;
      |    let timeSource = null;
      |    let counterSource = null;
      |
      |    function startEvents() {
      |      if (eventsSource) return;
      |      eventsSource = new EventSource('/events');
      |
      |      eventsSource.onmessage = function(e) {
      |        addEvent('events', 'message', e.data);
      |      };
      |
      |      eventsSource.addEventListener('update', function(e) {
      |        addEvent('events', 'update', e.data);
      |      });
      |
      |      eventsSource.onerror = function() {
      |        addEvent('events', 'error', 'Connection error');
      |      };
      |    }
      |
      |    function stopEvents() {
      |      if (eventsSource) {
      |        eventsSource.close();
      |        eventsSource = null;
      |      }
      |    }
      |
      |    function startTime() {
      |      if (timeSource) return;
      |      timeSource = new EventSource('/time');
      |      timeSource.onmessage = function(e) {
      |        document.getElementById('time').innerHTML =
      |          '<div class="event"><strong>Current Time:</strong> ' + e.data + '</div>';
      |      };
      |    }
      |
      |    function stopTime() {
      |      if (timeSource) {
      |        timeSource.close();
      |        timeSource = null;
      |      }
      |    }
      |
      |    function startCounter() {
      |      if (counterSource) return;
      |      counterSource = new EventSource('/counter');
      |      counterSource.onmessage = function(e) {
      |        document.getElementById('counter').innerHTML =
      |          '<div class="event"><strong>Count:</strong> ' + e.data + '</div>';
      |      };
      |    }
      |
      |    function stopCounter() {
      |      if (counterSource) {
      |        counterSource.close();
      |        counterSource = null;
      |      }
      |    }
      |
      |    function addEvent(containerId, type, data) {
      |      const container = document.getElementById(containerId);
      |      const eventDiv = document.createElement('div');
      |      eventDiv.className = 'event';
      |      eventDiv.innerHTML =
      |        '<span class="event-type">' + type + ':</span> ' + data +
      |        ' <span class="timestamp">(' + new Date().toLocaleTimeString() + ')</span>';
      |      container.insertBefore(eventDiv, container.firstChild);
      |
      |      // Keep only last 10 events
      |      while (container.children.length > 10) {
      |        container.removeChild(container.lastChild);
      |      }
      |    }
      |  </script>
      |</body>
      |</html>
    """.stripMargin

    for {
      body <- Eru.succeed(Body.text(html, MediaType.textHtml))
      response <- Response.ok(body).withContentType(MediaType.textHtml)
    } yield response
  }

  /** Handles static events stream.
    */
  def handleEventsStream(): Eru[HttpError, Response[Body]] = {
    val events = List(
      ServerSentEvent.data("Welcome to SSE!").copy(id = Some("1")),
      ServerSentEvent.event("update", "System initialized").copy(id = Some("2")),
      ServerSentEvent.data("Processing started...").copy(id = Some("3")),
      ServerSentEvent.event("update", "Processing complete").copy(id = Some("4")),
      ServerSentEvent.data("All done!").copy(id = Some("5"))
    )

    val stream = ServerSentEvent.toChunkStream(events)
    Response.sse(stream)
  }

  /** Handles time updates stream.
    *
    * Note: This is a simplified example that sends a finite number of time updates. In a real
    * application, you would use a proper streaming mechanism to continuously send updates.
    */
  def handleTimeStream(): Eru[HttpError, Response[Body]] = {
    // Generate 10 time updates
    val timeEvents = (1 to 10).map { i =>
      val timestamp = java.time.Instant.now().toString
      ServerSentEvent.data(timestamp).copy(id = Some(i.toString))
    }.toList

    val stream = ServerSentEvent.toChunkStream(timeEvents)
    Response.sse(stream)
  }

  /** Handles counter stream.
    */
  def handleCounterStream(): Eru[HttpError, Response[Body]] = {
    // Generate counter events from 1 to 20
    val counterEvents = (1 to 20).map { i =>
      ServerSentEvent.data(i.toString).copy(id = Some(i.toString))
    }.toList

    val stream = ServerSentEvent.toChunkStream(counterEvents)
    Response.sse(stream)
  }
}
