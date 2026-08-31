package net.ghoula.eru.http.server.hostile

import java.io.{BufferedReader, InputStreamReader}
import java.net.Socket
import scala.concurrent.duration.*
import scala.util.Try

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.hostile.HostileTestBase
import net.ghoula.eru.http.server.*
import net.ghoula.eru.http.server.TestHelpers.*
import net.ghoula.eru.prelude.*

/** HTTP/1.1 keep-alive pipelining, exercised end-to-end over a real socket.
  *
  * `BufferedSocketReader.reset()` compacts (not clears) the reader's internal buffer, preserving
  * any bytes of request N+1 already written in the same TCP segment as request N. This only matters
  * when a pipelined client writes request N+1 before request N is fully processed:
  * `BufferedSocketReaderSpec` covers the reader in isolation, while `HttpServerIntegrationSpec` and
  * `HttpServerSpec` exercise sequential keep-alive (request → response → request), which never puts
  * two requests in the reader buffer simultaneously.
  *
  * Scenario 1 writes 10 full requests in one `out.write(...)` call before reading any response; the
  * server must respond to all of them 1:1 in order (RFC 9112 §9.3) — otherwise requests 2..N are
  * dropped silently. Scenario 2 spreads 100 requests over 5 batches with brief 5ms pauses (well
  * under `idleTimeout`, so the connection stays open) so the reader buffer interleaves partial and
  * full requests. Scenario 3 writes request 1 plus the first half of request 2, waits 100ms (well
  * under `readHeaderTimeout`, letting `reset()` run), then writes the rest of request 2 — proving
  * partial next-request bytes survive `reset()`.
  */
class KeepAlivePipelineSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val config = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 64)
    .withReadHeaderTimeout(5.seconds)
    .withIdleTimeout(10.seconds)

  private val handler: RequestHandler = req =>
    Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text(req.uri.path)))

  /** Build a fully-formed HTTP/1.1 request with a body-less GET for path `/p-$i`. All requests use
    * `Connection: keep-alive`.
    */
  private def buildRequest(path: String, closeAfter: Boolean): Array[Byte] = {
    val conn = if closeAfter then "close" else "keep-alive"
    s"GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: $conn\r\n\r\n".getBytes("UTF-8")
  }

  /** Parse exactly N responses from the given reader in order. Returns (status, body) pairs. */
  private def readResponses(in: BufferedReader, expected: Int): List[(Int, String)] = {
    val out = scala.collection.mutable.ListBuffer.empty[(Int, String)]
    (1 to expected).foreach { idx =>
      val statusLine = Option(in.readLine()).getOrElse(
        fail(s"EOF before response #$idx (after ${out.size} successful responses)")
      )
      val parts = statusLine.split(" ", 3)
      assert(parts.length >= 2, s"Malformed status line for response #$idx: $statusLine")
      val status = parts(1).toInt

      var contentLength = 0
      var continue = true
      while continue do {
        Option(in.readLine()) match {
          case Some(line) if line.nonEmpty =>
            val colonIdx = line.indexOf(':')
            if colonIdx > 0 then {
              val name = line.substring(0, colonIdx).trim.toLowerCase
              val value = line.substring(colonIdx + 1).trim
              if name == "content-length" then contentLength = value.toInt
            }
          case _ => continue = false
        }
      }

      val body =
        if contentLength <= 0 then ""
        else {
          val buf = new Array[Char](contentLength)
          var total = 0
          while total < contentLength do {
            val n = in.read(buf, total, contentLength - total)
            if n == -1 then fail(s"EOF while reading body of response #$idx ($total/$contentLength bytes)")
            total += n
          }
          new String(buf, 0, total)
        }

      out += ((status, body))
    }
    out.toList
  }

  test("Pipelining: 10 requests written in one flush all receive responses in order") {
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

              val requestCount = 10
              val paths = (1 to requestCount).map(i => s"/p-$i").toList

              val lastIdx = paths.length - 1
              val allBytes = paths.zipWithIndex.flatMap { case (p, idx) =>
                buildRequest(p, closeAfter = idx == lastIdx).toList
              }.toArray
              out.write(allBytes)
              out.flush()

              val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
              val responses = readResponses(in, requestCount)

              assertEquals(responses.length, requestCount)
              responses.zip(paths).zipWithIndex.foreach { case (((status, body), expectedPath), idx) =>
                assertEquals(status, 200, s"response #${idx + 1} status")
                assertEquals(body, expectedPath, s"response #${idx + 1} body")
              }
            } finally Try(socket.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("Pipelining: 100 requests across 5 chunked writes with jitter all delivered in order") {
    requireHostileMode()

    HttpServer
      .scoped(config)(handler) { server =>
        for {
          address <- server.start
          _ <- Eru.effect {
            val socket = new Socket(address.host, address.port)
            try {
              socket.setSoTimeout(10_000)
              val out = socket.getOutputStream

              val requestCount = 100
              val paths = (1 to requestCount).map(i => s"/j-$i").toList

              val batchSize = requestCount / 5
              paths.grouped(batchSize).zipWithIndex.foreach { case (batch, batchIdx) =>
                val isLastBatch = batchIdx == 4
                batch.zipWithIndex.foreach { case (p, idxInBatch) =>
                  val isLastOverall = isLastBatch && (idxInBatch == batch.length - 1)
                  out.write(buildRequest(p, closeAfter = isLastOverall))
                }
                out.flush()
                if !isLastBatch then Thread.sleep(5)
              }

              val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
              val responses = readResponses(in, requestCount)

              assertEquals(responses.length, requestCount)
              responses.zip(paths).zipWithIndex.foreach { case (((status, body), expectedPath), idx) =>
                assertEquals(status, 200, s"response #${idx + 1} status")
                assertEquals(body, expectedPath, s"response #${idx + 1} body — ordering broken")
              }
            } finally Try(socket.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }

  test("Pipelining: partial next-request bytes survive reset() and complete on next read") {
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
              val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))

              val req1 = buildRequest("/first", closeAfter = false)
              val req2 = buildRequest("/second", closeAfter = true)
              val req2Half1 = req2.slice(0, req2.length / 2)
              val req2Half2 = req2.slice(req2.length / 2, req2.length)

              out.write(req1)
              out.write(req2Half1)
              out.flush()

              Thread.sleep(100)

              out.write(req2Half2)
              out.flush()

              val responses = readResponses(in, 2)
              assertEquals(responses.length, 2)
              assertEquals(responses(0), (200, "/first"))
              assertEquals(responses(1), (200, "/second"), "request 2 bytes survived reset()")
            } finally Try(socket.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"Test error: ${e.getMessage}", Some(e)))
        } yield ()
      }
      .assertSuccess
  }
}
