# Connection Pool Design - Using Eru Ref

**Date**: November 13, 2025
**Author**: Jonas Hakim
**Purpose**: Dogfood Eru's `Ref` primitive under concurrent HTTP client load

---

## Overview

This design implements HTTP/1.1 connection pooling for `NativeHttpClient` using Eru's `Ref[PoolState]` for concurrent state management. The primary goal is to stress-test `Ref` under real-world concurrent load while providing efficient connection reuse.

## Why Ref?

Eru's `Ref[A]` provides:
- **Atomic operations**: CAS-based read-modify-write
- **Fiber-safe**: Safe across Virtual Threads
- **Simple API**: No locks, no mutexes, just pure functions
- **Composable**: Fits naturally into Eru's effect system

This is perfect for connection pool state management where multiple concurrent requests need to atomically acquire/release connections.

## Architecture

### Data Structures

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

/** Pool state - managed by Ref[PoolState] */
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

**Key Design Choices**:
- `Queue[PooledConnection]` for FIFO connection reuse (oldest first)
- Separate `available` and `inUse` tracking prevents double-use
- Immutable case classes for safe sharing across fibers
- Host-based keying enables per-host limits

### Connection Pool Interface

```scala
trait ConnectionPool {
  /** Acquire a connection to the specified host:port.
    *
    * This may:
    * - Return an existing connection from the pool
    * - Create a new connection if under limits
    * - Retry with exponential backoff if at limits
    */
  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection]

  /** Release a connection back to the pool for reuse.
    */
  def release(conn: PooledConnection): Eru[HttpError, Unit]

  /** Remove a connection from the pool and close it.
    *
    * Used when:
    * - Connection error occurs
    * - Server sends Connection: close
    * - HTTP/1.0 response (no keep-alive)
    */
  def remove(conn: PooledConnection): Eru[HttpError, Unit]

  /** Shutdown the pool and close all connections.
    */
  def shutdown: Eru[HttpError, Unit]
}
```

### Ref-Based Implementation

The core insight is using `Ref.modify` for atomic read-modify-write operations:

```scala
class NativeConnectionPool(
  stateRef: Ref[PoolState],
  config: HttpClientConfig,
  connectTimeout: Duration
) extends ConnectionPool {

  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    attemptAcquire(host, port, attempt = 0)
  }

  private def attemptAcquire(
    host: String,
    port: Int,
    attempt: Int
  ): Eru[HttpError, PooledConnection] = {
    for {
      // Atomically try to get connection or make decision
      decision <- stateRef.modify { state =>
        val key = s"$host:$port"

        // Try to get from available queue
        state.available.get(key).flatMap(_.headOption) match {
          case Some(conn) =>
            // Found available connection - take it atomically
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
            // No available connection - check limits
            val hostConns = state.hostConnections(host, port)
            val totalConns = state.totalConnections

            if hostConns >= config.maxConnectionsPerHost then
              (state, Left("host-limit"))
            else if totalConns >= config.maxConnections then
              (state, Left("global-limit"))
            else
              // Can create new - don't modify state yet
              (state, Left("create-new"))
        }
      }

      // Handle decision outside of modify (no I/O inside modify!)
      conn <- decision match {
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
          // At limit - retry with exponential backoff
          if attempt < 10 then
            val delay = Math.min(10 * Math.pow(2, attempt).toLong, 5000)
            for {
              _ <- Eru.sleep(delay.millis)
              result <- attemptAcquire(host, port, attempt + 1)
            } yield result
          else
            Eru.fail(HttpError.TimeoutError(
              s"Pool exhausted: cannot acquire connection to $host:$port after 10 retries"
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
    }.map(_ => ())
      .mapError(_ => HttpError.NetworkError("Failed to release connection", None))
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

**Critical Design Points**:

1. **No I/O inside `modify`**: The `modify` block only makes decisions (return connection OR return instruction to create new). Actual I/O happens outside.

2. **Atomic state transitions**: Using `modify`, we ensure that checking limits and taking a connection are atomic - no race conditions.

3. **Exponential backoff**: When limits are reached, we retry with increasing delays (10ms, 20ms, 40ms, ..., up to 5s).

4. **Proper cleanup**: Errors and `Connection: close` trigger `remove()` which closes the socket.

### Integration with NativeHttpClient

The client will be modified to use the pool:

```scala
private[client] final class NativeHttpClient(
  config: HttpClientConfig,
  sslContext: Option[SSLContext],
  pool: ConnectionPool,  // NEW!
  requestInterceptors: List[RequestInterceptor] = List.empty,
  responseInterceptors: List[ResponseInterceptor] = List.empty
)(using runtime: EruRuntime)
    extends HttpClient {

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

      // Release or remove based on result
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
      _ <- HttpWriter.writeRequest(secureSocket, request).timeout(...)
      response <- HttpParser.parseResponse(secureSocket).timeout(...)
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

  def shutdown: Eru[Nothing, Unit] =
    pool.shutdown.orElse(Eru.unit)
}
```

**Key Integration Points**:

1. Pool is created once during client initialization
2. `executeRequest()` uses try-acquire-finally pattern
3. Connection reuse based on HTTP/1.1 keep-alive semantics
4. Proper cleanup in all error paths

## Testing Strategy

### Unit Tests (ConnectionPoolSpec.scala)

Focus on pool mechanics:
1. Pool creation and shutdown
2. Acquire creates new connection when empty
3. Release returns connection to pool
4. Second acquire reuses released connection
5. Remove closes socket and removes from pool
6. Per-host limit enforcement
7. Global limit enforcement
8. Concurrent acquire from multiple fibers
9. Exponential backoff at limits
10. Shutdown closes all connections
11. Error handling and cleanup

### Integration Tests (HttpClientPoolingSpec.scala)

Focus on real HTTP scenarios:
1. Single request works end-to-end
2. Sequential requests reuse connection
3. Concurrent requests use multiple connections
4. Respects "Connection: close" from server
5. HTTP/1.1 defaults to keep-alive
6. Pool limits prevent over-connection
7. Connection cleanup on errors
8. Mixed concurrent and sequential patterns
9. Stress test: 100 concurrent requests
10. Stress test: 1000 concurrent requests
11. Performance validation

### Ref Validation Focus

During testing, we specifically watch for:
- **Race conditions**: Do concurrent acquires/releases corrupt state?
- **Deadlocks**: Does the CAS loop ever hang?
- **Fairness**: Do waiting fibers eventually acquire connections?
- **Memory leaks**: Are connections properly cleaned up?
- **Performance**: How does Ref perform under load?

## Success Criteria

1. All tests pass (21+ tests total)
2. 100+ concurrent requests complete successfully
3. No connection leaks (verify with socket tracking)
4. Ref operations are atomic (no corrupt state)
5. Performance is acceptable (document metrics)
6. Document any Ref bugs found (that's a win!)

## Expected Challenges

1. **First I/O in modify mistake**: Easy to accidentally put I/O in the modify block. The compiler won't catch this - tests will.

2. **Error path cleanup**: Ensuring connections are removed on all error paths.

3. **Timeout tuning**: Balancing request timeouts vs pool acquire retries.

4. **Connection validation**: Detecting stale connections (server closed our end).

## Benefits

Once implemented:
- **Resource efficiency**: Reuse connections instead of creating new ones
- **Lower latency**: Avoid TCP handshake + TLS handshake on reuse
- **Controlled load**: Limits prevent overwhelming servers
- **Eru dogfooding**: Real-world validation of Ref under concurrency

## Future Enhancements

Not in scope for V1, but possible later:
- Connection eviction (max idle time, max lifetime)
- Health checks (validate connection before reuse)
- Metrics (pool utilization, wait times, etc.)
- HTTP/2 multiplexing (different pooling model)
- DNS-aware pooling (handle IP changes)

---

## Implementation Checklist

- [ ] Create `ConnectionPool.scala` with trait and implementation
- [ ] Modify `NativeHttpClient.scala` to use pool
- [ ] Write `ConnectionPoolSpec.scala` unit tests
- [ ] Write `HttpClientPoolingSpec.scala` integration tests
- [ ] Run tests and fix issues
- [ ] Create `ERU_REF_VALIDATION.md` with findings
- [ ] Update documentation

**Let's dogfood Ref!** 🚀
