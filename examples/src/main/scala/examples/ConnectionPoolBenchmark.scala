package examples

import scala.concurrent.duration.*
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.prelude.{given, *}

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

    println("Benchmark complete!")
  }

  def warmup(serverUrl: String): Unit = {
    println("Warming up...")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 100,
      maxConnectionsPerHost = 10
    )

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")
      _ <- Eru.foreach((1 to 100).toList) { _ =>
        client.send(Request.get(uri))
      }
      _ <- client.shutdown
    } yield ()

    program.unsafeRunSync()
    println("Warmup complete")
  }

  def runSequentialBenchmark(serverUrl: String): Unit = {
    println("=== Sequential Requests (Connection Reuse) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = 10,
      maxConnectionsPerHost = 1  // Force reuse
    )

    val requests = 1000

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()
      _ <- Eru.foreach((1 to requests).toList) { _ =>
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

  def runConcurrentBenchmark(serverUrl: String, concurrency: Int): Unit = {
    println(s"=== Concurrent Requests (concurrency=$concurrency) ===")

    val config = HttpClientConfig(
      connectTimeout = 5.seconds,
      requestTimeout = 5.seconds,
      maxConnections = concurrency,
      maxConnectionsPerHost = 10
    )

    val requestsPerFiber = 100
    val totalRequests = concurrency * requestsPerFiber

    val program = for {
      client <- HttpClient.create(config)
      uri <- Uri.parse(s"$serverUrl/")

      start = System.nanoTime()

      // Launch concurrent fibers, each making multiple requests
      fibers = (1 to concurrency).map { _ =>
        Eru.foreach((1 to requestsPerFiber).toList) { _ =>
          client.send(Request.get(uri))
        }.fork
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
}
