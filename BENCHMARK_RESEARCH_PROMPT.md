# HTTP Client Benchmarking Methodology Research

## Context

We've implemented an HTTP client with connection pooling using Eru's Ref primitive for concurrent state management. We need to properly benchmark the client's performance and validate that Ref doesn't become a bottleneck under load.

## Current State

**What we have:**
- HTTP client with Ref-based connection pool
- 13/13 integration tests passing (real HTTP requests)
- Stress tests showing 136K Ref operations/sec
- No actual client-to-server benchmarks yet

**What we've learned:**
- Pure Ref performance is excellent (sub-millisecond operations)
- Integration tests prove correctness but don't measure throughput
- Need isolated benchmarks that don't conflate client and server performance

## Research Task

**Investigate proper methodology for benchmarking HTTP client performance, specifically:**

1. **Isolation Strategy**
   - How to benchmark client without server becoming the bottleneck?
   - Should we use: nginx, simple Python/Node server, mock server, or something else?
   - Industry standard approaches for client benchmarking?

2. **Metrics to Measure**
   - Requests per second throughput
   - Latency distribution (P50, P95, P99)
   - Connection reuse rate
   - Pool contention indicators
   - How to isolate Ref.modify performance from network I/O?

3. **Realistic Load Patterns**
   - Sequential requests (connection reuse)
   - Concurrent requests (pool contention)
   - Mixed workloads (different hosts)
   - Burst traffic vs sustained load
   - What concurrency levels are realistic for HTTP clients?

4. **Comparison Baseline**
   - What HTTP clients should we compare against? (http4s, sttp, etc.)
   - How do they benchmark their clients?
   - What performance numbers are considered "good" for JVM HTTP clients?

5. **Tools**
   - For server benchmarks we used wrk/bombardier/rewrk as clients
   - For client benchmarks, should we write custom benchmark servers?
   - Are there existing benchmark harnesses for HTTP clients?

## Deliverable

Provide a concise benchmarking strategy document that includes:

1. **Recommended setup** - What server to use, how to isolate client performance
2. **Key metrics** - What to measure and how
3. **Benchmark scenarios** - Specific test cases to run
4. **Success criteria** - What performance numbers indicate success
5. **Tools/commands** - Concrete examples of how to run benchmarks

Focus on **practical, actionable recommendations** rather than exhaustive theory.

## Additional Notes

- We're on Scala 3.7.4, Java 21, using Virtual Threads
- Server benchmarks achieved 170-210K req/sec (validated with bombardier/rewrk)
- Goal is to validate that Ref-based connection pooling scales well, not to achieve record-breaking numbers
- Dogfooding Eru is the priority - we WANT to find limitations!
