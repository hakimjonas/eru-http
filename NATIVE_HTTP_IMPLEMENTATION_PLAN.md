# Native HTTP Implementation Plan: Simplify with Virtual Threads

## Executive Summary

**Key Insight**: Eru's Virtual Thread backend makes Netty unnecessary. We can build a simpler, more maintainable HTTP implementation using blocking NIO + Virtual Threads.

**Evidence**:
- POC demonstrates 70% code reduction (from ~313 lines to ~100 lines)
- Current Netty implementation has anti-pattern: `unsafeRunSync()` blocks event loop (NettyHttpServer.scala:186)
- Virtual Threads make blocking I/O efficient (10KB per thread vs 2MB for OS threads)
- Eru's structured concurrency provides automatic resource cleanup

**Result**: Simpler code, easier maintenance, better integration with Eru's execution model.

---

## Current Problems with Netty Implementation

### 1. Event Loop Blocking Anti-Pattern

**NettyHttpServer.scala:186-199**
```scala
// CURRENT (WRONG): Blocks Netty event loop
responseEru.attempt.unsafeRunSync() match {
  case Result.Success(response) =>
    val nettyResponse = convertResponse(response, ...)
    ctx.writeAndFlush(nettyResponse)
  // ...
}
```

**Problem**: `unsafeRunSync()` blocks Netty's event loop thread, preventing it from processing other connections.

**Comment on line 153-156**: "TODO: This currently uses unsafeRunSync() which blocks the Netty event loop."

### 2. Unnecessary Complexity

**Current Setup Requirements**:
- EventLoopGroup (boss and worker)
- ServerBootstrap configuration
- ChannelPipeline setup
- ChannelHandlers for each connection
- SSL/TLS handlers
- Codec handlers (HttpServerCodec, HttpObjectAggregator)
- Custom RequestChannelHandler

**Total Complexity**: ~332 lines (NettyHttpServer.scala) + ~402 lines (NettyHttpClient.scala) = **734 lines**

### 3. Impedance Mismatch

Netty's async callback model doesn't align with Eru's effect system:
- Netty: Callbacks and futures
- Eru: Effect chains with for-comprehensions
- Current code bridges this gap awkwardly

---

## Native Implementation Architecture

### Core Principle: Blocking NIO + Virtual Threads = Simple + Fast

**Key Components**:
1. `ServerSocketChannel` for server
2. `SocketChannel` for connections
3. Each connection on its own Virtual Thread via `.fork`
4. Blocking reads/writes (efficient on Virtual Threads)
5. Structured concurrency for cleanup

### Server Architecture

```scala
// Simplified server (from POC)
def acceptLoop(server: ServerSocketChannel): Eru[Throwable, Unit] =
  Eru.effect {
    while (true) {
      val clientSocket = server.accept()  // Blocks on Virtual Thread - efficient!
      handleClient(clientSocket).fork.unsafeRunSync()  // Each client on own VT
    }
  }

def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = for {
  request <- readRequest(socket)      // Blocking read - efficient!
  response <- handler(request)        // User handler (Eru effect)
  _ <- writeResponse(socket, response)  // Blocking write - efficient!
  _ <- Eru.effect { socket.close() }
} yield ()
```

**Benefits**:
- Sequential, readable code
- No callbacks
- Each connection isolated (structured concurrency)
- Blocking is efficient on Virtual Threads

### Client Architecture

```scala
def execute(request: Request[A]): Eru[HttpError, Response[B]] = for {
  socket <- connect(request.uri.host, request.uri.port)  // Blocking connect
  _ <- writeRequest(socket, request)                     // Blocking write
  response <- readResponse(socket)                       // Blocking read
  _ <- Eru.effect { socket.close() }
} yield response
```

**With Connection Pooling** (using Eru's structured concurrency):
```scala
class ConnectionPool(maxSize: Int) {
  private val availableRef = Ref.make(Queue.empty[SocketChannel]).unsafeRunSync()

  def withConnection[A](host: String, port: Int)(f: SocketChannel => Eru[HttpError, A]): Eru[HttpError, A] =
    for {
      socket <- acquire(host, port)
      result <- f(socket).ensuring(release(socket))  // Structured cleanup
    } yield result
}
```

---

## Implementation Plan

### Phase 1: Core HTTP Parser (Foundation)

**Goal**: Parse HTTP/1.1 requests and responses without Netty codecs.

#### 1.1 HTTP Request Parser

**File**: `eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/HttpParser.scala`

```scala
object HttpRequestParser {
  def parse(socket: SocketChannel): Eru[HttpError, Request[Body]] = for {
    requestLine <- readRequestLine(socket)
    (method, uri, version) <- parseRequestLine(requestLine)
    headers <- readHeaders(socket)
    body <- readBody(socket, headers)
  } yield Request(method, uri, headers, body, version)

  private def readRequestLine(socket: SocketChannel): Eru[HttpError, String] =
    Eru.effect {
      val buffer = ByteBuffer.allocate(8192)
      // Read until \r\n
      // ...
    }

  private def parseRequestLine(line: String): Eru[HttpError, (Method, Uri, HttpVersion)] = {
    // "GET /path HTTP/1.1" -> (Method.GET, Uri("/path"), HTTP_1_1)
    // ...
  }

  private def readHeaders(socket: SocketChannel): Eru[HttpError, Headers] = {
    // Read until empty line (\r\n\r\n)
    // Parse each "Name: Value\r\n"
    // ...
  }

  private def readBody(socket: SocketChannel, headers: Headers): Eru[HttpError, Body] = {
    headers.contentLength match {
      case Some(length) => readFixedLengthBody(socket, length)
      case None => headers.transferEncoding match {
        case Some("chunked") => readChunkedBody(socket)
        case _ => Eru.succeed(Body.Empty)
      }
    }
  }
}
```

#### 1.2 HTTP Response Parser

**File**: Same file, different object

```scala
object HttpResponseParser {
  def parse(socket: SocketChannel): Eru[HttpError, Response[Body]] = for {
    statusLine <- readStatusLine(socket)
    (version, status, reason) <- parseStatusLine(statusLine)
    headers <- readHeaders(socket)
    body <- readBody(socket, headers)
  } yield Response(status, headers, body, version)

  private def parseStatusLine(line: String): Eru[HttpError, (HttpVersion, StatusCode, String)] = {
    // "HTTP/1.1 200 OK" -> (HTTP_1_1, StatusCode(200), "OK")
    // ...
  }
}
```

#### 1.3 HTTP Request/Response Writer

```scala
object HttpWriter {
  def writeRequest(socket: SocketChannel, request: Request[Body]): Eru[HttpError, Unit] =
    Eru.effect {
      val requestLine = s"${request.method.value} ${request.uri.path}${queryString(request.uri)} HTTP/1.1\r\n"
      val headers = request.headers.toList.map { case (name, value) => s"$name: $value\r\n" }.mkString
      val headerEnd = "\r\n"

      // Write request line + headers
      socket.write(ByteBuffer.wrap((requestLine + headers + headerEnd).getBytes(StandardCharsets.UTF_8)))

      // Write body
      request.body match {
        case Body.Empty => ()
        case Body.Text(text, _, charset) =>
          socket.write(ByteBuffer.wrap(text.getBytes(charset.toJavaCharset)))
        case Body.Binary(bytes, _) =>
          socket.write(ByteBuffer.wrap(bytes.toArray))
        case Body.Stream(_, _, _) =>
          // TODO: streaming
          ()
      }
    }

  def writeResponse(socket: SocketChannel, response: Response[Body]): Eru[HttpError, Unit] = {
    // Similar to writeRequest
    // ...
  }
}
```

**Testing**: Unit tests for each parser/writer function with sample HTTP messages.

---

### Phase 2: Native HTTP Server

**Goal**: Replace NettyHttpServer with NativeHttpServer.

#### 2.1 Server Implementation

**File**: `eru-http-server/src/main/scala/net/ghoula/eru/http/server/NativeHttpServer.scala`

```scala
private[server] final class NativeHttpServer(
  config: HttpServerConfig,
  handler: RequestHandler,
  serverSocket: ServerSocketChannel
)(using runtime: EruRuntime) extends HttpServer {

  private val running = new AtomicBoolean(true)
  private val activeFibers = new ConcurrentLinkedQueue[Fiber[?, ?]]()

  def start: Eru[HttpError, ServerAddress] = for {
    _ <- Eru.effect {
      serverSocket.configureBlocking(true)  // Blocking is good on VTs!
      serverSocket.bind(new InetSocketAddress(config.host, config.port))
    }
    address <- Eru.effect {
      val addr = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress]
      ServerAddress(addr.getHostString, addr.getPort)
    }
    // Start accept loop on its own fiber
    acceptFiber <- acceptLoop.fork
    _ <- Eru.effect { activeFibers.add(acceptFiber) }
  } yield address

  private def acceptLoop: Eru[HttpError, Unit] =
    Eru.effect {
      while (running.get()) {
        val clientSocket = serverSocket.accept()  // Blocks efficiently!
        val clientFiber = handleClient(clientSocket).fork.unsafeRunSync()
        activeFibers.add(clientFiber)
      }
    }

  private def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = for {
    _ <- Eru.effect { socket.configureBlocking(true) }
    request <- HttpRequestParser.parse(socket)
    response <- handler(request).timeout(config.requestTimeout)
    _ <- HttpWriter.writeResponse(socket, response)
    _ <- Eru.effect { socket.close() }
  } yield ()

  def shutdown: Eru[HttpError, Unit] = for {
    _ <- Eru.effect { running.set(false) }
    _ <- Eru.effect { serverSocket.close() }
    // Structured concurrency ensures all client fibers are cleaned up automatically
  } yield ()

  def isRunning: Boolean = running.get()
}

object NativeHttpServer {
  def create(config: HttpServerConfig, handler: RequestHandler)(using runtime: EruRuntime): Eru[HttpError, HttpServer] =
    for {
      serverSocket <- Eru.effect { ServerSocketChannel.open() }
      server = new NativeHttpServer(config, handler, serverSocket)
    } yield server
}
```

**Key Features**:
- ~150 lines (vs 332 for Netty version) - **55% reduction**
- No event loops, no pipelines, no handlers
- Blocking I/O is efficient on Virtual Threads
- Structured concurrency handles cleanup
- Each connection on its own Virtual Thread

#### 2.2 TLS Support (Optional for Phase 2)

For HTTPS, wrap SocketChannel with SSLEngine:

```scala
private def wrapWithTLS(socket: SocketChannel, tlsConfig: TlsConfig): Eru[HttpError, SocketChannel] =
  Eru.effect {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(/* key managers */, /* trust managers */, null)
    val sslEngine = sslContext.createSSLEngine()
    sslEngine.setUseClientMode(false)

    // Wrap socket with SSL
    // This still uses blocking I/O, just encrypted
    // ...
  }
```

---

### Phase 3: Native HTTP Client

**Goal**: Replace NettyHttpClient with NativeHttpClient.

#### 3.1 Basic Client

**File**: `eru-http-client/src/main/scala/net/ghoula/eru/http/client/NativeHttpClient.scala`

```scala
private[client] final class NativeHttpClient(
  config: HttpClientConfig,
  connectionPool: Option[ConnectionPool] = None
) extends HttpClient {

  override def execute[A, B](request: Request[A])(using encoder: BodyEncoder[A], decoder: BodyDecoder[B]): Eru[HttpError, Response[B]] =
    for {
      // Validate and encode
      _ <- request.validate
      encodedBody <- encoder.encode(request.body)
      encodedRequest = request.copy(body = encodedBody)

      // Execute
      response <- connectionPool match {
        case Some(pool) => pool.withConnection(request.uri.host.get, getPort(request.uri)) { socket =>
          executeRequest(socket, encodedRequest)
        }
        case None =>
          for {
            socket <- connect(request.uri.host.get, getPort(request.uri))
            response <- executeRequest(socket, encodedRequest)
            _ <- Eru.effect { socket.close() }
          } yield response
      }

      // Decode
      decoded <- decoder.decode(response.body)
    } yield response.copy(body = decoded)

  private def connect(host: String, port: Int): Eru[HttpError, SocketChannel] =
    Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)  // Blocking is efficient on VTs!
      socket.connect(new InetSocketAddress(host, port))
      socket
    }.mapError(e => HttpError.ConnectionError(s"Failed to connect to $host:$port", Some(e)))

  private def executeRequest(socket: SocketChannel, request: Request[Body]): Eru[HttpError, Response[Body]] =
    for {
      _ <- HttpWriter.writeRequest(socket, request)
      response <- HttpResponseParser.parse(socket)
    } yield response

  private def getPort(uri: Uri): Int =
    uri.port.map(_.value).getOrElse {
      uri.scheme match {
        case Some("https") => 443
        case _ => 80
      }
    }
}

object NativeHttpClient {
  def create(config: HttpClientConfig): Eru[HttpError, NativeHttpClient] =
    Eru.succeed(new NativeHttpClient(config, None))

  def createWithPool(config: HttpClientConfig, maxConnections: Int): Eru[HttpError, NativeHttpClient] =
    for {
      pool <- ConnectionPool.create(maxConnections)
    } yield new NativeHttpClient(config, Some(pool))
}
```

**Key Features**:
- ~200 lines (vs 402 for Netty version) - **50% reduction**
- No event loops, no callbacks
- Blocking I/O efficient on Virtual Threads
- Connection pooling optional

#### 3.2 Connection Pool (Using Eru Ref)

```scala
class ConnectionPool(maxSize: Int)(using runtime: EruRuntime) {
  private case class PoolEntry(socket: SocketChannel, host: String, port: Int, lastUsed: Long)

  private val availableRef = Ref.make(Queue.empty[PoolEntry]).unsafeRunSync()
  private val activeCount = Ref.make(0).unsafeRunSync()

  def withConnection[A](host: String, port: Int)(f: SocketChannel => Eru[HttpError, A]): Eru[HttpError, A] =
    for {
      socket <- acquire(host, port)
      result <- f(socket).ensuring(release(socket, host, port))
    } yield result

  private def acquire(host: String, port: Int): Eru[HttpError, SocketChannel] =
    for {
      available <- availableRef.get
      socket <- available.dequeueOption match {
        case Some((entry, remaining)) if entry.host == host && entry.port == port =>
          for {
            _ <- availableRef.set(remaining)
            // Verify connection is still alive
            valid <- isValid(entry.socket)
            result <- if (valid) Eru.succeed(entry.socket)
                      else createNew(host, port)
          } yield result
        case _ => createNew(host, port)
      }
    } yield socket

  private def createNew(host: String, port: Int): Eru[HttpError, SocketChannel] =
    for {
      count <- activeCount.get
      _ <- if (count >= maxSize)
            Eru.fail(HttpError.ResourceExhausted("Connection pool exhausted"))
           else Eru.unit
      socket <- Eru.effect {
        val s = SocketChannel.open()
        s.configureBlocking(true)
        s.connect(new InetSocketAddress(host, port))
        s
      }
      _ <- activeCount.update(_ + 1)
    } yield socket

  private def release(socket: SocketChannel, host: String, port: Int): Eru[HttpError, Unit] =
    for {
      _ <- availableRef.update(_.enqueue(PoolEntry(socket, host, port, System.currentTimeMillis())))
    } yield ()

  private def isValid(socket: SocketChannel): Eru[HttpError, Boolean] =
    Eru.effect {
      socket.isConnected && socket.isOpen
    }.attempt.map {
      case Result.Success(valid) => valid
      case Result.Failure(_) => false
    }
}

object ConnectionPool {
  def create(maxSize: Int)(using runtime: EruRuntime): Eru[HttpError, ConnectionPool] =
    Eru.succeed(new ConnectionPool(maxSize))
}
```

---

### Phase 4: Remove Netty Dependencies

#### 4.1 Update build.sbt

```scala
// REMOVE these lines:
libraryDependencies ++= Seq(
  nettyHandler,        // REMOVE
  nettyCodecHttp,      // REMOVE
  nettyCodecHttp2      // REMOVE
)

// NO DEPENDENCIES NEEDED - just use java.nio!
```

#### 4.2 Remove Netty Files

```bash
rm eru-http-server/src/main/scala/net/ghoula/eru/http/server/NettyHttpServer.scala
rm eru-http-client/src/main/scala/net/ghoula/eru/http/client/NettyHttpClient.scala
```

#### 4.3 Update HttpServer/HttpClient Factory Methods

```scala
// HttpServer.scala
object HttpServer {
  def make(config: HttpServerConfig, handler: RequestHandler)(using runtime: EruRuntime): Eru[HttpError, HttpServer] =
    NativeHttpServer.create(config, handler)  // Was: NettyHttpServer.create
}

// HttpClient.scala
object HttpClient {
  def make(config: HttpClientConfig): Eru[HttpError, HttpClient] =
    NativeHttpClient.create(config)  // Was: NettyHttpClient.create
}
```

---

## Performance Expectations

### Virtual Thread Characteristics

From ERU_STRUCTURED_CONCURRENCY_REFERENCE.md:
- **Thread creation**: Lightweight (100K+ threads feasible)
- **Memory**: ~10KB per Virtual Thread vs ~2MB per OS thread
- **Context switching**: Minimal overhead

### Expected Throughput

**Server** (from POC comments):
- Simple responses: 50k-100k req/s
- With handler logic: 20k-50k req/s
- Limited by parsing, not I/O

**Memory** (10k concurrent connections):
- Virtual threads: ~100MB (10KB/thread)
- Netty would use: ~200MB+ (buffers + event loops)

### Benchmark Comparison

Target: Match or exceed Netty performance due to:
1. **Simpler code path**: No event loop overhead
2. **Zero-copy where possible**: ByteBuffer.wrap instead of Unpooled.copiedBuffer
3. **Reduced allocations**: No ChannelHandler objects per connection

---

## Migration Strategy

### Step 1: Implement Native Parser (Week 1)
- HttpRequestParser
- HttpResponseParser
- HttpWriter
- Unit tests

### Step 2: Implement Native Server (Week 1-2)
- NativeHttpServer
- Integration tests
- Performance benchmarks vs NettyHttpServer

### Step 3: Implement Native Client (Week 2)
- NativeHttpClient
- ConnectionPool
- Integration tests

### Step 4: Remove Netty (Week 2)
- Update build.sbt
- Remove Netty files
- Update factory methods
- Verify all tests pass

### Step 5: Documentation (Week 3)
- Update ARCHITECTURE-FIX.md
- Update MASTERPLAN.md
- Add performance comparison docs

---

## Risks and Mitigations

### Risk 1: HTTP Parsing Bugs

**Mitigation**:
- Comprehensive unit tests
- Property-based tests
- RFC compliance test suite
- Test against real-world HTTP traffic

### Risk 2: Performance Regression

**Mitigation**:
- Benchmark before/after
- Profile with JFR
- Optimize hot paths
- Fall back to Netty if necessary (keep old code in git history)

### Risk 3: Missing Netty Features

**Features to verify**:
- ✅ HTTP/1.1 - Native implementation
- ✅ Keep-alive - Track in connection pool
- ✅ Chunked encoding - Parse in readBody
- ✅ TLS/SSL - Use SSLEngine with blocking I/O
- ⚠️ HTTP/2 - Defer to later (most APIs use HTTP/1.1)
- ⚠️ WebSockets - Defer to later

**Mitigation**: Implement incrementally, test thoroughly.

---

## Success Criteria

### Code Metrics
- [ ] Server implementation < 200 lines (vs 332 with Netty)
- [ ] Client implementation < 250 lines (vs 402 with Netty)
- [ ] Total reduction: 30-50%

### Functionality
- [ ] All existing tests pass
- [ ] HTTP/1.1 compliance
- [ ] TLS support
- [ ] Connection pooling
- [ ] Keep-alive support

### Performance
- [ ] Server throughput ≥ Netty version
- [ ] Client throughput ≥ Netty version
- [ ] Memory usage ≤ Netty version
- [ ] Latency (p99) ≤ Netty version + 10%

### Architecture
- [ ] No event loops
- [ ] No callbacks (pure Eru effects)
- [ ] Blocking I/O throughout
- [ ] Structured concurrency for cleanup

---

## Conclusion

**The Eru Way**: Build from primitives, leverage Virtual Threads, simplify.

**Impact**:
- ✅ 30-50% code reduction
- ✅ Simpler, more maintainable code
- ✅ Better alignment with Eru's execution model
- ✅ No event loop blocking anti-pattern
- ✅ Structured concurrency throughout

**Next Steps**: Implement Phase 1 (HTTP Parser), verify with tests, benchmark, iterate.
