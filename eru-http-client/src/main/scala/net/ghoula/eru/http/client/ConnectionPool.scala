package net.ghoula.eru.http.client

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.time.Instant
import scala.collection.immutable.Queue
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

/** Connection wrapper with metadata for pool management.
  */
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

/** Immutable pool state managed by Ref[PoolState].
  *
  * This structure is designed for atomic updates via Eru's Ref primitive.
  */
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

  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] =
    attemptAcquire(host, port, attempt = 0)

  private def attemptAcquire(
    host: String,
    port: Int,
    attempt: Int
  ): Eru[HttpError, PooledConnection] = {
    for {
      // Atomically try to get connection or make decision
      // CRITICAL: No I/O in this modify block - only decisions!
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
              // We'll add to inUse after creation
              (state, Left("create-new"))
        }
      }

      // Handle decision outside of modify (I/O happens here)
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
            val delayMs = Math.min(10 * Math.pow(2, attempt).toLong, 5000)
            for {
              _ <- Eru.effect { Thread.sleep(delayMs) }
                .mapError(e => HttpError.NetworkError(s"Sleep interrupted: ${e.getMessage}", Some(e)))
              result <- attemptAcquire(host, port, attempt + 1)
            } yield result
          else
            Eru.fail(
              HttpError.TimeoutError(
                s"Pool exhausted: cannot acquire connection to $host:$port after 10 retries"
              )
            )
      }
    } yield conn
  }

  def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      now <- Eru.effect(Instant.now())
        .mapError(e => HttpError.NetworkError(s"Failed to get current time: ${e.getMessage}", Some(e)))
      _ <- stateRef.update { state =>
        val key = conn.key
        val queue = state.available.getOrElse(key, Queue.empty)
        val newQueue = queue.enqueue(conn.withLastUsed(now))
        state.copy(
          available = state.available + (key -> newQueue),
          inUse = state.inUse - conn
        )
      }
    } yield ()
  }

  def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      // Close socket first
      _ <- Eru
        .effect { conn.socket.close() }
        .mapError(e => HttpError.NetworkError(s"Failed to close socket: ${e.getMessage}", Some(e)))

      // Remove from pool state
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
      // Get all connections
      state <- stateRef.get
      allConns = state.available.values.flatten.toList ++ state.inUse.toList

      // Close all connections (don't fail on individual errors)
      _ <- Eru.foreach(allConns) { conn =>
        Eru
          .effect { conn.socket.close() }
          .mapError(e => HttpError.NetworkError(s"Failed to close socket: ${e.getMessage}", Some(e)))
          .orElse(Eru.unit)
      }

      // Clear pool state
      _ <- stateRef.set(PoolState.empty)
    } yield ()
  }

  private def createConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    for {
      socket <- connectSocket(host, port)
      now <- Eru
        .effect(Instant.now())
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

    connectEffect.attempt
      .flatMap {
        case Result.Success(socket) => Eru.succeed(socket)
        case Result.Failure(e) =>
          Eru.fail(
            HttpError.ConnectionError(
              s"Failed to connect to $host:$port: ${e.getMessage}",
              Some(e)
            )
          )
      }
      .timeout(java.time.Duration.ofMillis(connectTimeout.toMillis))
      .mapError {
        case e: HttpError => e
        case e => HttpError.ConnectionError(s"Connection timeout: ${e.getMessage}", Some(e))
      }
  }
}
