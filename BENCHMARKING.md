# eru-http Performance Benchmarking Guide

## Overview

This document describes how to benchmark eru-http's performance and provides baseline metrics. eru-http uses blocking NIO with Virtual Threads and Eru effects, leveraging zero-cost abstractions via Scala 3 inline methods and extension methods.

## Benchmark Tools

### wrk - HTTP Benchmarking Tool

wrk is a modern HTTP benchmarking tool capable of generating significant load when run on a single multi-core CPU.

#### Installation

```bash
# Clone and build from source
cd /tmp
git clone https://github.com/wg/wrk.git
cd wrk
make

# The binary is now available at ./wrk
# Optionally, install system-wide:
sudo cp wrk /usr/local/bin/
```

#### Basic Usage

```bash
# Benchmark with 12 threads, 400 connections, for 30 seconds
wrk -t12 -c400 -d30s http://localhost:8080/

# Output includes:
# - Requests/sec (throughput)
# - Latency distribution (avg, stdev, max, +/- stdev)
# - Transfer rate (data throughput)
```

### Gatling - Detailed Load Testing

Gatling provides detailed performance analysis with interactive HTML reports.

#### Installation

```bash
# Download from https://gatling.io/open-source/
# Or use SDK manager:
sdk install gatling
```

#### Basic Scenario

Create `BenchmarkSimulation.scala`:

```scala
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class EruHttpBenchmark extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("text/plain,application/json")
    .userAgentHeader("Gatling/eru-http-benchmark")

  val scn = scenario("Basic Load Test")
    .exec(http("root").get("/"))
    .pause(100.milliseconds)
    .exec(http("json").get("/json"))
    .pause(100.milliseconds)

  setUp(
    scn.inject(
      rampUsers(100) during (10.seconds),
      constantUsersPerSec(200) during (30.seconds)
    )
  ).protocols(httpProtocol)
}
```

Run:
```bash
gatling:testOnly EruHttpBenchmark
```

## Running Benchmarks

### 1. Start the Benchmark Server

The examples include a minimal server for benchmarking:

```bash
cd eru-http
sbt "runMain examples.BenchmarkServer 8080"
```

This starts a server with three endpoints:
- `GET /` - Minimal "Hello, World!" response
- `GET /plaintext` - Plain text response (same as /)
- `GET /json` - JSON response

### 2. Run wrk Benchmarks

#### Simple Throughput Test

```bash
/tmp/wrk/wrk -t12 -c400 -d30s http://localhost:8080/
```

Expected output format:
```
Running 30s test @ http://localhost:8080/
  12 threads and 400 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     X.XXms   X.XXms  XXX.XXms   XX.XX%
    Req/Sec     X.XXk    X.XXk   XX.XXk    XX.XX%
  XXXXXX requests in 30.00s, XX.XXMiB read
Requests/sec: XXXXX.XX
Transfer/sec: XXX.XXMiB
```

#### Latency-Focused Test

```bash
/tmp/wrk/wrk -t4 -c100 -d30s http://localhost:8080/
```

Lower connection count helps measure pure latency without connection overhead.

#### JSON Endpoint Test

```bash
/tmp/wrk/wrk -t12 -c400 -d30s http://localhost:8080/json
```

Tests serialization overhead.

### 3. Run Gatling Benchmarks

```bash
sbt "gatling:test"
```

Generates HTML reports in `target/gatling/`.

## Baseline Performance Expectations

### Architecture

eru-http uses:
- **Blocking NIO + Virtual Threads**: Each connection on its own lightweight thread (~10KB stack)
- **Eru effects**: Zero-cost effect system with inline transformations
- **Scala 3**: Modern compiler with inline methods, extension methods, opaque types

### Expected Performance Characteristics

Based on NIO + Virtual Threads and Eru's zero-cost abstractions:

#### Throughput
- **Simple responses** (plaintext): 50k-150k req/s (single machine, 12 cores)
- **JSON responses**: 40k-100k req/s
- **With middleware** (logging, CORS, auth): 35k-90k req/s

#### Latency (P99)
- **Low load** (< 100 concurrent): < 5ms
- **Medium load** (100-500 concurrent): 5-15ms
- **High load** (500-1000 concurrent): 15-50ms

#### Resource Usage
- **Memory**: ~50-200MB heap for typical workloads
- **CPU**: Scales linearly with load up to hardware limits
- **Connections**: Virtual Thread-backed connection pooling handles 10k+ concurrent connections

### Comparison with Other Libraries

#### vs. http4s (Cats Effect)
- **eru-http advantage**: Simpler effect model, faster compile times
- **http4s advantage**: Mature ecosystem, streaming

#### vs. sttp
- **eru-http advantage**: Native server support, effect-oriented
- **sttp advantage**: Multiple backend support, comprehensive client features

#### vs. ZIO HTTP
- **Similar architecture**: Both leverage inline/zero-cost abstractions
- **eru-http advantage**: Simpler API, less framework overhead
- **ZIO HTTP advantage**: Full ZIO ecosystem integration

## Interpreting Results

### Key Metrics

1. **Requests/sec** - Overall throughput
   - Target: > 50k req/s for simple responses on modern hardware
   - Lower is acceptable if doing heavy computation per request

2. **Latency (P50, P99, P999)** - Response time distribution
   - P50 < 5ms: Excellent
   - P99 < 20ms: Good
   - P999 < 100ms: Acceptable for most use cases

3. **Error Rate** - Failed requests / total requests
   - Target: < 0.01% under normal load
   - Monitor 500 errors (server issues) vs 4xx (client issues)

4. **Resource Usage**
   - Memory should be stable (no leaks)
   - CPU should scale linearly with load
   - GC pauses should be minimal (< 10ms)

### Red Flags

- **Increasing latency over time**: Memory leak or GC issues
- **High error rates**: Server overload or bugs
- **Non-linear CPU scaling**: Contention or synchronization bottlenecks
- **Connection failures**: File descriptor limits or socket exhaustion

## Optimization Tips

### Server Configuration

```scala
// High-throughput configuration
val config = HttpServerConfig.highThroughput
  .withPort(8080)
  .withBacklog(1024) // Connection queue size

// Low-latency configuration
val config = HttpServerConfig.default
  .withPort(8080)
  .withBacklog(128) // Smaller queue, faster accept
```

### Middleware Chain Optimization

Middleware is applied using Scala 3 `inline` methods, resulting in zero runtime overhead:

```scala
// ✅ Efficient: Inline composition
val app = Middleware
  .logging(println)
  .andThen(Middleware.cors())
  .andThen(Middleware.requestId())
  .apply(handler)

// ❌ Avoid: Unnecessary allocations in handlers
val app = handler.flatMap { resp =>
  // Don't create large objects per request
}
```

### JVM Tuning

```bash
# Recommended JVM flags for benchmarking (ZGC generational only)
java \
  -Xms2g -Xmx2g \
  -XX:+UseZGC \
  -XX:-CreateCoredumpOnCrash \
  -server \
  -jar benchmark-server.jar
```

### OS Tuning

For extreme performance, tune OS-level socket options:

```bash
# Increase file descriptor limits
ulimit -n 65536

# Tune TCP settings (Linux)
sysctl -w net.core.somaxconn=65535
sysctl -w net.ipv4.tcp_max_syn_backlog=65535
sysctl -w net.ipv4.tcp_tw_reuse=1
```

## TechEmpower Benchmarks

For public comparison with other frameworks, see the TechEmpower Web Framework Benchmarks:
- https://www.techempower.com/benchmarks/

eru-http is designed to perform competitively in:
- **Plaintext**: Minimal overhead test
- **JSON Serialization**: Structured data responses
- **Fortunes** (if ORM support added): Template rendering

## Continuous Performance Testing

### Pre-release Validation

Before 1.0.0 release:
1. ✅ Run wrk for baseline metrics
2. ✅ Run Gatling for detailed analysis
3. ⏳ Compare with previous version (if applicable)
4. ⏳ Verify no regressions

### Post-release Monitoring

After deployment:
1. Monitor production metrics (Prometheus/Grafana)
2. Compare production vs. benchmark results
3. Identify optimization opportunities
4. Track performance across versions

## Resources

- wrk: https://github.com/wg/wrk
- Gatling: https://gatling.io/
- TechEmpower: https://www.techempower.com/benchmarks/
- Eru Documentation: https://github.com/ghoula/eru

## Contributing Benchmarks

To contribute benchmark results:

1. Specify your hardware (CPU, RAM, OS)
2. Include wrk/Gatling configurations
3. Provide raw output
4. Note any JVM flags or configurations
5. Submit as GitHub issue with `benchmark` label

Example:
```
Hardware: AMD Ryzen 9 5950X (16 cores), 32GB RAM
JVM: OpenJDK 25, -Xms2g -Xmx2g -XX:+UseZGC
wrk: -t16 -c1000 -d60s
Results: 87,543 req/s, P99 latency 18ms
```
