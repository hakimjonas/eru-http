package net.ghoula.eru.http.server.hostile

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.Socket
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.{HostileTestBase, ResourceSnapshot}
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Connection flood (`maxConnections` accept-semaphore bound).
  *
  * `config.maxConnections` is wired into an accept semaphore in the accept loop. When the limit is
  * reached, the acceptor VT parks on `semaphore.acquire` instead of dispatching a handler. The
  * permit is released via `.ensure` when the handler fiber terminates.
  *
  * `HttpServerSpec.maxConnections bounds concurrent connections` asserts the config exists and that
  * the server continues serving — but uses `SimpleHttpClient` which closes each socket, so no two
  * requests ever hold slots simultaneously. That test would pass even if `maxConnections` was
  * unwired.
  *
  * This spec exercises the actual bound:
  *   1. Open `maxConnections` slow-handler connections that pin their slots, then N more — the N
  *      extra must NOT complete until slots free, and the peak number of concurrent handlers must
  *      never exceed `maxConnections`. `readHeaderTimeout` is set long so no timeout races during
  *      the test.
  *   2. Recovery: after the flood drains, queued connections proceed and subsequent clean requests
  *      succeed.
  *   3. FD count stays bounded under a sustained 500-request flood: the server's retained FDs stay
  *      close to `maxConnections` + acceptor sockets, NOT floodCount, measured a moment after the
  *      flood. If FDs grew proportionally to load, the server kept client FDs open long after
  *      responses — a leak.
  */
class ConnectionFloodSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  test("ConnectionFlood: excess connections are blocked until slots free") {
    requireHostileMode()

    val activeHandlers = new AtomicInteger(0)
    val peakActive = new AtomicInteger(0)
    val releaseSignal = new CountDownLatch(1)

    val slowHandler: RequestHandler = _ =>
      for {
        _ <- Eru.effect {
          val active = activeHandlers.incrementAndGet()
          peakActive.updateAndGet(prev => math.max(prev, active))
          releaseSignal.await(30, TimeUnit.SECONDS): Unit
          activeHandlers.decrementAndGet(): Unit
        }.mapError(e => HttpError.NetworkError(s"handler: ${e.getMessage}", Some(e)))
      } yield Response(StatusCode.Ok, Headers.empty, Body.Text("done"))

    val maxConnections = 4
    val excessCount = 6
    val totalAttempts = maxConnections + excessCount

    val config = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = maxConnections)
      .withReadHeaderTimeout(30.seconds)

    HttpServer
      .scoped(config)(slowHandler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val sockets = scala.collection.mutable.ArrayBuffer[Socket]()
            val responseStatuses = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
            val threads = (1 to totalAttempts).map { i =>
              val t = new Thread(
                () => {
                  Try(new Socket(address.host, address.port)).toOption.foreach { socket =>
                    sockets.synchronized(sockets += socket): Unit
                    try {
                      socket.setSoTimeout(30_000)
                      val out = socket.getOutputStream
                      out.write(s"GET /r-$i HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes)
                      out.flush()
                      val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
                      Option(in.readLine()).foreach { statusLine =>
                        val parts = statusLine.split(" ", 3)
                        if parts.length >= 2 then Try(parts(1).toInt).foreach(responseStatuses.add)
                      }
                    } catch { case _: IOException => () }
                  }
                },
                s"flood-$i"
              )
              t.setDaemon(true)
              t.start()
              t
            }.toList

            val deadline = System.currentTimeMillis() + 5_000
            while activeHandlers.get() < maxConnections && System.currentTimeMillis() < deadline do {
              Thread.sleep(10)
            }
            assertEquals(
              activeHandlers.get(),
              maxConnections,
              s"Expected exactly $maxConnections handlers active, got ${activeHandlers.get()}"
            )

            Thread.sleep(500)
            assert(
              activeHandlers.get() <= maxConnections,
              s"Active handlers ${activeHandlers.get()} exceeded maxConnections=$maxConnections after stabilization"
            )
            assert(
              peakActive.get() <= maxConnections,
              s"Peak active $peakActive exceeded maxConnections=$maxConnections during the test"
            )

            assertEquals(
              responseStatuses.size,
              0,
              "No response should have arrived — handlers are blocked"
            )

            releaseSignal.countDown()
            threads.foreach(_.join(15_000))

            val statusSnapshot = {
              val builder = scala.collection.mutable.ListBuffer[Int]()
              responseStatuses.forEach(s => builder += s)
              builder.toList
            }
            assertEquals(
              statusSnapshot.length,
              totalAttempts,
              s"Expected $totalAttempts responses, got ${statusSnapshot.length}"
            )
            assert(statusSnapshot.forall(_ == 200), s"All responses should be 200, got: $statusSnapshot")

            assert(
              peakActive.get() <= maxConnections,
              s"Final peakActive=${peakActive.get()} exceeded maxConnections=$maxConnections"
            )

            sockets.synchronized(sockets.foreach(s => Try(s.close()): Unit))
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("ConnectionFlood: server recovers and serves normally after flood releases") {
    requireHostileMode()

    val activeHandlers = new AtomicInteger(0)
    val gate = new AtomicReference[Option[CountDownLatch]](Some(new CountDownLatch(1)))

    val handler: RequestHandler = _ =>
      for {
        _ <- Eru.effect {
          activeHandlers.incrementAndGet(): Unit
          gate.get().foreach(_.await(10, TimeUnit.SECONDS): Unit)
          activeHandlers.decrementAndGet(): Unit
        }.mapError(e => HttpError.NetworkError(s"handler: ${e.getMessage}", Some(e)))
      } yield Response(StatusCode.Ok, Headers.empty, Body.Text("ok"))

    val maxConnections = 3
    val config = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = maxConnections)
      .withReadHeaderTimeout(30.seconds)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val floodSockets = (1 to maxConnections * 2).map { _ =>
              val s = new Socket(address.host, address.port)
              s.setSoTimeout(30_000)
              s.getOutputStream.write(
                "GET /flood HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes
              )
              s.getOutputStream.flush()
              s
            }.toList

            val deadline = System.currentTimeMillis() + 3_000
            while activeHandlers.get() < maxConnections && System.currentTimeMillis() < deadline do {
              Thread.sleep(10)
            }
            assertEquals(activeHandlers.get(), maxConnections, "expected saturation")

            gate.get().foreach(_.countDown())
            gate.set(Some(new CountDownLatch(0)))

            floodSockets.foreach { s =>
              Try(s.getInputStream.read(new Array[Byte](512))): Unit
              Try(s.close()): Unit
            }

            val drainDeadline = System.currentTimeMillis() + 5_000
            while activeHandlers.get() > 0 && System.currentTimeMillis() < drainDeadline do {
              Thread.sleep(10)
            }
            assertEquals(activeHandlers.get(), 0, "all handlers should have drained")

            val response = SimpleHttpClient.get(s"http://${address}")
            assertEquals(response.status, 200, "server must recover after flood")
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("ConnectionFlood: FD count stays bounded under sustained concurrent load") {
    requireHostileMode()

    val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

    val maxConnections = 16
    val config = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = maxConnections)

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val warm = SimpleHttpClient.get(s"http://${address}")
            assertEquals(warm.status, 200)
            val baseline = ResourceSnapshot.capture()

            val floodCount = 500
            val completed = new AtomicInteger(0)
            val threads = (1 to floodCount).map { _ =>
              val t = new Thread(() => {
                if Try { SimpleHttpClient.get(s"http://${address}") }.map(_.status).toOption.contains(200)
                then completed.incrementAndGet(): Unit
              })
              t.setDaemon(true)
              t.start()
              t
            }.toList
            threads.foreach(_.join(30_000))

            assertEquals(
              completed.get(),
              floodCount,
              s"Expected all $floodCount requests to complete, got ${completed.get()}"
            )

            Thread.sleep(1000)
            val after = ResourceSnapshot.capture()
            val delta = after.minus(baseline)

            if ResourceSnapshot.fdCountSupported then {
              val tolerance = Runtime.getRuntime.availableProcessors() + 50L
              assert(
                delta.openFileDescriptors < tolerance,
                s"FD delta ${delta.openFileDescriptors} exceeds tolerance $tolerance " +
                  s"after $floodCount-request flood. Server retaining FDs proportional to load. " +
                  s"Before=$baseline After=$after"
              )
            }
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }
}
