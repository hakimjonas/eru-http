package net.ghoula.eru.http.client

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.time.Instant
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** A connection in the pool with metadata for lifecycle management.
  *
  * @param socket
  *   The underlying socket channel
  * @param host
  *   Target host
  * @param port
  *   Target port
  * @param createdAt
  *   When this connection was opened
  * @param lastUsedAt
  *   When this connection was last used (for staleness detection)
  */
private[client] final case class PooledConnection(
  socket: SocketChannel,
  host: String,
  port: Int,
  createdAt: Instant,
  lastUsedAt: Instant
) {
  def connectionKey: String = s"$host:$port"

  def withLastUsed(time: Instant): PooledConnection =
    copy(lastUsedAt = time)
}

/** Connection pool for HTTP client with concurrent access support.
  *
  * Thread-safe for concurrent access from multiple Virtual Threads.
  */
trait ConnectionPool {

  /** Acquire a connection from the pool or create a new one.
    *
    * Behavior:
    *   1. Check available pool for host:port
    *   2. If available, return connection from queue (mark as in-use)
    *   3. If not available but under limits, create new connection
    *   4. If at limits, retry with exponential backoff (up to config timeout)
    *
    * @param host
    *   Target host
    * @param port
    *   Target port
    * @return
    *   A pooled connection ready to use
    */
  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection]

  /** Release a connection back to the pool for reuse.
    *
    * Call this after successfully using a connection when the server indicates keep-alive.
    *
    * @param conn
    *   The connection to release
    * @return
    *   Success or error
    */
  def release(conn: PooledConnection): Eru[HttpError, Unit]

  /** Remove a connection from the pool and close it.
    *
    * Call this when:
    *   - Connection error occurs
    *   - Server sends "Connection: close"
    *   - Connection is stale (detected during use)
    *
    * @param conn
    *   The connection to remove and close
    * @return
    *   Success or error
    */
  def remove(conn: PooledConnection): Eru[HttpError, Unit]

  /** Shutdown the pool, closing all connections.
    *
    * This should be called during client shutdown to ensure clean resource cleanup.
    *
    * @return
    *   Success or error
    */
  def shutdown: Eru[HttpError, Unit]
}

object ConnectionPool {

  /** Create a new connection pool.
    *
    * @param config
    *   HTTP client configuration (provides pool limits and timeouts)
    * @param connectTimeout
    *   Timeout for establishing new connections
    * @return
    *   A new connection pool
    */
  def create(config: HttpClientConfig, connectTimeout: Duration): Eru[HttpError, ConnectionPool] =
    Eru.succeed(new NativeConnectionPool(config, connectTimeout))
}

/** Native implementation of ConnectionPool using ConcurrentHashMap and ConcurrentLinkedQueue.
  *
  * Uses Java concurrent collections for thread-safe access from multiple Virtual Threads. All
  * operations are wrapped in Eru effects for composability and error handling.
  *
  * Pool organization:
  *   - available: Map[String, Queue[PooledConnection]] - FIFO queues per host
  *   - inUse: Set[PooledConnection] - Connections currently in use
  *
  * Limits:
  *   - maxConnectionsPerHost: Maximum connections to a single host
  *   - maxConnections: Maximum total connections across all hosts
  */
private[client] final class NativeConnectionPool(
  config: HttpClientConfig,
  connectTimeout: Duration
) extends ConnectionPool {

  // Available connections, keyed by "host:port"
  // Using ConcurrentHashMap for thread-safe access
  private val available = new ConcurrentHashMap[String, ConcurrentLinkedQueue[PooledConnection]]()

  // Connections currently in use
  // Using ConcurrentHashMap as a Set (value is always Unit)
  private val inUse = new ConcurrentHashMap[PooledConnection, Unit]()

  override def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    def attemptAcquire(attempt: Int): Eru[HttpError, PooledConnection] = {
      Eru
        .effectTotal {
          val key = connectionKey(host, port)

          // Try to get from available queue
          val queue = available.get(key)
          Option(queue).flatMap { q =>
            Option(q.poll()) match {
              case Some(conn) =>
                // Found available connection, mark as in-use
                inUse.put(conn, ())
                Some(Right(conn))
              case None =>
                // Queue exists but empty
                None
            }
          }
        }
        .flatMap {
          case Some(Right(conn)) =>
            // Got connection from pool
            Eru.succeed(conn)

          case _ =>
            // No available connection, check if we can create new
            canCreate(host, port).flatMap {
              case true =>
                // Create new connection
                createConnection(host, port)

              case false =>
                // At limit, retry with backoff
                if attempt < 10 then {
                  // Exponential backoff: 10ms, 20ms, 40ms, ..., max 5120ms
                  val backoffMs = Math.min(10L << attempt, 5120L)
                  Eru
                    .effect {
                      Thread.sleep(backoffMs)
                    }
                    .mapError(e => HttpError.NetworkError(s"Sleep error: ${e.getMessage}", Some(e)))
                    .flatMap(_ => attemptAcquire(attempt + 1))
                } else {
                  Eru.fail(
                    HttpError.ConnectionError(
                      s"Pool exhausted for $host:$port after ${attempt} retries (max per host: ${config.maxConnectionsPerHost}, global max: ${config.maxConnections})",
                      None
                    )
                  )
                }
            }
        }
    }

    attemptAcquire(0)
  }

  override def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    Eru.effectTotal {
      // Remove from in-use
      inUse.remove(conn)

      // Add to available queue
      val key = conn.connectionKey
      val queue = available.computeIfAbsent(key, _ => new ConcurrentLinkedQueue[PooledConnection]())

      // Update last used time
      val updatedConn = conn.withLastUsed(Instant.now())
      queue.offer(updatedConn)
      ()
    }
  }

  override def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    Eru.effectTotal {
      // Remove from in-use
      inUse.remove(conn)

      // Close socket
      try {
        if conn.socket.isOpen then {
          conn.socket.close()
        }
      } catch {
        case _: Exception => () // Best effort cleanup
      }
      ()
    }
  }

  override def shutdown: Eru[HttpError, Unit] = {
    Eru.effectTotal {
      // Close all available connections
      available.values().asScala.foreach { queue =>
        var conn = Option(queue.poll())
        while (conn.nonEmpty) {
          try {
            if conn.get.socket.isOpen then {
              conn.get.socket.close()
            }
          } catch {
            case _: Exception => () // Best effort cleanup
          }
          conn = Option(queue.poll())
        }
      }

      // Close all in-use connections (force close during shutdown)
      inUse.keys().asScala.foreach { conn =>
        try {
          if conn.socket.isOpen then {
            conn.socket.close()
          }
        } catch {
          case _: Exception => () // Best effort cleanup
        }
      }

      // Clear all data structures
      available.clear()
      inUse.clear()
      ()
    }
  }

  /** Check if we can create a new connection for the given host.
    */
  private def canCreate(host: String, port: Int): Eru[HttpError, Boolean] = {
    Eru.effectTotal {
      val key = connectionKey(host, port)

      // Count connections for this host (available + in-use)
      val availableCount = Option(available.get(key)).map(_.size()).getOrElse(0)
      val inUseCount = inUse.keys().asScala.count(c => c.connectionKey == key)
      val hostTotal = availableCount + inUseCount

      // Count total connections (all hosts)
      val totalAvailable = available.values().asScala.map(_.size()).sum
      val totalInUse = inUse.size()
      val globalTotal = totalAvailable + totalInUse

      // Check both per-host and global limits
      val underHostLimit = hostTotal < config.maxConnectionsPerHost
      val underGlobalLimit = globalTotal < config.maxConnections

      underHostLimit && underGlobalLimit
    }
  }

  /** Create a new connection to the specified host.
    */
  private def createConnection(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    val connectEffect = Eru.effect {
      val socket = SocketChannel.open()
      socket.configureBlocking(true) // Blocking is GOOD on Virtual Threads!
      socket.connect(new InetSocketAddress(host, port))
      socket
    }

    connectEffect.attempt
      .flatMap {
        case Result.Success(socket) =>
          val now = Instant.now()
          val conn = PooledConnection(
            socket = socket,
            host = host,
            port = port,
            createdAt = now,
            lastUsedAt = now
          )

          // Mark as in-use
          Eru.effectTotal {
            inUse.put(conn, ())
            conn
          }

        case Result.Failure(e: java.net.ConnectException) =>
          Eru.fail(
            HttpError.ConnectionError(s"Failed to connect to $host:$port: ${e.getMessage}", Some(e))
          )

        case Result.Failure(e) =>
          Eru.fail(
            HttpError.ConnectionError(s"Failed to connect to $host:$port: ${e.getMessage}", Some(e))
          )
      }
      .timeout(java.time.Duration.ofMillis(connectTimeout.toMillis))
      .mapError {
        case _: java.util.concurrent.TimeoutException =>
          HttpError.ConnectionError(s"Connection timeout after $connectTimeout to $host:$port", None)
        case e: HttpError => e
        case e: Throwable =>
          HttpError.ConnectionError(s"Failed to connect to $host:$port: ${e.getMessage}", Some(e))
      }
  }

  private def connectionKey(host: String, port: Int): String = s"$host:$port"
}
