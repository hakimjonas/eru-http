package examples

import scala.concurrent.duration.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.prelude.*

/** Benchmark for HTTP client connection pooling.
  *
  * This benchmark validates Eru's Ref primitive under realistic HTTP client load:
  *   - Multiple concurrent requests
  *   - Connection reuse via pooling
  *   - Ref contention under load
  *
  * Usage:
  *   1. Start the server: sbt "runMain examples.BenchmarkServer 8080"
  *   2. Run this benchmark: sbt "runMain examples.ConnectionPoolBenchmark"
  */
object ConnectionPoolBenchmark {

  given runtime: EruRuntime = EruRuntime.create()

  def main(args: Array[String]): Unit = {
    val serverUrl = args.headOption.getOrElse("http://localhost:8080")

    println("=== eru-http Connection Pool Benchmark ===")
    println(s"Server: $serverUrl")
    println()

    // Run benchmarks
    warmup(serverUrl)
    println()

    runSequentialBenchmark(serverUrl)
    println()

    runConcurrentBenchmark(serverUrl, concurrency = 10)
    println()

    runConcurrentBenchmark(serverUrl, concurrency = 50)
    println()

    runConcurrentBenchmark(serverUrl, concurrency = 100)
    println()

    runConcurrentBenchmark(serverUrl, concurrency = 200)
    println()

    runConcurrentBenchmark(serverUrl, concurrency = 1000)
    println()

    // EXTREME TEST: 1 million requests (sequential within fibers)
    runConcurrentBenchmark(serverUrl, concurrency = 1000, requestsPerFiber = 1000)
    println()

    // TRULY CONCURRENT TESTS: All requests fire at once
    println("=== TRULY CONCURRENT TESTS (Maximum Pool Contention) ===")
    println()
    runTrulyConcurrentBenchmark(serverUrl, totalRequests = 100)
    println()
    runTrulyConcurrentBenchmark(serverUrl, totalRequests = 1000)
    println()
    runTrulyConcurrentBenchmark(serverUrl, totalRequests = 10000)
    println()

    // CONSISTENCY TEST: Run the critical test 5 times to check variance
    println("=== CONSISTENCY TEST (5 iterations of 1M requests) ===")
    println()
    (1 to 5).foreach { iteration =>
      print(s"Iteration $iteration/5... ")
      runConcurrentBenchmark(serverUrl, concurrency = 1000, requestsPerFiber = 1000)
      println()
    }

    println("Benchmark complete!")
  }

  def warmup(serverUrl: String): Unit = {
    println("Warming up JIT compiler...")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 200,
      maxConnectionsPerHost = 100
    )

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")
      // Run 5 warmup iterations with different concurrency levels
      _ <- Eru.foreach(List(10, 50, 100, 200, 1000)) { concurrency =>
        for {
          _ <- Eru.effect(print(s"  Warmup iteration (concurrency=$concurrency)... "))
          start <- Eru.effect(System.nanoTime())
          _ <- parTraverse((1 to 1000).toList) { _ =>
            client.send(Request.get(uri)).map(_ => ())
          }
          end <- Eru.effect(System.nanoTime())
          _ <- Eru.effect(println(s"${(end - start) / 1_000_000}ms"))
        } yield ()
      }
      _ <- client.shutdown
    } yield ()

    program.unsafeRunSync()
    println("Warmup complete (JIT should be fully optimized now)")
  }

  def runSequentialBenchmark(serverUrl: String): Unit = {
    println("=== Sequential Requests (Connection Reuse) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 10,
      maxConnectionsPerHost = 1 // Force reuse
    )

    val requests = 1000

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()
      _ <- Eru.foreachDiscard((1 to requests).toList) { _ =>
        client.send(Request.get(uri))
      }
      end = System.nanoTime()

      durationMs = (end - start) / 1_000_000.0
      _ <- client.shutdown
    } yield {
      val reqPerSec = requests / (durationMs / 1000.0)
      val avgLatencyMs = durationMs / requests

      println(s"Requests: $requests")
      println(s"Duration: ${durationMs}ms")
      println(s"Throughput: ${reqPerSec.toInt} req/s")
      println(s"Avg Latency: ${avgLatencyMs}ms")
    }

    program.unsafeRunSync()
  }

  def runConcurrentBenchmark(serverUrl: String, concurrency: Int, requestsPerFiber: Int = 100): Unit = {
    val totalRequests = concurrency * requestsPerFiber
    println(s"=== Concurrent Requests (concurrency=$concurrency, total=$totalRequests) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = concurrency * 2,
      maxConnectionsPerHost = concurrency // Scale with concurrency
    )

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()

      // Launch concurrent fibers, each making multiple requests
      fibers = (1 to concurrency).map { _ =>
        Eru
          .foreachDiscard((1 to requestsPerFiber).toList) { _ =>
            client.send(Request.get(uri))
          }
          .fork
      }.toList

      fiberHandles <- parSequence(fibers)
      _ <- parSequence(fiberHandles.map(_.await))

      end = System.nanoTime()

      durationMs = (end - start) / 1_000_000.0
      _ <- client.shutdown
    } yield {
      val reqPerSec = totalRequests / (durationMs / 1000.0)
      val avgLatencyMs = durationMs / totalRequests

      println(s"Concurrent fibers: $concurrency")
      println(s"Requests per fiber: $requestsPerFiber")
      println(s"Total requests: $totalRequests")
      println(s"Duration: ${durationMs}ms")
      println(s"Throughput: ${reqPerSec.toInt} req/s")
      println(s"Avg Latency: ${avgLatencyMs}ms")
      println(s"Ref contention test: ${concurrency} fibers all using same pool")
    }

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        println(s"ERROR: $e")
    }
  }

  def runTrulyConcurrentBenchmark(serverUrl: String, totalRequests: Int): Unit = {
    println(s"=== Truly Concurrent: $totalRequests requests ALL fire simultaneously ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 200,
      maxConnectionsPerHost = 100
    )

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()

      // Fire ALL requests at once using parTraverse
      _ <- parTraverse((1 to totalRequests).toList) { _ =>
        client.send(Request.get(uri))
      }

      end = System.nanoTime()

      durationMs = (end - start) / 1_000_000.0
      _ <- client.shutdown
    } yield {
      val reqPerSec = totalRequests / (durationMs / 1000.0)
      val avgLatencyMs = durationMs / totalRequests

      println(s"Total requests: $totalRequests (all truly concurrent)")
      println(s"Duration: ${durationMs}ms")
      println(s"Throughput: ${reqPerSec.toInt} req/s")
      println(s"Avg Latency: ${avgLatencyMs}ms")
      println("Pool config: maxConnections=200, maxConnectionsPerHost=100")
      println(s"Contention: ${totalRequests} requests competing for pool simultaneously")
    }

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        println(s"ERROR: $e")
    }
  }
}
