package net.ghoula.eru.http.client

import jdk.net.ExtendedSocketOptions

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.time.Instant
import scala.collection.immutable.Queue
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

/** Opaque type for host:port connection keys.
  *
  * Ensures type safety and consistent key formatting throughout the pool.
  */
opaque type HostKey = String

object HostKey {
  def apply(host: String, port: Int): HostKey = s"$host:$port"

  extension (key: HostKey) {
    def value: String = key
  }
}

/** Connection wrapper with metadata for pool management.
  *
  * Pure immutable data - buffers managed separately via RefMap for proper isolation.
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
  * This structure is designed for atomic updates via Eru's Ref primitive.
  */
private[client] final case class PoolState(
  // Available connections per host
  available: Map[HostKey, Queue[PooledConnection]],
  // Connections currently in use
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
trait ConnectionPool {

  /** Acquire a connection to the specified host:port.
    *
    * This may:
    *   - Return an existing connection from the pool
    *   - Create a new connection if under limits
    *   - Retry with exponential backoff if at limits
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
    * its own 4KB direct buffer for zero-copy I/O.
    *
    * @param conn
    *   the connection needing a buffer
    * @return
    *   an effect that yields a ByteBuffer for this connection
    */
  def getBuffer(conn: PooledConnection): Eru[HttpError, java.nio.ByteBuffer]

  /** Get or create a BufferedSocketReader for a connection.
    *
    * Readers are managed separately from connections to enable zero-allocation response parsing.
    * Each connection gets its own reader (8KB ByteBuffer + StringBuilder), reused across all
    * requests.
    *
    * @param conn
    *   the connection needing a reader
    * @return
    *   an effect that yields a BufferedSocketReader for this connection
    */
  def getReader(conn: PooledConnection): Eru[HttpError, BufferedSocketReader]

  /** Shutdown the pool and close all connections.
    *
    * After shutdown, the pool cannot be used.
    *
    * @return
    *   an effect that completes when all connections are closed
    */
  def shutdown: Eru[HttpError, Unit]
}

object ConnectionPool {

  /** Create a new connection pool using Eru's Ref for state management.
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
    } yield new NativeConnectionPool(stateRef, globalSem, hostSemsRef, config, config.connectTimeout)
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
  config: HttpClientConfig,
  connectTimeout: Duration
)(using runtime: EruRuntime)
    extends ConnectionPool {

  // Buffer management: per-socket buffers with proper lifecycle isolation
  // Uses ConcurrentHashMap for lock-free per-key access (zero cross-connection contention)
  private val buffers = new java.util.concurrent.ConcurrentHashMap[SocketChannel, java.nio.ByteBuffer]()

  // Reader management: per-socket BufferedSocketReaders for zero-allocation response parsing
  // Each reader contains 8KB ByteBuffer + StringBuilder, reused across all requests on that connection
  private val readers = new java.util.concurrent.ConcurrentHashMap[SocketChannel, BufferedSocketReader]()

  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    for {
      // Acquire semaphore permits (blocks fairly until available)
      _ <- globalSem.acquire.eru.mapError(e => HttpError.NetworkError(s"Failed to acquire global permit: $e", None))
      hostSem <- getOrCreateHostSemaphore(host, port)
      _ <- hostSem.acquire.eru.mapError(e => HttpError.NetworkError(s"Failed to acquire host permit: $e", None))

      // Now we have permits - get or create connection
      conn <- getOrCreateConnection(host, port).tapError { _ =>
        // Connection acquisition failed - release permits (best effort)
        hostSem.release.eru.flatMap(_ => globalSem.release.eru).orElse(Eru.unit)
      }
    } yield conn
  }

  private def getOrCreateHostSemaphore(host: String, port: Int): Eru[HttpError, Semaphore] = {
    val key = HostKey(host, port)
    hostSemsRef.modify { sems =>
      sems.get(key) match {
        case Some(sem) =>
          // Already exists
          (sems, Right(sem))
        case None =>
          // Need to create new semaphore
          (sems, Left(key))
      }
    }.flatMap {
      case Right(sem) => Eru.succeed(sem)
      case Left(key) =>
        // Create new semaphore outside the modify
        for {
          newSem <- Semaphore
            .make(config.maxConnectionsPerHost.toLong)
            .mapError(e => HttpError.NetworkError(s"Failed to create host semaphore: $e", None))
          _ <- hostSemsRef.update(sems => sems + (key -> newSem))
        } yield newSem
    }
  }

  private def getOrCreateConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    stateRef.modify { state =>
      val key = HostKey(host, port)
      // Try to get from available queue
      state.available.get(key).flatMap(_.headOption) match {
        case Some(conn) =>
          // Found available connection - reuse it
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
          // No available connection - need to create new
          (state, Left((host, port)))
      }
    }.flatMap {
      case Right(conn) => Eru.succeed(conn)
      case Left((h, p)) =>
        // Create new connection
        for {
          newConn <- createConnection(h, p)
          _ <- stateRef.update(s => s.copy(inUse = s.inUse + newConn))
        } yield newConn
    }
  }

  def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      now <- currentTime
      // Reset buffer for next use (if it exists)
      _ <- Eru.effect {
        Option(buffers.get(conn.socket)).foreach(_.clear())
      }.mapError(e => HttpError.NetworkError(s"Failed to reset buffer: ${e.getMessage}", Some(e)))
      // Reset reader for next use (if it exists)
      _ <- Eru.effect {
        Option(readers.get(conn.socket)).foreach(_.reset())
      }.mapError(e => HttpError.NetworkError(s"Failed to reset reader: ${e.getMessage}", Some(e)))
      _ <- stateRef.update { state =>
        releaseConnection(state, conn, now)
      }
      // Release semaphore permits in reverse order of acquisition
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

  def getBuffer(conn: PooledConnection): Eru[HttpError, java.nio.ByteBuffer] = {
    Eru.effect {
      buffers.computeIfAbsent(
        conn.socket,
        _ => {
          // Allocate 4KB direct buffer for request headers (zero-copy I/O)
          val buffer = java.nio.ByteBuffer.allocateDirect(4096)
          buffer
        }
      )
    }.mapError(e => HttpError.NetworkError(s"Failed to get buffer: ${e.getMessage}", Some(e)))
  }

  def getReader(conn: PooledConnection): Eru[HttpError, BufferedSocketReader] = {
    Eru.effect {
      readers.computeIfAbsent(
        conn.socket,
        socket => {
          // Create BufferedSocketReader for response parsing (8KB buffer + StringBuilder)
          new BufferedSocketReader(socket)
        }
      )
    }.mapError(e => HttpError.NetworkError(s"Failed to get reader: ${e.getMessage}", Some(e)))
  }

  def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      _ <- closeSocket(conn.socket)
      _ <- Eru
        .effect(buffers.remove(conn.socket)) // Clean up buffer
        .mapError(e => HttpError.NetworkError(s"Failed to remove buffer: ${e.getMessage}", Some(e)))
      _ <- Eru
        .effect(readers.remove(conn.socket)) // Clean up reader
        .mapError(e => HttpError.NetworkError(s"Failed to remove reader: ${e.getMessage}", Some(e)))
      _ <- stateRef.update { state =>
        removeConnection(state, conn)
      }
      // Release semaphore permits (connection no longer in pool)
      hostSem <- getHostSemaphore(conn.key)
      _ <- hostSem.release.eru.mapError(e => HttpError.NetworkError(s"Failed to release host permit: $e", None))
      _ <- globalSem.release.eru.mapError(e => HttpError.NetworkError(s"Failed to release global permit: $e", None))
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

  def shutdown: Eru[HttpError, Unit] = {
    for {
      state <- stateRef.get
      allConns = collectAllConnections(state)
      _ <- closeAllConnections(allConns)
      _ <- Eru
        .effect(buffers.clear()) // Clean up all buffers
        .mapError(e => HttpError.NetworkError(s"Failed to clear buffers: ${e.getMessage}", Some(e)))
      _ <- Eru
        .effect(readers.clear()) // Clean up all readers
        .mapError(e => HttpError.NetworkError(s"Failed to clear readers: ${e.getMessage}", Some(e)))
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

  private def connectSocket(host: String, port: Int): Eru[HttpError, SocketChannel] = {
    Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)

      // Disable Nagle's algorithm for low-latency request/response
      // Without this, TCP may wait up to 40ms to buffer small packets
      socket.socket().setTcpNoDelay(true)

      // Try to enable TCP_QUICKACK (Linux-specific) to disable delayed ACKs
      // This prevents 40ms delay when reading response bodies on reused connections
      // Note: TCP_QUICKACK is also set before each read in BufferedSocketReader
      // because it's not sticky (gets reset after each ACK)
      try {
        socket.setOption(ExtendedSocketOptions.TCP_QUICKACK, true)
      } catch {
        case _: Exception =>
          // TCP_QUICKACK not available (non-Linux platform)
          // This is expected and non-fatal
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

  // Helper effects

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
