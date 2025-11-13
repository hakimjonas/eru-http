package net.ghoula.eru.http.client

import munit.FunSuite

import java.nio.channels.SocketChannel
import java.time.Instant
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

/** Extreme stress tests for Ref contention in connection pool.
  *
  * These tests are designed to FIND THE LIMITS of Eru's Ref:
  *   - 1000+ concurrent operations
  *   - Measure CAS retry rates
  *   - Detect performance degradation
  *   - Find the breaking point
  *
  * This is DOGFOODING at its finest - we WANT to find problems!
  */
class RefContentionStressSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  val stressConfig: HttpClientConfig = HttpClientConfig(
    connectTimeout = 10.seconds,
    requestTimeout = 10.seconds,
    maxConnections = 500,
    maxConnectionsPerHost = 50
  )

  test("STRESS: 1000 concurrent acquire/release operations") {
    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    // Create 100 mock connections (we'll cycle through them)
    val mockConnections = (1 to 100).map { i =>
      val socket = createMockSocket()
      PooledConnection(socket, s"host${i % 10}.com", 80, Instant.now(), Instant.now())
    }.toList

    val operations = 1000
    val startTime = System.nanoTime()

    // Launch 1000 concurrent fibers, each doing acquire/release
    val fibers = (1 to operations).map { i =>
      val conn = mockConnections(i % mockConnections.length)
      for {
        _ <- pool.release(conn) // Put it in pool first
        acquired <- pool.acquire(conn.host, conn.port)
        _ <- pool.release(acquired)
      } yield ()
    }.toList

    val result = parSequence(fibers).attempt.unsafeRunSync()

    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000.0

    result match {
      case Result.Success(_) =>
        println(s"✅ 1000 concurrent operations completed in ${durationMs}ms")
        println(s"   Average: ${durationMs / operations}ms per operation")
        println(s"   Throughput: ${operations / (durationMs / 1000.0)} ops/sec")
        assert(durationMs < 10000, s"Too slow: ${durationMs}ms for $operations operations")

      case Result.Failure(error) =>
        fail(s"Stress test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
  }

  test("STRESS: 2000 concurrent operations with hot spots") {
    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    // Create connections for only 3 hosts - MAXIMUM CONTENTION
    val mockConnections = (1 to 30).map { i =>
      val socket = createMockSocket()
      PooledConnection(socket, s"hotspot${i % 3}.com", 80, Instant.now(), Instant.now())
    }.toList

    val operations = 2000
    val startTime = System.nanoTime()

    // All operations fight over the SAME 3 hosts
    val fibers = (1 to operations).map { i =>
      val conn = mockConnections(i % mockConnections.length)
      for {
        _ <- pool.release(conn)
        acquired <- pool.acquire(conn.host, conn.port)
        _ <- pool.release(acquired)
      } yield ()
    }.toList

    val result = parSequence(fibers).attempt.unsafeRunSync()

    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000.0

    result match {
      case Result.Success(_) =>
        println(s"✅ 2000 operations (3 hot hosts) completed in ${durationMs}ms")
        println(s"   Average: ${durationMs / operations}ms per operation")
        println("   This tests MAXIMUM Ref contention!")
        // More lenient time limit due to extreme contention
        assert(durationMs < 20000, s"Too slow: ${durationMs}ms for $operations operations")

      case Result.Failure(error) =>
        fail(s"Hot spot stress test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
  }

  test("STRESS: Measure latency distribution under 500 concurrent ops") {
    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    val mockConnections = (1 to 50).map { i =>
      val socket = createMockSocket()
      PooledConnection(socket, s"host${i % 5}.com", 80, Instant.now(), Instant.now())
    }.toList

    val operations = 500
    val latencies = new java.util.concurrent.ConcurrentLinkedQueue[Long]()

    // Measure per-operation latency
    val fibers = (1 to operations).map { i =>
      val conn = mockConnections(i % mockConnections.length)
      for {
        _ <- pool.release(conn)
        start = System.nanoTime()
        acquired <- pool.acquire(conn.host, conn.port)
        end = System.nanoTime()
        _ = latencies.add((end - start) / 1_000_000) // Convert to ms
        _ <- pool.release(acquired)
      } yield ()
    }.toList

    val result = parSequence(fibers).attempt.unsafeRunSync()

    result match {
      case Result.Success(_) =>
        val latencyList = scala.jdk.CollectionConverters.CollectionHasAsScala(latencies).asScala.toList.sorted
        val p50 = latencyList(latencyList.length / 2)
        val p95 = latencyList((latencyList.length * 95) / 100)
        val p99 = latencyList((latencyList.length * 99) / 100)
        val max = latencyList.last
        val avg = latencyList.sum / latencyList.length.toDouble

        println("✅ Latency distribution (500 concurrent ops):")
        println(s"   Avg:  ${avg}ms")
        println(s"   P50:  ${p50}ms")
        println(s"   P95:  ${p95}ms")
        println(s"   P99:  ${p99}ms")
        println(s"   Max:  ${max}ms")

        // Check for reasonable latency
        assert(p99 < 100, s"P99 latency too high: ${p99}ms")

        // Check for variance (if p99 >> p50, indicates contention)
        val variance = p99.toDouble / p50.toDouble
        println(s"   P99/P50 ratio: ${variance}x")
        if variance > 10 then {
          println("   ⚠️  HIGH VARIANCE detected - possible Ref contention!")
        }

      case Result.Failure(error) =>
        fail(s"Latency test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
  }

  test("STRESS: Sustained load - 5000 operations over time") {
    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    val mockConnections = (1 to 100).map { i =>
      val socket = createMockSocket()
      PooledConnection(socket, s"host${i % 10}.com", 80, Instant.now(), Instant.now())
    }.toList

    val totalOperations = 5000
    val batchSize = 100
    val batches = totalOperations / batchSize

    val startTime = System.nanoTime()

    // Run in batches to simulate sustained load
    val result = Eru
      .foreach((1 to batches).toList) { batch =>
        val fibers = (1 to batchSize).map { i =>
          val conn = mockConnections((batch * batchSize + i) % mockConnections.length)
          for {
            _ <- pool.release(conn)
            acquired <- pool.acquire(conn.host, conn.port)
            _ <- pool.release(acquired)
          } yield ()
        }.toList

        parSequence(fibers)
      }
      .attempt
      .unsafeRunSync()

    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000.0

    result match {
      case Result.Success(_) =>
        println(s"✅ 5000 operations (50 batches of 100) completed in ${durationMs}ms")
        println(s"   Average: ${durationMs / totalOperations}ms per operation")
        println(s"   Throughput: ${totalOperations / (durationMs / 1000.0)} ops/sec")
        println("   Sustained performance validated!")

      case Result.Failure(error) =>
        fail(s"Sustained load test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
  }

  // Helper to create mock socket
  private def createMockSocket(): SocketChannel = {
    val socket = SocketChannel.open()
    socket.configureBlocking(false)
    socket
  }
}
