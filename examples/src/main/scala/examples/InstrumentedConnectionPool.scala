package examples

import java.util.concurrent.atomic.AtomicLong
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*

/** Metrics for connection pool benchmarking.
  */
final class PoolMetrics {
  val connectionsCreated = new AtomicLong(0)
  val connectionsReused = new AtomicLong(0)
  val connectionsReleased = new AtomicLong(0)
  val connectionsRemoved = new AtomicLong(0)
  val acquireAttempts = new AtomicLong(0)
  val acquireRetries = new AtomicLong(0)

  def reuseRate: Double = {
    val created = connectionsCreated.get()
    val reused = connectionsReused.get()
    val total = created + reused
    if total > 0 then reused.toDouble / total * 100 else 0.0
  }

  def printSummary(): Unit = {
    println(s"  Connections Created: ${connectionsCreated.get()}")
    println(s"  Connections Reused: ${connectionsReused.get()}")
    println(s"  Connections Released: ${connectionsReleased.get()}")
    println(s"  Connections Removed: ${connectionsRemoved.get()}")
    println(s"  Acquire Attempts: ${acquireAttempts.get()}")
    println(s"  Acquire Retries: ${acquireRetries.get()}")
    println(s"  Reuse Rate: ${reuseRate}%")
  }

  def reset(): Unit = {
    connectionsCreated.set(0)
    connectionsReused.set(0)
    connectionsReleased.set(0)
    connectionsRemoved.set(0)
    acquireAttempts.set(0)
    acquireRetries.set(0)
  }
}

/** Instrumented connection pool wrapper for benchmarking.
  *
  * This wrapper delegates to the underlying pool while collecting detailed metrics about connection
  * lifecycle events. It does not affect the pool's behavior - only observes it.
  *
  * Usage:
  * {{{
  *   val metrics = new PoolMetrics()
  *   val pool = ConnectionPool.create(config).unsafeRunSync()
  *   val instrumented = new InstrumentedConnectionPool(pool, metrics)
  *   // Use instrumented pool...
  *   metrics.printSummary()
  * }}}
  */
final class InstrumentedConnectionPool(
  underlying: ConnectionPool,
  metrics: PoolMetrics
) extends ConnectionPool {

  // Track connections we've seen before (to detect reuse)
  private val seenConnections = java.util.Collections.newSetFromMap(
    new java.util.concurrent.ConcurrentHashMap[PooledConnection, java.lang.Boolean]()
  )

  def acquire(host: String, port: Int): Eru[HttpError, PooledConnection] = {
    metrics.acquireAttempts.incrementAndGet()

    for {
      startNanos <- Eru.effect(System.nanoTime())
        .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      conn <- underlying.acquire(host, port)

      endNanos <- Eru.effect(System.nanoTime())
        .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- Eru.effect {
        val wasSeenBefore = seenConnections.contains(conn)
        if wasSeenBefore then {
          metrics.connectionsReused.incrementAndGet()
        } else {
          metrics.connectionsCreated.incrementAndGet()
          seenConnections.add(conn)
        }

        val durationMicros = (endNanos - startNanos) / 1000
        // Could track latency histogram here if needed
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield conn
  }

  def release(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      _ <- underlying.release(conn)
      _ <- Eru.effect {
        metrics.connectionsReleased.incrementAndGet()
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield ()
  }

  def remove(conn: PooledConnection): Eru[HttpError, Unit] = {
    for {
      _ <- underlying.remove(conn)
      _ <- Eru.effect {
        metrics.connectionsRemoved.incrementAndGet()
        seenConnections.remove(conn)
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield ()
  }

  def shutdown: Eru[HttpError, Unit] = {
    for {
      _ <- underlying.shutdown
      _ <- Eru.effect {
        seenConnections.clear()
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
    } yield ()
  }
}
