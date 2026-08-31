package net.ghoula.eru.http.server.hostile

import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.{HostileTestBase, ResourceSnapshot}
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Concurrent shutdown resource cleanup.
  *
  * Validates that `HttpServer.shutdown` releases every resource it allocated: file descriptors,
  * semaphore permits, direct-buffer pool slots, and VTs. This matters especially for long-running
  * services that redeploy frequently — leaks here compound on every restart.
  *
  * Shutdown is synchronous: every handler fiber's `.ensure(cleanup)` has run before `scoped(...)`
  * returns, and every handler fiber's socket is closed (by either the active-clients sweep or the
  * fiber's own cleanup) before shutdown returns, so every client socket observes EOF.
  *
  * The first lifecycle warms up the JVM/carrier-thread infra (epoll FDs, JIT classfile descriptors)
  * so the measured baseline does not include first-time artifacts. Post-warmup, any FD delta is
  * attributable to shutdown completeness: every per-lifecycle resource (acceptor sockets, handler
  * client sockets, write-buffer pool) must be released by shutdown.
  *
  * Scenarios:
  *   1. Clean shutdown after idle period: resources back to baseline.
  *   2. Shutdown with many keep-alive-idle connections in flight.
  *   3. Back-to-back server startup/shutdown cycles: no monotonic growth.
  */
class ConcurrentShutdownSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val baseConfig = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 512)
    .withReadHeaderTimeout(5.seconds)
    .withIdleTimeout(30.seconds)

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  test("shutdown after idle releases all resources (within tolerance)") {
    requireHostileMode()

    HttpServer
      .scoped(baseConfig)(handler) { server =>
        server.start.flatMap(address =>
          Eru.effect {
            (1 to 5).foreach { _ =>
              SimpleHttpClient.get(s"http://${address}"): Unit
            }
          }.mapError(e => HttpError.NetworkError(s"warmup: ${e.getMessage}", Some(e)))
        )
      }
      .assertSuccess

    Thread.sleep(300)
    val baseline = ResourceSnapshot.capture()

    HttpServer
      .scoped(baseConfig)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            (1 to 50).foreach { _ =>
              val resp = SimpleHttpClient.get(s"http://${address}")
              assertEquals(resp.status, 200)
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess

    val after = ResourceSnapshot.capture()
    val delta = after.minus(baseline)

    assert(
      delta.heapUsedBytes < 20L * 1024 * 1024,
      s"Heap retained ${delta.heapUsedBytes / 1024 / 1024}MB over measured lifecycle. " +
        s"Baseline=$baseline After=$after"
    )

    if ResourceSnapshot.fdCountSupported then {
      assert(
        delta.openFileDescriptors < 5L,
        s"FD delta ${delta.openFileDescriptors} — expected near zero after synchronous shutdown. " +
          "If this regresses, B.7's handlerTracker drain may not be running. " +
          s"Baseline=$baseline After=$after"
      )
    }
  }

  test("shutdown closes idle keep-alive connections") {
    requireHostileMode()

    val connectionsOpened = new AtomicInteger(0)
    val sockets = scala.collection.mutable.ArrayBuffer[Socket]()

    HttpServer
      .scoped(baseConfig)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            (1 to 200).foreach { _ =>
              Try {
                val s = new Socket(address.host, address.port)
                val out = s.getOutputStream
                out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n".getBytes)
                out.flush()
                val in = s.getInputStream
                val buf = new Array[Byte](256)
                in.read(buf): Unit
                sockets.synchronized(sockets += s): Unit
                connectionsOpened.incrementAndGet(): Unit
              }
            }
            Thread.sleep(200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess

    val observedEof = new AtomicInteger(0)
    sockets.foreach { s =>
      try {
        s.setSoTimeout(2000)
        val in = s.getInputStream
        try {
          while in.read() >= 0 do ()
          observedEof.incrementAndGet(): Unit
        } catch {
          case _: IOException => observedEof.incrementAndGet(): Unit
        }
      } finally {
        Try(s.close()): Unit
      }
    }

    assertEquals(
      observedEof.get(),
      connectionsOpened.get(),
      s"${connectionsOpened.get() - observedEof.get()} of ${connectionsOpened.get()} " +
        "keep-alive connections did NOT see shutdown. B.7 drain should be synchronous — " +
        "regression likely in NativeHttpServer.shutdown."
    )
  }

  test("repeated start/shutdown cycles do not leak resources") {
    requireHostileMode()

    HttpServer
      .scoped(baseConfig)(handler) { server =>
        server.start.flatMap(addr =>
          Eru.effect {
            val r = SimpleHttpClient.get(s"http://${addr}")
            assertEquals(r.status, 200)
          }.mapError(e => HttpError.NetworkError(s"warmup: ${e.getMessage}", Some(e)))
        )
      }
      .assertSuccess

    Thread.sleep(500)
    val baseline = ResourceSnapshot.capture()

    (1 to 20).foreach { cycle =>
      HttpServer
        .scoped(baseConfig)(handler) { server =>
          server.start.flatMap(addr =>
            Eru.effect {
              val r = SimpleHttpClient.get(s"http://${addr}")
              assertEquals(r.status, 200, s"cycle $cycle")
            }.mapError(e => HttpError.NetworkError(s"cycle $cycle: ${e.getMessage}", Some(e)))
          )
        }
        .assertSuccess
    }

    Thread.sleep(1000)
    val after = ResourceSnapshot.capture()
    val delta = after.minus(baseline)

    assert(
      delta.heapUsedBytes < 30L * 1024 * 1024,
      s"Heap grew by ${delta.heapUsedBytes / 1024 / 1024}MB over 20 cycles. " +
        s"Baseline=$baseline After=$after"
    )
    if ResourceSnapshot.fdCountSupported then {
      assert(
        delta.openFileDescriptors < 30L,
        s"FD count grew by ${delta.openFileDescriptors} over 20 cycles. " +
          s"Baseline=$baseline After=$after"
      )
    }
  }
}
