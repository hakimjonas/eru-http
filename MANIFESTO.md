# eru-http Manifesto

*A standards-compliant HTTP implementation built on Eru's foundation of correctness*

## Core Principles

eru-http inherits and extends the Eru manifesto's four pillars, applying them specifically to HTTP:

### 1. Standards-First Correctness

**We implement HTTP as specified, not as convenient.**

- Every implementation decision traces back to an RFC
- Type signatures encode HTTP semantics precisely
- Invalid HTTP states are unrepresentable at compile time
- Error messages reference specific RFC sections
- No shortcuts that violate standards

**Example**: A `StatusCode` is not just an `Int`. It's an opaque type with smart constructors that ensure only valid HTTP status codes can exist, with methods that reflect their semantic meaning (idempotent, cacheable, safe).

### 2. Type-Driven Development

**The type system teaches correct HTTP.**

- Types guide users toward standards-compliant implementations
- Illegal operations fail at compile time, not runtime
- Rich types over primitive obsession
- Compiler errors that educate about HTTP semantics

**Example**: Headers aren't `Map[String, String]`. They're a type-safe structure that handles:
- Case-insensitive names correctly
- Multi-value headers per RFC 9110
- Header-specific validation (Content-Length must be numeric)
- Forbidden header combinations

### 3. Minimal and Composable

**Do HTTP exceptionally well, nothing more.**

- No routing (that's a framework concern)
- No JSON assumptions (HTTP is content-agnostic)
- No authentication schemes (separate module)
- No templating (application layer)
- No sessions (framework layer)

**What we DO provide**:
- Request/Response models
- Client and Server implementations
- Connection management
- Content negotiation
- Caching directives
- Conditional requests
- Range requests
- WebSocket upgrade

### 4. Effect-Native Design

**Built on Eru from the ground up.**

- All I/O operations return `Eru[E, A]`
- Proper resource management via bracket/ensure
- Streaming with backpressure
- Virtual Thread concurrency on JVM
- Interruption and timeout support
- No `Future`, `IO`, or `Task` conversions

## Design Decisions

### What Makes a Valid HTTP Implementation?

1. **Semantic Precision**:
   - `GET` requests MUST be safe and idempotent
   - `201 Created` MUST include a `Location` header
   - `Transfer-Encoding` and `Content-Length` are mutually exclusive

2. **Error Transparency**:
   - Network errors vs protocol errors vs application errors
   - Typed error channels: `Eru[HttpError | NetworkError, Response[A]]`
   - Detailed error context for debugging

3. **Resource Safety**:
   - Connections are properly pooled and recycled
   - Request/Response bodies are streaming-first
   - Automatic cleanup on cancellation
   - Backpressure throughout

4. **Performance Through Simplicity**:
   - Virtual Threads eliminate callback complexity
   - Zero-copy where possible
   - Minimal allocations
   - Direct buffers for I/O

### What We Explicitly Reject

1. **Convenience Over Correctness**:
   - No automatic JSON serialization in core
   - No implicit conversions that hide semantics
   - No "smart" defaults that violate RFCs

2. **Framework Responsibilities**:
   - No routing DSL
   - No dependency injection
   - No configuration management

3. **Legacy Compatibility**:
   - No HTTP/1.0 support (HTTP/1.1 minimum)
   - No deprecated headers
   - No quirks mode for broken clients

## Implementation Standards

### RFCs We Implement

- **RFC 9110**: HTTP Semantics (core)
- **RFC 9111**: HTTP Caching
- **RFC 9112**: HTTP/1.1
- **RFC 9113**: HTTP/2
- **RFC 8999**: HTTP/3 (future)
- **RFC 6455**: WebSocket Protocol
- **RFC 7541**: HPACK Header Compression

### Type Hierarchy

```scala
// Not this:
type Headers = Map[String, String]
type StatusCode = Int
type Method = String

// But this:
opaque type Method = Method.Value
opaque type StatusCode = StatusCode.Value
opaque type HeaderName = CIString
opaque type HeaderValue = HeaderValue.Value

// With smart constructors that validate:
object Method {
  val GET: Method = Method("GET")     // Safe and idempotent
  val POST: Method = Method("POST")   // Neither safe nor idempotent

  def apply(value: String): Either[InvalidMethod, Method] =
    // Validate per RFC 9110 Section 9
}
```

### Error Model

```scala
enum HttpError {
  case InvalidMethod(error: Method.InvalidMethod)
  case InvalidStatusCode(error: StatusCode.InvalidStatusCode)
  case InvalidUri(error: Uri.InvalidUri)
  case InvalidMediaType(error: MediaType.InvalidMediaType)
  case InvalidRequest(error: http.InvalidRequest)
  case InvalidResponse(error: http.InvalidResponse)
  case BodyEncodeError(error: http.EncodeError)
  case BodyDecodeError(error: http.DecodeError)
  case InvalidCookie(error: Cookie.InvalidCookie)
  case NetworkError(msg: String, cause: Option[Throwable] = None)
  case TimeoutError(msg: String)
  case ConnectionError(msg: String, cause: Option[Throwable] = None)
  case ProtocolError(msg: String, rfc: String)

  def message: String = this match { ... }
  def toException: Exception = this match { ... }
}
```

Errors are pure data — they never extend `Exception`. The `message` method provides human-readable descriptions, and `toException` creates a JVM `Exception` only at interop boundaries.

## Usage Examples

### The Right Way

```scala
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

// Type-safe request construction
val request = for {
  uri <- Uri.parse("https://api.example.com/users")
  req <- Request.get(uri)
    .addHeader(Header.Accept, MediaType.json)
    .addHeader(Header.UserAgent, "eru-http/0.1.0")
} yield req

// Execute with proper resource management
val response: Eru[HttpError, Response[Json]] =
  client.send(request)(using JsonDecoder)

// Handle all error cases
response.flatMap {
  case Response(StatusCode.Ok, _, body) =>
    Eru.succeed(body)
  case Response(StatusCode.NotFound, _, _) =>
    Eru.fail(UserNotFound)
  case Response(status, _, _) if status.isClientError =>
    Eru.fail(ClientError(status))
  case Response(status, _, _) if status.isServerError =>
    // Retry on server errors
    client.send(request).retry(exponentialBackoff)
}
```

### What We Prevent

```scala
// This won't compile - invalid status code
val response = Response(StatusCode(999), ...)  // Compile error!

// This won't compile - mutually exclusive headers
val request = Request.post(uri)
  .addHeader(Header.ContentLength, "100")
  .addHeader(Header.TransferEncoding, "chunked")  // Compile error!

// This won't compile - GET with body
Request.get(uri).withBody(data)  // Compile error!
```

## Testing Philosophy

1. **Property-Based Testing**: Every RFC requirement has a property test
2. **Compliance Suite**: Automated tests against RFC test vectors
3. **Fuzzing**: Invalid input handling
4. **Performance Benchmarks**: Track allocations and latency

## Versioning and Compatibility

- Semantic versioning with strict guarantees
- Breaking changes only in major versions
- Binary compatibility within minor versions
- Deprecated features removed only in major versions

## Community Standards

- Issues must reference RFC sections when claiming non-compliance
- PRs must include tests demonstrating RFC compliance
- Performance regressions must be justified by correctness
- API changes require migration guides

## The eru-http Promise

When you use eru-http, you're not just getting an HTTP library. You're getting:

1. **Education**: Our types and errors teach you HTTP
2. **Correctness**: RFC-compliant by construction
3. **Safety**: Resource leaks are impossible
4. **Performance**: Virtual Threads and zero-copy where possible
5. **Debugging**: Clear errors that reference specifications
6. **Composition**: Clean integration with Eru ecosystem

We don't aim to be the most convenient HTTP library. We aim to be the most correct, and through that correctness, to be a foundation you can trust absolutely.

## Final Word

> "HTTP is a protocol, not a suggestion. Implement it correctly or not at all."

eru-http exists to prove that we can have both correctness and ergonomics, both standards compliance and performance, both safety and simplicity. We achieve this not by adding features, but by modeling the domain precisely and letting the type system do the work.

This is HTTP as it should be.