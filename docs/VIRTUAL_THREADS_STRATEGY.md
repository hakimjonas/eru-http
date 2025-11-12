# Virtual Threads & Modern HTTP Strategy

## Key Insight: Java 21 Virtual Threads Change Everything

Since eru-http targets Java 21+ and Eru likely uses virtual threads, the entire async architecture strategy needs reconsideration.

### What Are Virtual Threads?

Virtual threads (Project Loom - Java 21+) are lightweight threads managed by the JVM:
- **Millions of threads** - can create 1M+ virtual threads on modest hardware
- **Blocking is cheap** - blocking I/O doesn't block OS threads
- **No callback hell** - write sequential code that's actually concurrent
- **Structured concurrency** - nested scopes with automatic cleanup

### The Revolution

**Traditional approach (pre-virtual threads):**
```scala
// COMPLEX: Must use callbacks to avoid blocking OS threads
def readAsync(socket: Socket)(callback: Bytes => Unit): Unit = {
  socket.readAsync { bytes =>
    callback(bytes) // Callback hell
  }
}
```

**With virtual threads:**
```scala
// SIMPLE: Just block - virtual threads handle concurrency
def read(socket: Socket): Eru[IOError, Bytes] =
  Eru.effect {
    socket.read() // Blocks virtual thread, not OS thread!
  }
```

### How This Affects eru-http

#### If Eru Uses Virtual Threads (Hypothesis)

Each `Eru[E, A]` execution could run on a virtual thread. This means:

1. **Blocking is fine** - `unsafeRunSync()` doesn't block OS threads
2. **Simple I/O** - Use blocking Java NIO directly
3. **No callback bridging** - Netty's complexity might be overkill
4. **Structured concurrency** - Natural fit for Eru's effect model

#### Evidence to Check

```scala
// Does EruRuntime use virtual threads?
// Check Eru's implementation for:

object EruRuntime {
  val shared: EruRuntime = new EruRuntime(
    // Option 1: Virtual thread executor
    Executors.newVirtualThreadPerTaskExecutor()

    // Option 2: Platform threads (old way)
    Executors.newCachedThreadPool()
  )
}
```

### New Architecture Strategy

#### Option A: Virtual Threads + Blocking I/O (Simplest)

```scala
// eru-http-io/src/main/scala/net/ghoula/eru/http/io/Socket.scala

class Socket(channel: SocketChannel) {
  def read(buffer: ByteBuffer): Eru[IOError, Int] =
    Eru.effect {
      // This blocks, but on a virtual thread - perfectly fine!
      channel.read(buffer)
    }.mapError(e => IOError.ReadError(e.getMessage))

  def write(bytes: Bytes): Eru[IOError, Int] =
    Eru.effect {
      // Also blocks, but cheap on virtual threads
      channel.write(ByteBuffer.wrap(bytes.toArray))
    }.mapError(e => IOError.WriteError(e.getMessage))
}

object Socket {
  def connect(host: String, port: Int): Eru[IOError, Socket] =
    Eru.effect {
      val channel = SocketChannel.open()
      channel.configureBlocking(true) // Blocking is fine!
      channel.connect(new InetSocketAddress(host, port))
      new Socket(channel)
    }.mapError(e => IOError.ConnectionError(e.getMessage))
}
```

**Benefits:**
- ✅ No callbacks
- ✅ No async wrappers
- ✅ Simple sequential code
- ✅ Efficient concurrency via virtual threads
- ✅ Easy to understand and maintain

#### Option B: Keep Netty (If Virtual Threads Not Used)

If Eru doesn't use virtual threads, we need Netty's async I/O to avoid blocking platform threads.

### HTTP Server Example

**With Virtual Threads:**
```scala
def handleRequest(request: Request[Body]): Eru[HttpError, Response[Body]] =
  for {
    // Each handler runs on its own virtual thread
    // Blocking operations (DB queries, etc.) are fine
    user <- database.findUser(request.userId) // Blocks virtual thread
    profile <- api.fetchProfile(user.id)       // Blocks virtual thread
    response = Response.ok(Body.text(profile.toJson))
  } yield response

// Server accept loop
def acceptLoop(serverSocket: ServerSocket): Eru[Never, Unit] = {
  def loop: Eru[IOError, Unit] = for {
    clientSocket <- serverSocket.accept() // Blocks virtual thread waiting
    _ <- handleClient(clientSocket).fork  // Spawn new virtual thread
    _ <- loop                             // Continue accepting
  } yield ()

  loop.forever
}
```

Each connection gets its own virtual thread. With millions of virtual threads available, this scales beautifully.

**Current Netty Approach:**
```scala
// Complex: Event loop + callbacks
override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit = {
  val responseEru = convertRequest(req).flatMap(handler)

  // Must run async to avoid blocking event loop
  runtime.unsafeRunAsync(responseEru.attempt) { result =>
    ctx.writeAndFlush(convertResponse(result))
  }
}
```

### What We Can Drop (Modern HTTP Only)

Since it's 2025 and we're building fresh, we can drop legacy features:

#### 1. HTTP/0.9 Support
**Drop it.** Only ancient clients use this.

#### 2. HTTP/1.0 Support
**Questionable.** HTTP/1.1 is 25+ years old. Very few clients only support 1.0.
- **Recommendation:** Start with HTTP/1.1 only, add 1.0 if users request it

#### 3. Chunked Transfer Encoding (HTTP/1.1)
**Keep it.** Still used for streaming, but we can simplify:
- Drop complex buffering strategies
- Simple streaming only

#### 4. Ancient Ciphers (TLS)
**Drop them:**
- SSL 2.0/3.0 - completely broken
- TLS 1.0 - deprecated in 2020
- TLS 1.1 - deprecated in 2020
- Weak cipher suites (DES, RC4, MD5-based)

**Support only:**
- TLS 1.2 (minimum)
- TLS 1.3 (preferred)
- Modern cipher suites

#### 5. Complex Content Negotiation
**Simplify it:**
- Support Accept/Content-Type headers
- Drop complex q-values and wildcard matching unless requested
- Users can implement custom logic if needed

#### 6. Range Requests (Partial Content)
**Optional feature:**
- Not needed for most applications
- Can be added later if requested
- Don't build it into core

#### 7. HTTP Authentication Schemes
**Drop built-in support for:**
- Basic Auth (insecure without TLS, users should use Bearer)
- Digest Auth (obsolete)
- NTLM (Microsoft-specific)

**Keep simple helpers for:**
- Bearer tokens (common for APIs)
- Custom Authorization headers

#### 8. Cookies
**Keep, but simplified:**
- Support Set-Cookie / Cookie headers
- Drop complex cookie matching rules if not needed
- SameSite=Strict by default (modern security)

#### 9. Cache-Control Complexity
**Simplify:**
- Support basic headers (ETag, Cache-Control, Expires)
- Drop complex vary/revalidation logic
- Let CDNs/proxies handle sophisticated caching

#### 10. Trailers
**Drop it.** Almost never used.

#### 11. HTTP/2 Server Push
**Drop it.** Being removed from browsers, considered harmful.

#### 12. Legacy Character Encodings
**Drop exotic encodings:**
- Support UTF-8 (default)
- Support ISO-8859-1 (HTTP spec requires it)
- Drop everything else (users can handle manually)

### Features to Keep (Essential)

1. ✅ HTTP/1.1 (possibly HTTP/1.0)
2. ✅ TLS 1.2/1.3
3. ✅ Connection pooling
4. ✅ Keep-Alive
5. ✅ Chunked transfer encoding
6. ✅ Streaming request/response bodies
7. ✅ Basic headers (Content-Type, Accept, etc.)
8. ✅ Cookies (simplified)
9. ✅ Redirects
10. ✅ Multipart form data
11. ✅ Compression (gzip, deflate, brotli)

### Features to Add Later (If Requested)

- HTTP/2
- WebSocket
- Server-Sent Events (SSE)
- HTTP/3 (QUIC) - future

### Action Items

1. **Verify Eru uses virtual threads:**
   ```scala
   // Check Eru's source or documentation
   // Look for Executors.newVirtualThreadPerTaskExecutor()
   ```

2. **If YES (virtual threads):**
   - Use simple blocking I/O
   - Drop Netty entirely
   - Sequential effect chains work perfectly
   - Much simpler architecture

3. **If NO (platform threads):**
   - Keep Netty for async I/O
   - Add `unsafeRunAsync` to Eru
   - More complex, but necessary

4. **Either way:**
   - Drop legacy HTTP features
   - Focus on modern, secure defaults
   - Build incrementally based on user needs

### Next Steps

1. Investigate Eru's concurrency model
2. Decide: Virtual threads or Netty?
3. Create minimal viable implementation:
   - HTTP/1.1
   - TLS 1.2/1.3
   - Basic request/response
   - Prove the architecture works
4. Iterate based on real usage

### The Big Question

**Does Eru use virtual threads?**

If yes: Simple blocking I/O + virtual threads = elegant, fast HTTP library
If no: Need async I/O (Netty or similar) to avoid blocking platform threads

Let's find out!
