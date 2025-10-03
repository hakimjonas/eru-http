package net.ghoula.eru.http

import munit.*

import TestHelpers.*

class ServerSentEventSpec extends FunSuite {

  // ===== Basic Construction Tests =====

  test("data helper creates event with only data") {
    val event = ServerSentEvent.data("Hello World")
    assertEquals(event.data, "Hello World")
    assertEquals(event.id, None)
    assertEquals(event.event, None)
    assertEquals(event.retry, None)
  }

  test("event helper creates named event") {
    val event = ServerSentEvent.event("update", "New message")
    assertEquals(event.data, "New message")
    assertEquals(event.event, Some("update"))
    assertEquals(event.id, None)
    assertEquals(event.retry, None)
  }

  test("full event with all fields") {
    val event = ServerSentEvent(
      data = "Test data",
      id = Some("42"),
      event = Some("customEvent"),
      retry = Some(5000)
    )
    assertEquals(event.data, "Test data")
    assertEquals(event.id, Some("42"))
    assertEquals(event.event, Some("customEvent"))
    assertEquals(event.retry, Some(5000))
  }

  // ===== Serialization Tests =====

  test("toSSE serializes simple data-only event") {
    val event = ServerSentEvent.data("Hello World")
    val sse = event.toSSE
    assertEquals(sse, "data: Hello World\n\n")
  }

  test("toSSE serializes named event") {
    val event = ServerSentEvent.event("update", "New data")
    val sse = event.toSSE
    assertEquals(sse, "event: update\ndata: New data\n\n")
  }

  test("toSSE serializes event with ID") {
    val event = ServerSentEvent(data = "Data", id = Some("123"))
    val sse = event.toSSE
    assertEquals(sse, "data: Data\nid: 123\n\n")
  }

  test("toSSE serializes event with retry") {
    val event = ServerSentEvent(data = "Data", retry = Some(5000))
    val sse = event.toSSE
    assertEquals(sse, "data: Data\nretry: 5000\n\n")
  }

  test("toSSE serializes full event with all fields") {
    val event = ServerSentEvent(
      data = "Full event",
      id = Some("42"),
      event = Some("test"),
      retry = Some(3000)
    )
    val sse = event.toSSE
    // Order: event, data, id, retry per spec
    assertEquals(sse, "event: test\ndata: Full event\nid: 42\nretry: 3000\n\n")
  }

  test("toSSE handles multi-line data") {
    val event = ServerSentEvent.data("Line 1\nLine 2\nLine 3")
    val sse = event.toSSE
    assertEquals(sse, "data: Line 1\ndata: Line 2\ndata: Line 3\n\n")
  }

  test("toSSE handles empty data") {
    val event = ServerSentEvent.data("")
    val sse = event.toSSE
    assertEquals(sse, "data: \n\n")
  }

  test("toSSE handles data with trailing newline") {
    val event = ServerSentEvent.data("Text\n")
    val sse = event.toSSE
    // Split produces ["Text", ""]
    assertEquals(sse, "data: Text\ndata: \n\n")
  }

  test("toSSE handles special characters in data") {
    val event = ServerSentEvent.data("Special: !@#$%^&*()")
    val sse = event.toSSE
    assertEquals(sse, "data: Special: !@#$%^&*()\n\n")
  }

  // ===== Parsing Tests =====

  test("parse simple data-only event") {
    val text = "data: Hello World\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Hello World")
    assertEquals(events.head.id, None)
    assertEquals(events.head.event, None)
  }

  test("parse named event") {
    val text = "event: update\ndata: New message\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.event, Some("update"))
    assertEquals(events.head.data, "New message")
  }

  test("parse event with ID") {
    val text = "data: Test\nid: 42\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Test")
    assertEquals(events.head.id, Some("42"))
  }

  test("parse event with retry") {
    val text = "data: Test\nretry: 5000\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Test")
    assertEquals(events.head.retry, Some(5000))
  }

  test("parse full event with all fields") {
    val text = "event: custom\ndata: Full event\nid: 123\nretry: 3000\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    val event = events.head
    assertEquals(event.event, Some("custom"))
    assertEquals(event.data, "Full event")
    assertEquals(event.id, Some("123"))
    assertEquals(event.retry, Some(3000))
  }

  test("parse multi-line data") {
    val text = "data: Line 1\ndata: Line 2\ndata: Line 3\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Line 1\nLine 2\nLine 3")
  }

  test("parse multiple events") {
    val text = "data: Event 1\n\ndata: Event 2\n\ndata: Event 3\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 3)
    assertEquals(events(0).data, "Event 1")
    assertEquals(events(1).data, "Event 2")
    assertEquals(events(2).data, "Event 3")
  }

  test("parse ignores comments") {
    val text = ": This is a comment\ndata: Real data\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Real data")
  }

  test("parse ignores unknown fields") {
    val text = "data: Test\nunknown: value\nid: 42\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Test")
    assertEquals(events.head.id, Some("42"))
  }

  test("parse handles value with leading space") {
    val text = "data:  Leading space\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    // One space after colon is removed, rest preserved
    assertEquals(events.head.data, " Leading space")
  }

  test("parse handles value without space after colon") {
    val text = "data:NoSpace\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "NoSpace")
  }

  test("parse handles empty data field") {
    val text = "data: \n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "")
  }

  test("parse handles empty text") {
    val text = ""
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 0)
  }

  test("parse handles only blank lines") {
    val text = "\n\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 0)
  }

  test("parse ignores invalid retry values") {
    val text = "data: Test\nretry: invalid\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.retry, None)
  }

  test("parse ignores negative retry values") {
    val text = "data: Test\nretry: -100\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.retry, None)
  }

  test("parse fails on ID with null character") {
    val text = "data: Test\nid: has\u0000null\n\n"
    val result = ServerSentEvent.parse(text)
    assert(result.isFailure)
  }

  test("parse handles lines without colons") {
    val text = "data: Test\nno colon here\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Test")
  }

  test("parse dispatches event at end even without blank line") {
    val text = "data: Last event"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Last event")
  }

  test("parse handles event fields in different order") {
    val text = "id: 1\nretry: 1000\nevent: test\ndata: Message\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    val event = events.head
    assertEquals(event.id, Some("1"))
    assertEquals(event.retry, Some(1000))
    assertEquals(event.event, Some("test"))
    assertEquals(event.data, "Message")
  }

  // ===== Round-trip Tests =====

  test("round-trip: simple data") {
    val original = ServerSentEvent.data("Hello World")
    val serialized = original.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head, original)
  }

  test("round-trip: named event") {
    val original = ServerSentEvent.event("update", "New data")
    val serialized = original.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head, original)
  }

  test("round-trip: full event") {
    val original = ServerSentEvent(
      data = "Full event",
      id = Some("42"),
      event = Some("test"),
      retry = Some(5000)
    )
    val serialized = original.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head, original)
  }

  test("round-trip: multi-line data") {
    val original = ServerSentEvent.data("Line 1\nLine 2\nLine 3")
    val serialized = original.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head, original)
  }

  test("round-trip: empty data") {
    val original = ServerSentEvent.data("")
    val serialized = original.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head, original)
  }

  test("round-trip: multiple events") {
    val originals = List(
      ServerSentEvent.data("Event 1"),
      ServerSentEvent.event("update", "Event 2"),
      ServerSentEvent(data = "Event 3", id = Some("3"))
    )
    val serialized = originals.map(_.toSSE).mkString
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 3)
    assertEquals(parsed, originals)
  }

  // ===== Comment Tests =====

  test("comment generates proper format") {
    val comment = ServerSentEvent.comment("Keep alive")
    assertEquals(comment, ":Keep alive\n")
  }

  test("comment with empty text") {
    val comment = ServerSentEvent.comment("")
    assertEquals(comment, ":\n")
  }

  test("parse ignores various comment formats") {
    val text = ": comment\n:another\ndata: Real\n\n"
    val events = ServerSentEvent.parse(text).assertSuccess
    assertEquals(events.length, 1)
    assertEquals(events.head.data, "Real")
  }

  // ===== ChunkStream Conversion Tests =====

  test("toChunkStream converts list of events") {
    val events = List(
      ServerSentEvent.data("Event 1"),
      ServerSentEvent.data("Event 2")
    )
    val stream = ServerSentEvent.toChunkStream(events)
    val bytes = stream.toBytes.assertSuccess
    val text = bytes.asString(Charset.UTF8)
    assertEquals(text, "data: Event 1\n\ndata: Event 2\n\n")
  }

  test("toChunkStream handles empty list") {
    val events = List.empty[ServerSentEvent]
    val stream = ServerSentEvent.toChunkStream(events)
    val bytes = stream.toBytes.assertSuccess
    assertEquals(bytes.length, 0)
  }

  test("parseStream parses events from ChunkStream") {
    val text = "data: Event 1\n\ndata: Event 2\n\n"
    val stream = ChunkStream.fromString(text, Charset.UTF8)
    val events = ServerSentEvent.parseStream(stream).assertSuccess
    assertEquals(events.length, 2)
    assertEquals(events(0).data, "Event 1")
    assertEquals(events(1).data, "Event 2")
  }

  test("commentStream creates stream from comment") {
    val stream = ServerSentEvent.commentStream("heartbeat")
    val bytes = stream.toBytes.assertSuccess
    val text = bytes.asString(Charset.UTF8)
    assertEquals(text, ":heartbeat\n")
  }

  // ===== Edge Cases =====

  test("handles very long data") {
    val longData = "x" * 10000
    val event = ServerSentEvent.data(longData)
    val serialized = event.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head.data, longData)
  }

  test("handles Unicode characters") {
    val event = ServerSentEvent.data("Hello 世界 🌍")
    val serialized = event.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head.data, "Hello 世界 🌍")
  }

  test("handles data with only newlines") {
    val event = ServerSentEvent.data("\n\n\n")
    val serialized = event.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head.data, "\n\n\n")
  }

  test("handles special characters in event name") {
    val event = ServerSentEvent.event("user-login-event", "data")
    val serialized = event.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head.event, Some("user-login-event"))
  }

  test("handles ID with special characters") {
    val event = ServerSentEvent(data = "test", id = Some("uuid-123-456-789"))
    val serialized = event.toSSE
    val parsed = ServerSentEvent.parse(serialized).assertSuccess
    assertEquals(parsed.length, 1)
    assertEquals(parsed.head.id, Some("uuid-123-456-789"))
  }

  test("toChunk creates chunk with proper encoding") {
    val event = ServerSentEvent.data("Test")
    val chunk = event.toChunk(Charset.UTF8)
    assertEquals(chunk.asString(Charset.UTF8), "data: Test\n\n")
  }

  // ===== Integration with Response Tests =====

  test("Response.sse creates proper SSE response") {
    val events = List(
      ServerSentEvent.data("Hello"),
      ServerSentEvent.event("update", "World")
    )
    val stream = ServerSentEvent.toChunkStream(events)
    val response = Response.sse(stream).assertSuccess

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(
      response.headers.getFirst(HeaderNames.ContentType).map(_.value),
      Some("text/event-stream")
    )
    assertEquals(
      response.headers.getFirst(HeaderNames.CacheControl).map(_.value),
      Some("no-cache")
    )
    assertEquals(
      response.headers.getFirst(HeaderNames.Connection).map(_.value),
      Some("keep-alive")
    )

    // Verify body is a stream
    response.body match {
      case Body.Stream(_, _, mediaType) =>
        assertEquals(mediaType, Some(MediaType.textEventStream))
      case _ =>
        fail("Expected streaming body")
    }
  }

  test("Response.withSSE adds proper headers") {
    val events = List(ServerSentEvent.data("Test"))
    val stream = ServerSentEvent.toChunkStream(events)
    val base = Response(StatusCode.Ok, Headers.empty, Body.Empty)
    val response = base.withSSE(stream).assertSuccess

    assertEquals(
      response.headers.getFirst(HeaderNames.ContentType).map(_.value),
      Some("text/event-stream")
    )
    assertEquals(
      response.headers.getFirst(HeaderNames.CacheControl).map(_.value),
      Some("no-cache")
    )
    assertEquals(
      response.headers.getFirst(HeaderNames.Connection).map(_.value),
      Some("keep-alive")
    )
  }

  // ===== MediaType Tests =====

  test("MediaType.textEventStream is defined") {
    assertEquals(MediaType.textEventStream.mainType, "text")
    assertEquals(MediaType.textEventStream.subType, "event-stream")
  }

  test("MediaType.textEventStream serializes correctly") {
    assertEquals(MediaType.textEventStream.value, "text/event-stream")
  }
}
