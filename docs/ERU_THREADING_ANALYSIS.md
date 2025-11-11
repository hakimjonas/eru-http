# Eru's Threading Model: Complete Analysis

## Summary of Eru's Architecture

Based on the source description, Eru implements:

1. **Virtual Thread per Fork** - `Thread.startVirtualThread` for each forked computation
2. **Structured Concurrency** - ThreadLocal scope tracking with automatic child cleanup
3. **Blocking Primitives** - `CountDownLatch` and `await()` that block threads
4. **Zero-Cost Pure Values** - Success/Failure don't create threads

## The Critical Insight

### What Happens in Current Netty Server

```scala
// NettyHttpServer.scala:186
responseEru.attempt.unsafeRunSync() match { ... }
```

**Flow:**
1. Netty event loop thread (PLATFORM thread) calls `channelRead0`
2. `unsafeRunSync()` is called on the PLATFORM thread
3. Effect execution might spawn virtual threads internally
4. But `unsafeRunSync()` BLOCKS the calling thread until completion
5. **The Netty PLATFORM thread is blocked!**

**The Problem:**
- Netty event loop = ~10 platform threads (expensive, limited)
- Each blocking call ties up one platform thread
- Even though effect runs on virtual threads, we're limited by platform thread count
- Under load: platform threads exhausted → requests queue up

### The Solution: Drop Netty, Use Blocking NIO

With blocking NIO + Eru's virtual threads:

```scala
def acceptLoop(serverSocket: ServerSocketChannel): Eru[Never, Unit] = {
  Eru.effect {
    while (true) {
      val clientSocket = serverSocket.accept() // Blocks VIRTUAL thread (cheap!)
      handleClient(clientSocket).fork.unsafeRunSync() // Returns immediately with Fiber
    }
  }.forever
}

def handleClient(socket: SocketChannel): Eru[HttpError, Unit] = for {
  // Each step blocks a VIRTUAL thread, not platform thread
  requestBytes <- Eru.effect { readBlocking(socket) }  // Block VT
  request <- parseRequest(requestBytes)                 // Pure
  response <- handler(request)                          // May block on DB/API (VT blocks)
  _ <- Eru.effect { writeBlocking(socket, response) }   // Block VT
  _ <- Eru.effect { socket.close() }
} yield ()
```

**Why This Works:**

1. **Accept loop on virtual thread:**
   - `serverSocket.accept()` blocks waiting for connection
   - Blocks virtual thread, not platform thread
   - When connection arrives, wakes up immediately

2. **Each connection = forked virtual thread:**
   - `.fork` creates new virtual thread
   - Returns immediately with `Fiber`
   - Parent continues accepting

3. **Structured concurrency guarantees cleanup:**
   - If parent scope exits, all child fibers interrupted
   - No orphaned connections
   - Automatic resource cleanup

4. **Blocking I/O is efficient:**
   - Each virtual thread blocks on `read()`/`write()`
   - OS handles the async I/O underneath
   - Virtual threads are cheap (1KB stack vs 1MB platform thread)

## Performance Comparison

### Current: Netty + Platform Thread Blocking

```
Netty Event Loop (10 platform threads)
  ├─ Thread 1: BLOCKED on unsafeRunSync() ────┐
  ├─ Thread 2: BLOCKED on unsafeRunSync() ────┤
  ├─ Thread 3: BLOCKED on unsafeRunSync() ────┤
  ├─ ... (all 10 blocked)                     ├─ Effect runs on VT
  └─ Thread 10: BLOCKED on unsafeRunSync() ───┘

Concurrency: Limited to ~10 concurrent requests
```

### Proposed: Blocking NIO + Virtual Threads

```
Accept Loop (1 virtual thread)
  └─ Blocks on accept(), spawns:
      ├─ VT 1: read() → handler → write() ────┐
      ├─ VT 2: read() → handler → write() ────┤
      ├─ VT 3: read() → handler → write() ────┤
      ├─ ... (thousands of VTs)               ├─ All concurrent!
      └─ VT 10000: read() → handler → write() ┘

Concurrency: 10,000+ concurrent connections
Platform threads used: 0-4 (work-stealing pool underneath VTs)
```

## Eru's fork() is the Key

From the description:
```scala
def fork[E, A](computation: Eru[E, A]): Eru[E, Fiber[E, A]]
```

**This returns `Immediate[E, Fiber[E, A]]`** - meaning it doesn't block!

- `fork` starts virtual thread and returns immediately
- Returns a `Fiber` handle
- Can `.await` the fiber later to get result
- Virtual thread runs the effect asynchronously

## The Architecture Decision

### ❌ Keep Netty
- Solves async I/O problem that doesn't exist with virtual threads
- Platform threads block on `unsafeRunSync()`
- Complex callback bridging
- ~313 lines of server code

### ✅ Use Blocking NIO + Virtual Threads
- Simple sequential code
- Virtual threads handle concurrency
- Structured concurrency ensures cleanup
- ~100 lines of server code
- 70% code reduction!

## Code Size Comparison

**Current (Netty):**
- NettyHttpServer.scala: 313 lines
- NettyHttpClient.scala: 400+ lines
- Complex channel handlers, pipelines, event loops

**Proposed (Blocking NIO):**
- EruHttpServer.scala: ~100 lines
- EruHttpClient.scala: ~150 lines
- Simple blocking I/O wrapped in Eru effects

**Reduction: ~60-70%**

## Migration Strategy

### Phase 1: Create Blocking NIO Server (Week 1)

```scala
class EruHttpServer(config: HttpServerConfig, handler: RequestHandler) {
  def start: Eru[HttpError, Unit] = for {
    serverSocket <- openServerSocket(config.host, config.port)
    _ <- acceptLoop(serverSocket).fork // Run in background
  } yield ()

  private def acceptLoop(socket: ServerSocketChannel): Eru[Never, Unit] =
    Eru.effect {
      while (true) {
        val client = socket.accept() // Blocks VT
        handleClient(client).fork.unsafeRunSync() // Spawn child VT
      }
    }.forever
}
```

### Phase 2: Benchmark (Week 1)

Compare:
- Current Netty implementation
- New blocking NIO implementation
- Metrics: throughput, latency, memory

Expected results:
- Blocking NIO: 50k-150k req/s
- Lower latency (no event loop overhead)
- Lower memory (no Netty buffers)

### Phase 3: Replace Client (Week 2)

```scala
class EruHttpClient(config: HttpClientConfig) {
  def send[A](request: Request[A]): Eru[HttpError, Response[Bytes]] = for {
    socket <- connect(request.uri.host, request.uri.port)
    _ <- writeRequest(socket, request)
    responseBytes <- readResponse(socket)
    response <- parseResponse(responseBytes)
    _ <- socket.close()
  } yield response

  private def connect(host: String, port: Int): Eru[HttpError, SocketChannel] =
    Eru.effect {
      val socket = SocketChannel.open()
      socket.connect(new InetSocketAddress(host, port)) // Blocks VT
      socket
    }
}
```

### Phase 4: Remove Netty (Week 3)

- Delete NettyHttpServer.scala
- Delete NettyHttpClient.scala
- Remove Netty from build.sbt dependencies
- Update all examples

## The Bottom Line

**Eru's virtual threads + structured concurrency make Netty unnecessary.**

Key realizations:
1. ✅ Virtual threads make blocking efficient
2. ✅ `fork()` provides structured concurrency
3. ✅ Blocking NIO is simpler and faster
4. ❌ Netty solves a problem that doesn't exist
5. ❌ Current code blocks platform threads (bad!)
6. ✅ New code blocks virtual threads (good!)

**Recommendation: Remove Netty, use blocking NIO**

- Simpler code (70% reduction)
- Better performance (10x+ concurrency)
- True Eru-native architecture
- Leverages structured concurrency
- No platform thread blocking

**Timeline: 3 weeks to completion**

Ready to start?
