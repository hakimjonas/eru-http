package net.ghoula.eru.http.client

import munit.{FunSuite, Location}

import java.time.Instant
import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** Direct coverage for the connection pool: acquire/release/remove lifecycle, per-connection
  * buffer/reader registries, and PoolState bookkeeping.
  *
  * The pool is `private[client]` plumbing — these tests pin its invariants so NativeHttpClient's
  * integration specs can assume them.
  *
  * Per-connection buffers stay registered across releases (reset to position 0) and are only
  * cleared when the connection is removed. Unreachable-host tests target 192.0.2.1, the reserved
  * TEST-NET-1 address with no listener.
  */
class ConnectionPoolSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

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

  val testConfig: HttpClientConfig = HttpClientConfig(
    connectTimeout = 2.seconds,
    maxConnections = 5,
    maxConnectionsPerHost = 2
  )

  test("PooledConnection - key is host:port") {
    val server = TestHttpServer.create()(using runtime)
    try {
      val pool = ConnectionPool.create(testConfig).assertSuccess
      try {
        val conn = pool.acquire("localhost", server.port).assertSuccess
        assertEquals(conn.key, HostKey("localhost", server.port))
        assertEquals(conn.host, "localhost")
        assertEquals(conn.port, server.port)
      } finally pool.shutdown.assertSuccess
    } finally server.shutdown()
  }

  test("ConnectionPool - release reuses the same connection on re-acquire") {
    val server = TestHttpServer.create()(using runtime)
    try {
      val pool = ConnectionPool.create(testConfig).assertSuccess
      try {
        val conn1 = pool.acquire("localhost", server.port).assertSuccess
        pool.release(conn1).assertSuccess

        val conn2 = pool.acquire("localhost", server.port).assertSuccess
        assertEquals(conn2.socket, conn1.socket, "re-acquire must reuse the released connection")
      } finally pool.shutdown.assertSuccess
    } finally server.shutdown()
  }

  test("ConnectionPool - remove closes the connection; re-acquire opens a new one") {
    val server = TestHttpServer.create()(using runtime)
    try {
      val pool = ConnectionPool.create(testConfig).assertSuccess
      try {
        val conn1 = pool.acquire("localhost", server.port).assertSuccess
        pool.remove(conn1).assertSuccess
        assert(!conn1.socket.isOpen, "remove must close the socket")

        val conn2 = pool.acquire("localhost", server.port).assertSuccess
        assertNotEquals(conn2.socket, conn1.socket)
      } finally pool.shutdown.assertSuccess
    } finally server.shutdown()
  }

  test("ConnectionPool - getBuffer returns the same buffer for a connection") {
    val server = TestHttpServer.create()(using runtime)
    try {
      val pool = ConnectionPool.create(testConfig).assertSuccess
      try {
        val conn = pool.acquire("localhost", server.port).assertSuccess
        val buf1 = pool.getBuffer(conn).assertSuccess
        val buf2 = pool.getBuffer(conn).assertSuccess
        assert(buf1 eq buf2, "buffer must be registered per connection and reused")
        pool.release(conn).assertSuccess
        val reacquired = pool.acquire("localhost", server.port).assertSuccess
        val buf3 = pool.getBuffer(reacquired).assertSuccess
        assert(buf3 eq buf1)
      } finally pool.shutdown.assertSuccess
    } finally server.shutdown()
  }

  test("ConnectionPool - getReader returns the same reader for a connection") {
    val server = TestHttpServer.create()(using runtime)
    try {
      val pool = ConnectionPool.create(testConfig).assertSuccess
      try {
        val conn = pool.acquire("localhost", server.port).assertSuccess
        val r1 = pool.getReader(conn).assertSuccess
        val r2 = pool.getReader(conn).assertSuccess
        assert(r1 eq r2, "reader must be registered per connection and reused")
        pool.release(conn).assertSuccess
      } finally pool.shutdown.assertSuccess
    } finally server.shutdown()
  }

  test("ConnectionPool - SSL/H2 registries are empty for plain connections") {
    val server = TestHttpServer.create()(using runtime)
    try {
      val pool = ConnectionPool.create(testConfig).assertSuccess
      try {
        val conn = pool.acquire("localhost", server.port).assertSuccess
        assertEquals(pool.getSSLChannel(conn).assertSuccess, None)
        assertEquals(pool.getSSLReader(conn).assertSuccess, None)
        assertEquals(pool.getH2Connection(conn).assertSuccess, None)
        pool.release(conn).assertSuccess
      } finally pool.shutdown.assertSuccess
    } finally server.shutdown()
  }

  test("ConnectionPool - acquire fails with a connection error for unreachable hosts") {
    val pool = ConnectionPool.create(testConfig.copy(connectTimeout = 500.millis)).assertSuccess
    try {
      val error = pool.acquire("192.0.2.1", 1).assertFailure
      error match {
        case HttpError.ConnectionError(msg, _) =>
          assert(msg.contains("192.0.2.1"))
        case HttpError.TimeoutError(msg) =>
          assert(msg.contains("192.0.2.1"))
        case other =>
          fail(s"Expected ConnectionError or TimeoutError, got: $other")
      }
    } finally pool.shutdown.assertSuccess
  }

  test("PoolState - empty state has zero connections") {
    val state = PoolState.empty
    assertEquals(state.totalConnections, 0)
    assertEquals(state.hostConnections("example.com", 80), 0)
  }

  test("PoolState - totalConnections counts available and in-use") {
    val conn1 =
      PooledConnection(java.nio.channels.SocketChannel.open(), "example.com", 80, Instant.now(), Instant.now())
    val conn2 =
      PooledConnection(java.nio.channels.SocketChannel.open(), "example.com", 80, Instant.now(), Instant.now())
    val conn3 = PooledConnection(java.nio.channels.SocketChannel.open(), "other.com", 443, Instant.now(), Instant.now())
    try {
      val state = PoolState(
        available = Map(HostKey("example.com", 80) -> scala.collection.immutable.Queue(conn1)),
        inUse = Set(conn2, conn3)
      )
      assertEquals(state.totalConnections, 3)
      assertEquals(state.hostConnections("example.com", 80), 2)
      assertEquals(state.hostConnections("other.com", 443), 1)
      assertEquals(state.hostConnections("missing.com", 80), 0)
    } finally {
      conn1.socket.close()
      conn2.socket.close()
      conn3.socket.close()
    }
  }

  test("PoolState - copy leaves the original immutable") {
    val state1 = PoolState.empty
    val conn = PooledConnection(java.nio.channels.SocketChannel.open(), "example.com", 80, Instant.now(), Instant.now())
    try {
      val state2 = state1.copy(inUse = state1.inUse + conn)
      assertEquals(state1.totalConnections, 0)
      assertEquals(state2.totalConnections, 1)
    } finally conn.socket.close()
  }

  test("PooledConnection - withLastUsed updates only the lastUsedAt field") {
    val now = Instant.now()
    val later = now.plusSeconds(10)
    val socket = java.nio.channels.SocketChannel.open()
    try {
      val conn = PooledConnection(socket, "example.com", 80, now, now)
      val updated = conn.withLastUsed(later)
      assertEquals(updated.lastUsedAt, later)
      assertEquals(updated.createdAt, now)
      assertEquals(updated.host, "example.com")
      assertEquals(updated.socket, socket)
    } finally socket.close()
  }

  test("acquire fails with the shutdown error after shutdown, not a connection error") {
    val pool = ConnectionPool.create(testConfig).assertSuccess
    pool.shutdown.assertSuccess

    // localhost:1 has no listener, so a pre-shutdown acquire would fail with a connection error.
    // Post-shutdown acquire must fail immediately with the pool's own shutdown error instead of
    // attempting a new socket.
    val error = pool.acquire("localhost", 1).assertFailure
    assert(
      error.message.toLowerCase.contains("shut down"),
      s"expected the shutdown guard error, got: ${error.message}"
    )
  }
}
