package net.ghoula.eru.http.server.hostile

import java.io.IOException
import java.net.{Socket, SocketException}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.{HostileTestBase, ResourceSnapshot}
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.SimpleHttpClient
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Slowloris-style attacks.
  *
  * Validates that the timeout machinery (`readHeaderTimeout` + `idleTimeout`) prevents slow-drip
  * attacks. A `readHeaderTimeout` of 1s keeps the test's wall-clock cost bounded while giving a
  * legitimate concurrent client enough slack to succeed.
  *
  *   - Variant 1 (stalled after initial line): the per-read deadline fires after
  *     `readHeaderTimeout` of silence. The deadline is 10× the timeout (10s), giving CI + VT
  *     scheduling headroom without leaking flakiness.
  *   - Variant 2 (byte-drip with 1.5s inter-read gaps): the deadline is PER READ (nginx
  *     client_header_timeout semantics) — a drip whose gap between reads exceeds
  *     `readHeaderTimeout` is cut with 408. A drip faster than the deadline is legitimate progress
  *     by design and is served; if that ever stops being the desired posture, minimum data-rate
  *     enforcement becomes necessary.
  *
  * The resource-safety case asserts heap (<50MB retained) and FDs (<20 over baseline) return after
  * the attack, and that legitimate requests stay responsive (median < 500ms, max < 3s) under load.
  */
class SlowlorisAttackSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try EruRuntime.shared.cleanup()
    catch { case _: Exception => () }
    super.afterAll()
  }

  private val config = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 2048)
    .withReadHeaderTimeout(1.second)
    .withIdleTimeout(5.seconds)

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  test("Slowloris variant 1: 1000 stalled half-requests are all closed within readHeaderTimeout") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            runStalledAttack(address.host, address.port, connectionCount = 1000)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("Slowloris variant 1: legitimate clients stay responsive during attack") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            runStalledAttackWithConcurrentLegitimate(address.host, address.port)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("Slowloris variant 2: byte-drip with inter-read gaps beyond readHeaderTimeout is killed") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            runByteDripAttack(address.host, address.port, connectionCount = 500)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("Slowloris: resources released after attack completes") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val warmup = SimpleHttpClient.get(s"http://${address}")
            assertEquals(warmup.status, 200)

            val baseline = ResourceSnapshot.capture()

            runStalledAttack(address.host, address.port, connectionCount = 500)

            Thread.sleep(2000)

            val afterAttack = ResourceSnapshot.capture()
            val delta = afterAttack.minus(baseline)

            assert(
              delta.heapUsedBytes < 50L * 1024 * 1024,
              s"Heap retained ${delta.heapUsedBytes / 1024 / 1024}MB after attack. " +
                s"Before=$baseline After=$afterAttack"
            )

            if ResourceSnapshot.fdCountSupported then {
              assert(
                delta.openFileDescriptors < 20L,
                s"Leaked ${delta.openFileDescriptors} FDs. Before=$baseline After=$afterAttack"
              )
            }

            val final_ = SimpleHttpClient.get(s"http://${address}")
            assertEquals(final_.status, 200)
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  /** Open N sockets, write a partial request line on each, then do nothing. Returns when all
    * sockets have been observed closed by the server.
    */
  private def runStalledAttack(host: String, port: Int, connectionCount: Int): Unit = {
    val closedCount = new AtomicInteger(0)
    val threads = (0 until connectionCount).map { i =>
      val t = new Thread(
        () => {
          Try(new Socket(host, port)).toOption.foreach { socket =>
            try {
              val out = socket.getOutputStream
              out.write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes)
              out.flush()
              socket.setSoTimeout(10_000)
              val in = socket.getInputStream
              try {
                while in.read() >= 0 do ()
              } catch {
                case _: IOException => ()
              }
              closedCount.incrementAndGet(): Unit
            } finally {
              Try(socket.close()): Unit
            }
          }
        },
        s"slowloris-$i"
      )
      t.setDaemon(true)
      t.start()
      t
    }.toList

    val deadline = System.currentTimeMillis() + 10_000
    threads.foreach { t =>
      val remaining = deadline - System.currentTimeMillis()
      if remaining > 0 then t.join(remaining)
    }

    val closed = closedCount.get()
    val stillAlive = threads.count(_.isAlive)
    assertEquals(
      closed,
      connectionCount,
      s"Expected all $connectionCount connections to observe server close, got $closed. " +
        s"($stillAlive threads still alive after 10s deadline.) " +
        "readHeaderTimeout enforcement may be failing under concurrent load."
    )
  }

  /** Run the stalled attack while concurrently issuing legitimate requests. Legitimate requests
    * must succeed with low latency even under attack load.
    */
  private def runStalledAttackWithConcurrentLegitimate(host: String, port: Int): Unit = {
    val attackThread = new Thread(
      () => {
        try runStalledAttack(host, port, connectionCount = 500)
        catch { case _: Throwable => () }
      },
      "slowloris-attackers"
    )
    attackThread.setDaemon(true)
    attackThread.start()

    Thread.sleep(200)

    val legitimateLatencies = (1 to 20).map { _ =>
      val start = System.nanoTime()
      val resp = SimpleHttpClient.get(s"http://$host:$port")
      val elapsed = (System.nanoTime() - start) / 1_000_000L
      assertEquals(resp.status, 200, "legitimate request must succeed during attack")
      elapsed
    }

    attackThread.join(10_000)

    val maxLatency = legitimateLatencies.max
    val medianLatency = legitimateLatencies.sorted.apply(legitimateLatencies.size / 2)

    assert(
      medianLatency < 500L,
      s"Median legitimate-request latency ${medianLatency}ms too high during attack. " +
        s"All latencies: $legitimateLatencies"
    )
    assert(
      maxLatency < 3_000L,
      s"Max legitimate-request latency ${maxLatency}ms too high during attack. " +
        s"All latencies: $legitimateLatencies"
    )
  }

  /** Byte-drip attack: open sockets and send 1 byte, then another every 1.5s — each inter-read gap
    * exceeds readHeaderTimeout (1s), so the per-read deadline cuts every connection.
    */
  private def runByteDripAttack(host: String, port: Int, connectionCount: Int): Unit = {
    val killedByServer = new AtomicInteger(0)
    val payload = "GET / HTTP/1.1\r\nHost: x\r\nA: b\r\n\r\n".getBytes

    val threads = (0 until connectionCount).map { i =>
      val t = new Thread(
        () => {
          Try(new Socket(host, port)).toOption.foreach { socket =>
            try {
              socket.setSoTimeout(10_000)
              val out = socket.getOutputStream
              val in = socket.getInputStream

              var idx = 0
              var serverClosed = false
              while idx < payload.length && !serverClosed do {
                try {
                  out.write(payload(idx).toInt)
                  out.flush()
                  idx += 1
                } catch {
                  case _: SocketException =>
                    serverClosed = true
                }
                if !serverClosed then {
                  Thread.sleep(1500)
                  socket.setSoTimeout(1)
                  try {
                    if in.read() < 0 then serverClosed = true
                  } catch {
                    case _: java.net.SocketTimeoutException => ()
                    case _: IOException => serverClosed = true
                  }
                  socket.setSoTimeout(10_000)
                }
              }

              if serverClosed then killedByServer.incrementAndGet(): Unit
            } finally {
              Try(socket.close()): Unit
            }
          }
        },
        s"drip-$i"
      )
      t.setDaemon(true)
      t.start()
      t
    }.toList

    val deadline = System.currentTimeMillis() + 10_000
    threads.foreach { t =>
      val remaining = deadline - System.currentTimeMillis()
      if remaining > 0 then t.join(remaining)
    }

    val killed = killedByServer.get()
    val stillAlive = threads.count(_.isAlive)
    assertEquals(
      killed,
      connectionCount,
      s"Server killed $killed of $connectionCount byte-drip connections. " +
        s"($stillAlive client threads still alive.) " +
        "Eru.timeout's absolute deadline may not be firing under concurrent load — " +
        "would require implementing B.1 (minimum data rate enforcement)."
    )
  }
}
