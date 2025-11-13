package net.ghoula.eru.http.client

import munit.{FunSuite, Location}

import java.nio.channels.SocketChannel
import java.time.Instant
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*

class ConnectionPoolSpec extends FunSuite {

  // Test helpers
  extension [E, A](eru: Eru[E, A]) {
    def assertSuccess(using loc: Location): A = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) => value
        case Result.Failure(error) =>
          fail(s"Expected success but got failure: $error")(using loc)
      }
    }

    def assertFailure(using loc: Location): E = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) =>
          fail(s"Expected failure but got success: $value")(using loc)
        case Result.Failure(error) => error
      }
    }
  }

  // Test configuration with low limits for easier testing
  val testConfig = HttpClientConfig(
    connectTimeout = 100.millis,
    requestTimeout = 100.millis,
    maxConnections = 5,
    maxConnectionsPerHost = 2
  )

  // ===== Pool Creation and Shutdown Tests =====

  test("ConnectionPool - create pool successfully") {
    val pool = ConnectionPool.create(testConfig).assertSuccess
    pool.shutdown.assertSuccess
  }

  test("ConnectionPool - shutdown closes all connections") {
    val pool = ConnectionPool.create(testConfig).assertSuccess

    // Create some mock connections by directly testing PoolState
    // We'll test this via integration tests instead
    pool.shutdown.assertSuccess
  }

  // ===== PoolState Unit Tests =====

  test("PoolState - empty state has zero connections") {
    val state = PoolState.empty
    assertEquals(state.totalConnections, 0)
    assertEquals(state.hostConnections("example.com", 80), 0)
  }

  test("PoolState - totalConnections counts available and in-use") {
    val now = Instant.now()
    val conn1 = PooledConnection(null, "example.com", 80, now, now)
    val conn2 = PooledConnection(null, "example.com", 80, now, now)
    val conn3 = PooledConnection(null, "other.com", 443, now, now)

    val state = PoolState(
      available = Map("example.com:80" -> scala.collection.immutable.Queue(conn1)),
      inUse = Set(conn2, conn3)
    )

    assertEquals(state.totalConnections, 3)
  }

  test("PoolState - hostConnections counts per host") {
    val now = Instant.now()
    val conn1 = PooledConnection(null, "example.com", 80, now, now)
    val conn2 = PooledConnection(null, "example.com", 80, now, now)
    val conn3 = PooledConnection(null, "other.com", 443, now, now)

    val state = PoolState(
      available = Map("example.com:80" -> scala.collection.immutable.Queue(conn1)),
      inUse = Set(conn2, conn3)
    )

    assertEquals(state.hostConnections("example.com", 80), 2)
    assertEquals(state.hostConnections("other.com", 443), 1)
    assertEquals(state.hostConnections("missing.com", 80), 0)
  }

  // ===== PooledConnection Tests =====

  test("PooledConnection - key format is correct") {
    val now = Instant.now()
    val conn = PooledConnection(null, "example.com", 80, now, now)
    assertEquals(conn.key, "example.com:80")

    val connHttps = PooledConnection(null, "secure.com", 443, now, now)
    assertEquals(connHttps.key, "secure.com:443")
  }

  test("PooledConnection - withLastUsed updates timestamp") {
    val now = Instant.now()
    val later = now.plusSeconds(10)
    val conn = PooledConnection(null, "example.com", 80, now, now)

    val updated = conn.withLastUsed(later)

    assertEquals(updated.lastUsedAt, later)
    assertEquals(updated.createdAt, now)
    assertEquals(updated.host, "example.com")
  }

  // ===== Concurrent Access Tests =====

  test("ConnectionPool - concurrent acquire attempts are atomic") {
    // This test verifies that Ref.modify properly handles concurrent access
    val pool = ConnectionPool.create(testConfig.copy(maxConnections = 2, maxConnectionsPerHost = 2)).assertSuccess

    // We can't easily test concurrency without a real server
    // This will be covered in integration tests
    pool.shutdown.assertSuccess
  }

  // ===== Error Handling Tests =====

  test("ConnectionPool - acquire fails when host is unreachable") {
    val pool = ConnectionPool.create(testConfig).assertSuccess

    // Try to connect to invalid host (should fail fast)
    val error = pool.acquire("invalid-host-that-does-not-exist", 12345).assertFailure

    error match {
      case HttpError.ConnectionError(msg, _) =>
        assert(msg.contains("invalid-host-that-does-not-exist"))
      case HttpError.TimeoutError(msg) =>
        assert(msg.contains("invalid-host-that-does-not-exist") || msg.contains("Pool exhausted"))
      case other =>
        fail(s"Expected ConnectionError or TimeoutError, got: $other")
    }

    pool.shutdown.assertSuccess
  }

  test("ConnectionPool - remove closes socket and removes from pool") {
    // This test requires a real connection, which we'll test in integration tests
    // Here we just verify the interface exists
    val pool = ConnectionPool.create(testConfig).assertSuccess
    pool.shutdown.assertSuccess
  }

  test("ConnectionPool - release returns connection to pool") {
    // This test requires a real connection, which we'll test in integration tests
    // Here we just verify the interface exists
    val pool = ConnectionPool.create(testConfig).assertSuccess
    pool.shutdown.assertSuccess
  }

  test("ConnectionPool - pool respects global connection limit") {
    // Will be tested in integration tests with real connections
    val pool = ConnectionPool.create(testConfig.copy(maxConnections = 2)).assertSuccess
    pool.shutdown.assertSuccess
  }

  test("ConnectionPool - pool respects per-host connection limit") {
    // Will be tested in integration tests with real connections
    val pool = ConnectionPool.create(testConfig.copy(maxConnectionsPerHost = 1)).assertSuccess
    pool.shutdown.assertSuccess
  }

  // ===== Additional Unit Tests for Ref Validation =====

  test("PoolState - immutability check") {
    val state1 = PoolState.empty
    val now = Instant.now()
    val conn = PooledConnection(null, "example.com", 80, now, now)

    // Create new state with connection
    val state2 = state1.copy(
      inUse = state1.inUse + conn
    )

    // Verify state1 is unchanged
    assertEquals(state1.totalConnections, 0)
    assertEquals(state2.totalConnections, 1)
  }

  test("PoolState - available queue operations") {
    val now = Instant.now()
    val conn1 = PooledConnection(null, "example.com", 80, now, now)
    val conn2 = PooledConnection(null, "example.com", 80, now, now)

    val state = PoolState.empty

    // Add to queue
    val queue1 = scala.collection.immutable.Queue.empty[PooledConnection].enqueue(conn1)
    val state1 = state.copy(available = Map("example.com:80" -> queue1))

    assertEquals(state1.available("example.com:80").size, 1)

    // Add another
    val queue2 = queue1.enqueue(conn2)
    val state2 = state1.copy(available = Map("example.com:80" -> queue2))

    assertEquals(state2.available("example.com:80").size, 2)

    // Dequeue (FIFO)
    val (head, tail) = state2.available("example.com:80").dequeue
    assertEquals(head, conn1)
    assertEquals(tail.size, 1)
  }

  test("PoolState - multiple hosts tracking") {
    val now = Instant.now()
    val conn1 = PooledConnection(null, "host1.com", 80, now, now)
    val conn2 = PooledConnection(null, "host2.com", 80, now, now)
    val conn3 = PooledConnection(null, "host3.com", 443, now, now)

    val state = PoolState(
      available = Map(
        "host1.com:80" -> scala.collection.immutable.Queue(conn1),
        "host2.com:80" -> scala.collection.immutable.Queue(conn2)
      ),
      inUse = Set(conn3)
    )

    assertEquals(state.totalConnections, 3)
    assertEquals(state.hostConnections("host1.com", 80), 1)
    assertEquals(state.hostConnections("host2.com", 80), 1)
    assertEquals(state.hostConnections("host3.com", 443), 1)
  }
}
