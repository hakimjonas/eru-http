# eru-http Architecture Fix: Eru-First Design + Virtual Threads

## The Fundamental Problems

### Problem 1: Not Eru-Native

We built eru-http like a traditional library that *happens* to use Eru, when it should be an **Eru application** through and through. This is a critical architectural misunderstanding.

### Problem 2: Netty is Unnecessary Complexity

**Key Insight**: Eru's Virtual Thread backend makes Netty unnecessary. The current Netty implementation has an anti-pattern where `unsafeRunSync()` blocks the event loop (NettyHttpServer.scala:186), defeating Netty's async design.

**Evidence from ERU_STRUCTURED_CONCURRENCY_REFERENCE.md**:
- Eru uses `Thread.startVirtualThread` for fork operations
- Blocking I/O is efficient on Virtual Threads (~10KB per thread vs ~2MB for OS threads)
- Virtual Threads enable 100K+ concurrent connections
- Structured concurrency provides automatic cleanup

**Proof of Concept**: PROOF_OF_CONCEPT_BLOCKING_NIO.scala demonstrates 70% code reduction (from ~313 lines to ~100 lines) using blocking NIO + Virtual Threads.

## Current (WRONG) Architecture

```scala
// ❌ WRONG: Either-first with Eru as an afterthought
def parse(uri: String): Either[InvalidUri, Uri] = ???
def parseEru(uri: String): Eru[InvalidUri, Uri] = Eru.fromEither(parse(uri))

// ❌ WRONG: Pure functions with Eru extensions
final case class Request[A](...) {
  def validate: Either[InvalidRequest, Request[A]] = ???
}
// Then in package object...
extension [E, A](eru: Eru[E, Request[A]]) {
  def validateRequest: Eru[E | HttpError, Request[A]] = ???
}
```

## Correct (Eru-First) Architecture

```scala
// ✅ CORRECT: Eru is the ONLY API
object Uri {
  def parse(uri: String): Eru[InvalidUri, Uri] = {
    // Validation logic directly returns Eru
    if isValid(uri) then Eru.succeed(parseValid(uri))
    else Eru.fail(InvalidUri(uri, "..."))
  }
}

// ✅ CORRECT: All operations are effects
final case class Request[A](...) {
  def validate: Eru[InvalidRequest, Request[A]] = {
    for {
      _ <- validateMethodBodyCombination
      _ <- validateRequiredHeaders
      _ <- validateForbiddenHeaderCombinations
    } yield this
  }

  private def validateMethodBodyCombination: Eru[InvalidRequest, Unit] = {
    if !method.allowsRequestBody && body != EmptyBody then
      Eru.fail(InvalidRequest(s"Method ${method.value} does not allow a request body", "RFC 9110 Section 9"))
    else
      Eru.unit
  }
}

// ✅ CORRECT: Constructors return effects
object Headers {
  def empty: Headers = Headers(TreeMap.empty)

  def add(headers: Headers, name: String, value: String): Eru[InvalidHeader, Headers] = {
    for {
      headerName <- HeaderName.parse(name)
      headerValue <- HeaderValue.parse(value)
    } yield headers.unsafeAdd(headerName, headerValue)
  }
}
```

## Complete Redesign Needed

### 1. All Parsing/Validation Returns Eru

```scala
// BEFORE (Wrong)
object Method {
  def parse(value: String): Either[InvalidMethod, Method] = ???
}

// AFTER (Correct)
object Method {
  def parse(value: String): Eru[InvalidMethod, Method] = {
    if isValidToken(value) then
      Eru.succeed(value)
    else
      Eru.fail(InvalidMethod(value, "RFC 9110 Section 9.1: Method must be a valid token"))
  }

  // For constants, we can have pure values
  val GET: Method = "GET"  // These are pre-validated
  val POST: Method = "POST"
}
```

### 2. Builders Return Eru

```scala
// BEFORE (Wrong)
def addHeader(name: String, value: String): Request[A] =
  copy(headers = headers.add(name, value))

// AFTER (Correct)
def addHeader(name: String, value: String): Eru[InvalidHeader, Request[A]] = {
  for {
    newHeaders <- headers.add(name, value)
  } yield copy(headers = newHeaders)
}

// Or for a fluent API:
def withHeader(name: String, value: String): Eru[InvalidHeader, Request[A]] = {
  Headers.add(headers, name, value).map(h => copy(headers = h))
}
```

### 3. Smart Constructors Are Effects

```scala
// BEFORE (Wrong)
object Request {
  def get(uri: Uri): Request[EmptyBody] =
    Request(Method.GET, uri, Headers.empty, EmptyBody)
}

// AFTER (Correct)
object Request {
  def get(uri: String): Eru[InvalidUri, Request[EmptyBody]] = {
    for {
      parsedUri <- Uri.parse(uri)
    } yield Request(Method.GET, parsedUri, Headers.empty, EmptyBody)
  }

  // Pre-validated version for when you already have a Uri
  def getValid(uri: Uri): Request[EmptyBody] =
    Request(Method.GET, uri, Headers.empty, EmptyBody)
}
```

### 4. Effect-Native Validation

```scala
// BEFORE (Wrong)
trait Validator[A] {
  def validate(a: A): Either[ValidationError, A]
}

// AFTER (Correct)
trait Validator[A] {
  def validate(a: A): Eru[ValidationError, A]
}

// HTTP-specific validation
object HttpValidation {
  def validateRequest[A](req: Request[A]): Eru[HttpError, Request[A]] = {
    for {
      _ <- validateMethod(req.method)
      _ <- validateHeaders(req.headers)
      _ <- validateUri(req.uri)
      validatedReq <- req.validate
    } yield validatedReq
  }
}
```

### 5. Composition Through Effects

```scala
// Building a request becomes an effect chain
val request: Eru[HttpError, Request[String]] = for {
  uri <- Uri.parse("https://api.example.com/users")
  baseReq = Request.getValid(uri)
  reqWithAuth <- baseReq.withHeader("Authorization", "Bearer token")
  reqWithContent <- reqWithAuth.withHeader("Content-Type", "application/json")
  body = """{"name": "John"}"""
  finalReq = reqWithContent.withBody(body)
  validated <- finalReq.validate
} yield validated

// Or with error accumulation
val headers: Eru[InvalidHeader, Headers] = for {
  h1 <- Headers.empty.add("Content-Type", "application/json")
  h2 <- h1.add("Accept", "application/json")
  h3 <- h2.add("User-Agent", "eru-http/0.1.0")
} yield h3
```

## Why This Matters

### 1. Consistency with Eru Ecosystem
- Everything is an effect
- Errors flow through the Eru error channel
- Composability through monadic operations

### 2. Type Safety
- Invalid states caught at effect boundaries
- No surprise exceptions
- Clear error propagation

### 3. Following the Manifesto
- "Effect-native design" - not effects as an afterthought
- "Guided correctness" - the API guides users to correct usage
- "Foundational correctness" - validation happens in effects

## Migration Strategy

### Phase 1: Core Types (Immediate)
1. Change all `parse` methods to return `Eru[E, A]`
2. Remove all `Either` returns except for internal helpers
3. Make validation methods return Eru

### Phase 2: Builders (Next)
1. Make all builder methods return Eru when validation needed
2. Provide `unsafeX` methods that throw for testing/constants
3. Pre-validated constants remain pure

### Phase 3: Remove Either Completely
1. No public API should return Either
2. Internal methods can use Either if needed
3. All user-facing APIs are Eru-native

## Example: Complete Refactor of Uri

```scala
opaque type Uri = Uri.Components

object Uri {
  final case class Components(
    scheme: Option[String],
    authority: Option[Authority],
    path: String,
    query: Option[String],
    fragment: Option[String]
  )

  // Primary API - returns Eru
  def parse(uri: String): Eru[InvalidUri, Uri] = {
    Eru.effect {
      // Use Java URI for now, but this should be RFC 3986 parser
      val javaUri = new java.net.URI(uri)

      val authority = Option(javaUri.getHost).map { host =>
        Authority(
          userInfo = Option(javaUri.getUserInfo),
          host = host,
          port = if javaUri.getPort == -1 then None
                 else Some(Port.unsafeFromInt(javaUri.getPort))
        )
      }

      Components(
        scheme = Option(javaUri.getScheme),
        authority = authority,
        path = Option(javaUri.getPath).getOrElse(""),
        query = Option(javaUri.getQuery),
        fragment = Option(javaUri.getFragment)
      )
    }.mapError {
      case e: Exception => InvalidUri(uri, e.getMessage)
    }
  }

  // Builder that validates
  def http(host: String, path: String = "/"): Eru[InvalidUri, Uri] = {
    for {
      _ <- validateHost(host)
      _ <- validatePath(path)
    } yield Components(
      scheme = Some("http"),
      authority = Some(Authority(None, host, Some(Port.HTTP))),
      path = path,
      query = None,
      fragment = None
    )
  }

  // Pre-validated for constants
  def httpUnsafe(host: String, path: String = "/"): Uri = {
    Components(
      scheme = Some("http"),
      authority = Some(Authority(None, host, Some(Port.HTTP))),
      path = path,
      query = None,
      fragment = None
    )
  }

  extension (uri: Uri) {
    // These remain pure since they're just accessors
    def scheme: Option[String] = uri.scheme
    def host: Option[String] = uri.authority.map(_.host)

    // But modifications that could fail return Eru
    def withQueryParam(key: String, value: String): Eru[InvalidUri, Uri] = {
      for {
        validKey <- validateQueryKey(key)
        validValue <- validateQueryValue(value)
        encoded = s"${encode(validKey)}=${encode(validValue)}"
        newQuery = uri.query.fold(encoded)(q => s"$q&$encoded")
      } yield uri.copy(query = Some(newQuery))
    }
  }

  private def validateHost(host: String): Eru[InvalidUri, Unit] = {
    if host.isEmpty then
      Eru.fail(InvalidUri(host, "Host cannot be empty"))
    else
      Eru.unit
  }

  private def validatePath(path: String): Eru[InvalidUri, Unit] = {
    if !path.startsWith("/") then
      Eru.fail(InvalidUri(path, "Path must start with /"))
    else
      Eru.unit
  }
}
```

## New Architecture: Native HTTP with Virtual Threads

### The Eru Way: Simplify with Primitives

Just as Eru builds on `Thread.startVirtualThread()` instead of CompletableFuture, eru-http should build on blocking NIO (ServerSocketChannel/SocketChannel) instead of Netty.

### Simplified Server Architecture

```scala
// ✅ CORRECT: Simple, blocking NIO + Virtual Threads
private[server] final class NativeHttpServer(
  config: HttpServerConfig,
  handler: RequestHandler,
  serverSocket: ServerSocketChannel
)(using runtime: EruRuntime) extends HttpServer {

  def start: Eru[HttpError, ServerAddress] = for {
    _ <- Eru.effect {
      serverSocket.configureBlocking(true)  // Blocking is GOOD on VTs!
      serverSocket.bind(new InetSocketAddress(config.host, config.port))
    }
    acceptFiber <- acceptLoop.fork  // Accept loop on its own VT
  } yield ServerAddress(config.host, config.port)

  private def acceptLoop: Eru[HttpError, Unit] =
    Eru.effect {
      while (running.get()) {
        val clientSocket = serverSocket.accept()  // Blocks on VT - efficient!
        handleClient(clientSocket).fork.unsafeRunSync()  // Each client on own VT
      }
    }

  private def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = for {
    request <- HttpRequestParser.parse(socket)      // Blocking read - efficient!
    response <- handler(request)                    // User's Eru effect
    _ <- HttpWriter.writeResponse(socket, response)  // Blocking write - efficient!
    _ <- Eru.effect { socket.close() }
  } yield ()
}
```

**Benefits vs Netty**:
- **70% less code**: ~150 lines vs 332 lines
- **No event loops**: No EventLoopGroup, no channel pipelines
- **No callbacks**: Sequential Eru effects with for-comprehensions
- **No anti-pattern**: No `unsafeRunSync()` blocking event loops
- **Structured concurrency**: Automatic cleanup of client fibers

### Simplified Client Architecture

```scala
// ✅ CORRECT: Blocking NIO + Virtual Threads
private[client] final class NativeHttpClient(
  config: HttpClientConfig,
  connectionPool: Option[ConnectionPool] = None
) extends HttpClient {

  override def execute[A, B](request: Request[A])(using encoder: BodyEncoder[A], decoder: BodyDecoder[B]): Eru[HttpError, Response[B]] =
    for {
      socket <- connect(request.uri.host.get, getPort(request.uri))
      _ <- HttpWriter.writeRequest(socket, request)    // Blocking write
      response <- HttpResponseParser.parse(socket)     // Blocking read
      _ <- Eru.effect { socket.close() }
      decoded <- decoder.decode(response.body)
    } yield response.copy(body = decoded)

  private def connect(host: String, port: Int): Eru[HttpError, SocketChannel] =
    Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)  // Blocking is GOOD on VTs!
      socket.connect(new InetSocketAddress(host, port))
      socket
    }
}
```

**Benefits vs Netty**:
- **50% less code**: ~200 lines vs 402 lines
- **No event loops**: No NioEventLoopGroup
- **No callbacks**: Pure Eru effects
- **Connection pooling**: Simple with Eru Ref (structured concurrency)

### Performance Characteristics

**Virtual Thread Scaling** (from ERU_STRUCTURED_CONCURRENCY_REFERENCE.md):
- Thread creation: Lightweight (100K+ threads feasible)
- Memory: ~10KB per VT vs ~2MB per OS thread
- Context switching: Minimal overhead
- GC: No additional pressure

**Expected Throughput**:
- Simple responses: 50k-100k req/s
- With handler logic: 20k-50k req/s
- Limited by parsing, not I/O

**Memory (10k connections)**:
- Virtual threads: ~100MB (10KB/thread)
- Netty: ~200MB+ (buffers + platform threads)

### Implementation Plan

See **NATIVE_HTTP_IMPLEMENTATION_PLAN.md** for detailed implementation strategy covering:
1. HTTP Parser (request/response parsing without Netty codecs)
2. Native HTTP Server (blocking NIO + Virtual Threads)
3. Native HTTP Client (with connection pooling via Eru Ref)
4. Remove Netty dependencies
5. Performance validation

## Conclusion

eru-http needs to be **fundamentally restructured** in two ways:

1. **Eru-native design**: Every public API returns `Eru[E, A]`, effects are the foundation
2. **Virtual Thread architecture**: Use blocking NIO + Virtual Threads, remove Netty complexity

This is not a "nice to have" - it's the entire point of the project. Build the Eru way: from primitives, with simplicity and performance.