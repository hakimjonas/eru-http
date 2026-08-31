package net.ghoula.eru.http.server.hostile

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** Acceptor resilience.
  *
  * The mechanism: `Eru.forever` stops its fiber on the first iteration failure (verified in
  * `foreverStopsOnFailure` below), so before the acceptor was hardened a failed socket config — for
  * instance a peer that closed between `accept()` and the server's setup — would have ended
  * accepting permanently. The hardened acceptor contains every iteration failure: log, close the
  * owned socket, release acquired resources, brief backoff, keep accepting.
  *
  * The vanish-burst scenario could NOT reproduce the death pre-fix (the accept-to-config window is
  * far smaller than a client's close RTT, so no iteration failed in practice) — it is kept as a
  * survival pin for the vanish path, and the mechanism test pins the reason the fix matters.
  */
class AcceptorResilienceSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 512, acceptorThreads = 1)

  private def get(host: String, port: Int): Option[Int] = Try {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(3000)
      s.getOutputStream.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      Option(in.readLine()).map(_.split(" ", 3)(1).toInt)
    } finally Try(s.close()): Unit
  }.toOption.flatten

  test("Eru.forever stops on iteration failure (the mechanism the hardened acceptor guards against)") {
    var ran = 0
    val loop = Eru.forever(Eru.effect {
      ran += 1
      if ran == 3 then throw new RuntimeException("iteration failure")
      ()
    })
    loop.attempt.unsafeRunSync()
    assertEquals(ran, 3, "Eru.forever must stop at the first failing iteration")
  }

  test("acceptor survives a burst of peers that vanish before socket setup (survival pin)") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            // 40 peers that connect and close immediately. This did not fail the un-hardened loop (the
            // accept-to-config window is too small to lose the race), so this is a survival pin
            // for the vanish path, not a reproduction of the death.
            (1 to 40).foreach { _ =>
              Try {
                val s = new Socket(address.host, address.port)
                s.setSoTimeout(100)
                s.close()
              }
            }

            // The acceptor must still be alive and serving.
            (1 to 5).foreach { i =>
              val status = get(address.host, address.port)
              assertEquals(status, Some(200), s"acceptor dead after vanish burst (request $i of 5)")
            }
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
