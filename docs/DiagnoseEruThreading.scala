package examples

import net.ghoula.eru.*

/** Diagnostic to understand Eru's threading model in detail.
  *
  * This will help us understand:
  * 1. Does each unsafeRunSync() run on a new virtual thread?
  * 2. Does Eru reuse threads from a pool?
  * 3. What happens with nested effects?
  * 4. How does suspend() interact with threading?
  */
object DiagnoseEruThreading {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    println("=== Diagnosing Eru Threading Model ===\n")

    test1_BasicEffectThreading()
    test2_NestedEffects()
    test3_SuspendThreading()
    test4_ConcurrentEffects()
    test5_BlockingBehavior()
  }

  /** Test 1: What thread does a basic effect run on? */
  def test1_BasicEffectThreading(): Unit = {
    println("\n--- Test 1: Basic Effect Threading ---")

    val effect = Eru.effect {
      val thread = Thread.currentThread()
      ThreadInfo(thread.getName, thread.isVirtual, thread.getClass.getSimpleName)
    }

    println("Main thread: " + threadInfo())
    val info = effect.unsafeRunSync()
    println(s"Effect ran on: ${info.name}")
    println(s"  Is virtual: ${info.isVirtual}")
    println(s"  Class: ${info.className}")

    // Run it again to see if we get a different thread
    val info2 = effect.unsafeRunSync()
    println(s"Second run: ${info2.name}")
    println(s"  Same thread? ${info.name == info2.name}")
  }

  /** Test 2: How do nested effects work? */
  def test2_NestedEffects(): Unit = {
    println("\n--- Test 2: Nested Effects ---")

    val nested = for {
      outer <- Eru.effect {
        println(s"Outer effect on: ${threadInfo()}")
        "outer"
      }
      inner <- Eru.effect {
        println(s"Inner effect on: ${threadInfo()}")
        "inner"
      }
      combined <- Eru.effect {
        println(s"Combined effect on: ${threadInfo()}")
        s"$outer-$inner"
      }
    } yield combined

    val result = nested.unsafeRunSync()
    println(s"Result: $result")
  }

  /** Test 3: How does suspend() work with threads? */
  def test3_SuspendThreading(): Unit = {
    println("\n--- Test 3: Suspend Threading ---")

    val suspended = EruRuntime.shared.suspend[String, String] { callback =>
      Eru.effectTotal {
        println(s"Inside suspend callback on: ${threadInfo()}")

        // Simulate async operation
        val thread = new Thread(() => {
          println(s"Async operation on: ${threadInfo()}")
          Thread.sleep(100)
          callback(Right("async result"))
        })
        thread.start()
      }
    }

    println(s"Main thread before suspend: ${threadInfo()}")
    val result = suspended.unsafeRunSync()
    println(s"Result: $result")
    println(s"Main thread after suspend: ${threadInfo()}")
  }

  /** Test 4: Can we run many effects concurrently? */
  def test4_ConcurrentEffects(): Unit = {
    println("\n--- Test 4: Concurrent Effects ---")

    println("Launching 100 effects that sleep for 100ms...")
    val start = System.currentTimeMillis()

    val effects = (1 to 100).map { i =>
      Eru.effect {
        val thread = threadInfo()
        Thread.sleep(100)
        (i, thread)
      }
    }

    // Run them (if Eru has parallel execution, this should be fast)
    // For now, run a few sequentially
    val results = effects.take(5).map(_.unsafeRunSync())

    val duration = System.currentTimeMillis() - start
    println(s"Completed ${results.size} effects in ${duration}ms")

    results.foreach { case (i, thread) =>
      println(s"  Effect $i ran on: $thread")
    }

    // Check thread uniqueness
    val uniqueThreads = results.map(_._2).distinct.size
    println(s"Unique threads used: $uniqueThreads")
  }

  /** Test 5: What happens when we block? */
  def test5_BlockingBehavior(): Unit = {
    println("\n--- Test 5: Blocking Behavior ---")

    val initialCount = Thread.activeCount()
    println(s"Initial thread count: $initialCount")

    // Create effects that block
    val blocking = (1 to 10).map { i =>
      Eru.effect {
        val thread = threadInfo()
        Thread.sleep(1000) // Simulate blocking I/O
        (i, thread)
      }
    }

    // Run them
    val results = blocking.take(3).map(_.unsafeRunSync())

    val finalCount = Thread.activeCount()
    println(s"Final thread count: $finalCount")
    println(s"Threads created: ${finalCount - initialCount}")

    results.foreach { case (i, thread) =>
      println(s"  Blocking effect $i on: $thread")
    }
  }

  /** Helper to get thread info */
  def threadInfo(): String = {
    val thread = Thread.currentThread()
    s"${thread.getName} (virtual=${thread.isVirtual})"
  }

  case class ThreadInfo(name: String, isVirtual: Boolean, className: String)
}

/** Instructions for interpreting results:
  *
  * SCENARIO A: Eru uses virtual threads for each effect
  * - Test 1: Each unsafeRunSync() creates new virtual thread
  * - Test 2: Each effect in chain runs on different virtual threads
  * - Test 3: Suspend callback runs on virtual thread
  * - Test 4: Multiple unique virtual threads used
  * - Test 5: Minimal platform thread creation
  *
  * SCENARIO B: Eru uses a platform thread pool
  * - Test 1: Effects run on thread pool workers
  * - Test 2: Effects may reuse same platform thread
  * - Test 3: Callback runs on platform thread
  * - Test 4: Thread reuse from pool
  * - Test 5: Many platform threads created
  *
  * SCENARIO C: Eru runs effects on calling thread
  * - Test 1: All effects run on main thread
  * - Test 2: All nested effects on main thread
  * - Test 3: Suspend creates separate thread
  * - Test 4: All on same thread (sequential)
  * - Test 5: Only platform threads created manually
  */
