package net.ghoula.eru.http.server.hostile

import java.io.{BufferedReader, InputStreamReader}
import java.net.Socket
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Per-IP request rate limiting.
  *
  * Validates:
  *   1. First `burstSizePerIp` requests on a keep-alive connection succeed with 200; the next few
  *      return 429 with Retry-After + X-RateLimit-*.
  *   2. 429 does NOT close the connection: keep-alive continues to work.
  *   3. After waiting for bucket refill, a second burst succeeds.
  *   4. A drip rate at half the sustained rate never trips 429.
  *
  * The config uses a tight rate (2/sec, burst 5) so the burst exhausts fast and refill is
  * observable within the test runtime; at rate=2/sec the worst-case Retry-After is ≤3s, so the
  * refill wait is 3s. The drip case sends 1/sec (half the sustained rate) over 5s — every request
  * must succeed.
  */
class RateLimitSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 256, acceptorThreads = 2)
    .withPerIpGovernance(true)
    .withMaxConnectionsPerIp(100)
    .withAcceptRatePerIp(ratePerSec = 1000, burst = 1000)
    .withRequestRatePerIp(ratePerSec = 2, burst = 5)
    .withTrackedIpCap(1000)

  /** Parse a single HTTP/1.1 response off a raw socket's input. Returns (statusCode, headers,
    * bodyLen).
    */
  private def readOneResponse(in: BufferedReader): (Int, Map[String, String], String) = {
    val statusLine = Option(in.readLine()).getOrElse(throw new java.io.EOFException("no status line"))
    val statusCode = statusLine.split(" ", 3)(1).toInt
    val headers = scala.collection.mutable.Map[String, String]()
    var contentLength = 0
    var line = Option(in.readLine())
    while line.exists(_.nonEmpty) do {
      val raw = line.get
      val colon = raw.indexOf(':')
      if colon > 0 then {
        val name = raw.substring(0, colon).trim.toLowerCase
        val value = raw.substring(colon + 1).trim
        headers(name) = value
        if name == "content-length" then contentLength = value.toInt
      }
      line = Option(in.readLine())
    }
    val body = new Array[Char](contentLength)
    var read = 0
    while read < contentLength do {
      val n = in.read(body, read, contentLength - read)
      if n < 0 then throw new java.io.EOFException()
      read += n
    }
    (statusCode, headers.toMap, new String(body, 0, read))
  }

  test("request rate: first burst succeeds, excess gets 429 with Retry-After + X-RateLimit headers") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val out = s.getOutputStream
              val in = new BufferedReader(new InputStreamReader(s.getInputStream))

              val total = cfg.burstSizePerIp + 5
              val reqs = (1 to total)
                .map(_ => "GET /x HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n")
                .mkString
              out.write(reqs.getBytes)
              out.flush()

              val statuses = (1 to total).map { i =>
                try {
                  val (code, hdrs, _) = readOneResponse(in)
                  (code, hdrs)
                } catch {
                  case e: Exception =>
                    fail(
                      s"Failed reading response #$i of $total (burst=${cfg.burstSizePerIp}): ${e.getClass.getSimpleName}: ${e.getMessage}"
                    )
                }
              }

              statuses.take(cfg.burstSizePerIp).zipWithIndex.foreach { case ((code, _), idx) =>
                assertEquals(code, 200, s"request ${idx + 1} should be 200, got $code")
              }
              statuses.drop(cfg.burstSizePerIp).foreach { case (code, hdrs) =>
                assertEquals(code, 429, s"over-burst request should be 429, got $code")
                assert(hdrs.contains("retry-after"), s"missing Retry-After, got: ${hdrs.keys}")
                val retryAfter = hdrs("retry-after").toInt
                assert(retryAfter >= 1, s"Retry-After must be ≥ 1, got $retryAfter")
                assertEquals(hdrs.get("x-ratelimit-limit"), Some(cfg.requestsPerSecondPerIp.toString))
                assertEquals(hdrs.get("x-ratelimit-remaining"), Some("0"))
                assert(hdrs.contains("x-ratelimit-reset"))
              }
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("request rate: 429 response keeps the connection alive — a follow-up 200 works") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(5000)
              val out = s.getOutputStream
              val in = new BufferedReader(new InputStreamReader(s.getInputStream))

              val toExhaust = cfg.burstSizePerIp + 1
              val pipeline = (1 to toExhaust)
                .map(_ => "GET / HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n")
                .mkString
              out.write(pipeline.getBytes)
              out.flush()

              (1 to toExhaust).foreach { _ => readOneResponse(in): Unit }

              Thread.sleep(3000)

              out.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
              out.flush()
              val (code, _, _) = readOneResponse(in)
              assertEquals(code, 200, "after 429 + waiting, a fresh request should succeed")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("request rate: drip at rate/2 all succeed") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val count = 5
            val successes = (1 to count).map { i =>
              val s = new Socket(address.host, address.port)
              try {
                s.setSoTimeout(3000)
                val out = s.getOutputStream
                val in = new BufferedReader(new InputStreamReader(s.getInputStream))
                out.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
                out.flush()
                val (code, _, _) = readOneResponse(in)
                if i < count then Thread.sleep(1000)
                code == 200
              } finally Try(s.close()): Unit
            }
            assertEquals(
              successes.count(identity),
              count,
              s"all $count drip requests must succeed; results: $successes"
            )
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
