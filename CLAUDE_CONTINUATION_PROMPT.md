# Connection Pooling Implementation - Claude Code Continuation

**Date**: November 13, 2025
**Branch**: `http-client`
**Task**: Implement HTTP client connection pooling with Eru primitives

---

## Context & Mission

You are continuing work on **eru-http**, a high-performance HTTP/1.1 library built on Eru effects and Java 21 Virtual Threads. The server is complete and world-class (170-210k req/sec), and the client is 80% done. Your mission is to implement connection pooling for the HTTP client.

**Critical Context**: This is **dogfooding/validation work for Eru**. The primary goal is to stress-test Eru's client-side resource management and discover any bugs. Connection pooling is the vehicle for this validation.

---

## Repository Setup

```bash
# Clone and checkout the branch
git clone https://github.com/hakimjonas/eru-http.git
cd eru-http
git checkout http-client

# Ensure local Eru is available (required for local development)
cd ../eru
sbt publishLocal
cd ../eru-http

# Verify everything compiles
sbt compile
```

**Important**: eru-http depends on a local Eru build. The `build.sbt` automatically uses local Eru when `GITHUB_TOKEN` is not set.

---

## Project Status (Read First!)

### What's Complete ✅
- **Core HTTP types** (100%) - Method, StatusCode, Headers, Uri, Request, Response, Body, etc.
- **HTTP/1.1 Server** (100%) - Production ready, 170-210k req/sec
- **HTTP/1.1 Client** (80%) - Core functionality works, missing pooling/TLS/streaming

### Current Sprint: Connection Pooling 🔄
**Goal**: Validate Eru's client-side resource management through connection pooling implementation

**Why pooling first (not TLS)**:
1. Simpler to debug (HTTP-only, no TLS complexity)
2. Tests Eru fundamentals (Ref, structured concurrency, cleanup)
3. Can dogfood immediately (client → eru-http server, both HTTP)
4. More likely to reveal Eru bugs (which is the goal!)

### Architecture
- **No Netty** - Uses native blocking NIO (SocketChannel/ServerSocketChannel)
- **Virtual Threads** - One VT per connection (server) or request (client)
- **Eru effects** - All operations return `Eru[E, A]`
- **Structured concurrency** - Automatic cleanup via FiberTracker

---

## Technical Background

### Key Files to Understand

**Client Implementation**:
- `eru-http-client/src/main/scala/net/ghoula/eru/http/client/NativeHttpClient.scala`
  - Current implementation: ~374 lines
  - Opens new socket per request (line 227-250: `connect()`)
  - TODO at line 221: "Properly track and close socket"
  - Each request: connect → write → read → close

**Eru Primitives Available**:
- `Ref[A]` - Mutable reference with Eru effects (see Eru docs)
- `Semaphore` - Concurrency control (see Eru docs)
- `FiberTracker` - Already used in server (see server/NativeHttpServer.scala:35)
- Structured concurrency via `.fork` and scoped fibers

**Server Reference** (for inspiration):
- `eru-http-server/src/main/scala/net/ghoula/eru/http/server/NativeHttpServer.scala`
  - Uses FiberTracker for connection cleanup (line 35)
  - Keep-alive support (lines 112-167)
  - Connection header management (lines 169-186)

### Connection Pooling Requirements

**What the pool needs to do**:
1. Reuse TCP connections (HTTP/1.1 keep-alive)
2. Track available vs in-use connections
3. Handle pool limits (max connections per host)
4. Handle acquire timeout (when pool is exhausted)
5. Cleanup stale/closed connections
6. Graceful shutdown (close all connections)
7. **Thread-safe** - Multiple fibers acquiring concurrently

**Connection lifecycle**:
```scala
acquire() -> Connection available
  ↓
use connection (send request, read response)
  ↓
release() -> Connection back to pool (if keep-alive)
       OR
close() -> Connection dead, remove from pool
```

---

## Implementation Strategy

### Phase 1: Design (Read & Plan First)

**Before writing code**:
1. Read `NativeHttpClient.scala` completely
2. Read Eru's `Ref` and `Semaphore` documentation (in Eru repo)
3. Study how server uses `FiberTracker` for cleanup
4. Understand current `connect()` and `executeRequest()` flow

**Design questions to answer**:
- Where should the pool live? (In `NativeHttpClient` as a field?)
- What data structure? (Ref[Map[Host, Queue[Connection]]]?)
- How to detect stale connections? (Timeout? Test before use?)
- How to respect keep-alive headers from server?
- How to integrate with existing `executeRequest()`?

**Write a design document** before coding:
- Create `CONNECTION_POOL_DESIGN.md`
- Outline data structures, lifecycle, concurrency model
- Identify Eru patterns you'll use (Ref, Semaphore, structured concurrency)
- Plan how to test/validate (against eru-http server)

### Phase 2: Core Pool Implementation

**Step 1: Connection wrapper**
```scala
// Example structure (adapt as needed)
case class PooledConnection(
  socket: SocketChannel,
  host: String,
  port: Int,
  acquiredAt: Instant,
  lastUsed: Instant
)

// Pool state
case class ConnectionPool(
  available: Map[String, Queue[PooledConnection]], // key = "host:port"
  inUse: Set[PooledConnection],
  maxPerHost: Int,
  acquireTimeout: Duration
)
```

**Step 2: Pool operations**
```scala
trait ConnectionPool {
  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection]
  def release(conn: PooledConnection): Eru[HttpError, Unit]
  def remove(conn: PooledConnection): Eru[HttpError, Unit]
  def shutdown: Eru[HttpError, Unit]
}
```

**Step 3: Integrate into NativeHttpClient**
- Add `pool: Ref[ConnectionPool]` field
- Modify `executeRequest()` to use pool instead of `connect()`
- Parse `Connection` header to decide reuse vs close
- Handle errors (connection closed, timeout, etc.)

### Phase 3: Testing & Validation

**Test setup**:
1. Start eru-http server: `sbt "server/Test/runMain net.ghoula.eru.http.server.BenchmarkServer"`
2. Run client against server: HTTP requests to `http://localhost:8080/plaintext`

**Tests to write** (in `HttpClientSpec.scala`):
- Single request (basic smoke test)
- Multiple sequential requests (connection reuse)
- Concurrent requests (pool contention)
- Pool exhaustion (max connections reached)
- Stale connection handling
- Graceful shutdown

**Benchmarking**:
```bash
# Compare pooled vs non-pooled
# Non-pooled baseline (current implementation)
sbt "client/test:runMain net.ghoula.eru.http.client.ClientBenchmark"

# With pooling (after implementation)
sbt "client/test:runMain net.ghoula.eru.http.client.ClientBenchmark"
```

**Look for Eru bugs**:
- Ref deadlocks or race conditions?
- Fiber leaks (check with `RuntimeMetrics.global`)?
- Resource leaks (connections not closed)?
- Cleanup failures during shutdown?

### Phase 4: Stress Testing

**High concurrency test**:
```scala
// Example test structure
val program = for {
  client <- HttpClient.create(config.withConnectionPooling(maxPerHost = 10))

  // Launch 1000 concurrent requests
  fibers <- Eru.foreach(1 to 1000) { _ =>
    client.send(Request.get(uri)).fork
  }

  // Wait for all to complete
  results <- Eru.foreach(fibers)(_.join)

  _ <- client.shutdown
} yield results
```

**Things to monitor**:
- Does pool correctly limit connections?
- Are connections reused properly?
- Any deadlocks under load?
- Memory usage (check for leaks)
- Eru fiber tracking (any leaks?)

---

## Code Quality Standards

### Eru Philosophy (Critical!)
- **All operations return `Eru[E, A]`** (never throws, no Either in public API)
- **Resource safety** via structured concurrency
- **No type casts** - Forbidden in Eru codebase
- **No mutable state** except via Ref (in Eru effect context)

### Scala 3 Style
- Use modern syntax (`if-then-else`, `given`, no implicits)
- Opaque types for validated values
- Inline methods for zero-cost abstractions

### Testing
- Use munit framework (see existing tests)
- Test both success and failure cases
- Property-based tests where applicable

### Documentation
- ScalaDoc on public methods
- Comments for complex logic
- Keep README/ROADMAP updated with progress

---

## Deliverables

### 1. Design Document
- `CONNECTION_POOL_DESIGN.md` with architecture decisions
- Data structures and algorithms
- Concurrency model and Eru patterns used
- Integration plan with existing client

### 2. Implementation
- `ConnectionPool.scala` (or similar) with pool logic
- Updated `NativeHttpClient.scala` to use pool
- Configuration in `HttpClientConfig.scala` (pool size, timeouts)

### 3. Tests
- Unit tests for pool operations
- Integration tests against eru-http server
- Stress tests for concurrency

### 4. Benchmarks
- Before/after performance comparison
- Document any performance improvements
- Document connection reuse statistics

### 5. Documentation
- Update ROADMAP.md (mark connection pooling as complete)
- Update STATUS.md (progress update)
- Add section to README.md on connection pooling configuration

---

## Important Notes

### Debugging Tips
- Use `Eru.effect(println(...))` for debugging
- Check `RuntimeMetrics.global.snapshot()` for fiber stats
- Use `.debug("label")` on Eru effects for tracing
- Test small pieces incrementally

### Common Pitfalls to Avoid
- **Don't block OS threads** - Use Eru effects, not blocking calls outside effects
- **Don't use synchronized** - Use Eru Ref instead
- **Don't forget cleanup** - Every acquired connection must be released/removed
- **Don't assume connection is alive** - Test before reuse or handle errors gracefully

### If You Find Eru Bugs
1. Create a minimal reproduction case
2. Document in `ERU_BUGS_FOUND.md` with:
   - Description of bug
   - Stack trace or symptoms
   - Minimal code to reproduce
   - Expected vs actual behavior
3. Continue working around the bug if possible
4. Flag for user to fix in Eru repo

### Testing Against Local Eru
If you need to test Eru changes:
```bash
cd ../eru
# Make changes to Eru
sbt publishLocal
cd ../eru-http
sbt clean compile test
```

---

## Success Criteria

**Phase 1 Success** (Design):
- [ ] Design document complete and reviewed
- [ ] Data structures defined
- [ ] Concurrency model clear
- [ ] Integration approach planned

**Phase 2 Success** (Implementation):
- [ ] ConnectionPool trait and implementation complete
- [ ] NativeHttpClient integrated with pool
- [ ] Configuration options added
- [ ] Code compiles without errors

**Phase 3 Success** (Testing):
- [ ] Unit tests pass
- [ ] Integration tests pass against server
- [ ] Connections are reused (verify in tests)
- [ ] Pool limits work correctly
- [ ] Graceful shutdown works

**Phase 4 Success** (Validation):
- [ ] Stress tests pass (1000+ concurrent requests)
- [ ] No Eru bugs found OR bugs documented
- [ ] Performance measured (before/after)
- [ ] Memory stable (no leaks)

**Final Success** (Documentation):
- [ ] ROADMAP.md updated
- [ ] STATUS.md updated
- [ ] README.md has pooling configuration docs
- [ ] All code committed with clear messages

---

## Communication

### Commit Messages
Follow this format:
```
Implement connection pool for HTTP client

- Add ConnectionPool with Ref-based state management
- Integrate pool into NativeHttpClient.executeRequest
- Add configuration options (maxPerHost, acquireTimeout)
- Tests for concurrent access and pool limits
```

### Progress Updates
Create TODO comments for work in progress:
```scala
// TODO: Handle connection timeout detection
// TODO: Add metrics for pool statistics
```

### Questions/Blockers
If stuck, document in comments:
```scala
// QUESTION: Should we test connection liveness before reuse?
// BLOCKED: Ref.modify is not atomic across multiple fibers
```

---

## Final Notes

**Remember**:
1. **Dogfooding is the goal** - Finding Eru bugs is success, not failure
2. **Simplicity over features** - Basic pooling that works > complex pooling that's buggy
3. **Test incrementally** - Don't write everything then test
4. **HTTP-only for now** - TLS comes later (after pooling works)
5. **Ask questions** - Document anything unclear for review

**After completion**:
- Commit all changes
- Push to `http-client` branch
- Update this file with findings/lessons learned
- Leave notes for next session (TLS implementation)

Good luck! The server proved Eru can handle 170-210k req/sec. Let's see what the client can do! 🚀
