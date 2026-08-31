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

/** `bodyReadTimeout`: the per-read deadline applies to request BODY reads, not just headers.
  *
  * The header phase is bounded per read; previously the body phase had no socket deadline at all,
  * so a client trickling a chunked body at one small chunk per couple of seconds held a fiber and
  * buffer indefinitely. This spec pins the fix: with `bodyReadTimeout = 1s`, a client that stalls
  * 2s mid-body is cut — the server answers 408 (the channel stays open through a socket-level
  * deadline) or closes, and never serves the trickle as a 200.
  */
class BodyReadTimeoutSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = req =>
    BodyDecoder[String]
      .decode(req.body)
      .mapError(HttpError.BodyDecodeError.apply)
      .flatMap(text => Eru.succeed(Response.ok(Body.text(s"got:${text.length}"))))

  private val cfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 16, acceptorThreads = 1, bodyReadTimeout = 1.second, maxRequestSize = 64 * 1024)

  test("chunked body stalled mid-body is cut by bodyReadTimeout") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(5000)
              val out = s.getOutputStream
              out.write(
                "POST /upload HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes("US-ASCII")
              )
              // First chunk arrives promptly (so the request is accepted and streaming starts).
              out.write("5\r\nhello\r\n".getBytes("US-ASCII"))
              out.flush()

              // Now stall 2s mid-body — beyond bodyReadTimeout (1s).
              Thread.sleep(2000)
              out.write("5\r\nworld\r\n".getBytes("US-ASCII"))
              out.write("0\r\n\r\n".getBytes("US-ASCII"))
              out.flush()

              // The server must have cut the connection: either a 408 response or EOF, and in
              // neither case the 200 the full trickle would have produced previously.
              val collected = new java.io.ByteArrayOutputStream()
              val buf = new Array[Byte](1024)
              var done = false
              var endedWithEof = false
              while !done do {
                val n =
                  try s.getInputStream.read(buf)
                  catch { case _: java.io.IOException => -1 }
                if n <= 0 then {
                  done = true
                  endedWithEof = n == -1
                } else collected.write(buf, 0, n)
              }
              val resp = collected.toString("UTF-8")
              val rejected = resp.isEmpty || resp.startsWith("HTTP/1.1 408") || endedWithEof
              assert(
                rejected && !resp.startsWith("HTTP/1.1 200"),
                s"trickled body must not be served as 200, got: ${resp.take(80)}"
              )
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("a body trickling faster than bodyReadTimeout is served normally") {
    requireHostileMode()

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(5000)
              val out = s.getOutputStream
              out.write(
                "POST /upload HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes("US-ASCII")
              )
              // Chunks every 250ms — well inside the 1s body deadline: legitimate progress.
              (1 to 6).foreach { i =>
                out.write(f"5\r\nchunk$i%03d!\r\n".getBytes("US-ASCII"))
                out.flush()
                Thread.sleep(250)
              }
              out.write("0\r\n\r\n".getBytes("US-ASCII"))
              out.flush()

              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val status = Option(in.readLine()).map(_.split(" ", 3)(1).toInt)
              assertEquals(status, Some(200), "progressing trickle must be served")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
