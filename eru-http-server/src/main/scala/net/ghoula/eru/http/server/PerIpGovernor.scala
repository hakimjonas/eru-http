package net.ghoula.eru.http.server

import com.github.benmanes.caffeine.cache.{Cache, Caffeine, RemovalCause}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.annotation.tailrec

/** Per-IP DoS governor: connection caps, an acceptance rate, and a request rate.
  *
  * Backed by a Caffeine cache with a **hard** upper bound (`trackedIpCap`). When the cache is full
  * and a new IP arrives, this class REJECTS the new IP (fail-closed) rather than evicting an
  * existing entry. That's deliberate: an attacker rotating through fresh IPs must not be able to
  * forcibly evict a legitimate client's counter.
  *
  * All three per-IP counters (concurrent-connections, accept-rate, request-rate) share the same
  * `IpEntry` to keep the entry count bounded by a single cap. The connection counter is an
  * `AtomicInteger` (exact, increment/decrement on accept/release). The two token buckets are
  * `AtomicLong`-backed with nanosecond-precision CAS refill.
  *
  * Caffeine handles:
  *   - Thread-safe get-or-compute (`getIfPresent` + `put`).
  *   - Background expiration (`expireAfterAccess = 10 minutes`) so abandoned entries are freed even
  *     if they were never at capacity. Expired entries don't reclaim connections — a live
  *     connection holds the entry via its counter; the decrement on release is what actually frees
  *     it.
  *
  * Construction and all methods are blocking and synchronous on purpose: connection gating runs on
  * the accept-loop Virtual Thread (before a connection permit is acquired), and request-rate checks
  * run on the per-connection handler Virtual Thread. An Eru-wrapped API would add overhead without
  * value.
  */
private[server] final class PerIpGovernor(
  val trackedIpCap: Int,
  val maxConnectionsPerIp: Int,
  val acceptRatePerIp: Double,
  val acceptBurstPerIp: Double,
  val requestsPerSecondPerIp: Double,
  val burstSizePerIp: Double
) {
  import PerIpGovernor.*

  /** Bounded cache. Caffeine's `maximumSize` is a soft cap — the fail-closed `acquire*` methods
    * enforce the hard cap by pre-checking `estimatedSize()` before put.
    */
  private val cache: Cache[IpKey, IpEntry] = Caffeine
    .newBuilder()
    .maximumSize(trackedIpCap.toLong)
    .expireAfterAccess(java.time.Duration.ofMinutes(10))
    .removalListener[IpKey, IpEntry] { (_, _, cause: RemovalCause) =>
      if cause == RemovalCause.SIZE then {
        evictions.increment()
      }
    }
    .build[IpKey, IpEntry]()

  private val evictions = new java.util.concurrent.atomic.LongAdder()

  /** Stat: how many Caffeine SIZE evictions ever happened. Should stay at 0 if the fail-closed
    * guard is working — non-zero means a race slipped through and we evicted an existing entry.
    */
  def sizeEvictions: Long = evictions.sum()

  /** Approximate count of tracked IPs. Caffeine's count is eventually-consistent but good enough
    * for test assertions and metrics.
    */
  def trackedCount: Long = cache.estimatedSize()

  /** Try to claim a concurrent-connection slot for `ip`.
    *
    * Returns one of:
    *   - `AcquireResult.Ok` — slot claimed. Caller must call `releaseConnection(ip)` exactly once.
    *   - `AcquireResult.ConnectionCap` — this IP is already at `maxConnectionsPerIp`.
    *   - `AcquireResult.AcceptRateExceeded` — this IP has exhausted its accept-rate budget.
    *   - `AcquireResult.TrackingMapFull` — tracking cap reached AND this IP is unknown.
    *     Fail-closed.
    *
    * The accept-rate check short-circuits before the connection-count check so an attacker churning
    * open/close can't bypass the cap by staying under `maxConnectionsPerIp`. If the count check
    * then hits the cap, the consumed accept token is refunded so the caller isn't charged twice.
    */
  def tryAcquireConnection(ip: IpKey): AcquireResult = {
    getOrCreateEntry(ip) match {
      case None => AcquireResult.TrackingMapFull
      case Some(entry) =>
        if !entry.acceptBucket.tryConsume(1.0) then AcquireResult.AcceptRateExceeded
        else
          incrementConnIfUnderCap(entry) match {
            case true => AcquireResult.Ok
            case false =>
              entry.acceptBucket.refund(1.0)
              AcquireResult.ConnectionCap
          }
    }
  }

  /** CAS-increment the connection counter if below `maxConnectionsPerIp`. Returns true if the
    * caller successfully claimed a slot, false if the cap was reached.
    */
  @tailrec
  private def incrementConnIfUnderCap(entry: IpEntry): Boolean = {
    val current = entry.connCount.get()
    if current >= maxConnectionsPerIp then false
    else if entry.connCount.compareAndSet(current, current + 1) then true
    else incrementConnIfUnderCap(entry)
  }

  /** Decrement the concurrent-connection count for `ip`. Safe to call multiple times — if the entry
    * no longer exists (expired), it's a no-op. Counter never goes below zero.
    */
  def releaseConnection(ip: IpKey): Unit =
    Option(cache.getIfPresent(ip)).foreach(decrementConnClampedAtZero)

  @tailrec
  private def decrementConnClampedAtZero(entry: IpEntry): Unit = {
    val current = entry.connCount.get()
    if current <= 0 then ()
    else if entry.connCount.compareAndSet(current, current - 1) then ()
    else decrementConnClampedAtZero(entry)
  }

  /** Try to consume 1 token from the per-IP request-rate bucket.
    *
    * Returns:
    *   - `AcquireResult.Ok` — token consumed, request allowed.
    *   - `AcquireResult.RequestRateExceeded` — no tokens, request must be rejected with 429.
    *   - `AcquireResult.TrackingMapFull` — tracking map full AND this IP is unknown.
    *
    * Note: under normal operation, `tryAcquireConnection` runs before this on every connection, so
    * the entry will already exist for any IP making requests. `TrackingMapFull` from here is only
    * possible if the entry was evicted between accept and request — extremely rare.
    *
    * An IP that arrives via `X-Forwarded-For` (never connected to us directly) still gets a bucket
    * via `getOrCreateEntry`; if the tracking map is at capacity and `ip` is unknown, it fails
    * closed as usual.
    */
  def tryAcquireRequest(ip: IpKey): AcquireResult = {
    getOrCreateEntry(ip) match {
      case None => AcquireResult.TrackingMapFull
      case Some(entry) =>
        if entry.requestBucket.tryConsume(1.0) then AcquireResult.Ok
        else AcquireResult.RequestRateExceeded
    }
  }

  /** Wall-clock estimate of when the request-rate bucket will have at least one token again. Used
    * to populate `Retry-After`. Returns seconds (rounded up, minimum 1).
    */
  def requestRateRetryAfterSeconds(ip: IpKey): Int =
    Option(cache.getIfPresent(ip)).fold(1)(_.requestBucket.secondsUntilOneToken)

  /** Get the per-IP entry, creating it if absent.
    *
    * Fail-closed hard cap: if the tracking map is at capacity and `ip` is unknown, returns `None`
    * rather than letting Caffeine evict an existing entry. `estimatedSize` is
    * eventually-consistent, so a transient over-count (by at most a few entries) is acceptable; an
    * attacker cannot bulk-evict because each attempt is O(1) and rejected.
    *
    * `asMap().putIfAbsent` gives atomic-if-absent semantics: a prior value means this fiber lost a
    * race and another fiber's entry wins — ours is discarded and theirs is used.
    */
  private def getOrCreateEntry(ip: IpKey): Option[IpEntry] =
    Option(cache.getIfPresent(ip)).orElse {
      if cache.estimatedSize() >= trackedIpCap then None
      else {
        val fresh = new IpEntry(
          new AtomicInteger(0),
          new TokenBucket(acceptBurstPerIp, acceptRatePerIp),
          new TokenBucket(burstSizePerIp, requestsPerSecondPerIp)
        )
        Some(Option(cache.asMap().putIfAbsent(ip, fresh)).getOrElse(fresh))
      }
    }
}

private[server] object PerIpGovernor {

  /** Result of an acquire call. */
  enum AcquireResult {
    case Ok
    case ConnectionCap
    case AcceptRateExceeded
    case RequestRateExceeded
    case TrackingMapFull
  }

  /** Per-IP state. Packed into a single value so the Caffeine cache's entry count bounds memory. */
  private[server] final class IpEntry(
    val connCount: AtomicInteger,
    val acceptBucket: TokenBucket,
    val requestBucket: TokenBucket
  )

  /** Token bucket with nanosecond-precision refill.
    *
    * State is two fields guarded by a single `AtomicLong` holding `lastRefillNanos`; tokens are
    * kept in a separate `AtomicLong` storing the current integer token count × 1000 (millitokens)
    * so sub-token refills are not lost to integer truncation between calls.
    *
    * @param capacity
    *   maximum tokens the bucket can hold (burst size)
    * @param ratePerSecond
    *   refill rate in tokens per second
    */
  final class TokenBucket(capacity: Double, ratePerSecond: Double) {
    private val nanosPerToken: Long =
      if ratePerSecond <= 0 then Long.MaxValue
      else math.max(1L, (1_000_000_000.0 / ratePerSecond).toLong)

    private val milliTokens = new AtomicLong((capacity * 1000.0).toLong)
    private val lastRefillNanos = new AtomicLong(System.nanoTime())
    private val capacityMilli: Long = (capacity * 1000.0).toLong

    /** Try to consume `count` tokens. Returns true on success, false if insufficient tokens. */
    def tryConsume(count: Double): Boolean = {
      val wantMilli = (count * 1000.0).toLong
      refill()
      tryConsumeCas(wantMilli)
    }

    @tailrec
    private def tryConsumeCas(wantMilli: Long): Boolean = {
      val current = milliTokens.get()
      if current < wantMilli then false
      else if milliTokens.compareAndSet(current, current - wantMilli) then true
      else tryConsumeCas(wantMilli)
    }

    /** Refund tokens (e.g. after a speculative consume that got rolled back). Caps at capacity. */
    def refund(count: Double): Unit = refundCas((count * 1000.0).toLong)

    @tailrec
    private def refundCas(addMilli: Long): Unit = {
      val current = milliTokens.get()
      val next = math.min(capacityMilli, current + addMilli)
      if milliTokens.compareAndSet(current, next) then () else refundCas(addMilli)
    }

    /** Estimated seconds until at least 1 token is available. Rounded up, minimum 1. */
    def secondsUntilOneToken: Int = {
      refill()
      val current = milliTokens.get()
      if current >= 1000 then 1
      else if ratePerSecond <= 0 then Int.MaxValue
      else {
        val needed = 1000 - current
        val seconds = math.ceil(needed.toDouble / (ratePerSecond * 1000.0)).toInt
        math.max(1, seconds)
      }
    }

    /** Bring the token count up to date based on elapsed wall time.
      *
      * Only one fiber advances `lastRefillNanos` per CAS. Losers see the updated timestamp and
      * refill from that point; no double-refill.
      */
    private def refill(): Unit = {
      val now = System.nanoTime()
      val last = lastRefillNanos.get()
      val elapsedNanos = now - last
      val didAdvance = elapsedNanos > 0 && lastRefillNanos.compareAndSet(last, now)
      if didAdvance then {
        val addMilli = (elapsedNanos.toDouble / nanosPerToken.toDouble * 1000.0).toLong
        if addMilli > 0 then addToBucketCas(addMilli)
      }
    }

    @tailrec
    private def addToBucketCas(addMilli: Long): Unit = {
      val current = milliTokens.get()
      val next = math.min(capacityMilli, current + addMilli)
      if current == next then ()
      else if milliTokens.compareAndSet(current, next) then ()
      else addToBucketCas(addMilli)
    }
  }
}
