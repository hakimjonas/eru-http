# Running Connection Pool Benchmarks

**Date**: November 13, 2025
**Context**: Benchmarking eru-http client with Ref-based connection pooling
**Strategy**: See `HTTP_CLIENT_BENCHMARKING_STRATEGY.md`

---

## Quick Start

### Option 1: Use nginx Stub Server (Recommended for High Throughput)

nginx provides 200k+ req/s capacity, ensuring the server isn't the bottleneck.

**1. Start nginx stub server:**

```bash
# nginx config already created at /tmp/nginx-stub.conf
nginx -c /tmp/nginx-stub.conf -g 'daemon off;'
```

**2. Verify nginx capacity (in another terminal):**

```bash
# Should see 150k-200k+ req/s
wrk -t12 -c400 -d10s http://localhost:9999/
```

**3. Run enhanced benchmark:**

```bash
sbt "examples/runMain examples.ConnectionPoolBenchmarkEnhanced http://localhost:9999"
```

### Option 2: Use Eru Benchmark Server

The Eru benchmark server achieves ~70k req/s, which is good for Eru-to-Eru validation but may become a bottleneck under extreme load.

**1. Start Eru server:**

```bash
sbt "examples/runMain examples.BenchmarkServer 9999"
```

**2. Run enhanced benchmark (in another terminal):**

```bash
sbt "examples/runMain examples.ConnectionPoolBenchmarkEnhanced http://localhost:9999"
```

---

## Benchmark Scenarios

The enhanced benchmark runs 5 scenarios based on the benchmarking strategy:

### Scenario 1: Sequential Reuse (Baseline)
- **Purpose**: Validate HTTP/1.1 keep-alive works
- **Config**: maxConnectionsPerHost=1 (forces reuse)
- **Expected**: High throughput, single connection used

### Scenario 2: Concurrent Below Limit
- **Purpose**: Validate pool works without contention
- **Config**: 10 concurrent requests, limit=20
- **Expected**: No retries, low latency

### Scenario 3: Concurrent At Limit (Ref Stress Test)
- **Purpose**: Stress-test Ref under contention
- **Config**: 100 concurrent requests, limit=20 (5x over)
- **Expected**: Exponential backoff, all requests succeed, Ref handles contention

### Scenario 4: Multiple Hosts
- **Purpose**: Validate per-host limits work
- **Setup**: Start multiple nginx instances on different ports
- **Expected**: Linear scaling, per-host isolation

### Scenario 5: Sustained Load
- **Purpose**: Detect memory/connection leaks
- **Config**: 30 seconds of continuous load
- **Expected**: Stable memory, consistent throughput

---

## Running Individual Benchmarks

### Original Simple Benchmark

```bash
sbt "examples/runMain examples.ConnectionPoolBenchmark http://localhost:9999"
```

This runs a simpler set of benchmarks with varying concurrency levels.

### With Instrumented Metrics

For detailed connection pool metrics (created, reused, released), you can modify `HttpClient.create()` to use `InstrumentedConnectionPool`:

```scala
import examples.{InstrumentedConnectionPool, PoolMetrics}

val metrics = new PoolMetrics()

// After creating client:
val client = HttpClient.create(config).unsafeRunSync()
// Would need to inject instrumented pool here
// (requires HttpClient modification to accept pool)

// After benchmark:
metrics.printSummary()
```

*Note: Full instrumentation requires HttpClient to accept an external ConnectionPool*

---

## Expected Results

### Success Criteria (from strategy document)

| Metric | Target | Red Flag |
|--------|--------|----------|
| **Throughput** | 90%+ of server capacity | < 50% of server capacity |
| **Connection reuse** | 95%+ (sequential) | < 80% |
| **Ref.modify P99** | < 1ms | > 5ms |
| **Client latency P95** | < 5ms (vs nginx) | > 20ms |
| **Pool exhaustion** | Graceful backoff | Failures or hangs |
| **Memory** | Stable over time | Growing heap |

### Example Output

**Scenario 1: Sequential Reuse**
```
=== Scenario 1: Sequential Reuse (Baseline) ===
Requests: 10000
Duration: 0.5s
Throughput: 20000 req/s
Avg Latency: 0.05ms/req
Max Connections Per Host: 1 (forces sequential reuse)

✓ Target: 99%+ connection reuse via maxConnectionsPerHost=1
✓ With only 1 connection allowed, all requests must reuse it
```

**Scenario 3: Concurrent At Limit**
```
=== Scenario 3: Concurrent At Limit (Ref Stress Test) ===
Concurrency: 100 (limit: 20)
Total Requests: 100
Successful: 100
Failed: 0
Duration: 0.15s
Throughput: 666 req/s
Connection Pool Limit: 20

✓ Target: All requests succeed with exponential backoff
✓ Pool limited to 20 connections, excess requests wait and retry
✓ This stresses Ref.modify under contention
```

---

## Multiple Hosts Setup

To test multiple hosts (Scenario 4):

**1. Start 3 nginx instances:**

```bash
# Terminal 1
nginx -c /tmp/nginx-stub.conf -g 'daemon off;'

# Terminal 2 (edit config to use port 9998)
# Edit /tmp/nginx-stub-2.conf, change listen to 9998
nginx -c /tmp/nginx-stub-2.conf -g 'daemon off;'

# Terminal 3 (edit config to use port 9997)
# Edit /tmp/nginx-stub-3.conf, change listen to 9997
nginx -c /tmp/nginx-stub-3.conf -g 'daemon off;'
```

**2. Run benchmark with multiple hosts:**

```bash
sbt "examples/runMain examples.ConnectionPoolBenchmarkEnhanced \
  http://localhost:9999 \
  http://localhost:9998 \
  http://localhost:9997"
```

---

## Monitoring

### Memory Usage

Monitor for memory leaks during Scenario 5 (sustained load):

```bash
# Option 1: jconsole
jconsole

# Option 2: VisualVM
jvisualvm

# Option 3: Command line
jps # Get PID
jstat -gcutil <PID> 1000  # Every 1 second
```

**What to look for:**
- Heap usage should stabilize after warmup
- Old Gen should not continuously grow
- GC frequency should be reasonable

### Connection Count

Monitor actual TCP connections:

```bash
# While benchmark is running
watch -n 1 'lsof -i :9999 | grep ESTABLISHED | wc -l'
```

**What to look for:**
- Connection count should match pool limits
- Connections should not leak (count grows unbounded)
- After benchmark: connections should be closed (count returns to 0)

---

## Troubleshooting

### Server Becomes Bottleneck

**Symptom**: Client throughput much lower than expected
**Solution**: Use nginx stub server instead of Eru server

### Too Many Open Files

**Symptom**: "Too many open files" error
**Solution**: Increase ulimit

```bash
ulimit -n 10000
```

### Port Already in Use

**Symptom**: nginx fails to start
**Solution**: Kill existing process

```bash
lsof -ti :9999 | xargs kill -9
```

### JVM Running Out of Memory

**Symptom**: OutOfMemoryError
**Solution**: Increase heap size

```bash
export SBT_OPTS="-Xmx4G -Xms4G"
sbt "examples/runMain examples.ConnectionPoolBenchmarkEnhanced"
```

---

## Comparing with Other Clients

### sttp Client

```scala
// Add to build.sbt
libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.9.0"

// Benchmark
import sttp.client3.*

val backend = HttpClientSyncBackend()
val start = System.nanoTime()
(1 to 10000).foreach { _ =>
  basicRequest.get(uri"http://localhost:9999/").send(backend)
}
val duration = (System.nanoTime() - start) / 1_000_000_000.0
println(s"sttp throughput: ${10000 / duration} req/s")
```

### http4s Client

```scala
// Add to build.sbt
libraryDependencies += "org.http4s" %% "http4s-ember-client" % "0.23.x"

// Similar benchmark with http4s
```

**Expected**: eru-http should be competitive (within 20%) due to:
- Virtual threads efficiency
- Ref-based pooling (vs locks)
- Zero-cost Eru abstractions

---

## Validation Checklist

After running all benchmarks, verify:

- [ ] Sequential requests reuse connections (inferred from high throughput)
- [ ] Concurrent requests below limit work without retries
- [ ] Concurrent requests at limit succeed with backoff (no failures)
- [ ] Multiple hosts respect per-host limits
- [ ] Sustained load shows no memory/connection leaks
- [ ] Client throughput approaches server capacity (90%+)
- [ ] All tests from HttpClientPoolingSpec pass

---

## Next Steps

1. **Run all benchmarks**: Follow quick start guide
2. **Document results**: Record throughput, latency, success rates
3. **Compare baselines**: If available, compare with sttp/http4s
4. **Update validation report**: Add findings to `ERU_REF_VALIDATION.md`
5. **Tune if needed**: Adjust pool sizes, timeouts based on results
6. **CI integration**: Consider adding performance regression tests

---

## See Also

- `HTTP_CLIENT_BENCHMARKING_STRATEGY.md` - Detailed methodology and rationale
- `CONNECTION_POOL_DESIGN.md` - Architecture and design decisions
- `ERU_REF_VALIDATION.md` - Eru Ref validation findings
- `examples/ConnectionPoolBenchmark.scala` - Original simple benchmark
- `examples/ConnectionPoolBenchmarkEnhanced.scala` - Enhanced 5-scenario benchmark
- `examples/InstrumentedConnectionPool.scala` - Metrics collection wrapper
