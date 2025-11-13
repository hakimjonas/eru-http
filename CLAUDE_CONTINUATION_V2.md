# Connection Pooling Implementation V2 - Using Eru Ref

**Date**: November 13, 2025
**Branch**: Start from `http-client`
**Task**: Implement HTTP client connection pooling using Eru's `Ref` primitive

---

## CRITICAL: Commit Attribution

**ALL commits must be in the repository owner's name ONLY:**
- ❌ NO "Claude" mentions anywhere
- ❌ NO "Anthropic" mentions anywhere
- ❌ NO "AI-generated" or similar watermarks
- ❌ NO co-author tags

**Commit messages format**:
```
Short description of change

Detailed explanation if needed.

- Bullet points for key changes
- Implementation details
```

Example:
```
Implement connection pooling with Eru Ref

Add ConnectionPool using Ref for concurrent state management:
- Acquire/release/remove operations
- Per-host connection limits
- Global pool limits with backoff
- HTTP/1.1 keep-alive detection
```

---

## What Went Wrong in V1

Your first attempt was excellent but had one critical issue:

**You assumed Eru's `Ref` doesn't exist - but it DOES!**

You cannot access the Eru repository directly (cloud limitation), but `Ref` is available in the eru-http build. Here's what you need to know:

### Eru Ref API (Critical Information)

Eru has a `Ref[A]` type for concurrent mutable state. You can find examples by searching the existing eru-http codebase, but here's the API:

```scala
import net.ghoula.eru.Ref

// Create a Ref
val ref: Eru[Nothing, Ref[A]] = Ref.make(initialValue)

// Or in comprehension:
for {
  ref <- Ref.make(PoolState.empty)
  // use ref...
} yield ()

// Read value
ref.get: Eru[Nothing, A]

// Write value
ref.set(newValue): Eru[Nothing, Unit]

// Atomic modify (most important!)
ref.modify { currentState =>
  val newState = ... // compute new state
  val result = ...   // compute result to return
  (newState, result) // Return (newState, result)
}: Eru[Nothing, Result]

// Atomic update (when you don't need a result)
ref.update { currentState =>
  ... // compute and return new state
}: Eru[Nothing, Unit]
```

**Key points**:
- All operations return `Eru[Nothing, A]` (never fail)
- `modify` is atomic and returns a value
- Use `modify` for read-modify-write operations
- This is thread-safe for concurrent Virtual Thread access

### Eru Imports You'll Need

```scala
import net.ghoula.eru.*
import net.ghoula.eru.Ref
import net.ghoula.eru.prelude.*  // For .timeout extension method
```

**The `.timeout` extension** is in `eru.prelude.*` - this is what caused your compilation errors.

---

## Mission: Dogfooding Eru Ref

**Primary Goal**: Stress-test Eru's `Ref` under concurrent HTTP client load.

**Success Criteria**:
1. Connection pooling works correctly
2. Uses `Ref[PoolState]` for all state management
3. Tests pass with 100+ concurrent requests
4. Document any Ref bugs/issues found
5. Performance is reasonable

**It's OK to find bugs!** That's the whole point. Document them clearly.

---

## Implementation Strategy

### Step 1: Study the Codebase First

**Before writing ANY code:**

1. Read `NativeHttpClient.scala` completely (eru-http-client/src/main/scala/.../NativeHttpClient.scala)
   - Understand current `connect()` and `executeRequest()` flow
   - Note the TODO at line 221 about socket tracking
   - See how `shouldReuseConnection()` might work (look at server for reference)

2. Read `NativeHttpServer.scala` (eru-http-server/src/main/scala/.../NativeHttpServer.scala)
   - Study `shouldKeepAlive()` logic (lines ~169-186)
   - Understand HTTP/1.1 keep-alive semantics
   - See how connection cleanup works

3. Look for any Ref usage examples in the codebase:
   ```bash
   # Search for Ref usage patterns
   grep -r "Ref\\.make\|Ref\[" eru-http-*/src/
   ```

4. Check `HttpClientConfig.scala`:
   - Note `maxConnections` and `maxConnectionsPerHost` already exist
   - These are your pool limits

### Step 2: Design with Eru Ref

Create `CONNECTION_POOL_DESIGN.md` with:

**Data Structures**:
```scala
/** Connection wrapper with metadata */
private[client] final case class PooledConnection(
  socket: SocketChannel,
  host: String,
  port: Int,
  createdAt: Instant,
  lastUsedAt: Instant
) {
  def key: String = s"$host:$port"
  def withLastUsed(time: Instant): PooledConnection = copy(lastUsedAt = time)
}

/** Pool state - managed by Ref */
private[client] final case class PoolState(
  // Available connections per host (key = "host:port")
  available: Map[String, Queue[PooledConnection]],
  // Connections currently in use
  inUse: Set[PooledConnection]
) {
  def totalConnections: Int =
    available.values.map(_.size).sum + inUse.size

  def hostConnections(host: String, port: Int): Int = {
    val key = s"$host:$port"
    available.get(key).map(_.size).getOrElse(0) +
      inUse.count(c => c.key == key)
  }
}

object PoolState {
  val empty: PoolState = PoolState(Map.empty, Set.empty)
}
```

**Pool Operations using Ref.modify**:
```scala
class NativeConnectionPool(
  stateRef: Ref[PoolState],
  config: HttpClientConfig,
  connectTimeout: Duration
) extends ConnectionPool {

  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    // Try to get from pool or create new
    attemptAcquire(host, port, attempt = 0)
  }

  private def attemptAcquire(
    host: String,
    port: Int,
    attempt: Int
  ): Eru[HttpError, PooledConnection] = {
    for {
      // Atomically try to get or decide to create
      result <- stateRef.modify { state =>
        val key = s"$host:$port"

        // Try to get from available queue
        state.available.get(key).flatMap(_.headOption) match {
          case Some(conn) =>
            // Found available connection - take it
            val newQueue = state.available(key).tail
            val newAvailable =
              if newQueue.isEmpty then state.available - key
              else state.available + (key -> newQueue)
            val newState = state.copy(
              available = newAvailable,
              inUse = state.inUse + conn
            )
            (newState, Right(conn))

          case None =>
            // No available connection
            // Check limits
            val hostConns = state.hostConnections(host, port)
            val totalConns = state.totalConnections

            if hostConns >= config.maxConnectionsPerHost then
              (state, Left("host-limit"))
            else if totalConns >= config.maxConnections then
              (state, Left("global-limit"))
            else
              // Can create new connection
              // Reserve spot by not modifying state yet
              (state, Left("create-new"))
        }
      }

      // Handle result
      conn <- result match {
        case Right(conn) =>
          // Got connection from pool
          Eru.succeed(conn)

        case Left("create-new") =>
          // Create new connection and mark as in-use
          for {
            newConn <- createConnection(host, port)
            _ <- stateRef.update(s => s.copy(inUse = s.inUse + newConn))
          } yield newConn

        case Left(_) =>
          // At limit, retry with backoff
          if attempt < 10 then
            val delay = Math.min(10 * Math.pow(2, attempt).toLong, 5000)
            for {
              _ <- Eru.sleep(delay.millis)
              result <- attemptAcquire(host, port, attempt + 1)
            } yield result
          else
            Eru.fail(HttpError.TimeoutError(
              s"Pool exhausted: cannot acquire connection to $host:$port"
            ))
      }
    } yield conn
  }

  def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    stateRef.update { state =>
      val key = conn.key
      val queue = state.available.getOrElse(key, Queue.empty)
      val newQueue = queue.enqueue(conn.withLastUsed(Instant.now()))
      state.copy(
        available = state.available + (key -> newQueue),
        inUse = state.inUse - conn
      )
    }.mapError(_ => HttpError.NetworkError("Failed to release connection", None))
  }

  def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      _ <- Eru.effect { conn.socket.close() }
        .mapError(e => HttpError.NetworkError(s"Failed to close socket: ${e.getMessage}", Some(e)))
      _ <- stateRef.update { state =>
        val key = conn.key
        val queue = state.available.getOrElse(key, Queue.empty)
        state.copy(
          available = state.available + (key -> queue.filterNot(_ == conn)),
          inUse = state.inUse - conn
        )
      }
    } yield ()
  }

  def shutdown: Eru[HttpError, Unit] = {
    for {
      state <- stateRef.get
      allConns = state.available.values.flatten.toList ++ state.inUse.toList
      _ <- Eru.foreach(allConns) { conn =>
        Eru.effect { conn.socket.close() }
          .mapError(e => HttpError.NetworkError(s"Failed to close socket: ${e.getMessage}", Some(e)))
          .orElse(Eru.unit)
      }
      _ <- stateRef.set(PoolState.empty)
    } yield ()
  }

  private def createConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    for {
      socket <- connectSocket(host, port)
      now <- Eru.effect(Instant.now())
        .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield PooledConnection(socket, host, port, now, now)
  }

  private def connectSocket(host: String, port: Int): Eru[HttpError, SocketChannel] = {
    // Similar to current NativeHttpClient.connect()
    val connectEffect = Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)
      socket.connect(new InetSocketAddress(host, port))
      socket
    }

    connectEffect.attempt.flatMap {
      case Result.Success(socket) => Eru.succeed(socket)
      case Result.Failure(e) =>
        Eru.fail(HttpError.ConnectionError(
          s"Failed to connect to $host:$port: ${e.getMessage}",
          Some(e)
        ))
    }
    .timeout(java.time.Duration.ofMillis(connectTimeout.toMillis))
    .mapError {
      case e: HttpError => e
      case e => HttpError.ConnectionError(s"Connection timeout: ${e.getMessage}", Some(e))
    }
  }
}
```

**Key Design Points**:
- Use `Ref.modify` for atomic read-modify-write
- Return connection OR decision to create in modify block
- Create outside modify block (don't do I/O inside modify)
- Use exponential backoff when limits reached
- Proper cleanup in all error paths

### Step 3: Implement

**File**: `eru-http-client/src/main/scala/net/ghoula/eru/http/client/ConnectionPool.scala`

1. Create the file with imports
2. Define `PooledConnection` case class
3. Define `PoolState` case class
4. Define `ConnectionPool` trait (interface)
5. Implement `NativeConnectionPool` using Ref
6. Add `ConnectionPool.create` factory

**File**: Modify `NativeHttpClient.scala`

1. Add `pool: ConnectionPool` field
2. Modify `create()` to initialize pool
3. Refactor `executeRequest()` to use pool:
   ```scala
   private def executeRequest(
     host: String,
     port: Int,
     request: Request[Body]
   ): Eru[HttpError, Response[Bytes]] = {
     for {
       // Get connection from pool
       conn <- pool.acquire(host, port)

       // Use connection (with error handling)
       result <- useConnection(conn, request).attempt

       // Handle result - release or remove
       _ <- result match {
         case Result.Success(response) =>
           if shouldReuseConnection(response) then
             pool.release(conn)
           else
             pool.remove(conn)

         case Result.Failure(_) =>
           pool.remove(conn)  // Error - discard connection
       }

       // Return response or error
       response <- result match {
         case Result.Success(r) => Eru.succeed(r)
         case Result.Failure(e) => Eru.fail(e)
       }
     } yield response
   }

   private def useConnection(
     conn: PooledConnection,
     request: Request[Body]
   ): Eru[HttpError, Response[Bytes]] = {
     for {
       secureSocket <- wrapWithTLS(conn.socket, ...)
       _ <- HttpWriter.writeRequest(secureSocket, request)
         .timeout(...)
       response <- HttpParser.parseResponse(secureSocket)
         .timeout(...)
       responseBytes = convertBodyToBytes(response)
     } yield responseBytes
   }

   private def shouldReuseConnection(response: Response[Bytes]): Boolean = {
     val connHeader = response.headers
       .getFirst(HeaderNames.Connection)
       .map(_.value.toLowerCase)

     if connHeader.contains("close") then false
     else response.version == HttpVersion.HTTP_1_1 ||
          connHeader.contains("keep-alive")
   }
   ```

4. Update `shutdown()` to close pool:
   ```scala
   def shutdown: Eru[Nothing, Unit] =
     pool.shutdown.orElse(Eru.unit)
   ```

### Step 4: Write Tests

**File**: `eru-http-client/src/test/scala/net/ghoula/eru/http/client/ConnectionPoolSpec.scala`

Unit tests for the pool itself (11+ tests):
- Pool creation and shutdown
- Acquire creates new connection when empty
- Release returns connection to pool
- Second acquire reuses released connection
- Remove closes socket and removes from pool
- Per-host limit enforcement
- Global limit enforcement
- Concurrent acquire from multiple fibers
- Shutdown closes all connections
- Error handling

**File**: `eru-http-client/src/test/scala/net/ghoula/eru/http/client/HttpClientPoolingSpec.scala`

Integration tests with real HTTP (10+ tests):
- Single request works
- Sequential requests reuse connection
- Concurrent requests use multiple connections
- Respects "Connection: close" from server
- HTTP/1.1 defaults to keep-alive
- Pool limits prevent over-connection
- **Stress test: 100-1000 concurrent requests**
- Error handling (connection failures)
- Full end-to-end validation

**Run tests**:
```bash
sbt "client/test"
```

### Step 5: Document Findings

Create `ERU_REF_VALIDATION.md`:

```markdown
# Eru Ref Validation Report

**Date**: November 13, 2025
**Component**: Connection Pool using Ref[PoolState]
**Load**: 100+ concurrent requests

## Summary

[Did Ref work correctly? Any bugs found?]

## Test Results

### Correctness
- [ ] Atomic operations work correctly
- [ ] No race conditions observed
- [ ] Limits enforced properly
- [ ] Cleanup works on all paths

### Performance
- Time for 100 sequential requests: X ms
- Time for 100 concurrent requests: Y ms
- Avg acquire latency: Z ms

### Ref-Specific Observations
- [Any deadlocks?]
- [Any contention issues?]
- [Memory usage?]
- [Fiber leaks?]

## Bugs Found

[List any Eru Ref bugs discovered]

OR

[No bugs found - Ref worked perfectly under load]

## Recommendations

[Suggestions for Eru or connection pool improvements]
```

---

## Important Notes

### Testing Strategy

1. **Start small**: Get one request working first
2. **Sequential then concurrent**: Test reuse before concurrency
3. **Gradually increase load**: 10 → 100 → 1000 requests
4. **Monitor resources**: Check for leaks with `lsof` or `RuntimeMetrics`

### Common Issues to Watch For

1. **I/O in Ref.modify**:
   - ❌ DON'T create connections inside modify
   - ✅ DO return decision, then create outside

2. **Error handling**:
   - Always remove connection on error
   - Don't leak connections in error paths

3. **Timeout imports**:
   - `import net.ghoula.eru.prelude.*`
   - This adds `.timeout` extension method

4. **Type mismatches**:
   - Ref operations return `Eru[Nothing, A]`
   - Use `.mapError` to convert if needed
   - Use `.orElse(Eru.unit)` for cleanup that shouldn't fail

### Debugging

If tests fail:
```scala
// Add debug logging
for {
  state <- stateRef.get
  _ <- Eru.effect(println(s"Pool state: $state"))
  // ... rest of operation
} yield ()
```

Check RuntimeMetrics:
```scala
val metrics = RuntimeMetrics.global.snapshot()
println(s"Active fibers: ${metrics.fibersActive}")
println(s"Total operations: ${metrics.totalEffects}")
```

---

## Deliverables Checklist

- [ ] `CONNECTION_POOL_DESIGN.md` - Architecture with Ref approach
- [ ] `ConnectionPool.scala` - Implementation using Ref
- [ ] `NativeHttpClient.scala` - Modified to use pool
- [ ] `ConnectionPoolSpec.scala` - Unit tests (11+ tests)
- [ ] `HttpClientPoolingSpec.scala` - Integration tests (10+ tests)
- [ ] `ERU_REF_VALIDATION.md` - Findings report
- [ ] All tests pass
- [ ] Documentation updated (README, STATUS, ROADMAP)
- [ ] All commits have proper attribution (NO Claude/Anthropic mentions!)

---

## Final Notes

**This is dogfooding!**
- Finding Ref bugs = Success
- Performance issues = Document them
- Unexpected behavior = Report it

**Focus on correctness first**:
- Get it working with Ref
- Then optimize if needed
- Document trade-offs

**Commit often**:
- Small, focused commits
- Clear messages
- Test after each major change

**Start fresh**:
- Ignore your V1 implementation
- Start from `http-client` branch
- Follow this design using Ref

Good luck! Remember: finding bugs in Eru Ref is a WIN, not a failure. 🚀
