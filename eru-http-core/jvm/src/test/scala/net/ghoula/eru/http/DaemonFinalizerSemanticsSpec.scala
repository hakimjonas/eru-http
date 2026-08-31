package net.ghoula.eru.http

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Empirical verification of Eru's finalizer-drain semantics.
  *
  * This test codifies our understanding of when `.ensure` finalizers actually execute. It is NOT
  * testing eru-http — it is a hedge against regressions in Eru that would silently break the
  * cleanup patterns used throughout NativeHttpServer / ConnectionPool.
  *
  * Findings (verified empirically against Eru's source + runtime):
  *   - Awaited fibers: finalizers DO run via Await's drainFinalizers (Eru.scala:1638).
  *   - Daemon fibers on VirtualThreads backend: finalizers run on the fiber's OWN thread right
  *     after the body completes (RuntimeBackend.scala:225-230). Awaited or not.
  *   - Interrupted daemon fibers (plain Eru.effect): finalizers still run — Effect case catches
  *     InterruptedException and rethrows as InterruptedWithFinalizers which preserves the finalizer
  *     list (Eru.scala:1704-1713).
  *   - Interrupted daemon fibers (Eru.interruptibleBlocking): same — evalInterruptible preserves
  *     finalizers via InterruptedWithFinalizers (Eru.scala:1347-1359).
  *   - **Long sequential for-comprehension (NOT forked)**: finalizers from each step accumulate and
  *     drain only at the outer unsafeRunSync boundary. This is the scope-local-release gotcha —
  *     `.ensure` is WRONG here; use inline release.
  *
  * Implication for eru-http:
  *   - Daemon-fiber cleanup (handleClient, per-request handler): `.ensure` is correct.
  *   - Scope-local release inside a long inline chain (ConnectionPool.remove): use inline
  *     `.attempt.flatMap` release so permits free up between iterations.
  */
class DaemonFinalizerSemanticsSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  test("awaited fork: ensure finalizer runs on normal completion") {
    val ran = new AtomicBoolean(false)
    val prog = for {
      fiber <- runtime.fork {
        Eru.succeed("x").ensure(Eru.effect { ran.set(true); () })
      }
      _ <- fiber.await
    } yield ()
    prog.unsafeRunSync()
    assert(ran.get(), "awaited fiber's ensure finalizer must run")
  }

  test("awaited forkDaemon: ensure finalizer runs on normal completion") {
    val ran = new AtomicBoolean(false)
    val prog = for {
      fiber <- runtime.forkDaemon {
        Eru.succeed("x").ensure(Eru.effect { ran.set(true); () })
      }
      _ <- fiber.await
    } yield ()
    prog.unsafeRunSync()
    assert(ran.get(), "awaited daemon fiber's ensure finalizer must run")
  }

  test("UNAWAITED forkDaemon: ensure finalizer runs when the daemon body completes") {
    val ran = new AtomicBoolean(false)
    val prog = for {
      _ <- runtime.forkDaemon {
        Eru.effect { Thread.sleep(20); "done" }
          .ensure(Eru.effect { ran.set(true); () })
      }
      _ <- runtime.sleep(Duration.ofMillis(300))
    } yield ()
    prog.unsafeRunSync()

    assert(ran.get(), "daemon finalizer must run after fiber body completes")
  }

  test("UNAWAITED forkDaemon: finalizer runs during daemon completion, visible to parent") {
    val daemonBodyCompleted = new AtomicBoolean(false)
    val finalizerRan = new AtomicBoolean(false)
    val observedInsideParent = new AtomicBoolean(false)

    val prog = for {
      _ <- runtime.forkDaemon {
        Eru.effect { Thread.sleep(20); daemonBodyCompleted.set(true); "done" }
          .ensure(Eru.effect { finalizerRan.set(true); () })
      }
      _ <- runtime.sleep(Duration.ofMillis(200))
      _ <- Eru.effect {
        observedInsideParent.set(finalizerRan.get())
      }
    } yield ()
    prog.unsafeRunSync()

    assert(daemonBodyCompleted.get(), "daemon body should have completed")
    assert(
      observedInsideParent.get(),
      "finalizer must have run by the time parent observes — runs on fiber thread after body"
    )
  }

  test("INTERRUPTED daemon body with Eru.effect: ensure finalizer DOES run") {
    val ran = new AtomicBoolean(false)
    val prog = for {
      fiber <- runtime.forkDaemon {
        Eru.effect { Thread.sleep(500); "done" }
          .ensure(Eru.effect { ran.set(true); () })
      }
      _ <- runtime.sleep(Duration.ofMillis(20))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- runtime.sleep(Duration.ofMillis(100))
    } yield ()
    prog.unsafeRunSync()

    assert(ran.get(), "ensure finalizer runs even when daemon fiber is interrupted via Eru.effect")
  }

  test("INTERRUPTED daemon body with interruptibleBlocking: finalizer DOES run") {
    val ran = new AtomicBoolean(false)
    val prog = for {
      fiber <- runtime.forkDaemon {
        Eru.interruptibleBlocking { Thread.sleep(500); "done" }
          .ensure(Eru.effect { ran.set(true); () })
      }
      _ <- runtime.sleep(Duration.ofMillis(20))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- runtime.sleep(Duration.ofMillis(100))
    } yield ()
    prog.unsafeRunSync()

    assert(
      ran.get(),
      "interruptibleBlocking converts InterruptedException to Eru.Interrupt, preserving finalizers"
    )
  }

  test("ensure in LONG sequential chain (NOT forked): finalizer defers to program end") {
    val releasedDuringChain = new java.util.concurrent.atomic.AtomicInteger(0)
    val sem = Semaphore.make(2L).unsafeRunSync()

    val doOne: Eru[Nothing, Unit] = {
      val work = Eru.effect { Thread.sleep(5); () }.attempt.map(_ => ())
      work.ensure(sem.release.eru.attempt.map { _ =>
        releasedDuringChain.incrementAndGet(): Unit
      })
    }

    val chain = for {
      _ <- sem.acquire.eru.flatMap(_ => doOne)
      _ <- sem.acquire.eru.flatMap(_ => doOne)
      _ <- sem.acquire.eru.flatMap(_ => doOne)
    } yield ()

    val exit = chain.timeout(java.time.Duration.ofSeconds(2)).attempt.unsafeRunSync()

    exit match {
      case Result.Failure(_) =>
        ()
      case Result.Success(_) =>
        fail(
          "chain unexpectedly completed; ensure is draining mid-chain. " +
            s"Released during chain: ${releasedDuringChain.get()}"
        )
    }
  }

  test("inline .attempt.flatMap in daemon: cleanup DOES run") {
    val ran = new AtomicBoolean(false)
    val prog = for {
      _ <- runtime.forkDaemon {
        Eru.effect { Thread.sleep(20); "done" }.attempt.flatMap { _ =>
          Eru.effect { ran.set(true); () }.attempt.map(_ => ())
        }
      }
      _ <- runtime.sleep(Duration.ofMillis(300))
    } yield ()
    prog.unsafeRunSync()

    assert(ran.get(), "inline cleanup in daemon fiber must run")
  }
}
