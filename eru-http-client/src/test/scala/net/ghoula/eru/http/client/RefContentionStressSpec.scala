package net.ghoula.eru.http.client

import munit.FunSuite

import scala.concurrent.duration.*

import net.ghoula.eru.*
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
  *
  * Timing budgets are per-test and real-time: the 1000-op test on 10 servers must finish under 10s,
  * while the 2000-op hot-spot test over just 3 hosts allows 20s because it contends harder.
  */
class RefContentionStressSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  val stressConfig: HttpClientConfig = HttpClientConfig(
    connectTimeout = 10.seconds,
    requestTimeout = 10.seconds,
    maxConnections = 500,
    maxConnectionsPerHost = 50
  )

  test("STRESS: 1000 concurrent acquire/release operations") {
    val servers = (0 until 10).map { i =>
      TestHttpServer.simple(port = 0, body = s"Server $i")
    }.toList

    val hosts = servers.map(s => ("localhost", s.port))

    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    val operations = 1000
    val startTime = System.nanoTime()

    val fibers = (1 to operations).map { i =>
      val (host, port) = hosts(i % hosts.length)
      for {
        acquired <- pool.acquire(host, port)
        _ <- pool.release(acquired)
      } yield ()
    }.toList

    val result = parSequence(fibers).attempt.unsafeRunSync()

    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000.0

    result match {
      case Result.Success(_) =>

        assert(durationMs < 10000, s"Too slow: ${durationMs}ms for $operations operations")

      case Result.Failure(error) =>
        fail(s"Stress test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
    servers.foreach(_.shutdown())
  }

  test("STRESS: 2000 concurrent operations with hot spots") {
    val servers = (0 until 3).map { i =>
      TestHttpServer.simple(port = 0, body = s"Hotspot $i")
    }.toList

    val hosts = servers.map(s => ("localhost", s.port))

    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    val operations = 2000
    val startTime = System.nanoTime()

    val fibers = (1 to operations).map { i =>
      val (host, port) = hosts(i % hosts.length)
      for {
        acquired <- pool.acquire(host, port)
        _ <- pool.release(acquired)
      } yield ()
    }.toList

    val result = parSequence(fibers).attempt.unsafeRunSync()

    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000.0

    result match {
      case Result.Success(_) =>

        assert(durationMs < 20000, s"Too slow: ${durationMs}ms for $operations operations")

      case Result.Failure(error) =>
        fail(s"Hot spot stress test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
    servers.foreach(_.shutdown())
  }

  test("STRESS: Measure latency distribution under 500 concurrent ops") {
    val servers = (0 until 5).map { i =>
      TestHttpServer.simple(port = 0, body = s"Server $i")
    }.toList

    val hosts = servers.map(s => ("localhost", s.port))

    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    val operations = 500
    val latencies = new java.util.concurrent.ConcurrentLinkedQueue[Long]()

    val fibers = (1 to operations).map { i =>
      val (host, port) = hosts(i % hosts.length)
      for {
        start <- Eru.effect(System.nanoTime())
        acquired <- pool.acquire(host, port)
        end <- Eru.effect(System.nanoTime())
        _ <- Eru.effect(latencies.add((end - start) / 1_000_000))
        _ <- pool.release(acquired)
      } yield ()
    }.toList

    val result = parSequence(fibers).attempt.unsafeRunSync()

    result match {
      case Result.Success(_) =>
        val latencyList = scala.jdk.CollectionConverters.CollectionHasAsScala(latencies).asScala.toList.sorted
        val p50 = latencyList(latencyList.length / 2)
        val p99 = latencyList((latencyList.length * 99) / 100)
        val avg = latencyList.sum / latencyList.length.toDouble

        assert(p99 < 500, s"P99 latency too high: ${p99}ms")
        assert(avg > 0)
        assert(avg < 500, s"Average latency too high: ${avg}ms")
        assert(p50 <= p99, "p50 must not exceed p99")

      case Result.Failure(error) =>
        fail(s"Latency test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
    servers.foreach(_.shutdown())
  }

  test("STRESS: Sustained load - 5000 operations over time") {
    val servers = (0 until 10).map { i =>
      TestHttpServer.simple(port = 0, body = s"Server $i")
    }.toList

    val hosts = servers.map(s => ("localhost", s.port))

    val pool = ConnectionPool.create(stressConfig).unsafeRunSync()

    val totalOperations = 5000
    val batchSize = 100
    val batches = totalOperations / batchSize

    val startTime = System.nanoTime()

    val result = Eru
      .foreach((1 to batches).toList) { batch =>
        val fibers = (1 to batchSize).map { i =>
          val (host, port) = hosts((batch * batchSize + i) % hosts.length)
          for {
            acquired <- pool.acquire(host, port)
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
        assert(durationMs < 60000, s"Sustained load too slow: ${durationMs}ms")

      case Result.Failure(error) =>
        fail(s"Sustained load test failed: $error")
    }

    pool.shutdown.unsafeRunSync()
    servers.foreach(_.shutdown())
  }
}
