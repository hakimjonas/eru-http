package net.ghoula.eru.http.client

import jdk.net.ExtendedSocketOptions

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.time.Instant
import scala.collection.immutable.Queue
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.SSLSocketChannel
import net.ghoula.eru.http.h2.H2ClientConnection
import net.ghoula.eru.prelude.*

/** Opaque type for host:port connection keys.
  *
  * Ensures type safety and consistent key formatting throughout the pool.
  */
private[client] opaque type HostKey = String

private[client] object HostKey {
  def apply(host: String, port: Int): HostKey = s"$host:$port"
}

/** Connection wrapper with metadata for pool management.
  *
  * Pure immutable data; write buffers and readers are managed separately by the pool.
  */
private[client] final case class PooledConnection(
  socket: SocketChannel,
  host: String,
  port: Int,
  createdAt: Instant,
  lastUsedAt: Instant
) {
  def key: HostKey = HostKey(host, port)
  def withLastUsed(time: Instant): PooledConnection =
    copy(lastUsedAt = time)
}

/** Immutable pool state managed by Ref[PoolState].
  *
  * This structure is designed for atomic updates via Eru's Ref primitive. `available` holds the
  * idle connections per host (FIFO), `inUse` the connections currently checked out.
  */
private[client] final case class PoolState(
  available: Map[HostKey, Queue[PooledConnection]],
  inUse: Set[PooledConnection]
) {
  def totalConnections: Int = {
    val availableCount = available.values.map(_.size).sum
    val inUseCount = inUse.size
    availableCount + inUseCount
  }

  def hostConnections(host: String, port: Int): Int = {
    val key = HostKey(host, port)
    val availableCount = available.get(key).map(_.size).getOrElse(0)
    val inUseCount = inUse.count(_.key == key)
    availableCount + inUseCount
  }
}

private[client] object PoolState {
  val empty: PoolState = PoolState(Map.empty, Set.empty)
}

/** HTTP connection pool interface.
  *
  * Manages connection lifecycle: creation, reuse, and cleanup.
  */
private[client] trait ConnectionPool {

  /** Acquire a connection to the specified host:port.
    *
    * This may:
    *   - Return an existing connection from the pool
    *   - Create a new connection if under limits
    *   - Block until a permit is available when at limits (fair FIFO via the semaphores)
    *
    * @param host
    *   the target host
    * @param port
    *   the target port
    * @return
    *   an effect that yields a pooled connection or fails with HttpError
    */
  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection]

  /** Release a connection back to the pool for reuse.
    *
    * The connection is moved from in-use to available and can be acquired again.
    *
    * @param conn
    *   the connection to release
    * @return
    *   an effect that completes when the connection is released
    */
  def release(conn: PooledConnection): Eru[HttpError, Unit]

  /** Remove a connection from the pool and close it.
    *
    * Used when:
    *   - Connection error occurs
    *   - Server sends Connection: close
    *   - HTTP/1.0 response (no keep-alive)
    *
    * @param conn
    *   the connection to remove
    * @return
    *   an effect that completes when the connection is removed and closed
    */
  def remove(conn: PooledConnection): Eru[HttpError, Unit]

  /** Get or create a buffer for a connection.
    *
    * Buffers are managed separately from connections for proper type safety. Each connection gets
    * its own 4KB direct buffer (a direct buffer avoids a heap-to-native copy on writes).
    *
    * @param conn
    *   the connection needing a buffer
    * @return
    *   an effect that yields a ByteBuffer for this connection
    */
  def getBuffer(conn: PooledConnection): Eru[HttpError, java.nio.ByteBuffer]

  /** Get or create a BufferedSocketReader for a connection.
    *
    * Readers are managed separately from connections so their buffers can be reused across requests
    * (reducing per-request allocation). Each connection gets its own reader (8KB ByteBuffer +
    * StringBuilder), reused across all requests.
    *
    * @param conn
    *   the connection needing a reader
    * @return
    *   an effect that yields a BufferedSocketReader for this connection
    */
  def getReader(conn: PooledConnection): Eru[HttpError, BufferedSocketReader]

  /** Get an existing SSL channel for a connection, if one was previously created.
    *
    * For HTTPS connections, the SSLSocketChannel must be reused across requests on the same
    * connection to avoid re-handshaking.
    *
    * @param conn
    *   the connection to check
    * @return
    *   an effect that yields Some(sslChannel) if exists, None otherwise
    */
  def getSSLChannel(conn: PooledConnection): Eru[HttpError, Option[SSLSocketChannel]]

  /** Store an SSL channel for a connection.
    *
    * Called after TLS handshake completes to enable reuse on subsequent requests.
    *
    * @param conn
    *   the connection
    * @param sslChannel
    *   the SSL channel to store
    * @return
    *   an effect that completes when stored
    */
  def setSSLChannel(conn: PooledConnection, sslChannel: SSLSocketChannel): Eru[HttpError, Unit]

  /** Get an existing SSL reader for a connection, if one was previously created.
    *
    * For HTTPS connections, the BufferedSocketReader wraps the SSLSocketChannel (not the raw
    * socket) and must be reused across requests to avoid allocations.
    *
    * @param conn
    *   the connection to check
    * @return
    *   an effect that yields Some(reader) if exists, None otherwise
    */
  def getSSLReader(conn: PooledConnection): Eru[HttpError, Option[BufferedSocketReader]]

  /** Store an SSL reader for a connection.
    *
    * Called after first HTTPS request to enable reuse on subsequent requests.
    *
    * @param conn
    *   the connection
    * @param reader
    *   the SSL reader to store
    * @return
    *   an effect that completes when stored
    */
  def setSSLReader(conn: PooledConnection, reader: BufferedSocketReader): Eru[HttpError, Unit]

  /** Get an existing HTTP/2 connection for a socket, if one was previously created.
    *
    * For HTTP/2 connections, the H2ClientConnection must be reused across requests to maintain
    * stream state and HPACK encoder/decoder context.
    *
    * @param conn
    *   the connection to check
    * @return
    *   an effect that yields Some(h2conn) if exists, None otherwise
    */
  def getH2Connection(conn: PooledConnection): Eru[HttpError, Option[H2ClientConnection]]

  /** Store an HTTP/2 connection for a socket.
    *
    * Called after HTTP/2 connection preface exchange completes.
    *
    * @param conn
    *   the connection
    * @param h2conn
    *   the HTTP/2 connection to store
    * @return
    *   an effect that completes when stored
    */
  def setH2Connection(conn: PooledConnection, h2conn: H2ClientConnection): Eru[HttpError, Unit]

  /** Shutdown the pool and close all connections.
    *
    * After shutdown, the pool cannot be used.
    *
    * @return
    *   an effect that completes when all connections are closed
    */
  def shutdown: Eru[HttpError, Unit]
}

private[client] object ConnectionPool {

  /** Create a new connection pool using Eru's Ref for state management.
    *
    * A bounded direct-ByteBuffer pool backs request writes: its size matches `maxConnections` so
    * every in-use connection can hold a borrowed buffer; acquire blocks fairly via the pool's
    * backing Queue if the global semaphore is bypassed. This caps direct-memory usage at
    * `maxConnections * 4KB` and closes the direct-buffer lifecycle deterministically instead of
    * relying on Phantom-reference collection.
    *
    * @param config
    *   client configuration (contains pool limits and timeouts)
    * @param runtime
    *   the Eru runtime
    * @return
    *   an effect that yields a new connection pool
    */
  def create(config: HttpClientConfig)(using runtime: EruRuntime): Eru[HttpError, ConnectionPool] =
    for {
      stateRef <- Ref
        .make(PoolState.empty)
        .mapError(e => HttpError.NetworkError(s"Failed to create pool state: $e", None))
      globalSem <- Semaphore
        .make(config.maxConnections.toLong)
        .mapError(e => HttpError.NetworkError(s"Failed to create global semaphore: $e", None))
      hostSemsRef <- Ref
        .make(Map.empty[HostKey, Semaphore])
        .mapError(e => HttpError.NetworkError(s"Failed to create host semaphores ref: $e", None))
      writeBufferPool <- Pools
        .directByteBuffers(capacity = config.maxConnections, bufferSize = 4096)
        .mapError(e => HttpError.NetworkError(s"Failed to create write buffer pool: $e", None))
    } yield new NativeConnectionPool(stateRef, globalSem, hostSemsRef, writeBufferPool, config, config.connectTimeout)
}

/** Native connection pool implementation using semaphores for backpressure.
  *
  * Uses semaphores for fair backpressure instead of sleep-retry loops:
  *   - Global semaphore controls total connection count
  *   - Per-host semaphores control per-host limits
  *   - FIFO fairness via semaphore waiters
  *   - No retry loops, no exponential backoff
  *   - Proper cleanup in all error paths
  */
private[client] final class NativeConnectionPool(
  stateRef: Ref[PoolState],
  globalSem: Semaphore,
  hostSemsRef: Ref[Map[HostKey, Semaphore]],
  writeBufferPool: ObjectPool[java.nio.ByteBuffer],
  config: HttpClientConfig,
  connectTimeout: Duration
)(using runtime: EruRuntime)
    extends ConnectionPool {

  /** Per-socket write-buffer registry. Buffers are borrowed from `writeBufferPool` on first
    * getBuffer() for a connection and returned when the connection is removed from the pool or on
    * shutdown(). Bounded at `maxConnections * 4KB` of direct memory. ConcurrentHashMap gives
    * lock-free lookup; the pool provides the allocation cap.
    */
  /** Set before shutdown tears anything down; acquire checks it so a post-shutdown acquire fails
    * with the pool's own error instead of opening a new socket.
    */
  private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

  private val buffers = new java.util.concurrent.ConcurrentHashMap[SocketChannel, java.nio.ByteBuffer]()

  /** Per-socket BufferedSocketReaders for response parsing. Each reader contains an 8KB ByteBuffer
    * and a StringBuilder, reused across all requests on that connection, which reduces per-request
    * allocation.
    */
  private val readers = new java.util.concurrent.ConcurrentHashMap[SocketChannel, BufferedSocketReader]()

  /** Per-socket SSLSocketChannel wrappers for HTTPS connection reuse. Preserved across requests to
    * avoid re-handshaking.
    */
  private val sslChannels = new java.util.concurrent.ConcurrentHashMap[SocketChannel, SSLSocketChannel]()

  /** Per-socket BufferedSocketReaders that wrap the SSLSocketChannel (not the raw socket), kept
    * separate from `readers` because their source is decrypted.
    */
  private val sslReaders = new java.util.concurrent.ConcurrentHashMap[SocketChannel, BufferedSocketReader]()

  /** Per-socket H2ClientConnection for HTTP/2 multiplexing. Preserved across requests to maintain
    * stream state and HPACK context.
    */
  private val h2Connections = new java.util.concurrent.ConcurrentHashMap[SocketChannel, H2ClientConnection]()

  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    if closed.get() then {
      Eru.fail(HttpError.NetworkError("Connection pool is shut down", None))
    } else {
      // The global permit is held in a bracket: it is released on every exit path, including a
      // failed host-semaphore acquisition or a failed connection attempt.
      globalSem.acquire.eru
        .mapError(e => HttpError.NetworkError(s"Failed to acquire global permit: $e", None))
        .bracket(_ => globalSem.release.eru) { _ =>
          for {
            hostSem <- getOrCreateHostSemaphore(host, port)
            _ <- hostSem.acquire.eru.mapError(e => HttpError.NetworkError(s"Failed to acquire host permit: $e", None))
            conn <- getOrCreateConnection(host, port).tapError { _ =>
              hostSem.release.eru.orElse(Eru.unit)
            }
          } yield conn
        }
    }
  }

  /** Fetches the per-host semaphore, creating it speculatively when absent and inserting it
    * atomically only if it is still missing; losing fibers discard their semaphore (never acquired,
    * safe to GC).
    */
  private def getOrCreateHostSemaphore(host: String, port: Int): Eru[HttpError, Semaphore] = {
    val key = HostKey(host, port)
    hostSemsRef.get.flatMap { sems =>
      sems.get(key) match {
        case Some(sem) => Eru.succeed(sem)
        case None =>
          for {
            newSem <- Semaphore
              .make(config.maxConnectionsPerHost.toLong)
              .mapError(e => HttpError.NetworkError(s"Failed to create host semaphore: $e", None))
            sem <- hostSemsRef.modify { currentSems =>
              currentSems.get(key) match {
                case Some(existing) => (currentSems, existing)
                case None => (currentSems + (key -> newSem), newSem)
              }
            }
          } yield sem
      }
    }
  }

  /** Pops an idle connection for the host from the pool, or creates a new one when the queue is
    * empty.
    */
  private def getOrCreateConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    stateRef.modify { state =>
      val key = HostKey(host, port)
      state.available.get(key).flatMap(_.headOption) match {
        case Some(conn) =>
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
          (state, Left((host, port)))
      }
    }.flatMap {
      case Right(conn) => Eru.succeed(conn)
      case Left((h, p)) =>
        for {
          newConn <- createConnection(h, p)
          _ <- stateRef.update(s => s.copy(inUse = s.inUse + newConn))
        } yield newConn
    }
  }

  def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      now <- currentTime
      _ <- Eru.effect {
        Option(buffers.get(conn.socket)).foreach(_.clear())
      }.mapError(e => HttpError.NetworkError(s"Failed to reset buffer: ${e.getMessage}", Some(e)))
      _ <- Eru.effect {
        Option(readers.get(conn.socket)).foreach(_.reset())
      }.mapError(e => HttpError.NetworkError(s"Failed to reset reader: ${e.getMessage}", Some(e)))
      _ <- Eru.effect {
        Option(sslReaders.get(conn.socket)).foreach(_.reset())
      }.mapError(e => HttpError.NetworkError(s"Failed to reset SSL reader: ${e.getMessage}", Some(e)))
      _ <- stateRef.update { state =>
        releaseConnection(state, conn, now)
      }
      hostSem <- getHostSemaphore(conn.key)
      _ <- hostSem.release.eru.mapError(e => HttpError.NetworkError(s"Failed to release host permit: $e", None))
      _ <- globalSem.release.eru.mapError(e => HttpError.NetworkError(s"Failed to release global permit: $e", None))
    } yield ()
  }

  private def getHostSemaphore(key: HostKey): Eru[HttpError, Semaphore] = {
    hostSemsRef.get.flatMap { sems =>
      sems.get(key) match {
        case Some(sem) => Eru.succeed(sem)
        case None => Eru.fail(HttpError.NetworkError(s"Host semaphore not found for $key", None))
      }
    }
  }

  private def releaseConnection(
    state: PoolState,
    conn: PooledConnection,
    now: Instant
  ): PoolState = {
    val key = conn.key
    val queue = state.available.getOrElse(key, Queue.empty)
    val updatedConn = conn.withLastUsed(now)
    val newQueue = queue.enqueue(updatedConn)

    state.copy(
      available = state.available + (key -> newQueue),
      inUse = state.inUse - conn
    )
  }

  /** Returns the write buffer for a connection, borrowing one from the bounded pool on first use.
    *
    * A connection is held by exactly one fiber between `acquire()` and `release()`, so no two
    * fibers race on the same SocketChannel here. Pool capacity equals `maxConnections`, which
    * equals the global semaphore's permits, so the borrow never blocks in steady state.
    */
  def getBuffer(conn: PooledConnection): Eru[HttpError, java.nio.ByteBuffer] = {
    Option(buffers.get(conn.socket)) match {
      case Some(existing) => Eru.succeed(existing)
      case None =>
        writeBufferPool.acquire.eru
          .mapError(e => HttpError.NetworkError(s"Failed to acquire write buffer: $e", None))
          .flatMap { buf =>
            Eru.effect {
              buffers.put(conn.socket, buf)
              buf
            }.mapError(e => HttpError.NetworkError(s"Failed to register buffer: ${e.getMessage}", Some(e)))
          }
    }
  }

  /** Returns the response-parsing reader for a connection, creating one on first use (8KB buffer
    * plus StringBuilder).
    */
  def getReader(conn: PooledConnection): Eru[HttpError, BufferedSocketReader] = {
    Eru.effect {
      readers.computeIfAbsent(
        conn.socket,
        socket => {
          new BufferedSocketReader(socket)
        }
      )
    }.mapError(e => HttpError.NetworkError(s"Failed to get reader: ${e.getMessage}", Some(e)))
  }

  def getSSLChannel(conn: PooledConnection): Eru[HttpError, Option[SSLSocketChannel]] = {
    Eru.effect {
      Option(sslChannels.get(conn.socket))
    }.mapError(e => HttpError.NetworkError(s"Failed to get SSL channel: ${e.getMessage}", Some(e)))
  }

  def setSSLChannel(conn: PooledConnection, sslChannel: SSLSocketChannel): Eru[HttpError, Unit] = {
    Eru.effect {
      sslChannels.put(conn.socket, sslChannel)
      ()
    }.mapError(e => HttpError.NetworkError(s"Failed to set SSL channel: ${e.getMessage}", Some(e)))
  }

  def getSSLReader(conn: PooledConnection): Eru[HttpError, Option[BufferedSocketReader]] = {
    Eru.effect {
      Option(sslReaders.get(conn.socket))
    }.mapError(e => HttpError.NetworkError(s"Failed to get SSL reader: ${e.getMessage}", Some(e)))
  }

  def setSSLReader(conn: PooledConnection, reader: BufferedSocketReader): Eru[HttpError, Unit] = {
    Eru.effect {
      sslReaders.put(conn.socket, reader)
      ()
    }.mapError(e => HttpError.NetworkError(s"Failed to set SSL reader: ${e.getMessage}", Some(e)))
  }

  def getH2Connection(conn: PooledConnection): Eru[HttpError, Option[H2ClientConnection]] = {
    Eru.effect {
      Option(h2Connections.get(conn.socket))
    }.mapError(e => HttpError.NetworkError(s"Failed to get H2 connection: ${e.getMessage}", Some(e)))
  }

  def setH2Connection(conn: PooledConnection, h2conn: H2ClientConnection): Eru[HttpError, Unit] = {
    Eru.effect {
      h2Connections.put(conn.socket, h2conn)
      ()
    }.mapError(e => HttpError.NetworkError(s"Failed to set H2 connection: ${e.getMessage}", Some(e)))
  }

  /** Removes a connection from the pool, closes it, and releases its permits.
    *
    * Cleanup runs inline via `.attempt.flatMap`, not `.ensure`: `ensure`'s finalizer only runs at
    * the end of the enclosing top-level `unsafeRunSync`, so composing `remove` into long sequential
    * chains (e.g. `foreach` over 50 `Connection: close` requests in one run) would accumulate all
    * the release finalizers and fire them only at the very end — deadlocking an `acquire` on a
    * permit that would not be released until the chain terminates. Each cleanup step is wrapped in
    * `.attempt` so a failure in one step (e.g. closing a reset connection) cannot short-circuit the
    * releases that follow. Borrowed write buffers are returned to the bounded pool, and semaphore
    * releases run inline within the same top-level run so they take effect immediately for the next
    * acquire.
    */
  def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      _ <- Eru.effect {
        Option(h2Connections.remove(conn.socket)).foreach { h2conn =>
          h2conn.shutdown().attempt.unsafeRunSync(): Unit
        }
      }.attempt.map(_ => ())
      _ <- Eru.effect {
        Option(sslChannels.remove(conn.socket)).foreach(_.close())
      }.attempt.map(_ => ())
      _ <- closeSocket(conn.socket).attempt.map(_ => ())
      _ <- Eru.effect {
        Option(buffers.remove(conn.socket)).foreach { buf =>
          writeBufferPool.release(buf).eru.unsafeRunSync(): Unit
        }
      }.attempt.map(_ => ())
      _ <- Eru.effect(readers.remove(conn.socket)).attempt.map(_ => ())
      _ <- Eru.effect(sslReaders.remove(conn.socket)).attempt.map(_ => ())
      _ <- stateRef.update(state => removeConnection(state, conn))
      hostSemResult <- getHostSemaphore(conn.key).attempt
      _ <- hostSemResult match {
        case Result.Success(sem) => sem.release.eru
        case Result.Failure(_) => Eru.unit
      }
      _ <- globalSem.release.eru
    } yield ()
  }

  private def removeConnection(state: PoolState, conn: PooledConnection): PoolState = {
    val key = conn.key
    val queue = state.available.getOrElse(key, Queue.empty)
    val filteredQueue = queue.filterNot(_ == conn)

    state.copy(
      available = state.available + (key -> filteredQueue),
      inUse = state.inUse - conn
    )
  }

  /** Shuts down the pool and closes every connection.
    *
    * HTTP/2 connections are shut down first (sends GOAWAY), then SSL channels are closed (sends TLS
    * close_notify), then sockets. Every borrowed buffer is returned to the pool before the registry
    * is cleared, preserving the `writeBufferPool.available == maxConnections` invariant for any
    * post-shutdown inspection.
    */
  def shutdown: Eru[HttpError, Unit] = {
    closed.set(true)
    for {
      state <- stateRef.get
      allConns = collectAllConnections(state)
      _ <- Eru.effect {
        h2Connections.values().forEach { h2conn =>
          h2conn.shutdown().attempt.unsafeRunSync(): Unit
        }
        h2Connections.clear()
      }.mapError(e => HttpError.NetworkError(s"Failed to shutdown H2 connections: ${e.getMessage}", Some(e)))
      _ <- Eru.effect {
        sslChannels.values().forEach(_.close())
        sslChannels.clear()
      }.mapError(e => HttpError.NetworkError(s"Failed to close SSL channels: ${e.getMessage}", Some(e)))
      _ <- closeAllConnections(allConns)
      _ <- Eru.effect {
        buffers.values().forEach { buf =>
          writeBufferPool.release(buf).eru.unsafeRunSync(): Unit
        }
        buffers.clear()
      }
        .mapError(e => HttpError.NetworkError(s"Failed to clear buffers: ${e.getMessage}", Some(e)))
      _ <- Eru
        .effect(readers.clear())
        .mapError(e => HttpError.NetworkError(s"Failed to clear readers: ${e.getMessage}", Some(e)))
      _ <- Eru
        .effect(sslReaders.clear())
        .mapError(e => HttpError.NetworkError(s"Failed to clear SSL readers: ${e.getMessage}", Some(e)))
      _ <- stateRef.set(PoolState.empty)
    } yield ()
  }

  private def collectAllConnections(state: PoolState): List[PooledConnection] = {
    val availableConns = state.available.values.flatten.toList
    val inUseConns = state.inUse.toList
    availableConns ++ inUseConns
  }

  private def closeAllConnections(connections: List[PooledConnection]): Eru[HttpError, Unit] = {
    Eru
      .foreach(connections) { conn =>
        closeSocket(conn.socket).orElse(Eru.unit)
      }
      .map(_ => ())
  }

  private def createConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    for {
      socket <- connectSocket(host, port)
      now <- currentTime
    } yield PooledConnection(socket, host, port, now, now)
  }

  /** Opens a blocking socket to `host:port`, disabling Nagle's algorithm (low-latency
    * request/response; without it TCP may buffer small packets for up to 40ms) and, on Linux,
    * enabling TCP_QUICKACK to avoid the delayed-ACK delay when reading response bodies on reused
    * connections. TCP_QUICKACK is not sticky (it resets after each ACK) and is re-set before each
    * read in `BufferedSocketReader`; its absence on non-Linux platforms is expected and non-fatal.
    */
  private def connectSocket(host: String, port: Int): Eru[HttpError, SocketChannel] = {
    Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)

      socket.socket().setTcpNoDelay(true)

      try {
        socket.setOption(ExtendedSocketOptions.TCP_QUICKACK, true)
      } catch {
        case _: Exception =>
          ()
      }

      socket.connect(new InetSocketAddress(host, port))
      socket
    }.mapError { e =>
      HttpError.ConnectionError(
        s"Failed to connect to $host:$port: ${e.getMessage}",
        Some(e)
      )
    }
      .timeout(java.time.Duration.ofMillis(connectTimeout.toMillis))
      .mapError {
        case e: HttpError => e
        case e: Throwable => HttpError.TimeoutError(s"Connection timeout to $host:$port: ${e.getMessage}")
      }
  }

  private def currentTime: Eru[HttpError, Instant] = {
    Eru
      .effect(Instant.now())
      .mapError(e => HttpError.NetworkError(s"Failed to get current time: ${e.getMessage}", Some(e)))
  }

  private def closeSocket(socket: SocketChannel): Eru[HttpError, Unit] = {
    Eru.effect { socket.close() }
      .mapError(e => HttpError.NetworkError(s"Failed to close socket: ${e.getMessage}", Some(e)))
  }
}
