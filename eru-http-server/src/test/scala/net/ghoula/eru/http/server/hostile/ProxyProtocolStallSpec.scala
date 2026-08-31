package net.ghoula.eru.http.server.hostile

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Hostile: a peer that stalls mid-PROXY-preamble must not park the acceptor.
  *
  * PROXY parsing runs on the accept-loop virtual thread with a blocking read that has no socket
  * read timeout. A peer that sends part of the 12-byte signature and then stalls would park that
  * acceptor forever; with `acceptorThreads = 1` that is the whole server. This spec pins the
  * mitigation:
  *
  *   1. While one peer stalls after 11 of 12 signature bytes, a well-behaved peer with a complete
  *      preamble still gets served (the acceptor is not parked past `proxyHandshakeTimeout`).
  *   2. The stalled peer is reaped: its socket is closed by the server once the window expires.
  */
class ProxyProtocolStallSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val signature = Array(0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a).map(_.toByte)

  private def buildPreamble(dstPort: Int): Array[Byte] = {
    val versionCommand = 0x21.toByte // version=2, command=PROXY
    val familyProto = 0x11.toByte // AF_INET | TCP
    val payload = new Array[Byte](12)
    val src = Array[Byte](127, 0, 0, 1)
    System.arraycopy(src, 0, payload, 0, 4)
    System.arraycopy(src, 0, payload, 4, 4)
    payload(8) = 0
    payload(9) = 1
    payload(10) = ((dstPort >>> 8) & 0xff).toByte
    payload(11) = (dstPort & 0xff).toByte

    val out = new Array[Byte](16 + payload.length)
    System.arraycopy(signature, 0, out, 0, 12)
    out(12) = versionCommand
    out(13) = familyProto
    out(14) = ((payload.length >>> 8) & 0xff).toByte
    out(15) = (payload.length & 0xff).toByte
    System.arraycopy(payload, 0, out, 16, payload.length)
    out
  }

  private def readStatusLine(s: Socket): Option[Int] = Try {
    val in = new BufferedReader(new InputStreamReader(s.getInputStream))
    Option(in.readLine()).map(_.split(" ", 3)(1).toInt)
  }.toOption.flatten

  test("a peer stalling mid-preamble does not starve well-behaved clients") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(acceptorThreads = 1, proxyHandshakeTimeout = 1.second)
      .withProxyProtocolMode(ProxyProtocolMode.Required)

    val server = HttpServer.create(cfg, handler).assertSuccess
    val address = server.start.assertSuccess

    val stall = new Socket(address.host, address.port)
    try {
      stall.getOutputStream.write(signature.take(11))
      stall.getOutputStream.flush()

      // A well-behaved peer completes its full flow while the stall holds. With a parked acceptor
      // this times out at soTimeout; with the bounded preamble read it is served promptly.
      val good = new Socket(address.host, address.port)
      good.setSoTimeout(3000)
      good.getOutputStream.write(buildPreamble(address.port))
      good.getOutputStream.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes)
      good.getOutputStream.flush()
      val served = readStatusLine(good)
      Try(good.close()): Unit

      assertEquals(served, Some(200), "good client starved while a peer stalled mid-preamble")

      // The stalled peer is reaped: the server closes its socket within the window.
      stall.setSoTimeout(4000)
      val reaped = Try(stall.getInputStream.read()).toOption.map(_ == -1).getOrElse(true)
      assert(reaped, "stalled peer socket was not closed by the server")
    } finally {
      Try(stall.close()): Unit
      server.shutdown.assertSuccess
    }
  }
}
