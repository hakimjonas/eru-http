package net.ghoula.eru.http

import net.ghoula.eru.*

/** A Server-Sent Event as defined in the HTML5/WHATWG EventSource specification.
  *
  * Server-Sent Events (SSE) provide a standard way to push real-time updates from server to client
  * over HTTP. The SSE format is a simple text-based protocol where events are separated by blank
  * lines.
  *
  * Each event can have the following fields:
  *   - data: The payload of the event (can span multiple lines)
  *   - event: The event type name (optional, defaults to "message")
  *   - id: A unique identifier for the event (optional, used for reconnection)
  *   - retry: Reconnection time in milliseconds (optional)
  *
  * @param data
  *   the event payload
  * @param id
  *   optional unique identifier for this event
  * @param event
  *   optional event type name
  * @param retry
  *   optional reconnection time in milliseconds
  *
  * @see
  *   [[https://html.spec.whatwg.org/multipage/server-sent-events.html WHATWG Server-Sent Events]]
  */
final case class ServerSentEvent(
  data: String,
  id: Option[String] = None,
  event: Option[String] = None,
  retry: Option[Int] = None
) {

  /** Serializes this event to SSE text format.
    *
    * The format follows the WHATWG specification:
    *   - Each field on its own line: "field: value\n"
    *   - Multi-line data is split into multiple "data:" fields
    *   - Event ends with blank line "\n"
    *   - Line endings are "\n" (not "\r\n")
    *
    * Example output:
    * {{{
    * event: update
    * data: First line
    * data: Second line
    * id: 42
    * retry: 5000
    *
    * }}}
    *
    * @return
    *   the SSE-formatted string
    */
  def toSSE: String = {
    val builder = new StringBuilder

    // Event type (optional)
    event.foreach { e =>
      builder.append("event: ").append(e).append('\n')
    }

    // Data (can be multi-line)
    if data.isEmpty then {
      // Empty data is still valid
      builder.append("data: \n")
    } else {
      // Split by newlines and emit separate data: fields
      val lines = data.split("\n", -1) // -1 to keep trailing empty strings
      lines.foreach { line =>
        builder.append("data: ").append(line).append('\n')
      }
    }

    // ID (optional)
    id.foreach { i =>
      builder.append("id: ").append(i).append('\n')
    }

    // Retry (optional)
    retry.foreach { r =>
      builder.append("retry: ").append(r).append('\n')
    }

    // Blank line to mark end of event
    builder.append('\n')

    builder.toString
  }

  /** Converts this event to a chunk for streaming.
    *
    * @param charset
    *   the character encoding to use (defaults to UTF-8)
    * @return
    *   a Chunk containing the SSE-formatted event
    */
  def toChunk(charset: Charset = Charset.UTF8): Chunk = {
    Chunk.fromString(toSSE, charset)
  }
}

object ServerSentEvent {

  /** Parses SSE events from text stream.
    *
    * The parser handles the SSE text format per WHATWG spec:
    *   - Lines ending with \n
    *   - Field format: "name: value"
    *   - Comments start with ":"
    *   - Blank lines dispatch events
    *   - Multiple "data:" fields are concatenated with \n
    *   - Unknown fields are ignored
    *
    * @param text
    *   the SSE text to parse
    * @return
    *   a list of parsed events or a ParseError
    */
  def parse(text: String): Eru[ParseError, List[ServerSentEvent]] = {
    val events = scala.collection.mutable.ListBuffer[ServerSentEvent]()
    val currentData = scala.collection.mutable.ListBuffer[String]()
    var currentEvent: Option[String] = None
    var currentId: Option[String] = None
    var currentRetry: Option[Int] = None
    var error: Option[ParseError] = None

    // Helper to dispatch the current event
    def dispatchEvent(): Unit = {
      if currentData.nonEmpty || currentEvent.isDefined || currentId.isDefined || currentRetry.isDefined then {
        val data = if currentData.isEmpty then "" else currentData.mkString("\n")
        events += ServerSentEvent(
          data = data,
          id = currentId,
          event = currentEvent,
          retry = currentRetry
        )
        // Reset for next event
        currentData.clear()
        currentEvent = None
        currentId = None
        currentRetry = None
      }
    }

    // Split by \n and process each line
    val lines = text.split("\n", -1)
    var lineNum = 0

    var i = 0
    while i < lines.length && error.isEmpty do {
      val line = lines(i)
      lineNum += 1

      if line.isEmpty then {
        // Blank line dispatches event
        dispatchEvent()
      } else if line.startsWith(":") then {
        // Comment - ignore
        ()
      } else {
        // Parse field
        val colonIndex = line.indexOf(':')
        if colonIndex >= 0 then {
          val fieldName = line.substring(0, colonIndex)
          // Value starts after colon, optionally skipping one space
          val valueStart = colonIndex + 1
          val fieldValue = if valueStart < line.length && line.charAt(valueStart) == ' ' then {
            line.substring(valueStart + 1)
          } else if valueStart < line.length then {
            line.substring(valueStart)
          } else {
            ""
          }

          fieldName match {
            case "data" =>
              currentData += fieldValue

            case "event" =>
              currentEvent = Some(fieldValue)

            case "id" =>
              // ID must not contain null character
              if fieldValue.contains('\u0000') then {
                error = Some(
                  ParseError(
                    line,
                    s"ID field cannot contain null character (line $lineNum)"
                  )
                )
              } else {
                currentId = Some(fieldValue)
              }

            case "retry" =>
              // Retry must be a valid integer
              try {
                val retryValue = fieldValue.toInt
                if retryValue >= 0 then {
                  currentRetry = Some(retryValue)
                }
                // Negative values are ignored per spec
              } catch {
                case _: NumberFormatException =>
                  // Invalid retry values are ignored per spec
                  ()
              }

            case _ =>
              // Unknown field names are ignored per spec
              ()
          }
        }
        // Lines without colons are ignored per spec
      }

      i += 1
    }

    // Check for errors
    error match {
      case Some(err) => Eru.fail(err)
      case None =>
        // Dispatch any remaining event (in case text doesn't end with blank line)
        dispatchEvent()
        Eru.succeed(events.toList)
    }
  }

  /** Creates a simple event with just data.
    *
    * @param text
    *   the event data
    * @return
    *   a ServerSentEvent with only data field set
    */
  def data(text: String): ServerSentEvent = {
    ServerSentEvent(data = text)
  }

  /** Creates a named event with data.
    *
    * @param name
    *   the event type name
    * @param data
    *   the event data
    * @return
    *   a ServerSentEvent with event and data fields set
    */
  def event(name: String, data: String): ServerSentEvent = {
    ServerSentEvent(data = data, event = Some(name))
  }

  /** Creates an SSE comment line (used for keep-alive).
    *
    * Comments are lines starting with ":" and are ignored by clients. They're commonly used as
    * heartbeat/keep-alive messages to prevent connection timeouts.
    *
    * @param text
    *   the comment text
    * @return
    *   the formatted comment line
    */
  def comment(text: String): String = {
    s":$text\n"
  }

  /** Converts a list of events to a ChunkStream for streaming.
    *
    * Each event is serialized to SSE format and emitted as a separate chunk. This is useful for
    * creating SSE response bodies.
    *
    * @param events
    *   the events to stream
    * @param charset
    *   the character encoding (defaults to UTF-8)
    * @return
    *   a ChunkStream of SSE-formatted events
    */
  def toChunkStream(events: List[ServerSentEvent], charset: Charset = Charset.UTF8): ChunkStream = {
    events match {
      case Nil => ChunkStream.empty
      case head :: tail =>
        val headChunk = head.toChunk(charset)
        val tailStream = toChunkStream(tail, charset)
        ChunkStream.fromChunks(headChunk) ++ tailStream
    }
  }

  /** Converts a stream of values to SSE ChunkStream using an encoder.
    *
    * Each value is encoded to a string and wrapped in an SSE event.
    *
    * @param stream
    *   the stream of values to convert
    * @param serialize
    *   function to convert each value to a ServerSentEvent
    * @param charset
    *   the character encoding (defaults to UTF-8)
    * @tparam E
    *   the error type
    * @tparam A
    *   the value type
    * @return
    *   a ChunkStream of SSE-formatted events
    */
  def fromStream[E, A](
    stream: Eru[E, List[A]],
    serialize: A => ServerSentEvent,
    charset: Charset = Charset.UTF8
  ): Eru[E, ChunkStream] = {
    stream.map { values =>
      toChunkStream(values.map(serialize), charset)
    }
  }

  /** Parses SSE events from a ChunkStream.
    *
    * Consumes the entire stream, concatenates all chunks, and parses the result as SSE text.
    *
    * Note: This loads the entire stream into memory. For very large streams, consider implementing
    * incremental parsing.
    *
    * @param stream
    *   the chunk stream to parse
    * @param charset
    *   the character encoding (defaults to UTF-8)
    * @return
    *   an Eru effect producing the list of parsed events or a ParseError
    */
  def parseStream(stream: ChunkStream, charset: Charset = Charset.UTF8): Eru[ParseError, List[ServerSentEvent]] = {
    stream.toBytes.flatMap { bytes =>
      val text = bytes.asString(charset)
      parse(text)
    }
  }

  /** Creates a ChunkStream from a comment (for keep-alive).
    *
    * @param text
    *   the comment text
    * @param charset
    *   the character encoding (defaults to UTF-8)
    * @return
    *   a ChunkStream containing the comment
    */
  def commentStream(text: String, charset: Charset = Charset.UTF8): ChunkStream = {
    ChunkStream.single(Chunk.fromString(comment(text), charset))
  }
}
