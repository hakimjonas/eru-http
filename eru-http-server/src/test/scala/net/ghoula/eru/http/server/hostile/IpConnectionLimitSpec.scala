package net.ghoula.eru.http.server.hostile

import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Per-IP concurrent connection cap.
  *
  * Validates:
  *   1. From a single IP, `maxConnectionsPerIp + 1` connections: first N accepted, (N+1)th dropped
  *      via TCP close (no HTTP response). The excess sockets connect at the TCP layer (kernel
  *      accept) but are closed immediately by the accept-loop gate, so their reads observe EOF.
  *   2. Per-IP isolation: while one IP is at its cap, a different source IP (loopback alias
  *      127.0.0.2 — the kernel treats all of 127/8 as loopback) still succeeds; if the alias is not
  *      available the test is skipped rather than failed. Cap-filling sockets send a complete
  *      keep-alive request and hold the connection open by not reading the response body (an idle
  *      socket with an unterminated header would trip `readHeaderTimeout` instead).
  *   3. Decrement: releasing connections from the capped IP lets new ones through.
  *
  * The fail-closed tracked-IP cap is proven at the unit level in `PerIpGovernorSpec`; a real
  * accept-loop version would require many distinct source IPs, which Linux loopback 127/8 does not
  * natively provide.
  */
class IpConnectionLimitSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 512, acceptorThreads = 2)
    .withPerIpGovernance(true)
    .withMaxConnectionsPerIp(5)
    .withAcceptRatePerIp(ratePerSec = 100, burst = 100)
    .withRequestRatePerIp(ratePerSec = 1000, burst = 1000)
    .withTrackedIpCap(1000)

  test("per-IP cap: excess connections from one IP are dropped via TCP close") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val accepted = scala.collection.mutable.ListBuffer[Socket]()
            try {
              val attempts = cfg.maxConnectionsPerIp + 5
              (1 to attempts).foreach { _ =>
                val s = new Socket()
                s.setSoTimeout(1000)
                try {
                  s.connect(new java.net.InetSocketAddress(address.host, address.port), 1000)
                  accepted += s
                } catch { case _: Exception => Try(s.close()): Unit }
              }

              val ok = new AtomicInteger(0)
              val eof = new AtomicInteger(0)
              accepted.foreach { s =>
                try {
                  val out = s.getOutputStream
                  out.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
                  out.flush()
                  val in = s.getInputStream
                  val firstByte = in.read()
                  if firstByte < 0 then eof.incrementAndGet(): Unit
                  else {
                    val buf = new Array[Byte](256)
                    buf(0) = firstByte.toByte
                    in.read(buf, 1, 255): Unit
                    val resp = new String(buf, "US-ASCII")
                    if resp.startsWith("HTTP/1.1 200") then ok.incrementAndGet(): Unit
                  }
                } catch {
                  case _: IOException => eof.incrementAndGet(): Unit
                } finally Try(s.close()): Unit
              }

              assertEquals(
                ok.get(),
                cfg.maxConnectionsPerIp,
                s"expected exactly ${cfg.maxConnectionsPerIp} accepted, got ok=${ok.get()} eof=${eof.get()}"
              )
              assert(eof.get() >= 5, s"expected ≥5 rejected with EOF, got ${eof.get()}")
            } finally {
              accepted.foreach(s => Try(s.close()))
            }
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("per-IP cap: isolation — other IPs unaffected") {
    requireHostileMode()

    val alias = Try(new java.net.InetSocketAddress("127.0.0.2", 0))
    assume(alias.isSuccess, "127.0.0.2 loopback alias not available on this host")

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val fromFirstIp = (1 to cfg.maxConnectionsPerIp).map { _ =>
              val s = new Socket()
              s.connect(new java.net.InetSocketAddress(address.host, address.port), 1000)
              s.setSoTimeout(1000)
              s.getOutputStream.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n".getBytes)
              s.getOutputStream.flush()
              s
            }.toList
            Thread.sleep(100)

            try {
              val s2 = new Socket()
              val localBind = new java.net.InetSocketAddress("127.0.0.2", 0)
              s2.bind(localBind)
              s2.setSoTimeout(2000)
              try {
                s2.connect(new java.net.InetSocketAddress(address.host, address.port), 1000)
                s2.getOutputStream.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
                s2.getOutputStream.flush()
                val in = s2.getInputStream
                val firstByte = in.read()
                assert(firstByte >= 0, "Connection from 127.0.0.2 was closed — per-IP isolation failed")
                val buf = new Array[Byte](256)
                buf(0) = firstByte.toByte
                in.read(buf, 1, 255): Unit
                val resp = new String(buf, "US-ASCII")
                assert(resp.startsWith("HTTP/1.1 200"), s"127.0.0.2 got: $resp")
              } finally Try(s2.close()): Unit
            } finally {
              fromFirstIp.foreach(s => Try(s.close()))
            }
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("per-IP cap: releasing connections lets new ones through") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val firstBatch = (1 to cfg.maxConnectionsPerIp).map { _ =>
              val s = new Socket(address.host, address.port)
              s.setSoTimeout(1000)
              s.getOutputStream.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
              s.getOutputStream.flush()
              val in = s.getInputStream
              while in.read() >= 0 do ()
              Try(s.close()): Unit
              s
            }.toList
            assertEquals(firstBatch.size, cfg.maxConnectionsPerIp)

            Thread.sleep(200)

            val secondBatch = (1 to cfg.maxConnectionsPerIp).map { _ =>
              val s = new Socket(address.host, address.port)
              s.setSoTimeout(1000)
              s.getOutputStream.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
              s.getOutputStream.flush()
              val in = s.getInputStream
              val firstByte = in.read()
              Try(s.close()): Unit
              firstByte >= 0
            }
            assertEquals(
              secondBatch.count(identity),
              cfg.maxConnectionsPerIp,
              "second batch should all succeed after first batch released"
            )
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
