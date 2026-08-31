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

/** Strict path validation (`strictPathValidation`).
  *
  * eru-http's default contract is byte-faithful: paths reach handlers un-decoded, un-normalized.
  * Handlers that do file-system or DB lookups from the path are responsible for normalization. The
  * opt-in `strictPathValidation` flag makes the server reject requests whose path contains any
  * control character (0x00–0x1F or 0x7F). Percent-decoding is deliberately NOT performed — a path
  * of `..%2f..%2f` is accepted (it's a legal byte sequence); handlers that dereference it are
  * responsible for rejecting traversal.
  *
  * Scenarios:
  *   1. strictPathValidation=true + path containing NUL (0x00) → 400.
  *   2. strictPathValidation=true + path containing raw CR (0x0D) → 400. (0x0A triggers an earlier
  *      parser layer; not testable via raw HTTP/1.1 here.)
  *   3. strictPathValidation=true + literal '..' → 200 (no normalization).
  *   4. strictPathValidation=true + percent-encoded NUL (`%00`) → 200 (byte-faithful, no decode).
  *   5. strictPathValidation=false (default) + NUL path → 200 (opt-in means opt-in).
  *   6. Regression: a normal valid path returns 200 under strict mode.
  */
class StrictPathSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  private val strictCfg = HttpServerConfig.localhost
    .withPort(0)
    .withStrictPathValidation(true)
    .copy(maxConnections = 64, acceptorThreads = 1)

  private val lenientCfg = HttpServerConfig.localhost
    .withPort(0)
    .copy(maxConnections = 64, acceptorThreads = 1)

  private def sendRawAndReadStatus(host: String, port: Int, raw: Array[Byte]): Option[Int] = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(2000)
      s.getOutputStream.write(raw)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      val statusLine = Option(in.readLine())
      statusLine.flatMap { line =>
        val parts = line.split(" ", 3)
        if parts.length >= 2 then parts(1).toIntOption else None
      }
    } finally Try(s.close()): Unit
  }

  /** Compose raw HTTP/1.1 request bytes with the given path bytes embedded verbatim — avoids
    * Scala-string quoting pitfalls when the path contains NUL / CR / other control chars.
    */
  private def rawRequestWithPathBytes(pathBytes: Array[Byte]): Array[Byte] = {
    val prefix = "GET ".getBytes("US-ASCII")
    val suffix = " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
    val out = new Array[Byte](prefix.length + pathBytes.length + suffix.length)
    System.arraycopy(prefix, 0, out, 0, prefix.length)
    System.arraycopy(pathBytes, 0, out, prefix.length, pathBytes.length)
    System.arraycopy(suffix, 0, out, prefix.length + pathBytes.length, suffix.length)
    out
  }

  test("strict path: NUL byte (0x00) in path returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(strictCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val pathBytes = Array[Byte]('/', 'f', 'o', 'o', 0x00.toByte, 'b', 'a', 'r')
            val status =
              sendRawAndReadStatus(address.host, address.port, rawRequestWithPathBytes(pathBytes))
            assertEquals(status, Some(400), s"NUL in path must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("strict path: bare CR (0x0D) in path returns 400") {
    requireHostileMode()

    HttpServer
      .scoped(strictCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val pathBytes = Array[Byte]('/', 'f', 'o', 'o', 0x0d.toByte, 'b', 'a', 'r')
            val status =
              sendRawAndReadStatus(address.host, address.port, rawRequestWithPathBytes(pathBytes))
            assertEquals(status, Some(400), s"bare CR in path must be rejected, got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("strict path: byte-faithful — literal '..' is accepted (not normalized)") {
    requireHostileMode()

    HttpServer
      .scoped(strictCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GET /foo/../bar HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"literal '..' must be accepted (byte-faithful); got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("strict path: percent-encoded NUL (%00) is accepted (no decoding)") {
    requireHostileMode()

    HttpServer
      .scoped(strictCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw = "GET /foo%00bar HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"percent-encoded NUL must be accepted; got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("strict path: default config is now strict — NUL in path answers 400") {
    requireHostileMode()

    // lenientCfg never sets the flag, so this exercises the DEFAULT (strict on by default).
    HttpServer
      .scoped(lenientCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val pathBytes = Array[Byte]('/', 'f', 'o', 'o', 0x00.toByte, 'b', 'a', 'r')
            val status =
              sendRawAndReadStatus(address.host, address.port, rawRequestWithPathBytes(pathBytes))
            assertEquals(status, Some(400), s"strict default must reject NUL in path; got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("strict path: explicit opt-out (withStrictPathValidation(false)) still accepts NUL") {
    requireHostileMode()

    val optOutCfg = lenientCfg.withStrictPathValidation(false)
    HttpServer
      .scoped(optOutCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val pathBytes = Array[Byte]('/', 'f', 'o', 'o', 0x00.toByte, 'b', 'a', 'r')
            val status =
              sendRawAndReadStatus(address.host, address.port, rawRequestWithPathBytes(pathBytes))
            assertEquals(status, Some(200), s"explicit opt-out must accept NUL; got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("strict path: regression — normal path still returns 200 under strict mode") {
    requireHostileMode()

    HttpServer
      .scoped(strictCfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val raw =
              "GET /api/v1/users?id=42 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
            val status = sendRawAndReadStatus(address.host, address.port, raw)
            assertEquals(status, Some(200), s"normal path must work under strict mode; got $status")
          }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
