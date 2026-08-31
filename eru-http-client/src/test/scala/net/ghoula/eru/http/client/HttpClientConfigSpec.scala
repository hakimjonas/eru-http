package net.ghoula.eru.http.client

import munit.FunSuite

import scala.concurrent.duration.*

class HttpClientConfigSpec extends FunSuite {

  test("HttpClientConfig - default configuration") {
    val config = HttpClientConfig.default

    assertEquals(config.connectTimeout, 30.seconds)
    assertEquals(config.requestTimeout, 60.seconds)
    assertEquals(config.maxConnections, 100)
    assertEquals(config.maxConnectionsPerHost, 10)
    assertEquals(config.followRedirects, true)
    assertEquals(config.maxRedirects, 5)
    assertEquals(config.enableHttp2, true)
    assertEquals(config.userAgent, Some("eru-http/0.1.0"))
  }

  test("HttpClientConfig - lowLatency preset optimizes for speed") {
    val config = HttpClientConfig.lowLatency

    assert(config.connectTimeout < HttpClientConfig.default.connectTimeout)
    assert(config.requestTimeout < HttpClientConfig.default.requestTimeout)
    assertEquals(config.connectTimeout, 10.seconds)
    assertEquals(config.requestTimeout, 30.seconds)

    assertEquals(config.maxConnections, 50)
    assertEquals(config.maxConnectionsPerHost, 5)
  }

  test("HttpClientConfig - highThroughput preset optimizes for volume") {
    val config = HttpClientConfig.highThroughput

    assert(config.connectTimeout > HttpClientConfig.default.connectTimeout)
    assert(config.requestTimeout > HttpClientConfig.default.requestTimeout)
    assertEquals(config.connectTimeout, 60.seconds)
    assertEquals(config.requestTimeout, 120.seconds)

    assert(config.maxConnections > HttpClientConfig.default.maxConnections)
    assert(config.maxConnectionsPerHost > HttpClientConfig.default.maxConnectionsPerHost)
    assertEquals(config.maxConnections, 500)
    assertEquals(config.maxConnectionsPerHost, 50)
  }

  test("HttpClientConfig - custom configuration") {
    val config = HttpClientConfig(
      connectTimeout = 15.seconds,
      requestTimeout = 30.seconds,
      maxConnections = 200,
      maxConnectionsPerHost = 20,
      followRedirects = false,
      maxRedirects = 3,
      enableHttp2 = false,
      userAgent = Some("custom-agent/2.0")
    )

    assertEquals(config.connectTimeout, 15.seconds)
    assertEquals(config.requestTimeout, 30.seconds)
    assertEquals(config.maxConnections, 200)
    assertEquals(config.maxConnectionsPerHost, 20)
    assertEquals(config.followRedirects, false)
    assertEquals(config.maxRedirects, 3)
    assertEquals(config.enableHttp2, false)
    assertEquals(config.userAgent, Some("custom-agent/2.0"))
  }

  test("HttpClientConfig - copy with modifications") {
    val base = HttpClientConfig.default
    val modified = base.copy(
      requestTimeout = 5.seconds,
      followRedirects = false
    )

    assertEquals(modified.requestTimeout, 5.seconds)
    assertEquals(modified.followRedirects, false)

    assertEquals(modified.connectTimeout, base.connectTimeout)
    assertEquals(modified.maxConnections, base.maxConnections)
  }

  test("HttpClientConfig - no user agent") {
    val config = HttpClientConfig.default.copy(userAgent = None)
    assertEquals(config.userAgent, None)
  }
}
