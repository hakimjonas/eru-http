package net.ghoula.eru.http.client

import munit.FunSuite

import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.defaultRuntime

import TestHelpers.*

class ConnectionPoolSpec extends FunSuite {

  // ===== Pool Lifecycle Tests =====

  test("ConnectionPool - create and shutdown") {
    val config = HttpClientConfig.default
    val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

    pool.shutdown.assertSuccess
  }

  test("ConnectionPool - acquire creates new connection") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Acquire connection
        val conn = pool.acquire("localhost", server.port).assertSuccess

        // Verify connection is valid
        assert(conn.socket.isOpen)
        assert(conn.socket.isConnected)
        assertEquals(conn.host, "localhost")
        assertEquals(conn.port, server.port)

        // Clean up
        pool.remove(conn).assertSuccess
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  test("ConnectionPool - release returns connection to pool") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Acquire and release
        val conn1 = pool.acquire("localhost", server.port).assertSuccess
        pool.release(conn1).assertSuccess

        // Acquire again - should reuse connection
        val conn2 = pool.acquire("localhost", server.port).assertSuccess

        // Verify same socket (connection reused)
        assertEquals(conn2.socket, conn1.socket)

        pool.remove(conn2).assertSuccess
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  test("ConnectionPool - remove closes connection") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Acquire connection
        val conn = pool.acquire("localhost", server.port).assertSuccess
        assert(conn.socket.isOpen)

        // Remove connection
        pool.remove(conn).assertSuccess

        // Verify socket is closed
        assert(!conn.socket.isOpen)
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  test("ConnectionPool - shutdown closes all connections") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      // Acquire multiple connections
      val conn1 = pool.acquire("localhost", server.port).assertSuccess
      val conn2 = pool.acquire("localhost", server.port).assertSuccess
      val conn3 = pool.acquire("localhost", server.port).assertSuccess

      // Release some connections
      pool.release(conn1).assertSuccess
      pool.release(conn2).assertSuccess
      // conn3 remains in-use

      // All should be open before shutdown
      assert(conn1.socket.isOpen)
      assert(conn2.socket.isOpen)
      assert(conn3.socket.isOpen)

      // Shutdown
      pool.shutdown.assertSuccess

      // All should be closed after shutdown
      assert(!conn1.socket.isOpen)
      assert(!conn2.socket.isOpen)
      assert(!conn3.socket.isOpen)
    } finally {
      server.shutdown()
    }
  }

  // ===== Pool Limit Tests =====

  test("ConnectionPool - enforces maxConnectionsPerHost limit") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(2)
        .withMaxConnections(100)
        .withConnectTimeout(1.second)
        .withRequestTimeout(1.second)

      val pool = ConnectionPool.create(config, 1.second).assertSuccess

      try {
        // Acquire up to limit
        val conn1 = pool.acquire("localhost", server.port).assertSuccess
        val conn2 = pool.acquire("localhost", server.port).assertSuccess

        // Third acquire should timeout (pool exhausted)
        val acquireResult = pool
          .acquire("localhost", server.port)
          .timeout(java.time.Duration.ofSeconds(2))
          .attempt
          .unsafeRunSync()

        // Should fail with pool exhausted error
        acquireResult match {
          case Result.Failure(error: HttpError.ConnectionError) =>
            assert(error.message.contains("Pool exhausted"))
          case Result.Failure(error) =>
            fail(s"Expected ConnectionError but got: $error")
          case Result.Success(_) =>
            fail("Expected failure but got success")
        }

        // Clean up
        pool.remove(conn1).assertSuccess
        pool.remove(conn2).assertSuccess
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  test("ConnectionPool - release allows new acquisition after limit reached") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(2)
        .withMaxConnections(100)

      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Fill pool to limit
        val conn1 = pool.acquire("localhost", server.port).assertSuccess
        val conn2 = pool.acquire("localhost", server.port).assertSuccess

        // Release one connection
        pool.release(conn1).assertSuccess

        // Should now be able to acquire (gets released connection)
        val conn3 = pool.acquire("localhost", server.port).assertSuccess

        // Clean up
        pool.remove(conn2).assertSuccess
        pool.remove(conn3).assertSuccess
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  test("ConnectionPool - enforces global maxConnections limit") {
    val server1 = TestHttpServer.simple(port = 0, body = "server1")
    val server2 = TestHttpServer.simple(port = 0, body = "server2")
    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(10)
        .withMaxConnections(3) // Global limit of 3

      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Acquire from two different hosts
        val conn1 = pool.acquire("localhost", server1.port).assertSuccess
        val conn2 = pool.acquire("localhost", server1.port).assertSuccess
        val conn3 = pool.acquire("localhost", server2.port).assertSuccess

        // Fourth acquire should fail (global limit reached)
        val acquireResult = pool
          .acquire("localhost", server2.port)
          .timeout(java.time.Duration.ofSeconds(2))
          .attempt
          .unsafeRunSync()

        acquireResult match {
          case Result.Failure(error: HttpError.ConnectionError) =>
            assert(error.message.contains("Pool exhausted"))
          case Result.Failure(error) =>
            fail(s"Expected ConnectionError but got: $error")
          case Result.Success(_) =>
            fail("Expected failure but got success")
        }

        // Clean up
        pool.remove(conn1).assertSuccess
        pool.remove(conn2).assertSuccess
        pool.remove(conn3).assertSuccess
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server1.shutdown()
      server2.shutdown()
    }
  }

  // ===== Concurrent Access Tests =====

  test("ConnectionPool - concurrent acquire from multiple fibers") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(10)
        .withMaxConnections(50)

      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Launch 20 concurrent acquires
        val program = Eru
          .foreach(1 to 20) { _ =>
            pool
              .acquire("localhost", server.port)
              .flatMap { conn =>
                // Hold connection briefly
                Eru
                  .effect(Thread.sleep(10))
                  .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
                  .flatMap(_ => pool.remove(conn))
              }
              .fork
          }
          .flatMap(fibers => Eru.foreach(fibers)(_.join))

        program.assertSuccess
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  test("ConnectionPool - concurrent release does not lose connections") {
    val server = TestHttpServer.simple(body = "test")
    try {
      val config = HttpClientConfig.default
        .withMaxConnectionsPerHost(20)
        .withMaxConnections(100)

      val pool = ConnectionPool.create(config, 10.seconds).assertSuccess

      try {
        // Acquire 10 connections
        val connections = (1 to 10)
          .map(_ => pool.acquire("localhost", server.port).assertSuccess)
          .toList

        // Release all concurrently
        val releaseProgram = Eru
          .foreach(connections) { conn =>
            pool.release(conn).fork
          }
          .flatMap(fibers => Eru.foreach(fibers)(_.join))

        releaseProgram.assertSuccess

        // Verify all released connections are reusable
        val reacquired = (1 to 10)
          .map(_ => pool.acquire("localhost", server.port).assertSuccess)
          .toList

        // Clean up
        reacquired.foreach(conn => pool.remove(conn).assertSuccess)
      } finally {
        pool.shutdown.assertSuccess
      }
    } finally {
      server.shutdown()
    }
  }

  // ===== Error Handling Tests =====

  test("ConnectionPool - acquire fails for invalid host") {
    val config = HttpClientConfig.default
    val pool = ConnectionPool.create(config, 1.second).assertSuccess

    try {
      // Try to connect to non-existent host
      val result = pool.acquire("invalid.nonexistent.host", 99999).attempt.unsafeRunSync()

      result match {
        case Result.Failure(_: HttpError.ConnectionError) =>
          () // Expected
        case Result.Failure(error) =>
          fail(s"Expected ConnectionError but got: $error")
        case Result.Success(_) =>
          fail("Expected failure for invalid host")
      }
    } finally {
      pool.shutdown.assertSuccess
    }
  }

  test("ConnectionPool - acquire respects connect timeout") {
    val config = HttpClientConfig.default
    val pool = ConnectionPool.create(config, 100.milliseconds).assertSuccess

    try {
      // Try to connect to non-routable IP (will timeout)
      val result = pool.acquire("10.255.255.1", 9999).attempt.unsafeRunSync()

      result match {
        case Result.Failure(_: HttpError.ConnectionError) =>
          () // Expected (timeout)
        case Result.Failure(error) =>
          fail(s"Expected ConnectionError but got: $error")
        case Result.Success(_) =>
          fail("Expected timeout for non-routable IP")
      }
    } finally {
      pool.shutdown.assertSuccess
    }
  }
}
