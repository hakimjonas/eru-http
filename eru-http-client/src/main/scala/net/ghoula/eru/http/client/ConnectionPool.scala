package net.ghoula.eru.http.client

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
  */
private[client] final case class PooledConnection(
  socket: SocketChannel,
  host: String,
  port: Int,
  createdAt: Instant,
  lastUsedAt: Instant
) {
  def key: HostKey = HostKey(host, port)
  def withLastUsed(time: Instant): PooledConnection = copy(lastUsedAt = time)
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
      stateRef <- Ref.make(PoolState.empty)
        .mapError(e => HttpError.NetworkError(s"Failed to create pool state: $e", None))
    } yield new NativeConnectionPool(stateRef, config, config.connectTimeout)
}

/** Decision result from attempting to acquire a connection.
  *
  * This ADT represents the atomic decision made inside Ref.modify, allowing I/O to happen outside
  * the CAS loop.
  */
private enum AcquireDecision {
  case Found(conn: PooledConnection)
  case CreateNew
  case HostLimit
  case GlobalLimit
}

/** Native connection pool implementation using Eru's Ref primitive.
  *
  * This implementation uses Ref[PoolState] for lock-free, atomic state management. All state
  * transitions (acquire, release, remove) use Ref.modify to ensure atomicity.
  *
  * Key design principles:
  *   - No I/O inside Ref.modify blocks (only decisions)
  *   - Atomic read-modify-write via modify
  *   - Exponential backoff when limits reached
  *   - Proper cleanup in all error paths
  */
private[client] final class NativeConnectionPool(
  stateRef: Ref[PoolState],
  config: HttpClientConfig,
  connectTimeout: Duration
)(using runtime: EruRuntime)
    extends ConnectionPool {

  private val MaxRetries = 10
  private val InitialBackoffMs = 10L
  private val MaxBackoffMs = 5000L

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
      // CRITICAL: No I/O in this modify block - only decisions!
      decision <- stateRef.modify { state =>
        makeAcquireDecision(state, host, port)
      }

      // Handle decision outside of modify (I/O happens here)
      conn <- decision match {
        case AcquireDecision.Found(conn) =>
          Eru.succeed(conn)

        case AcquireDecision.CreateNew =>
          for {
            newConn <- createConnection(host, port)
            _ <- stateRef.update(s => s.copy(inUse = s.inUse + newConn))
          } yield newConn

        case AcquireDecision.HostLimit | AcquireDecision.GlobalLimit =>
          if attempt < MaxRetries then {
            retryWithBackoff(host, port, attempt)
          } else {
            Eru.fail(
              HttpError.TimeoutError(
                s"Pool exhausted: cannot acquire connection to $host:$port after $MaxRetries retries"
              )
            )
          }
      }
    } yield conn
  }

  private def makeAcquireDecision(
    state: PoolState,
    host: String,
    port: Int
  ): (PoolState, AcquireDecision) = {
    val key = HostKey(host, port)

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
        (newState, AcquireDecision.Found(conn))

      case None =>
        // No available connection - check limits
        val hostConns = state.hostConnections(host, port)
        val totalConns = state.totalConnections

        if hostConns >= config.maxConnectionsPerHost then
          (state, AcquireDecision.HostLimit)
        else if totalConns >= config.maxConnections then
          (state, AcquireDecision.GlobalLimit)
        else
          (state, AcquireDecision.CreateNew)
    }
  }

  private def retryWithBackoff(
    host: String,
    port: Int,
    attempt: Int
  ): Eru[HttpError, PooledConnection] = {
    val delayMs = Math.min(
      InitialBackoffMs * Math.pow(2, attempt).toLong,
      MaxBackoffMs
    )

    for {
      _ <- Eru.effect { Thread.sleep(delayMs) }
        .mapError(e => HttpError.NetworkError(s"Sleep interrupted: ${e.getMessage}", Some(e)))
      result <- attemptAcquire(host, port, attempt + 1)
    } yield result
  }

  def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      now <- currentTime
      _ <- stateRef.update { state =>
        releaseConnection(state, conn, now)
      }
    } yield ()
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

  def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      _ <- closeSocket(conn.socket)
      _ <- stateRef.update { state =>
        removeConnection(state, conn)
      }
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
      _ <- stateRef.set(PoolState.empty)
    } yield ()
  }

  private def collectAllConnections(state: PoolState): List[PooledConnection] = {
    val availableConns = state.available.values.flatten.toList
    val inUseConns = state.inUse.toList
    availableConns ++ inUseConns
  }

  private def closeAllConnections(connections: List[PooledConnection]): Eru[HttpError, Unit] = {
    Eru.foreach(connections) { conn =>
      closeSocket(conn.socket).orElse(Eru.unit)
    }.map(_ => ())
  }

  private def createConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    for {
      socket <- connectSocket(host, port)
      now <- currentTime
    } yield PooledConnection(socket, host, port, now, now)
  }

  private def connectSocket(host: String, port: Int): Eru[HttpError, SocketChannel] = {
    val connectEffect = Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true)
      socket.connect(new InetSocketAddress(host, port))
      socket
    }

    for {
      result <- connectEffect.attempt
      socket <- result match {
        case Result.Success(socket) =>
          Eru.succeed(socket)
        case Result.Failure(e) =>
          Eru.fail(
            HttpError.ConnectionError(
              s"Failed to connect to $host:$port: ${e.getMessage}",
              Some(e)
            )
          )
      }
      withTimeout <- socket
        .timeout(java.time.Duration.ofMillis(connectTimeout.toMillis))
        .mapError {
          case e: HttpError => e
          case e => HttpError.ConnectionError(s"Connection timeout: ${e.getMessage}", Some(e))
        }
    } yield withTimeout
  }

  // Helper effects

  private def currentTime: Eru[HttpError, Instant] = {
    Eru.effect(Instant.now())
      .mapError(e => HttpError.NetworkError(s"Failed to get current time: ${e.getMessage}", Some(e)))
  }

  private def closeSocket(socket: SocketChannel): Eru[HttpError, Unit] = {
    Eru.effect { socket.close() }
      .mapError(e => HttpError.NetworkError(s"Failed to close socket: ${e.getMessage}", Some(e)))
  }
}
