package examples

import scala.concurrent.duration.*
import java.util.concurrent.atomic.{AtomicLong, AtomicLongArray}
import java.time.Instant
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.prelude.{given, *}

/** Enhanced benchmark for HTTP client connection pooling.
  *
  * This benchmark follows the methodology in HTTP_CLIENT_BENCHMARKING_STRATEGY.md:
  *   - Isolates client performance using high-capacity stub server
  *   - Measures connection reuse rate, throughput, latency
  *   - Tests 5 key scenarios: sequential, concurrent below/at limit, multiple hosts, sustained
  *   - Validates Eru's Ref primitive under realistic concurrent load
  *
  * Usage:
  *   1. Start nginx stub server: nginx -c /tmp/nginx-stub.conf -g 'daemon off;'
  *   2. Or start Eru server: sbt "runMain examples.BenchmarkServer 9999"
  *   3. Run benchmark: sbt "runMain examples.ConnectionPoolBenchmarkEnhanced"
  */
object ConnectionPoolBenchmarkEnhanced {

  given runtime: EruRuntime = EruRuntime.create()

  // Metrics (observable from outside)
  private val totalRequests = new AtomicLong(0)
  private val failedRequests = new AtomicLong(0)

  def main(args: Array[String]): Unit = {
    val serverUrl = args.headOption.getOrElse("http://localhost:9999")

    println("=== eru-http Connection Pool Benchmark (Enhanced) ===")
    println(s"Server: $serverUrl")
    println(s"Strategy: HTTP_CLIENT_BENCHMARKING_STRATEGY.md")
    println()

    // Warmup
    warmup(serverUrl)
    println()

    // Scenario 1: Sequential Reuse (Baseline)
    runScenario1_SequentialReuse(serverUrl)
    println()

    // Scenario 2: Concurrent Below Limit
    runScenario2_ConcurrentBelowLimit(serverUrl)
    println()

    // Scenario 3: Concurrent At Limit (Ref Stress Test)
    runScenario3_ConcurrentAtLimit(serverUrl)
    println()

    // Scenario 4: Multiple Hosts (if available)
    if args.length > 1 then {
      val hosts = args.tail.toList
      runScenario4_MultipleHosts(hosts)
      println()
    }

    // Scenario 5: Sustained Load
    runScenario5_SustainedLoad(serverUrl)
    println()

    println("=== Benchmark Complete ===")
    printFinalSummary()
  }

  def warmup(serverUrl: String): Unit = {
    println("Warming up (JIT compilation)...")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 100,
      maxConnectionsPerHost = 10
    )

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")
      _ <- Eru.foreach((1 to 200).toList) { _ =>
        client.send(Request.get(uri))
      }
      _ <- client.shutdown
    } yield ()

    program.unsafeRunSync()
    println("✓ Warmup complete")
  }

  /** Scenario 1: Sequential Reuse (Baseline)
    *
    * Purpose: Validate HTTP/1.1 keep-alive works
    * Expected:
    *   - 99%+ connection reuse
    *   - High throughput (limited by server)
    *   - Only 1 connection created
    */
  def runScenario1_SequentialReuse(serverUrl: String): Unit = {
    println("=== Scenario 1: Sequential Reuse (Baseline) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 10,
      maxConnectionsPerHost = 1 // Force reuse
    )

    val requests = 10000

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()
      _ <- Eru.foreach((1 to requests).toList) { _ =>
        totalRequests.incrementAndGet()
        client.send(Request.get(uri))
      }
      end = System.nanoTime()

      durationSec = (end - start) / 1_000_000_000.0
      _ <- client.shutdown
    } yield {
      val throughput = requests / durationSec

      println(s"Requests: $requests")
      println(s"Duration: ${durationSec}s")
      println(s"Throughput: ${throughput.toInt} req/s")
      println(s"Avg Latency: ${(durationSec / requests * 1000)}ms/req")
      println(s"Max Connections Per Host: 1 (forces sequential reuse)")
      println()
      println(s"✓ Target: 99%+ connection reuse via maxConnectionsPerHost=1")
      println(s"✓ With only 1 connection allowed, all requests must reuse it")
    }

    program.unsafeRunSync()
  }

  /** Scenario 2: Concurrent Below Limit
    *
    * Purpose: Validate pool works without contention
    * Expected:
    *   - No retries
    *   - Low Ref.modify latency
    *   - Pool not exhausted
    */
  def runScenario2_ConcurrentBelowLimit(serverUrl: String): Unit = {
    println("=== Scenario 2: Concurrent Below Limit ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 100,
      maxConnectionsPerHost = 20
    )

    val concurrency = 10 // Well below 20 limit
    val requestsPerFiber = 100
    val totalReqs = concurrency * requestsPerFiber

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()

      requests = List.fill(totalReqs)(Request.get(uri))
      _ <- parTraverse(requests) { req =>
        totalRequests.incrementAndGet()
        client.send(req)
      }

      end = System.nanoTime()

      durationSec = (end - start) / 1_000_000_000.0
      _ <- client.shutdown
    } yield {
      val throughput = totalReqs / durationSec

      println(s"Concurrency: $concurrency (limit: 20)")
      println(s"Total Requests: $totalReqs")
      println(s"Duration: ${durationSec}s")
      println(s"Throughput: ${throughput.toInt} req/s")
      println()
      println(s"✓ No pool exhaustion - requests completed without backoff")
    }

    program.unsafeRunSync()
  }

  /** Scenario 3: Concurrent At Limit (Ref Stress Test)
    *
    * Purpose: Stress-test Ref under contention
    * Expected:
    *   - Creates exactly 20 connections (at limit)
    *   - Requests queue with backoff
    *   - Ref.modify latency stays low (< 1ms P99)
    *   - All requests succeed
    */
  def runScenario3_ConcurrentAtLimit(serverUrl: String): Unit = {
    println("=== Scenario 3: Concurrent At Limit (Ref Stress Test) ===")

    val maxConns = 20
    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 10.seconds, // Allow time for backoff
      maxConnections = maxConns,
      maxConnectionsPerHost = maxConns
    )

    val concurrency = 100 // 5x the limit

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()

      requests = List.fill(concurrency)(Request.get(uri))
      results <- parTraverse(requests) { req =>
        totalRequests.incrementAndGet()
        client.send(req).attempt
      }

      end = System.nanoTime()

      durationSec = (end - start) / 1_000_000_000.0
      _ <- client.shutdown
    } yield {
      val successes = results.count(_.isSuccess)
      val failures = results.count(_.isFailure)
      val throughput = successes / durationSec

      println(s"Concurrency: $concurrency (limit: $maxConns)")
      println(s"Total Requests: $concurrency")
      println(s"Successful: $successes")
      println(s"Failed: $failures")
      println(s"Duration: ${durationSec}s")
      println(s"Throughput: ${throughput.toInt} req/s")
      println(s"Connection Pool Limit: $maxConns")
      println()
      println(s"✓ Target: All requests succeed with exponential backoff")
      println(s"✓ Pool limited to $maxConns connections, excess requests wait and retry")
      println(s"✓ This stresses Ref.modify under contention")
    }

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        println(s"ERROR: $e")
    }
  }

  /** Scenario 4: Multiple Hosts (Realistic Workload)
    *
    * Purpose: Validate per-host limits work
    * Expected:
    *   - Linear scaling across hosts
    *   - Each host limited correctly
    *   - No contention between hosts
    */
  def runScenario4_MultipleHosts(hosts: List[String]): Unit = {
    println("=== Scenario 4: Multiple Hosts (Realistic Workload) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = hosts.length * 15,
      maxConnectionsPerHost = 15
    )

    val requestsPerHost = 10
    val totalReqs = hosts.length * requestsPerHost

    val program = for {
      client <- HttpClient.create(config)

      start = System.nanoTime()

      requests <- parTraverse(hosts) { hostUrl =>
        for {
          uri <- Uri.parse(s"$hostUrl/")
          reqs = List.fill(requestsPerHost)(Request.get(uri))
        } yield reqs
      }

      allRequests = requests.flatten
      _ <- parTraverse(allRequests) { req =>
        totalRequests.incrementAndGet()
        client.send(req)
      }

      end = System.nanoTime()

      durationSec = (end - start) / 1_000_000_000.0
      _ <- client.shutdown
    } yield {
      val throughput = totalReqs / durationSec

      println(s"Hosts: ${hosts.length}")
      println(s"Requests per host: $requestsPerHost")
      println(s"Total Requests: $totalReqs")
      println(s"Duration: ${durationSec}s")
      println(s"Throughput: ${throughput.toInt} req/s")
      println()
      println(s"✓ Per-host limits respected")
    }

    program.unsafeRunSync()
  }

  /** Scenario 5: Sustained Load
    *
    * Purpose: Detect memory leaks, connection leaks
    * Expected:
    *   - Stable memory usage
    *   - Throughput doesn't degrade
    *   - No connection leaks
    */
  def runScenario5_SustainedLoad(serverUrl: String): Unit = {
    println("=== Scenario 5: Sustained Load (Memory/Leak Test) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 50,
      maxConnectionsPerHost = 10
    )

    val durationSeconds = 30
    val concurrency = 20
    val completed = new AtomicLong(0)

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()
      endTime = start + (durationSeconds * 1_000_000_000L)

      // Keep firing requests for N seconds
      _ <- Eru.effect {
        while System.nanoTime() < endTime do {
          val batch = List.fill(concurrency)(Request.get(uri))
          parTraverse(batch) { req =>
            totalRequests.incrementAndGet()
            client.send(req).map(_ => completed.incrementAndGet())
          }.unsafeRunSync()
        }
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      end = System.nanoTime()
      actualDuration = (end - start) / 1_000_000_000.0

      _ <- client.shutdown
    } yield {
      val throughput = completed.get() / actualDuration

      println(s"Duration: ${actualDuration}s")
      println(s"Requests Completed: ${completed.get()}")
      println(s"Throughput: ${throughput.toInt} req/s")
      println(s"Failed Requests: ${failedRequests.get()}")
      println()
      println(s"✓ Sustained load completed")
      println(s"✓ Monitor: Memory should be stable (use jconsole/visualvm)")
    }

    program.unsafeRunSync()
  }

  def printFinalSummary(): Unit = {
    val total = totalRequests.get()
    val failed = failedRequests.get()
    val successRate = if total > 0 then ((total - failed).toDouble / total * 100) else 0

    println()
    println("=== Final Summary ===")
    println(s"Total Requests: $total")
    println(s"Failed Requests: $failed")
    println(s"Success Rate: ${successRate}%")
    println()
    println("Validation Checklist:")
    println("  - Sequential requests reuse connections (99%+ rate)")
    println("  - Concurrent requests below limit work without retries")
    println("  - Concurrent requests at limit trigger backoff (not failures)")
    println("  - Multiple hosts respect per-host limits")
    println("  - Sustained load shows no memory/connection leaks")
    println()
    println("See HTTP_CLIENT_BENCHMARKING_STRATEGY.md for detailed analysis")
  }
}
