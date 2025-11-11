# eru-http Masterplan

**Mission**: Build a foundational, production-quality HTTP library that demonstrates Eru's capabilities through uncompromising design, extreme quality, and performance.

---

## Philosophy: The Eru Way

### What It Means to Build HTTP with Eru

eru-http is **NOT**:
- A wrapper around existing HTTP libraries
- A convenience layer that "happens to use Eru"
- A compromise between pragmatism and correctness

eru-http **IS**:
- An Eru application built from foundational primitives
- A demonstration of what Eru enables when uncompromised
- A production-quality HTTP implementation with extreme standards

### Core Tenets

1. **Eru-Native Throughout**
   - ALL public APIs return `Eru[E, A]`, never `Either`, `Option`, or throws
   - Effects are the foundation, not an afterthought
   - Validation happens in the effect context

2. **Built from Primitives**
   - Like Eru builds on `Thread.startVirtualThread()`, not CompletableFuture
   - We build on blocking NIO (ServerSocketChannel/SocketChannel), not Netty
   - Direct control over TCP/TLS without event loop complexity
   - Blocking I/O is efficient on Virtual Threads (~10KB per thread)

3. **Type Safety as Foundation**
   - Invalid states unrepresentable at compile time
   - Opaque types for validated values (HeaderName, HeaderValue, Uri, etc.)
   - Parse, don't validate™ - once parsed, it's correct

4. **RFC Compliance Without Compromise**
   - Strict adherence to HTTP RFCs (9110, 9111, 9112, 3986)
   - Validation at boundaries, correctness by construction
   - Semantic properties exposed (Method.isSafe, StatusCode.isRetryable)

5. **Performance Through Design**
   - Eru showed 2-4x improvement over ZIO through control
   - eru-http should demonstrate the same through architecture
   - Zero-cost abstractions via careful effect design

---

## Architectural Principles

### Layer 1: HTTP Primitives (CURRENT FOCUS)

**Goal**: Type-safe, validated HTTP primitives that make invalid states impossible.

**Components**:
- ✅ `Method` - Validated HTTP methods with semantic properties
- ✅ `StatusCode` - HTTP status codes with requirements and cacheability
- ✅ `Port` - Validated TCP/UDP ports with categorization
- ✅ `Uri` - RFC 3986 compliant URIs with validated builders
- ✅ `Headers` - Case-insensitive, multi-value, validated headers
- ✅ `MediaType` - RFC 9110 token validation with parameter handling
- ✅ `Request[A]` - Validated HTTP requests with body type
- ✅ `Response[A]` - Validated HTTP responses with body type
- ⚠️ `Body` - Needs streaming support with Eru effects

**Quality Bar**:
- Every parse method returns `Eru[E, A]`
- Every builder validates inputs
- No throws, no Either, no Option in public API
- Comprehensive tests covering validation and semantics

### Layer 2: HTTP Transport (NEXT PHASE)

**Goal**: High-performance HTTP client and server built on blocking NIO + Virtual Threads with Eru effects.

**New Architecture** (Simplified):

**Client Components**:
- `NativeHttpClient` - Main client interface (~200 lines vs 402 with Netty)
- `HttpRequestParser` - Parse HTTP responses from SocketChannel
- `HttpWriter` - Write HTTP requests to SocketChannel
- `ConnectionPool` - Connection pooling with Eru Ref (structured concurrency)

**Server Components**:
- `NativeHttpServer` - Main server interface (~150 lines vs 332 with Netty)
- `HttpRequestParser` - Parse HTTP requests from SocketChannel
- `HttpWriter` - Write HTTP responses to SocketChannel
- `Router` - Path-based routing with typed extractors (future)
- `Middleware` - Request/response transformation

**Transport Layer** (Simplified):
- Built on blocking NIO (ServerSocketChannel/SocketChannel)
- Each connection on its own Virtual Thread via `.fork`
- Blocking reads/writes (efficient on Virtual Threads)
- No event loops, no callbacks
- Connection pooling using Eru Ref with structured concurrency
- Streaming bodies with Eru effects (future)

**Benefits vs Netty**:
- 50-70% code reduction
- No event loop blocking anti-pattern
- Better alignment with Eru's execution model
- Simpler, more maintainable code

**Quality Bar**:
- All async operations use Eru effects
- Connection lifecycle managed via structured concurrency
- Backpressure handled through Eru streams
- Performance benchmarks vs other Scala HTTP libraries

### Layer 3: High-Level Abstractions (FUTURE)

**Goal**: Developer-friendly abstractions built on solid foundation.

**Components**:
- Body encoding/decoding type classes
- JSON integration (circe, zio-json, etc.)
- WebSocket support
- HTTP/2 and HTTP/3 protocols
- Content negotiation
- Cookie handling
- OAuth/JWT helpers

---

## Current State Assessment

### ✅ What We've Built Correctly

#### 1. Eru-Native Core Types

**Method.scala** - EXCELLENT
```scala
def parse(value: String): Eru[InvalidMethod, Method]  // ✅ Returns Eru
val isSafe: Boolean                                    // ✅ Semantic properties
val allowsRequestBody: Boolean                         // ✅ RFC compliance
```

**StatusCode.scala** - EXCELLENT
```scala
def apply(code: Int): Eru[InvalidStatusCode, StatusCode]  // ✅ Returns Eru
val requiredHeaders: Set[String]                          // ✅ RFC requirements
val isCacheable: Boolean                                  // ✅ Semantic properties
```

**Port.scala** - EXCELLENT
```scala
def apply(value: Int): Eru[InvalidPort, Port]  // ✅ Returns Eru
val isWellKnown: Boolean                        // ✅ Semantic categorization
val requiresPrivileges: Boolean                 // ✅ System-level awareness
```

#### 2. Validation Throughout

**HeaderName/HeaderValue** - EXCELLENT
- Opaque types preventing invalid construction
- RFC 9110 token and field-value validation
- Parse methods returning Eru with specific errors

**Uri Builders** - EXCELLENT
```scala
def withPath(path: String): Eru[InvalidUri, Uri]              // ✅ Validates non-empty
def /(segment: String): Eru[InvalidUri, Uri]                  // ✅ Prevents '/' in segments
def withQueryParam(key: String, value: String): Eru[InvalidUri, Uri]  // ✅ Validates key
```

#### 3. Headers Collection - SOLID

**Multi-value support** - CORRECT
- TreeMap with CIString for case-insensitivity
- Preserves original casing for transmission
- Multiple values per header name

**Eru-native operations** - CORRECT
```scala
def add(name: String, value: String): Eru[InvalidHeaderName | InvalidHeaderValue, Headers]
def contentType: Eru[InvalidMediaType, Option[MediaType]]  // ✅ Parsed accessor
def accept: Eru[InvalidMediaType, List[MediaType]]         // ✅ Batch parsing
```

#### 4. Request/Response - GOOD FOUNDATION

**Validation** - CORRECT
```scala
def validate: Eru[InvalidRequest, Request[A]]  // ✅ Returns Eru
- Checks method/body combination
- Validates required headers for HTTP version
- Checks forbidden header combinations
```

**Type Safety** - CORRECT
- Generic body type `Request[A]`
- Immutable by default
- Builders return new instances

### ⚠️ What Needs Improvement

#### 1. Body Types - INCOMPLETE

**Current State**:
```scala
sealed trait Body
case object EmptyBody extends Body
final case class StringBody(value: String, mediaType: Option[MediaType]) extends Body
```

**Problems**:
- No streaming support
- No integration with Eru effects
- Missing common body types (JSON, form data, multipart)
- No backpressure handling

**Solution Needed**:
```scala
sealed trait Body[F[_]]
case object EmptyBody extends Body[Nothing]
final case class StringBody(value: String, mediaType: Option[MediaType]) extends Body[String]
final case class StreamBody[A](
  stream: Eru[Nothing, Stream[Byte]],  // Eru streams with backpressure
  mediaType: Option[MediaType],
  contentLength: Option[Long]
) extends Body[Stream[Byte]]

// Type class for encoding
trait BodyEncoder[A] {
  def encode(value: A, mediaType: MediaType): Eru[EncodeError, Body[?]]
}

// Type class for decoding
trait BodyDecoder[A] {
  def decode(body: Body[?]): Eru[DecodeError, A]
}
```

#### 2. Uri Parsing - PLACEHOLDER

**Current State**:
```scala
def parse(uri: String): Eru[InvalidUri, Uri] = {
  Eru.effect {
    val url = new java.net.URI(uri)  // ⚠️ Using Java's parser
    // ...
  }.mapError { case e: Throwable => InvalidUri(uri, ...) }
}
```

**Problems**:
- Delegates to java.net.URI (not Eru-native)
- Doesn't validate according to RFC 3986 precisely
- Throws exceptions internally

**Solution Needed**:
- Proper RFC 3986 parser written in Eru
- Character-by-character validation
- Precise error messages with position information

#### 3. MediaType Parameter Validation - INCOMPLETE

**Current State**:
```scala
private def parseParameters(params: List[String]): Eru[InvalidMediaType, Map[String, String]] = {
  // Parses but doesn't validate parameter values properly
  // Should handle quoted-string vs token distinction
}
```

**Solution Needed**:
- Distinguish between token and quoted-string parameter values
- Proper quote escaping handling
- RFC 9110 compliant parameter parsing

#### 4. Error Types - COULD BE BETTER

**Current State**:
- Each error extends Exception
- Good: includes context (value, reason, RFC reference)
- Missing: structured error ADT for pattern matching

**Enhancement**:
```scala
sealed trait HttpError
object HttpError {
  final case class InvalidMethod(method: String, reason: String) extends HttpError
  final case class InvalidUri(uri: String, reason: String, position: Option[Int]) extends HttpError
  final case class NetworkError(message: String, cause: Option[Throwable]) extends HttpError
  final case class TimeoutError(duration: Duration) extends HttpError
  // etc.
}
```

---

## Foundation Quality Checklist

Before building client/server, verify:

### ✅ Eru-Native Design
- [x] All parse methods return `Eru[E, A]`
- [x] All builders validate and return `Eru[E, A]`
- [x] No throws in public API
- [x] No Either/Option in public API
- [x] Effect composition via for-comprehensions

### ✅ Type Safety
- [x] Opaque types for validated values
- [x] Invalid states unrepresentable
- [x] Generic body types
- [x] Immutable data structures

### ⚠️ Completeness (Needs Work)
- [x] Basic body types
- [ ] Streaming body support with Eru
- [ ] Pure Eru URI parser (currently uses java.net.URI)
- [ ] Complete MediaType parameter validation
- [ ] Body encoder/decoder type classes

### ✅ RFC Compliance
- [x] Method semantics (safe, idempotent, cacheable)
- [x] StatusCode requirements and properties
- [x] Header name/value validation (RFC 9110)
- [x] URI structure (using java.net.URI for now)
- [x] MediaType token validation

### ✅ Testing
- [x] 24/24 tests passing
- [x] Validation success cases
- [x] Validation failure cases
- [x] Eru effect composition patterns
- [ ] Property-based tests for parsers
- [ ] Round-trip tests (encode/decode)

### ✅ Documentation
- [x] IMPLEMENTATION-LOG.md tracking all changes
- [x] PROGRESS-REVIEW.md with strategic analysis
- [x] Code comments explaining RFC compliance
- [ ] API documentation with examples
- [ ] Architecture decision records

---

## The Path Forward

### Phase 1: Solidify Foundation (1-2 sessions)

**Priority 1: Complete Body Types**
```scala
// Goal: Streaming bodies with Eru effects
trait StreamBody {
  def chunks: Eru[Nothing, Stream[Chunk[Byte]]]
  def contentLength: Option[Long]
  def mediaType: Option[MediaType]
}
```

**Priority 2: Pure Eru URI Parser**
```scala
// Goal: RFC 3986 compliant parser built with Eru
object UriParser {
  def parse(input: String): Eru[InvalidUri, Uri] = {
    // Character-by-character parsing
    // Precise error positions
    // No delegation to java.net.URI
  }
}
```

**Priority 3: Complete MediaType Validation**
```scala
// Goal: Proper quoted-string parameter handling
private def parseParameterValue(value: String): Eru[InvalidMediaType, String] = {
  if (isToken(value)) Eru.succeed(value)
  else if (isQuotedString(value)) parseQuotedString(value)
  else Eru.fail(...)
}
```

### Phase 2: HTTP Client with Blocking NIO (3-4 sessions)

**Architecture** (Simplified):
```
NativeHttpClient
    ↓
ConnectionPool (Eru Ref for pooling)
    ↓
SocketChannel (blocking I/O)
    ↓
Virtual Threads (via Eru .fork)
```

**Components to Build**:

1. **HTTP Parser** (1 session)
   - Request/Response line parsing
   - Header parsing
   - Body parsing (fixed-length, chunked)
   - RFC 9110 compliant

2. **HTTP Writer** (1 session)
   - Request/Response serialization
   - Header formatting
   - Body writing (with chunking)

3. **Native HTTP Client** (1 session)
   - Basic client implementation (~200 lines)
   - Request execution (blocking I/O on VT)
   - Error handling

4. **Connection Pooling** (1 session)
   - Pool with Eru Ref for state
   - Connection acquisition/release via structured concurrency
   - Connection validation and eviction
   - Max connections, timeouts

5. **TLS Support** (Optional - can defer)
   - SSLEngine wrapping SocketChannel
   - Certificate validation
   - Hostname verification

**Success Criteria**:
```scala
// This should work:
val client = EruHttpClient.make.unsafeRunSync()

val request = Request.get(Uri.https("api.github.com", None, "/users/octocat"))

val response: Response[String] = (for {
  resp <- client.execute(request)
  validated <- resp.validate
} yield validated).unsafeRunSync()

println(response.status)  // StatusCode.Ok
println(response.body)    // JSON response
```

### Phase 3: HTTP Server with Blocking NIO (2-3 sessions)

**Architecture** (Simplified):
```
NativeHttpServer
    ↓
ServerSocketChannel (accept loop on VT)
    ↓
Handler[Req, Resp] (user's request processing)
    ↓
SocketChannel per client (each on own VT)
```

**Components to Build**:

1. **Server Foundation** (1 session)
   - ServerSocketChannel setup (~150 lines)
   - Accept loop (blocking on VT)
   - Per-client handler (each on own VT via .fork)
   - Structured concurrency for cleanup

2. **Router** (1 session)
   - Path pattern matching
   - Parameter extraction
   - Typed route handlers

3. **Middleware** (1 session)
   - Composable middleware
   - Common middleware (logging, CORS, etc.)
   - Error handling

**Success Criteria**:
```scala
// This should work:
val routes = Router(
  Route.get("/users/:id") { req =>
    val userId = req.pathParam("id")
    Eru.succeed(Response.ok(s"User $userId"))
  },
  Route.post("/users") { req =>
    for {
      user <- req.decodeJson[User]
      saved <- UserService.save(user)
      resp <- Response.created(Uri.https("api.example.com", None, s"/users/${saved.id}"), saved)
    } yield resp
  }
)

val server = EruHttpServer.make(routes, port = Port(8080).unsafeRunSync())

server.start.unsafeRunSync()  // Blocks until shutdown
```

### Phase 4: Enhancements (Ongoing)

- JSON integration (circe, zio-json)
- HTTP/2 support (Netty HTTP/2 codec)
- WebSocket support
- Performance benchmarks
- Cross-platform (when Eru supports Native/JS)

---

## Success Metrics

### Foundation Quality
- [ ] 100% of public APIs return Eru
- [ ] 0 throws in public API surface
- [ ] All parsers are pure Eru (no java.net.URI)
- [ ] Streaming bodies with Eru effects
- [ ] Property-based tests for all parsers

### Performance (vs Other Scala HTTP Libraries)
- [ ] Client request/response: Match or exceed http4s
- [ ] Connection pooling: Efficient reuse, low overhead
- [ ] Concurrent requests: Scale to 10k+ concurrent with VTs
- [ ] Memory usage: Constant memory for streaming

### Production Readiness
- [ ] Comprehensive error handling
- [ ] Resource cleanup guaranteed (structured concurrency)
- [ ] Graceful shutdown
- [ ] Observability (metrics, logging)
- [ ] Security (TLS, certificate validation)

### Developer Experience
- [ ] Clear error messages with context
- [ ] Composable API (builders, combinators)
- [ ] Comprehensive documentation
- [ ] Examples for common use cases
- [ ] Migration guide from other libraries

---

## Key Decisions

### Decision 1: Use Blocking NIO + Virtual Threads (NOT Netty)
**Rationale**:
- Eru's Virtual Thread backend makes blocking I/O efficient (~10KB per thread vs ~2MB for OS threads)
- Current Netty implementation has anti-pattern: `unsafeRunSync()` blocks event loop
- POC demonstrates 70% code reduction (from ~313 lines to ~100 lines)
- Simpler code, better alignment with Eru's execution model
- No event loops, no callbacks, pure Eru effects

**Trade-off**:
- Need to implement HTTP parser (vs using Netty codecs)
- TLS requires manual SSLEngine setup
- But gains: simplicity, maintainability, performance

**Previous Decision (Wrong)**: "Use Netty for TCP/TLS" - This was based on not understanding Eru's Virtual Thread backend. Netty's async model conflicts with Eru's effect system.

### Decision 2: Streaming with Eru Effects
**Rationale**: Consistent with Eru philosophy, enables backpressure, composes with other Eru operations.

**Trade-off**: Need to design Eru streaming API carefully. May start simple (Iterator-like) and evolve.

### Decision 3: Pure Eru URI Parser
**Rationale**: RFC 3986 compliance, better error messages, no throws, full control.

**Trade-off**: More work than using java.net.URI, but worth it for quality and Eru-native design.

### Decision 4: Type Classes for Body Encoding
**Rationale**: Extensible, allows user-defined encoders, separates serialization from HTTP.

**Trade-off**: Requires understanding of type classes, but this is standard Scala practice.

---

## Anti-Patterns to Avoid

### ❌ Don't Wrap Existing HTTP Clients
```scala
// WRONG: This is not Eru-native
class EruHttpClient(underlying: java.net.http.HttpClient) {
  def execute[A](req: Request[A]): Eru[HttpError, Response[A]] = {
    Eru.effect {
      val javaReq = convert(req)
      val javaResp = underlying.send(javaReq, BodyHandlers.ofString())
      convert(javaResp)
    }
  }
}
```

**Why Wrong**: We're building HTTP, not wrapping it. This is the Eru way.

### ❌ Don't Use Either/Option in Public API
```scala
// WRONG: Not Eru-native
def parse(uri: String): Either[InvalidUri, Uri]  // ❌
def contentType: Option[MediaType]                // ❌

// RIGHT: Eru all the way
def parse(uri: String): Eru[InvalidUri, Uri]                    // ✅
def contentType: Eru[InvalidMediaType, Option[MediaType]]       // ✅
```

### ❌ Don't Compromise on Validation
```scala
// WRONG: Trusting input
def unsafeFromString(s: String): HeaderName = s  // ❌

// RIGHT: Always validate
def parse(s: String): Eru[InvalidHeaderName, HeaderName] = {  // ✅
  if (isValidToken(s)) Eru.succeed(s)
  else Eru.fail(InvalidHeaderName(s, "must be valid token"))
}
```

### ❌ Don't Sacrifice Performance for Convenience
```scala
// WRONG: Creating intermediate collections
def headers: List[(String, String)] =
  rawHeaders.map(convertHeader).toList  // ❌ Allocations

// RIGHT: Lazy evaluation, minimal allocations
def headers: Iterator[(String, String)] =  // ✅ Stream
  rawHeaders.iterator.map(convertHeader)
```

---

## Conclusion

eru-http is being built the **Eru way**: from foundational primitives, with uncompromising quality, demonstrating what Eru enables when we don't compromise.

**Current foundation**: SOLID (24/24 tests, Eru-native, type-safe)
**Needs before client**: Streaming bodies, pure URI parser, complete validation
**Path forward**: Netty-based client/server with Eru effects throughout

This is not the "fast path" - it's the **correct path**. And given that you built both Valar and Eru to production quality in 6 months, building eru-http properly is absolutely achievable.

Let's build HTTP the way it should be built.
