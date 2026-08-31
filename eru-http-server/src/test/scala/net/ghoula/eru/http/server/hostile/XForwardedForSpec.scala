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

/** X-Forwarded-For trusted-proxies resolution for request-rate limiting.
  *
  * Validates:
  *   1. When the TCP peer is inside `trustedProxies`, the server uses the leftmost untrusted IP in
  *      XFF as the rate-limit subject. Two different XFF values get independent buckets.
  *   2. When the TCP peer is NOT trusted, XFF is ignored — rate limit applies to TCP peer only.
  *   3. Malformed XFF values silently fall back to TCP peer (no crash, no XFF bypass).
  *   4. An all-trusted XFF chain (no real client) falls back to TCP peer.
  *
  * The config trusts 127/8 so the test's own loopback TCP peer counts as a "proxy"; the request
  * rate is 2/sec with a 3-token burst so the bucket exhausts quickly within the test.
  */
class XForwardedForSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val trustedLoopback = Cidr.unsafeParse("127.0.0.0/8")

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 256, acceptorThreads = 2)
    .withPerIpGovernance(true)
    .withMaxConnectionsPerIp(100)
    .withAcceptRatePerIp(ratePerSec = 1000, burst = 1000)
    .withRequestRatePerIp(ratePerSec = 2, burst = 3)
    .withTrackedIpCap(1000)
    .withTrustedProxies(List(trustedLoopback))

  /** Send a single HTTP/1.1 request with optional XFF, then read the status code. */
  private def sendAndReadStatus(host: String, port: Int, xff: Option[String]): Int = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(3000)
      val xffHeader = xff.map(v => s"X-Forwarded-For: $v\r\n").getOrElse("")
      val req = s"GET / HTTP/1.1\r\nHost: x\r\n${xffHeader}Connection: close\r\n\r\n"
      s.getOutputStream.write(req.getBytes)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = in.readLine()
      statusLine.split(" ", 3)(1).toInt
    } finally Try(s.close()): Unit
  }

  test("XFF: trusted TCP peer → leftmost XFF IP is the rate-limit subject; distinct XFFs isolate") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val ip1 = "1.2.3.4"
            val s1 = (1 to 4).map(_ => sendAndReadStatus(address.host, address.port, Some(ip1)))
            assertEquals(s1.take(3), Seq(200, 200, 200), s"first 3 for $ip1 should be 200: $s1")
            assertEquals(s1(3), 429, s"4th for $ip1 should be 429: $s1")

            val ip2 = "5.6.7.8"
            val s2 = sendAndReadStatus(address.host, address.port, Some(ip2))
            assertEquals(s2, 200, "XFF-isolated bucket for $ip2 should serve 200")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("XFF: untrusted TCP peer → XFF is ignored, rate limit applies to TCP peer") {
    requireHostileMode()

    val cfgNoTrust = cfg.withTrustedProxies(Nil)

    HttpServer
      .scoped(cfgNoTrust)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val n = cfgNoTrust.burstSizePerIp + 1
            val statuses = (1 to n).map { i =>
              sendAndReadStatus(address.host, address.port, Some(s"99.0.0.$i"))
            }
            assertEquals(statuses.take(cfgNoTrust.burstSizePerIp).toSet, Set(200))
            assertEquals(statuses.last, 429, s"spoofed XFF must NOT bypass rate limit; got $statuses")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("XFF: malformed header value falls back to TCP peer") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val n = cfg.burstSizePerIp + 1
            val statuses = (1 to n).map { _ =>
              sendAndReadStatus(address.host, address.port, Some("not-an-ip"))
            }
            assertEquals(statuses.take(cfg.burstSizePerIp).toSet, Set(200))
            assertEquals(statuses.last, 429, "malformed XFF must fall through to TCP peer, not bypass")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("XFF: all-trusted chain falls back to TCP peer") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val n = cfg.burstSizePerIp + 1
            val statuses = (1 to n).map { _ =>
              sendAndReadStatus(address.host, address.port, Some("127.10.10.10, 127.20.20.20"))
            }
            assertEquals(statuses.take(cfg.burstSizePerIp).toSet, Set(200))
            assertEquals(statuses.last, 429, "all-trusted XFF must fall back to TCP peer")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("XFF: mixed chain — leftmost UNTRUSTED IP is the subject") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val xff = "1.2.3.4, 127.10.10.10, 127.20.20.20"
            val n = cfg.burstSizePerIp + 1
            val statuses = (1 to n).map(_ => sendAndReadStatus(address.host, address.port, Some(xff)))
            assertEquals(statuses.take(cfg.burstSizePerIp).toSet, Set(200))
            assertEquals(statuses.last, 429, s"mixed XFF chain should rate-limit real client: $statuses")

            val otherXff = "9.9.9.9, 127.10.10.10"
            val s2 = sendAndReadStatus(address.host, address.port, Some(otherXff))
            assertEquals(s2, 200, "distinct real client must have a fresh bucket")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
