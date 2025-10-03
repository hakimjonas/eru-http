package net.ghoula.eru.http.server

import munit.FunSuite

import scala.concurrent.duration.*

class HttpServerConfigSpec extends FunSuite {

  test("HttpServerConfig - default configuration") {
    val config = HttpServerConfig.default

    assertEquals(config.host, "0.0.0.0")
    assertEquals(config.port, 8080)
    assertEquals(config.backlog, 128)
    assertEquals(config.idleTimeout, 60.seconds)
    assertEquals(config.maxRequestSize, 10 * 1024 * 1024)
    assertEquals(config.enableHttp2, false)
    assertEquals(config.gracefulShutdownTimeout, 10.seconds)
  }

  test("HttpServerConfig - localhost preset") {
    val config = HttpServerConfig.localhost

    assertEquals(config.host, "localhost")
    assertEquals(config.port, 8080)
  }

  test("HttpServerConfig - highThroughput preset") {
    val config = HttpServerConfig.highThroughput

    // Higher limits for throughput
    assert(config.backlog > HttpServerConfig.default.backlog)
    assert(config.maxRequestSize > HttpServerConfig.default.maxRequestSize)
    assert(config.gracefulShutdownTimeout > HttpServerConfig.default.gracefulShutdownTimeout)

    assertEquals(config.backlog, 1024)
    assertEquals(config.maxRequestSize, 50 * 1024 * 1024)
    assertEquals(config.gracefulShutdownTimeout, 30.seconds)
  }

  test("HttpServerConfig - microservice preset") {
    val config = HttpServerConfig.microservice

    // Smaller limits and faster shutdown for containers
    assertEquals(config.port, 8080)
    assertEquals(config.backlog, 256)
    assertEquals(config.idleTimeout, 30.seconds)
    assertEquals(config.maxRequestSize, 5 * 1024 * 1024)
    assertEquals(config.gracefulShutdownTimeout, 5.seconds)
  }

  test("HttpServerConfig - custom configuration") {
    val config = HttpServerConfig(
      host = "127.0.0.1",
      port = 9000,
      backlog = 512,
      idleTimeout = 45.seconds,
      maxRequestSize = 20 * 1024 * 1024,
      enableHttp2 = true,
      gracefulShutdownTimeout = 15.seconds
    )

    assertEquals(config.host, "127.0.0.1")
    assertEquals(config.port, 9000)
    assertEquals(config.backlog, 512)
    assertEquals(config.idleTimeout, 45.seconds)
    assertEquals(config.maxRequestSize, 20 * 1024 * 1024)
    assertEquals(config.enableHttp2, true)
    assertEquals(config.gracefulShutdownTimeout, 15.seconds)
  }

  test("HttpServerConfig - builder methods") {
    val config = HttpServerConfig.default
      .withHost("192.168.1.1")
      .withPort(3000)
      .withBacklog(256)
      .withIdleTimeout(90.seconds)
      .withMaxRequestSize(15 * 1024 * 1024)
      .withHttp2(true)
      .withGracefulShutdownTimeout(20.seconds)

    assertEquals(config.host, "192.168.1.1")
    assertEquals(config.port, 3000)
    assertEquals(config.backlog, 256)
    assertEquals(config.idleTimeout, 90.seconds)
    assertEquals(config.maxRequestSize, 15 * 1024 * 1024)
    assertEquals(config.enableHttp2, true)
    assertEquals(config.gracefulShutdownTimeout, 20.seconds)
  }

  test("HttpServerConfig - copy with modifications") {
    val base = HttpServerConfig.default
    val modified = base.copy(
      host = "10.0.0.1",
      port = 4000
    )

    assertEquals(modified.host, "10.0.0.1")
    assertEquals(modified.port, 4000)

    // Other fields should remain unchanged
    assertEquals(modified.backlog, base.backlog)
    assertEquals(modified.idleTimeout, base.idleTimeout)
    assertEquals(modified.maxRequestSize, base.maxRequestSize)
  }
}
