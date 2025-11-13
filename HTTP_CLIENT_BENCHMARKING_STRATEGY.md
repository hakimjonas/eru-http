# HTTP Client Benchmarking Strategy

**Date**: November 13, 2025
**Context**: Benchmarking eru-http client with Ref-based connection pooling
**Goal**: Validate that Ref doesn't become a bottleneck under concurrent load

---

## Executive Summary

**The Challenge**: Benchmarking HTTP clients is harder than servers because you need a server that won't be the bottleneck. Industry standard: use a **high-performance stub server** that eliminates network/processing delays.

**Recommended Approach**:
1. Use **nginx** as stub server (200k+ req/s capability)
2. Measure client-specific metrics (connection reuse, pool contention)
3. Compare against baseline (no pooling) and other clients
4. Focus on Ref.modify latency under contention

---

## 1. Isolation Strategy

### Problem
Your server does 68-74k req/s. If testing client concurrency, the server becomes the bottleneck before you stress-test Ref.

### Solution: Nginx as Stub Server

**Why nginx?**
- Handles 200k+ req/s easily (3x your server capacity)
- Minimal latency (0.1-0.5ms response time)
- Can serve static responses from memory
- Industry standard for client benchmarking

**Setup**:

```nginx
# /tmp/nginx-stub.conf
worker_processes auto;
error_log /dev/null crit;
pid /tmp/nginx-stub.pid;

events {
    worker_connections 10000;
    use epoll;
}

http {
    access_log off;
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    keepalive_requests 10000;

    server {
        listen 9999 backlog=4096;

        location / {
            return 200 "OK\n";
            add_header Content-Type text/plain;
        }

        location /json {
            return 200 '{"status":"ok","timestamp":1234567890}\n';
            add_header Content-Type application/json;
        }

        location /close {
            return 200 "OK\n";
            add_header Connection close;
        }
    }
}
```

**Run**:
```bash
nginx -c /tmp/nginx-stub.conf -g 'daemon off;'
```

**Verify it's not the bottleneck**:
```bash
# Should see 200k+ req/s
wrk -t12 -c400 -d10s http://localhost:9999/
```

### Alternative: Custom Eru Stub Server

For Eru-specific testing, create minimal server:

```scala
// Minimal server - just echo, no middleware
val handler: RequestHandler = req =>
  Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("OK")))

val server = HttpServer.create(
  HttpServerConfig.default.withPort(9999),
  handler
)
```

Benefits:
- Same technology stack
- Can measure full round-trip Eru effects
- Already validated at 70k+ req/s

Drawbacks:
- Lower ceiling than nginx (70k vs 200k)
- May still become bottleneck under extreme concurrency

**Recommendation**: Use nginx for raw throughput tests, custom server for Eru-to-Eru validation.

---

## 2. Metrics to Measure

### Client-Specific Metrics

#### A. Throughput (Requests/sec)
```scala
// Measure over time window
val start = System.nanoTime()
val completed = new AtomicLong(0)

// Run N concurrent requests
parTraverse(requests) { req =>
  client.send(req).map(_ => completed.incrementAndGet())
}

val duration = (System.nanoTime() - start) / 1_000_000_000.0
val throughput = completed.get() / duration
```

**Target**: Client should achieve close to server capacity when:
- Server = nginx (200k req/s) → Client should hit 150k+
- Server = eru-http (70k req/s) → Client should hit 65k+

**Bottleneck indicators**:
- Client throughput << server capacity → client is bottleneck
- High CPU in client → computational bottleneck
- Time spent in `Ref.modify` → pool contention

#### B. Connection Reuse Rate

```scala
// Add instrumentation to ConnectionPool
private val connectionCreations = new AtomicLong(0)
private val connectionReuses = new AtomicLong(0)

def acquire(...): Eru[HttpError, PooledConnection] = {
  decision match {
    case AcquireDecision.Found(conn) =>
      connectionReuses.incrementAndGet()
      Eru.succeed(conn)
    case AcquireDecision.CreateNew =>
      connectionCreations.incrementAndGet()
      // create...
  }
}

// After benchmark:
val reuseRate = connectionReuses.get().toDouble /
                (connectionCreations.get() + connectionReuses.get())
println(s"Connection reuse rate: ${reuseRate * 100}%")
```

**Target**:
- Sequential requests: 99%+ reuse
- Concurrent requests (below pool limit): 95%+ reuse
- At pool limit: Lower reuse OK (creating at capacity)

#### C. Pool Contention (Time in Ref.modify)

```scala
// Add timing to Ref.modify calls
private def attemptAcquire(...): Eru[HttpError, PooledConnection] = {
  val modifyStart = System.nanoTime()

  decision <- stateRef.modify { state =>
    makeAcquireDecision(state, host, port)
  }

  val modifyDuration = System.nanoTime() - modifyStart
  modifyLatencies.record(modifyDuration)  // Histogram
}
```

**Target**:
- P50: < 10μs (microseconds!)
- P95: < 100μs
- P99: < 1ms

**Red flag**: P99 > 5ms indicates CAS contention

#### D. Latency Distribution

Focus on **client-side latency** (not including server processing):

```scala
val start = System.nanoTime()
val response = client.send(request).unsafeRunSync()
val clientLatency = (System.nanoTime() - start) / 1_000_000.0  // ms
```

**Components**:
- Pool acquisition time
- TCP connection time (if new)
- Request write time
- Response read time
- Pool release time

**Target** (against nginx with 0.5ms response time):
- P50: < 2ms total
- P95: < 5ms total
- P99: < 10ms total

#### E. Backoff Behavior

When pool is exhausted:
```scala
// Instrument retry behavior
private val retryAttempts = new AtomicLongArray(MaxRetries + 1)

if attempt < MaxRetries then {
  retryAttempts.incrementAndGet(attempt)
  retryWithBackoff(host, port, attempt)
}
```

**Analyze**:
- How often do retries happen?
- Distribution of retry counts (most should be 0)
- Exponential backoff effectiveness

---

## 3. Benchmark Scenarios

### Scenario 1: Sequential Reuse (Baseline)

**Purpose**: Validate HTTP/1.1 keep-alive works

```scala
val config = HttpClientConfig.default
val client = HttpClient.create(config).unsafeRunSync()

val start = System.nanoTime()
val n = 10000

(1 to n).foreach { _ =>
  client.send(Request.get(uri)).unsafeRunSync()
}

val duration = (System.nanoTime() - start) / 1_000_000_000.0
val throughput = n / duration

// Should see 99%+ connection reuse
// Should see high throughput (limited by server, not client)
```

**Expected**:
- Throughput: 50k+ req/s (limited by server)
- Reuse rate: 99%+
- New connections: 1 (or small number)

### Scenario 2: Concurrent Below Limit

**Purpose**: Validate pool works correctly without contention

```scala
val config = HttpClientConfig.default
  .withMaxConnections(100)
  .withMaxConnectionsPerHost(20)

val client = HttpClient.create(config).unsafeRunSync()

// 10 concurrent requests (well below 20 limit)
val requests = List.fill(10)(Request.get(uri))

parTraverse(requests) { req =>
  client.send(req)
}.unsafeRunSync()
```

**Expected**:
- Creates 10 connections (one per concurrent request)
- No retries
- No pool exhaustion
- Low Ref.modify latency

### Scenario 3: Concurrent At Limit (Pool Contention)

**Purpose**: Stress-test Ref under contention

```scala
val config = HttpClientConfig.default
  .withMaxConnections(20)
  .withMaxConnectionsPerHost(20)

val client = HttpClient.create(config).unsafeRunSync()

// 100 concurrent requests (5x the limit)
val requests = List.fill(100)(Request.get(uri))

val start = System.nanoTime()

parTraverse(requests) { req =>
  client.send(req)
}.unsafeRunSync()

val duration = (System.nanoTime() - start) / 1_000_000_000.0
```

**Expected**:
- Creates 20 connections (at limit)
- Requests queue up with backoff
- Some retry attempts
- Ref.modify latency stays low (< 1ms P99)
- All requests eventually succeed

**Key metric**: Throughput should approach server capacity:
- If server = 70k req/s, client should achieve 60-65k req/s
- If much lower, pool is bottleneck

### Scenario 4: Multiple Hosts (Realistic Workload)

**Purpose**: Validate per-host limits work

```scala
// Run 3 nginx instances on different ports
val hosts = List("localhost:9999", "localhost:9998", "localhost:9997")

val config = HttpClientConfig.default
  .withMaxConnections(30)
  .withMaxConnectionsPerHost(15)

// 10 requests per host concurrently (30 total)
val requests = hosts.flatMap { host =>
  List.fill(10)(Request.get(Uri.parse(s"http://$host/").unsafeRunSync()))
}

parTraverse(requests) { req =>
  client.send(req)
}.unsafeRunSync()
```

**Expected**:
- 30 connections created (10 per host)
- Each host limited to 15 connections (we're using 10)
- No contention
- Linear scaling across hosts

### Scenario 5: Sustained Load

**Purpose**: Detect memory leaks, connection leaks

```scala
val config = HttpClientConfig.default
val client = HttpClient.create(config).unsafeRunSync()

val duration = 60.seconds  // Run for 1 minute
val concurrency = 50

val start = Instant.now()
val completed = new AtomicLong(0)

// Keep firing requests for 60 seconds
while (Instant.now().isBefore(start.plus(duration))) {
  parTraverse(List.fill(concurrency)(Request.get(uri))) { req =>
    client.send(req).map(_ => completed.incrementAndGet())
  }.unsafeRunSync()
}

val actualDuration = Duration.between(start, Instant.now()).toMillis / 1000.0
val throughput = completed.get() / actualDuration

// Monitor:
// - Memory usage over time (should be stable)
// - Connection count (should stabilize)
// - Throughput (should be consistent)
```

**Expected**:
- Stable memory usage
- Throughput doesn't degrade
- No connection leaks (verify with `lsof`)

---

## 4. Success Criteria

### Performance Targets

| Metric | Target | Red Flag |
|--------|--------|----------|
| **Throughput** | 90%+ of server capacity | < 50% of server capacity |
| **Connection reuse** | 95%+ (sequential) | < 80% |
| **Ref.modify P99** | < 1ms | > 5ms |
| **Client latency P95** | < 5ms (vs nginx) | > 20ms |
| **Pool exhaustion** | Graceful backoff | Failures or hangs |
| **Memory** | Stable over time | Growing heap |

### Validation Checklist

- [ ] Sequential requests reuse connections (99%+ rate)
- [ ] Concurrent requests below limit work without retries
- [ ] Concurrent requests at limit trigger backoff (not failures)
- [ ] Ref.modify latency stays low under contention (< 1ms P99)
- [ ] Client throughput approaches server capacity (90%+)
- [ ] Multiple hosts respect per-host limits
- [ ] Sustained load shows no memory/connection leaks
- [ ] Performance matches or exceeds sttp/http4s baselines

---

## 5. Tools & Commands

### Setup Nginx Stub Server

```bash
# Create config
cat > /tmp/nginx-stub.conf <<'EOF'
worker_processes auto;
error_log /dev/null crit;
pid /tmp/nginx-stub.pid;

events {
    worker_connections 10000;
    use epoll;
}

http {
    access_log off;
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    keepalive_requests 10000;

    server {
        listen 9999 backlog=4096;
        location / { return 200 "OK\n"; }
        location /close {
            return 200 "OK\n";
            add_header Connection close;
        }
    }
}
EOF

# Run nginx
nginx -c /tmp/nginx-stub.conf -g 'daemon off;'

# Verify capacity
wrk -t12 -c400 -d10s http://localhost:9999/
# Should see 200k+ req/s
```

### Create Client Benchmark

```scala
// BenchmarkClient.scala
object BenchmarkClient extends App {
  given runtime: EruRuntime = EruRuntime.create()

  val config = HttpClientConfig.default
    .withMaxConnections(100)
    .withMaxConnectionsPerHost(20)

  val program = for {
    client <- HttpClient.create(config)
    uri <- Uri.parse("http://localhost:9999/")

    // Warmup
    _ <- Eru.foreach(1 to 1000)(client.send(Request.get(uri)))

    // Benchmark
    start = System.nanoTime()
    requests = List.fill(10000)(Request.get(uri))

    responses <- parTraverse(requests)(client.send)

    end = System.nanoTime()
    duration = (end - start) / 1_000_000_000.0
    throughput = responses.length / duration

    _ = println(s"Throughput: ${throughput.toInt} req/s")
    _ = println(s"Latency: ${duration / responses.length * 1000} ms/req")

    _ <- client.shutdown
  } yield ()

  program.unsafeRunSync()
}
```

### Run Benchmark

```bash
# Compile
sbt "client/compile"

# Run
sbt "client/runMain BenchmarkClient"
```

### Monitor Ref Performance

Add instrumentation to ConnectionPool:

```scala
// Add to NativeConnectionPool
private val modifyLatencies = new ConcurrentHashMap[String, LongAdder]()

private def recordModifyLatency(operation: String, nanos: Long): Unit = {
  modifyLatencies
    .computeIfAbsent(operation, _ => new LongAdder())
    .add(nanos)
}

// In attemptAcquire:
val start = System.nanoTime()
decision <- stateRef.modify { state =>
  makeAcquireDecision(state, host, port)
}
recordModifyLatency("acquire", System.nanoTime() - start)

// Report at end:
def printStats(): Unit = {
  modifyLatencies.forEach { (op, total) =>
    println(s"$op: avg ${total.sum() / requestCount}ns")
  }
}
```

### Compare with Baseline (No Pooling)

Create a client that creates a new connection per request:

```scala
// NoPoolClient - for comparison
val noPoolClient = new HttpClient {
  def send(request: Request[Body]): Eru[HttpError, Response[Bytes]] = {
    for {
      socket <- connectNew(host, port)  // New connection each time
      response <- useConnection(socket, request)
      _ <- Eru.effect(socket.close())
    } yield response
  }
}

// Benchmark both:
// With pooling: should be much faster
// Without pooling: limited by TCP handshake time
```

---

## 6. Comparison Baseline

### sttp Client Benchmark

sttp is a popular Scala HTTP client. Compare against it:

```scala
// build.sbt
libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.9.0"

// Benchmark
import sttp.client3.*

val backend = HttpClientSyncBackend()

val start = System.nanoTime()
(1 to 10000).foreach { _ =>
  basicRequest.get(uri"http://localhost:9999/").send(backend)
}
val duration = (System.nanoTime() - start) / 1_000_000_000.0
```

**Expected**: eru-http should be competitive (within 20%) due to:
- Virtual threads efficiency
- Ref-based pooling (vs locks)
- Zero-cost Eru abstractions

### http4s Client Benchmark

http4s also uses connection pooling:

```scala
libraryDependencies += "org.http4s" %% "http4s-ember-client" % "0.23.x"

// Similar benchmark with http4s
```

**Target**: Match or exceed http4s throughput

---

## Summary: Quick Start

**1. Setup nginx stub server** (200k+ req/s capability):
```bash
nginx -c /tmp/nginx-stub.conf -g 'daemon off;'
```

**2. Run client benchmarks**:
```bash
sbt "client/runMain BenchmarkClient"
```

**3. Measure key metrics**:
- Throughput (should approach nginx capacity)
- Ref.modify latency (should be < 1ms P99)
- Connection reuse rate (should be 95%+)

**4. Compare against baseline**:
- No pooling (should be much worse)
- sttp/http4s (should be competitive)

**5. Validate under load**:
- Sequential: 99%+ reuse
- Concurrent below limit: No retries
- Concurrent at limit: Graceful backoff
- Sustained: No leaks

**Success = Client achieves 90%+ of server capacity with sub-millisecond Ref.modify latency**

---

## Next Steps

After completing benchmarks:

1. **Document results** - Add to BENCHMARK_RESULTS.md
2. **Update validation report** - Update ERU_REF_VALIDATION.md with findings
3. **Performance tuning** - If needed, optimize based on metrics
4. **CI integration** - Add performance regression tests

The goal is to prove Ref scales well, not to achieve record numbers. Finding limitations is success!
