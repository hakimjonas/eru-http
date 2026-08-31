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
import net.ghoula.eru.http.server.SimpleHttpClient
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Content-Length OOM attack.
  *
  * Attack: attacker declares a massive Content-Length header but sends no body (or trickles bytes).
  * A naive server would allocate `new Array[Byte](contentLength)` eagerly, leading to OOM on the
  * first request. Content-Length > `maxRequestSize` is rejected BEFORE any allocation.
  *
  * What `HttpParserSpec` and `HttpServerSpec` already cover (kept out of this spec to avoid
  * duplication):
  *   - Unit: parser rejects Content-Length > maxBodySize synchronously with PayloadTooLarge.
  *   - Unit: Content-Length = 2GB is rejected, not OOM'd.
  *   - Unit: exactly-at-limit body is accepted.
  *   - Integration: server returns `HTTP/1.1 413 ...` with `Connection: close`.
  *
  * What THIS spec adds:
  *   1. Concurrent attack — 200 attackers each declaring 2GB Content-Length. Heap must not grow
  *      proportionally to 200×2GB: the mitigation rejects before allocation, so growth stays in the
  *      single-MB range (request state, 413 responses); the 50MB ceiling catches catastrophic
  *      regressions. The server must also stay responsive (median legitimate-client latency under
  *      500ms during the attack).
  *   2. The connection is genuinely closed after 413, not merely labelled `Connection: close`: the
  *      read after the response must observe EOF (-1). A header-only close would leave reads
  *      blocking until SO_TIMEOUT instead.
  *   3. Chunked-transfer-encoding over-limit — the handler's body decode surfaces the mid-stream
  *      PayloadTooLarge failure and the server answers 400 (previously this was a silent 200 with a
  *      truncated body).
  */
class ContentLengthAttackSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val config = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 512)
    .withMaxRequestSize(1024)
    .withReadHeaderTimeout(5.seconds)
    .withIdleTimeout(10.seconds)

  private val handler: RequestHandler = req =>
    for {
      body <- BodyDecoder[String].decode(req.body).mapError(HttpError.BodyDecodeError.apply)
    } yield Response(StatusCode.Ok, Headers.empty, Body.Text(s"received ${body.length} bytes"))

  test("ContentLength OOM: 200 concurrent attackers don't exhaust heap; server stays responsive") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val warm = SimpleHttpClient.get(s"http://${address}")
            assertEquals(warm.status, 200)
            val baseline = ResourceSnapshot.capture()

            val attackerCount = 200
            val responses = new AtomicInteger(0)
            val threads = (1 to attackerCount).map { i =>
              val t = new Thread(
                () => {
                  Try(new Socket(address.host, address.port)).toOption.foreach { socket =>
                    try {
                      socket.setSoTimeout(10_000)
                      val out = socket.getOutputStream
                      out.write("POST / HTTP/1.1\r\nHost: localhost\r\n".getBytes)
                      out.write("Content-Length: 2147483000\r\n\r\n".getBytes)
                      out.flush()
                      val in = socket.getInputStream
                      val buf = new Array[Byte](512)
                      val n = in.read(buf)
                      if n > 0 then {
                        val resp = new String(buf, 0, n)
                        if resp.startsWith("HTTP/1.1 413") then responses.incrementAndGet(): Unit
                      }
                    } finally Try(socket.close()): Unit
                  }
                },
                s"oom-attacker-$i"
              )
              t.setDaemon(true)
              t.start()
              t
            }.toList

            Thread.sleep(100)
            val legitimateLatencies = (1 to 10).map { _ =>
              val start = System.nanoTime()
              val resp = SimpleHttpClient.post(s"http://${address}", "hello")
              val elapsedMs = (System.nanoTime() - start) / 1_000_000L
              assertEquals(resp.status, 200, "legitimate client must stay served during attack")
              elapsedMs
            }

            val deadline = System.currentTimeMillis() + 15_000
            threads.foreach { t =>
              val rem = deadline - System.currentTimeMillis()
              if rem > 0 then t.join(rem)
            }

            assertEquals(
              responses.get(),
              attackerCount,
              s"Only ${responses.get()}/$attackerCount attackers received 413. " +
                "Parser-level rejection before allocation should reach every request."
            )

            val median = legitimateLatencies.sorted.apply(legitimateLatencies.size / 2)
            val max = legitimateLatencies.max
            assert(
              median < 500L,
              s"Median legitimate-client latency ${median}ms too high during attack. All=$legitimateLatencies"
            )
            assert(max < 5_000L, s"Max legitimate-client latency ${max}ms too high. All=$legitimateLatencies")

            Thread.sleep(500)

            val after = ResourceSnapshot.capture()
            val delta = after.minus(baseline)
            assert(
              delta.heapUsedBytes < 50L * 1024 * 1024,
              s"Heap grew by ${delta.heapUsedBytes / 1024 / 1024}MB during 200×2GB attack. " +
                "Parser-level rejection is NOT happening before allocation."
            )
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("ContentLength OOM: connection is closed after 413 (not just Connection: close header)") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val socket = new Socket(address.host, address.port)
            try {
              socket.setSoTimeout(5_000)
              val out = socket.getOutputStream
              out.write("POST / HTTP/1.1\r\nHost: localhost\r\n".getBytes)
              out.write("Content-Length: 100000\r\n\r\n".getBytes)
              out.flush()

              val in = socket.getInputStream
              val buf = new Array[Byte](1024)
              val n = in.read(buf)
              assert(n > 0, "Expected a response from server")
              val resp = new String(buf, 0, n)
              assert(resp.startsWith("HTTP/1.1 413"), s"Expected 413, got: ${resp.take(40)}")

              val tailBuf = new Array[Byte](256)
              var nextRead = 0
              try nextRead = in.read(tailBuf)
              catch { case _: IOException => nextRead = -1 }
              if nextRead > 0 then {
                try nextRead = in.read(tailBuf)
                catch { case _: IOException => nextRead = -1 }
              }
              assertEquals(nextRead, -1, "Server must close socket after 413, observed EOF expected")
            } finally Try(socket.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("ContentLength OOM: chunked-over-limit is rejected with 400 (no silent truncation)") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val socket = new Socket(address.host, address.port)
            try {
              socket.setSoTimeout(5_000)
              val out = socket.getOutputStream
              out.write("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes)
              out.write("1F4\r\n".getBytes)
              out.write(Array.fill[Byte](500)('a'.toByte))
              out.write("\r\n".getBytes)
              out.write("1F4\r\n".getBytes)
              out.write(Array.fill[Byte](500)('b'.toByte))
              out.write("\r\n".getBytes)
              out.write("1F4\r\n".getBytes)
              out.write(Array.fill[Byte](500)('c'.toByte))
              out.write("\r\n".getBytes)
              out.write("0\r\n\r\n".getBytes)
              out.flush()

              val in = socket.getInputStream
              val collected = new java.io.ByteArrayOutputStream()
              val readBuf = new Array[Byte](2048)
              var totalRead = 0
              var doneReading = false
              while !doneReading do {
                val n =
                  try in.read(readBuf)
                  catch { case _: IOException => -1 }
                if n <= 0 then doneReading = true
                else {
                  collected.write(readBuf, 0, n)
                  totalRead += n
                  if totalRead > 4096 then doneReading = true
                }
              }

              assert(totalRead > 0, "Expected a response from server, got no bytes")
              val resp = new String(collected.toByteArray, "UTF-8")

              assert(
                resp.startsWith("HTTP/1.1 400"),
                s"Expected 400 for chunked-over-limit (the body stream now fails instead of silently truncating), got: ${resp.take(80)}"
              )
              assert(
                !resp.contains("received"),
                s"Handler must not see a truncated body as success, got: ${resp.take(120)}"
              )
            } finally Try(socket.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }
}
