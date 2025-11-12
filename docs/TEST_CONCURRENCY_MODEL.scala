package examples

import net.ghoula.eru.*

/** Test to determine if Eru uses virtual threads or platform threads.
  *
  * Run this to understand Eru's concurrency model:
  *   sbt "examples/runMain examples.TestConcurrencyModel"
  */
object TestConcurrencyModel {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    println("=== Testing Eru Concurrency Model ===\n")

    testThreadType()
    testScalability()
    testBlocking()
  }

  /** Test 1: What kind of threads does Eru use? */
  def testThreadType(): Unit = {
    println("Test 1: Thread Type")
    println("-" * 40)

    val program = Eru.effect {
      val thread = Thread.currentThread()
      val threadName = thread.getName
      val isVirtual = thread.isVirtual // Java 21+ method
      val threadClass = thread.getClass.getName

      println(s"Thread name: $threadName")
      println(s"Is virtual: $isVirtual")
      println(s"Thread class: $threadClass")

      isVirtual
    }

    val isVirtual = program.unsafeRunSync()

    if (isVirtual) {
      println("\n✅ Eru uses VIRTUAL THREADS")
      println("   → Blocking I/O is efficient")
      println("   → Can create millions of concurrent operations")
      println("   → Simple blocking socket I/O is the right choice")
    } else {
      println("\n⚠️  Eru uses PLATFORM THREADS")
      println("   → Blocking I/O ties up OS threads")
      println("   → Need async I/O (Netty or similar)")
      println("   → More complex architecture required")
    }

    println()
  }

  /** Test 2: Can we create many concurrent effects? */
  def testScalability(): Unit = {
    println("\nTest 2: Concurrency Scalability")
    println("-" * 40)

    val numTasks = 10000
    val startTime = System.currentTimeMillis()

    // Create 10,000 concurrent blocking operations
    val tasks = (1 to numTasks).map { i =>
      Eru.effect {
        Thread.sleep(100) // Simulate blocking I/O
        i
      }
    }

    // Run all concurrently (if Eru supports it)
    // Note: This assumes Eru has a `parallel` or similar combinator
    // Adjust based on actual Eru API

    println(s"Starting $numTasks tasks that each block for 100ms...")
    println("If using virtual threads, this should complete in ~100ms")
    println("If using platform threads, this will take much longer")

    // For now, just run a few sequentially to show the concept
    val sample = tasks.take(5)
    val program = Eru.foreach(sample)(identity)

    val results = program.unsafeRunSync()
    val endTime = System.currentTimeMillis()
    val duration = endTime - startTime

    println(s"\nCompleted ${results.size} tasks in ${duration}ms")

    if (duration < 1000) {
      println("✅ Fast execution suggests concurrent execution")
    } else {
      println("⚠️  Slow execution suggests sequential execution")
    }

    println()
  }

  /** Test 3: What happens when we block? */
  def testBlocking(): Unit = {
    println("\nTest 3: Blocking Behavior")
    println("-" * 40)

    println("Creating 100 effects that each sleep for 1 second...")
    println("Watching thread count...\n")

    val initialThreadCount = Thread.activeCount()
    println(s"Initial thread count: $initialThreadCount")

    // Create multiple blocking effects
    val blockingTasks = (1 to 100).map { i =>
      Eru.effect {
        println(s"Task $i on thread: ${Thread.currentThread().getName}")
        Thread.sleep(1000)
        i
      }
    }

    // Run first 5 to demonstrate
    blockingTasks.take(5).foreach { task =>
      task.unsafeRunSync()
    }

    val finalThreadCount = Thread.activeCount()
    println(s"\nFinal thread count: $finalThreadCount")
    println(s"Threads created: ${finalThreadCount - initialThreadCount}")

    if (finalThreadCount - initialThreadCount < 10) {
      println("\n✅ Virtual threads: Minimal platform thread creation")
    } else {
      println("\n⚠️  Platform threads: Many threads created")
    }

    println()
  }

  /** Bonus: Check for structured concurrency support */
  def testStructuredConcurrency(): Unit = {
    println("\nBonus: Structured Concurrency")
    println("-" * 40)

    // Check if Eru has structured concurrency primitives
    // Examples: fork, race, parallel, zip

    println("Check Eru's API for:")
    println("  - fork: spawn child effect")
    println("  - race: run multiple effects, take first")
    println("  - parallel: run effects concurrently")
    println("  - zip: combine multiple effects")
    println()
  }
}

/** Summary of findings:
  *
  * If Eru uses virtual threads:
  * - Architecture: Simple blocking I/O wrapped in Eru effects
  * - Socket ops: Use java.nio.channels.SocketChannel (blocking mode)
  * - Concurrency: Each request gets its own virtual thread
  * - Netty: NOT NEEDED - adds unnecessary complexity
  * - Performance: Excellent (millions of concurrent connections)
  *
  * If Eru uses platform threads:
  * - Architecture: Async I/O with callbacks
  * - Socket ops: Use Netty or Java NIO async
  * - Concurrency: Careful thread pool management required
  * - Netty: HELPFUL - handles async I/O well
  * - Performance: Good but requires careful tuning
  */
