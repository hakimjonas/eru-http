package net.ghoula.eru.http.server

import munit.FunSuite

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.http.server.PerIpGovernor.AcquireResult

/** Unit tests for [[PerIpGovernor]].
  *
  * Several cases are timing-sensitive around token-bucket refill: at rate 100/sec a token arrives
  * every 10ms (a 30ms sleep yields ~3 tokens, capped at burst), at 10/sec every 100ms (retry-after
  * ≤ 2s), at 1/sec every 1s, and zero rate never refills. Concurrent-count assertions allow +1
  * token for refill slop at a 0.01/sec rate and +10 `estimatedSize` slack on `trackedCount`.
  * Accept-rate rejection does NOT consume a connection slot, so the caller need not release on a
  * rejected acquire.
  */
class PerIpGovernorSpec extends FunSuite {

  private def newGovernor(
    trackedIpCap: Int = 100,
    maxConnectionsPerIp: Int = 10,
    acceptRate: Double = 100,
    acceptBurst: Double = 100,
    requestRate: Double = 100,
    requestBurst: Double = 100
  ): PerIpGovernor = new PerIpGovernor(
    trackedIpCap = trackedIpCap,
    maxConnectionsPerIp = maxConnectionsPerIp,
    acceptRatePerIp = acceptRate,
    acceptBurstPerIp = acceptBurst,
    requestsPerSecondPerIp = requestRate,
    burstSizePerIp = requestBurst
  )

  private def ip(s: String): IpKey = IpKey.parse(s).get

  test("concurrent connection cap: first N succeed, N+1 is rejected") {
    val g = newGovernor(maxConnectionsPerIp = 5)
    val addr = ip("1.2.3.4")
    (1 to 5).foreach { _ => assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok) }
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.ConnectionCap)
  }

  test("releasing a connection allows the next acquire") {
    val g = newGovernor(maxConnectionsPerIp = 2)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.ConnectionCap)
    g.releaseConnection(addr)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
  }

  test("release on unknown IP is a no-op") {
    val g = newGovernor()
    g.releaseConnection(ip("1.2.3.4"))
  }

  test("counter does not go below zero on over-release") {
    val g = newGovernor(maxConnectionsPerIp = 5)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    g.releaseConnection(addr)
    g.releaseConnection(addr)
    g.releaseConnection(addr)
    (1 to 5).foreach { _ => assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok) }
  }

  test("per-IP isolation: cap on one IP does not block another") {
    val g = newGovernor(maxConnectionsPerIp = 2)
    val a = ip("1.2.3.4")
    val b = ip("5.6.7.8")
    assertEquals(g.tryAcquireConnection(a), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(a), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(a), AcquireResult.ConnectionCap)
    assertEquals(g.tryAcquireConnection(b), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(b), AcquireResult.Ok)
  }

  test("release on unknown IP is a no-op") {
    val g = newGovernor()
    g.releaseConnection(ip("1.2.3.4"))
  }

  test("counter does not go below zero on over-release") {
    val g = newGovernor(maxConnectionsPerIp = 5)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    g.releaseConnection(addr)
    g.releaseConnection(addr)
    g.releaseConnection(addr)
    (1 to 5).foreach { _ => assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok) }
  }

  test("per-IP isolation: cap on one IP does not block another") {
    val g = newGovernor(maxConnectionsPerIp = 2)
    val a = ip("1.2.3.4")
    val b = ip("5.6.7.8")
    assertEquals(g.tryAcquireConnection(a), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(a), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(a), AcquireResult.ConnectionCap)
    assertEquals(g.tryAcquireConnection(b), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(b), AcquireResult.Ok)
  }

  test("accept-rate cap: burst is honored, over-burst is rejected") {
    val g = newGovernor(maxConnectionsPerIp = 1000, acceptRate = 1, acceptBurst = 10)
    val addr = ip("1.2.3.4")
    (1 to 10).foreach { i =>
      g.tryAcquireConnection(addr) match {
        case AcquireResult.Ok => g.releaseConnection(addr)
        case other => fail(s"iteration $i: expected Ok, got $other")
      }
    }
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.AcceptRateExceeded)
  }

  test("accept-rate refills over time") {
    val g = newGovernor(maxConnectionsPerIp = 1000, acceptRate = 100, acceptBurst = 2)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok); g.releaseConnection(addr)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok); g.releaseConnection(addr)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.AcceptRateExceeded)
    Thread.sleep(30)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok); g.releaseConnection(addr)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok); g.releaseConnection(addr)
  }

  test("accept-rate rejection does NOT consume a connection slot") {
    val g = newGovernor(maxConnectionsPerIp = 5, acceptRate = 1, acceptBurst = 1)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.AcceptRateExceeded)
    g.releaseConnection(addr)
    Thread.sleep(1100)
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
  }

  test("request rate: burst honored, over-burst returns RequestRateExceeded") {
    val g = newGovernor(requestRate = 1, requestBurst = 5)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    (1 to 5).foreach { _ => assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok) }
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.RequestRateExceeded)
    g.releaseConnection(addr)
  }

  test("request-rate retry-after reports bounded seconds") {
    val g = newGovernor(requestRate = 10, requestBurst = 1)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.RequestRateExceeded)
    val retrySec = g.requestRateRetryAfterSeconds(addr)
    assert(retrySec >= 1 && retrySec <= 2, s"retry-after was $retrySec seconds")
    g.releaseConnection(addr)
  }

  test("request rate refills at configured rate") {
    val g = newGovernor(requestRate = 100, requestBurst = 2)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.RequestRateExceeded)
    Thread.sleep(30)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok)
    g.releaseConnection(addr)
  }

  test("fail-closed: new IPs rejected when trackedIpCap reached") {
    val g = newGovernor(trackedIpCap = 3)
    assertEquals(g.tryAcquireConnection(ip("1.0.0.1")), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(ip("1.0.0.2")), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(ip("1.0.0.3")), AcquireResult.Ok)
    assertEquals(g.tryAcquireConnection(ip("1.0.0.4")), AcquireResult.TrackingMapFull)
    assertEquals(g.tryAcquireConnection(ip("1.0.0.1")), AcquireResult.Ok)
    assertEquals(g.sizeEvictions, 0L)
  }

  test("once an IP is fully released, tracking slot can be reclaimed by eviction over time") {
    val g = newGovernor(trackedIpCap = 1000)
    (1 to 500).foreach { i =>
      val addr = ip(s"10.0.${i / 256}.${i % 256}")
      assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
      g.releaseConnection(addr)
    }
    assert(g.trackedCount <= 500, s"trackedCount = ${g.trackedCount}, expected ≤ 500")
  }

  test("concurrent acquire never exceeds maxConnectionsPerIp") {
    val cap = 20
    val g = newGovernor(maxConnectionsPerIp = cap, acceptRate = 100000, acceptBurst = 100000)
    val addr = ip("1.2.3.4")
    val threadCount = 64
    val accepted = new AtomicInteger(0)
    val latch = new CountDownLatch(threadCount)
    val start = new CountDownLatch(1)

    val threads = (1 to threadCount).map { _ =>
      new Thread(() => {
        try {
          start.await()
          if g.tryAcquireConnection(addr) == AcquireResult.Ok then accepted.incrementAndGet(): Unit
        } finally latch.countDown()
      })
    }
    threads.foreach(_.start())
    start.countDown()
    latch.await()

    assertEquals(accepted.get(), cap, s"accepted=${accepted.get()} but cap=$cap")
  }

  test("concurrent token consume never over-consumes") {
    val burst = 50
    val g = newGovernor(maxConnectionsPerIp = 100000, acceptRate = 0.01, acceptBurst = burst.toDouble)
    val addr = ip("1.2.3.4")
    val threadCount = 100
    val accepted = new AtomicInteger(0)
    val latch = new CountDownLatch(threadCount)
    val start = new CountDownLatch(1)

    val threads = (1 to threadCount).map { _ =>
      new Thread(() => {
        try {
          start.await()
          if g.tryAcquireConnection(addr) == AcquireResult.Ok then {
            accepted.incrementAndGet(): Unit
            g.releaseConnection(addr)
          }
        } finally latch.countDown()
      })
    }
    threads.foreach(_.start())
    start.countDown()
    latch.await()

    assert(accepted.get() >= burst && accepted.get() <= burst + 1, s"accepted=${accepted.get()} but burst=$burst")
  }

  test("concurrent tracking-map insert under cap is race-safe") {
    val g = newGovernor(trackedIpCap = 30)
    val threadCount = 50
    val okCount = new AtomicInteger(0)
    val fullCount = new AtomicInteger(0)
    val latch = new CountDownLatch(threadCount)
    val start = new CountDownLatch(1)

    val threads = (1 to threadCount).map { i =>
      new Thread(() => {
        try {
          start.await()
          g.tryAcquireConnection(ip(s"10.0.0.$i")) match {
            case AcquireResult.Ok => okCount.incrementAndGet(): Unit
            case AcquireResult.TrackingMapFull => fullCount.incrementAndGet(): Unit
            case other => fail(s"Unexpected result: $other")
          }
        } finally latch.countDown()
      })
    }
    threads.foreach(_.start())
    start.countDown()
    latch.await()

    assertEquals(okCount.get() + fullCount.get(), threadCount)
    assertEquals(g.sizeEvictions, 0L, "no SIZE-based evictions should have occurred")
    assert(g.trackedCount <= okCount.get().toLong + 10, s"trackedCount=${g.trackedCount}, ok=${okCount.get()}")
  }

  test("zero rate means bucket never refills (but burst still works)") {
    val g = newGovernor(requestRate = 0, requestBurst = 3)
    val addr = ip("1.2.3.4")
    assertEquals(g.tryAcquireConnection(addr), AcquireResult.Ok)
    (1 to 3).foreach { _ => assertEquals(g.tryAcquireRequest(addr), AcquireResult.Ok) }
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.RequestRateExceeded)
    Thread.sleep(50)
    assertEquals(g.tryAcquireRequest(addr), AcquireResult.RequestRateExceeded)
    g.releaseConnection(addr)
  }

  test("TokenBucket - capacity bounds initial and refunded tokens") {
    val bucket = new PerIpGovernor.TokenBucket(capacity = 5.0, ratePerSecond = 1.0)
    assert(bucket.tryConsume(3.0))
    assert(bucket.tryConsume(2.0))
    assert(!bucket.tryConsume(0.1), "bucket must be empty after consuming the full capacity")
    bucket.refund(10.0)
    assert(bucket.tryConsume(4.0), "refund must work but cap at capacity")
    assert(!bucket.tryConsume(2.0), "refund beyond capacity must be capped")
  }

  test("TokenBucket - zero rate never refills but the burst budget is usable") {
    val bucket = new PerIpGovernor.TokenBucket(capacity = 2.0, ratePerSecond = 0.0)
    assert(bucket.tryConsume(2.0))
    assert(!bucket.tryConsume(0.1))
    assertEquals(bucket.secondsUntilOneToken, Int.MaxValue)
  }

  test("TokenBucket - partial consumption leaves a partial balance") {
    val bucket = new PerIpGovernor.TokenBucket(capacity = 10.0, ratePerSecond = 0.0)
    assert(bucket.tryConsume(4.5))
    assert(bucket.tryConsume(5.5))
    assert(!bucket.tryConsume(0.1))
  }
}
