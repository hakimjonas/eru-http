# Connection Pool Design for eru-http Client

**Date**: November 13, 2025
**Author**: Claude Code
**Purpose**: Design document for HTTP client connection pooling with Eru primitives

---

## 1. Overview

### Goals
1. **Primary**: Validate Eru's client-side resource management (dogfooding)
2. Implement HTTP/1.1 connection reuse (keep-alive support)
3. Improve client performance through connection reuse
4. Ensure thread-safe concurrent access from multiple fibers
5. Provide graceful cleanup and resource safety

### Non-Goals (for this iteration)
- TLS/SSL support (comes after pooling works)
- HTTP/2 multiplexing
- Connection health checking beyond basic error handling
- Connection pool metrics/observability (can add later)

---

## 2. Current State Analysis

### Existing Implementation Issues

**NativeHttpClient.scala (lines 135-223)**:
- Opens new socket per request via `connect()` (line 142)
- No connection reuse
- **Resource leak**: Sockets are not explicitly closed (TODO at line 221)
- Each request cycle: `connect → write → read → (socket abandoned)`

**Configuration Already Exists**:
- `HttpClientConfig.maxConnections: Int = 100` (total pool size)
- `HttpClientConfig.maxConnectionsPerHost: Int = 10` (per-host limit)
- `HttpClientConfig.connectTimeout: Duration = 30.seconds`
- `HttpClientConfig.requestTimeout: Duration = 60.seconds`

### Server Keep-Alive Reference

**NativeHttpServer.scala (lines 169-186)** shows keep-alive logic:
```scala
private def shouldKeepAlive(request: Request[Body], response: Response[Body]): Boolean = {
  val responseConnection = response.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
  if responseConnection.contains("close") then false
  else {
    val requestConnection = request.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
    if requestConnection.contains("close") then false
    else {
      // Default for HTTP/1.1 is keep-alive
      request.version == HttpVersion.HTTP_1_1 || responseConnection.contains("keep-alive")
    }
  }
}
```

**Key Insight**: Check `Connection` header in response to decide whether to pool or close the connection.

---

## 3. Architecture Design

### 3.1 Data Structures

#### Pooled Connection Wrapper
```scala
/** A connection in the pool with metadata for lifecycle management.
  *
  * @param socket The underlying socket channel
  * @param host Target host
  * @param port Target port
  * @param createdAt When this connection was opened
  * @param lastUsedAt When this connection was last used (for staleness detection)
  */
private[client] final case class PooledConnection(
  socket: SocketChannel,
  host: String,
  port: Int,
  createdAt: Instant,
  lastUsedAt: Instant
)
```

#### Pool State
```scala
/** Internal state of the connection pool.
  *
  * Keyed by "host:port" to separate pools per destination.
  *
  * @param available Connections available for reuse (FIFO queue per host)
  * @param inUse Connections currently in use (to track total)
  * @param totalConnections Total number of connections across all hosts
  */
private[client] final case class PoolState(
  available: Map[String, Queue[PooledConnection]], // key = "host:port"
  inUse: Set[PooledConnection],
  totalConnections: Int
) {
  def connectionKey(host: String, port: Int): String = s"$host:$port"
}
```

**Design Rationale**:
- **Map[String, Queue[PooledConnection]]**: Separate FIFO queues per host for fairness
- **Set[PooledConnection]**: Track in-use connections to enforce limits
- **totalConnections counter**: Enforce global max connections limit

### 3.2 Connection Pool Interface

```scala
/** Connection pool for HTTP client with Eru-based concurrency control.
  *
  * Thread-safe for concurrent access from multiple Virtual Threads via Eru Ref.
  */
trait ConnectionPool {

  /** Acquire a connection from the pool or create a new one.
    *
    * Behavior:
    *   1. Check available pool for host:port
    *   2. If available, return connection from queue (mark as in-use)
    *   3. If not available but under limits, create new connection
    *   4. If at limits, retry with exponential backoff (up to acquireTimeout)
    *
    * @param host Target host
    * @param port Target port
    * @return A pooled connection ready to use
    */
  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection]

  /** Release a connection back to the pool for reuse.
    *
    * Call this after successfully using a connection when the server indicates keep-alive.
    *
    * @param conn The connection to release
    * @return Success or error
    */
  def release(conn: PooledConnection): Eru[HttpError, Unit]

  /** Remove a connection from the pool and close it.
    *
    * Call this when:
    *   - Connection error occurs
    *   - Server sends "Connection: close"
    *   - Connection is stale (detected during use)
    *
    * @param conn The connection to remove and close
    * @return Success or error
    */
  def remove(conn: PooledConnection): Eru[HttpError, Unit]

  /** Shutdown the pool, closing all connections.
    *
    * This should be called during client shutdown to ensure clean resource cleanup.
    *
    * @return Success or error
    */
  def shutdown: Eru[HttpError, Unit]
}
```

### 3.3 Concurrency Model

**Use Eru Ref for state management** (as instructed for dogfooding):
```scala
private val stateRef: Ref[PoolState] = Ref.make(PoolState.empty).unsafeRunSync()
```

**Operations use `Ref.modify` for atomic updates**:
```scala
def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
  stateRef.modify { state =>
    // Atomically update state and return connection
    // Logic: check available queue, enforce limits, etc.
  }
}
```

**Why Ref instead of ConcurrentHashMap?**
1. **Dogfooding**: Primary goal is to stress-test Eru Ref under concurrent load
2. **Atomic operations**: Need atomic read-modify-write for pool limits
3. **Composability**: Ref operations return Eru effects, composing naturally
4. **Find bugs**: More likely to expose Eru concurrency issues

**Potential Eru Issues to Watch For**:
- Deadlocks under high contention
- Race conditions in Ref.modify
- Fiber leaks if connections aren't properly tracked
- Memory leaks if cleanup fails

---

## 4. Integration with NativeHttpClient

### 4.1 Add Connection Pool Field

```scala
private[client] final class NativeHttpClient(
  config: HttpClientConfig,
  sslContext: Option[SSLContext],
  pool: ConnectionPool,  // NEW: Add pool field
  requestInterceptors: List[RequestInterceptor] = List.empty,
  responseInterceptors: List[ResponseInterceptor] = List.empty
)(using runtime: EruRuntime)
    extends HttpClient {
  // ...
}
```

### 4.2 Modify executeRequest Flow

**Current flow**:
```scala
def executeRequest(host, port, request):
  socket <- connect(host, port)              // NEW SOCKET EVERY TIME
  secureSocket <- wrapWithTLS(socket)
  _ <- writeRequest(secureSocket, request)
  response <- parseResponse(secureSocket)
  return response                            // SOCKET ABANDONED (LEAK!)
```

**New flow with pooling**:
```scala
def executeRequest(host, port, request):
  conn <- pool.acquire(host, port)           // GET FROM POOL OR CREATE

  result <- (for {
    secureSocket <- wrapWithTLS(conn.socket)  // Wrap if HTTPS
    _ <- writeRequest(secureSocket, request)
    response <- parseResponse(secureSocket)
  } yield response).attempt

  result match {
    case Success(response) =>
      // Check if server wants to keep connection alive
      if shouldReuseConnection(response) then
        pool.release(conn.copy(lastUsedAt = Instant.now()))
      else
        pool.remove(conn)  // Server said "Connection: close"

      Eru.succeed(response)

    case Failure(error) =>
      pool.remove(conn)  // Error, discard connection
      Eru.fail(error)
  }
```

### 4.3 Keep-Alive Detection

```scala
/** Check if connection should be reused based on response headers.
  *
  * Follows HTTP/1.1 keep-alive semantics (similar to server implementation).
  */
private def shouldReuseConnection(response: Response[Bytes]): Boolean = {
  val connectionHeader = response.headers
    .getFirst(HeaderNames.Connection)
    .map(_.value.toLowerCase)

  // If server explicitly says "close", don't reuse
  if connectionHeader.contains("close") then false

  // HTTP/1.1 defaults to keep-alive
  else if response.version == HttpVersion.HTTP_1_1 then true

  // HTTP/1.0 requires explicit "keep-alive"
  else connectionHeader.contains("keep-alive")
}
```

### 4.4 Shutdown Cleanup

```scala
def shutdown: Eru[Nothing, Unit] =
  Eru.effectTotal {
    pool.shutdown.unsafeRunSync()  // Close all pooled connections
    ()
  }
```

---

## 5. Implementation Plan

### Phase 1: Core Pool Implementation

**File**: `eru-http-client/src/main/scala/net/ghoula/eru/http/client/ConnectionPool.scala`

1. Define `PooledConnection` case class
2. Define `PoolState` case class with helpers
3. Implement `ConnectionPool` trait
4. Implement `NativeConnectionPool` using Eru Ref:
   - `acquire()`: Check pool, enforce limits, create if needed
   - `release()`: Return connection to available queue
   - `remove()`: Close socket and remove from tracking
   - `shutdown()`: Close all connections

**Ref API to use** (inferred from Eru patterns):
```scala
// Create ref
val ref = Ref.make(initialState)

// Atomic modify
ref.modify { state =>
  val newState = state.copy(...)
  val result = ... // compute result
  (result, newState)  // Return (result, newState)
}

// Atomic get
ref.get

// Atomic set
ref.set(newState)
```

**Connection creation** (extracted from current `connect()`):
```scala
private def createConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
  val now = Instant.now()
  connect(host, port).map { socket =>
    PooledConnection(socket, host, port, createdAt = now, lastUsedAt = now)
  }
}
```

**Acquire timeout logic**:
```scala
def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
  def attemptAcquire(attempt: Int = 0): Eru[HttpError, PooledConnection] = {
    stateRef.modify { state =>
      val key = state.connectionKey(host, port)

      // Try to get from available queue
      state.available.get(key).flatMap(_.headOption) match {
        case Some(conn) =>
          // Found available connection
          val newAvailable = state.available + (key -> state.available(key).tail)
          val newInUse = state.inUse + conn
          val newState = state.copy(available = newAvailable, inUse = newInUse)
          (Right(conn), newState)

        case None =>
          // No available connection, check if we can create new
          val hostConnections = state.inUse.count(c => c.host == host && c.port == port)
          val canCreateHost = hostConnections < config.maxConnectionsPerHost
          val canCreateGlobal = state.totalConnections < config.maxConnections

          if canCreateHost && canCreateGlobal then
            // Can create new connection (done outside modify to avoid blocking Ref)
            (Left("create"), state)
          else
            // At limit, retry
            (Left("retry"), state)
      }
    }.flatMap {
      case Right(conn) => Eru.succeed(conn)
      case Left("create") => createConnection(host, port).flatMap(addToInUse)
      case Left("retry") if attempt < 10 =>
        // Exponential backoff: 10ms, 20ms, 40ms, ...
        Eru.sleep(Duration.ofMillis(10 << attempt)).flatMap(_ => attemptAcquire(attempt + 1))
      case Left("retry") =>
        Eru.fail(HttpError.ConnectionError(s"Pool exhausted for $host:$port", None))
    }
  }

  attemptAcquire()
}
```

### Phase 2: Integration

1. Update `NativeHttpClient` constructor to create pool
2. Modify `executeRequest()` to use pool
3. Add `shouldReuseConnection()` helper
4. Update `shutdown()` to cleanup pool
5. Extract `connect()` logic for pool to use

### Phase 3: Testing

**Unit tests** (`ConnectionPoolSpec.scala`):
```scala
class ConnectionPoolSpec extends munit.FunSuite {
  test("acquire creates new connection when pool is empty")
  test("release returns connection to pool")
  test("acquire reuses released connection")
  test("remove closes connection")
  test("enforces maxConnectionsPerHost limit")
  test("enforces maxConnections global limit")
  test("concurrent acquire from multiple fibers")
  test("shutdown closes all connections")
}
```

**Integration tests** (`HttpClientPoolingSpec.scala`):
```scala
class HttpClientPoolingSpec extends munit.FunSuite {
  // Start eru-http server for testing
  test("single request works with pooling")
  test("sequential requests reuse connection")
  test("respects Connection: close from server")
  test("handles connection errors gracefully")
  test("concurrent requests use multiple connections")
  test("pool limits prevent over-connection")
}
```

**Stress test**:
```scala
test("1000 concurrent requests with pool") {
  given runtime: EruRuntime = EruRuntime.shared

  val config = HttpClientConfig.default
    .withMaxConnectionsPerHost(10)
    .withMaxConnections(50)

  val program = HttpClient.scoped(config) { client =>
    // Launch 1000 concurrent requests
    val requests = (1 to 1000).map { i =>
      Uri.parse("http://localhost:8080/plaintext").flatMap { uri =>
        client.send(Request.get(uri))
      }.fork
    }

    // Wait for all to complete
    Eru.foreach(requests)(_.join)
  }

  val results = program.unsafeRunSync()
  assertEquals(results.length, 1000)
  assert(results.forall(_.status == StatusCode.Ok))
}
```

---

## 6. Eru Validation Checklist

### Things to Monitor for Eru Bugs

1. **Ref deadlocks**: Multiple fibers modifying same Ref
   - Watch for: Program hangs during concurrent acquire()
   - Test: 100+ concurrent acquire() calls

2. **Ref race conditions**: Non-atomic operations
   - Watch for: Pool size limits violated (more connections than max)
   - Test: Concurrent acquire() + release()

3. **Fiber leaks**: Forked fibers not properly tracked
   - Watch for: `RuntimeMetrics.global.snapshot()` showing growing fiber count
   - Test: Repeated acquire/release cycles

4. **Resource leaks**: Sockets not closed
   - Watch for: `lsof` showing growing socket count
   - Test: Error paths (connection failures)

5. **Cleanup failures**: Shutdown doesn't close all connections
   - Watch for: Sockets remaining open after shutdown
   - Test: Shutdown under load

6. **Timeout handling**: Acquire timeout doesn't work correctly
   - Watch for: Hangs when pool exhausted
   - Test: Exhaust pool and time acquisition

### How to Check

```scala
// Before test
val beforeMetrics = RuntimeMetrics.global.snapshot()
val beforeSockets = countOpenSockets() // via lsof or similar

// Run test
runStressTest()

// After test
val afterMetrics = RuntimeMetrics.global.snapshot()
val afterSockets = countOpenSockets()

// Validate
assert(afterMetrics.activeFibers == beforeMetrics.activeFibers)
assert(afterSockets == beforeSockets)
```

---

## 7. Simplifications (for v1)

To get a working implementation quickly:

1. **No stale connection detection**: Trust that errors will cause removal
   - Future: Add TTL or health checks

2. **No connection pool statistics**: Focus on correctness first
   - Future: Add metrics (total acquired, reused, created, etc.)

3. **Simple retry logic**: Linear backoff, fixed attempts
   - Future: Configurable retry strategy

4. **No per-host SSL context caching**: Create each time
   - Future: Cache SSLEngine per host

5. **HTTP-only for now**: TLS comes after pooling validated
   - Current: Test with HTTP server
   - Future: Add TLS after pooling works

---

## 8. Success Criteria

### Phase 1 (Implementation Complete)
- [ ] ConnectionPool.scala compiles
- [ ] NativeHttpClient integrated with pool
- [ ] Basic unit tests pass

### Phase 2 (Functional Validation)
- [ ] Can make HTTP request and get response
- [ ] Sequential requests reuse connection (verify via logging)
- [ ] Connection closed when server sends "Connection: close"
- [ ] Pool limits enforced (max per host, max global)

### Phase 3 (Eru Validation)
- [ ] 1000 concurrent requests complete successfully
- [ ] No fiber leaks (RuntimeMetrics stable)
- [ ] No socket leaks (lsof shows cleanup)
- [ ] Graceful shutdown works
- [ ] **Any Eru bugs found are documented**

### Phase 4 (Performance)
- [ ] Benchmark before/after pooling
- [ ] Measure connection reuse rate
- [ ] Latency improvement documented

---

## 9. Open Questions & Decisions

### Q1: Should we validate connection liveness before reuse?
**Decision**: No, for v1. Trust that errors during use will cause removal.
**Rationale**: Simpler implementation, failures are already handled.

### Q2: How to handle stale connections?
**Decision**: Remove on first error. No proactive staleness checking.
**Rationale**: Keeps implementation simple, errors will naturally clean up stale connections.

### Q3: Should pool be per-client or global?
**Decision**: Per-client (field in NativeHttpClient).
**Rationale**: Allows different clients with different pool configs. Cleaner resource management.

### Q4: What if Ref.modify becomes a bottleneck?
**Decision**: Accept it for v1, this is dogfooding to find limits.
**Rationale**: If Ref is too slow, that's valuable feedback for Eru. We can optimize later (sharded Refs, etc.)

---

## 10. Next Steps

1. ✅ Design complete (this document)
2. Implement `ConnectionPool.scala` with Eru Ref
3. Integrate into `NativeHttpClient`
4. Write unit tests
5. Write integration tests (against eru-http server)
6. Run stress tests
7. Benchmark performance
8. Document any Eru bugs found
9. Update ROADMAP.md and STATUS.md
10. Commit and push

---

## 11. References

- **CLAUDE_CONTINUATION_PROMPT.md**: Implementation strategy and requirements
- **NativeHttpClient.scala**: Current client implementation (lines 1-374)
- **NativeHttpServer.scala**: Keep-alive reference (lines 169-186)
- **HttpClientConfig.scala**: Pool configuration parameters
- **ERU_STRUCTURED_CONCURRENCY_REFERENCE.md**: Eru concurrency patterns

---

**Document Status**: Complete, ready for implementation
**Last Updated**: November 13, 2025
