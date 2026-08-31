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

/** Hostile test A.12: PROXY protocol v2 integration with the real server.
  *
  * Complements the unit-level `ProxyProtocolSpec` by driving the full accept path: preamble →
  * gateConnection → per-IP governance → HTTP response. Validates:
  *   1. `Required` mode accepts a valid PROXY v2 preamble and uses the carried client IP for per-IP
  *      governance (distinct XFF-carried IPs have independent buckets).
  *   2. `Required` mode REJECTS a plain HTTP request (no preamble) via TCP close.
  *   3. `Optional` mode serves a plain HTTP request normally (fallback to TCP peer).
  *   4. `Off` mode (default) does NOT interpret preamble bytes as PROXY — the "PROXY-looking" bytes
  *      sent by a naive client are treated as HTTP and rejected at the HTTP parser.
  */
class ProxyProtocolIntegrationSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = _ => Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text("ok")))

  /** Build a valid PROXY v2 AF_INET preamble declaring `src` as the real client. */
  private def buildPreamble(src: String, dst: String, srcPort: Int, dstPort: Int): Array[Byte] = {
    val sig = Array(0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a).map(_.toByte)
    val versionCommand = 0x21.toByte // version=2, command=PROXY
    val familyProto = 0x11.toByte // AF_INET | TCP
    val payload = new Array[Byte](12)
    val srcBytes = IpKey.parse(src).get.bytes
    val dstBytes = IpKey.parse(dst).get.bytes
    System.arraycopy(srcBytes, 0, payload, 0, 4)
    System.arraycopy(dstBytes, 0, payload, 4, 4)
    payload(8) = ((srcPort >>> 8) & 0xff).toByte
    payload(9) = (srcPort & 0xff).toByte
    payload(10) = ((dstPort >>> 8) & 0xff).toByte
    payload(11) = (dstPort & 0xff).toByte

    val out = new Array[Byte](16 + payload.length)
    System.arraycopy(sig, 0, out, 0, 12)
    out(12) = versionCommand
    out(13) = familyProto
    out(14) = ((payload.length >>> 8) & 0xff).toByte
    out(15) = (payload.length & 0xff).toByte
    System.arraycopy(payload, 0, out, 16, payload.length)
    out
  }

  private def sendWithPreamble(
    host: String,
    port: Int,
    preamble: Array[Byte],
    httpRequest: String
  ): Int = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(3000)
      s.getOutputStream.write(preamble)
      s.getOutputStream.write(httpRequest.getBytes)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      Option(in.readLine()).fold(-1)(_.split(" ", 3)(1).toInt)
    } finally Try(s.close()): Unit
  }

  private def simpleGet = "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"

  // --------------------------------------------------------------------
  // A.12 scenario 1: Required mode + PROXY v2 → client IP from preamble
  // --------------------------------------------------------------------

  test("PROXY Required: valid preamble accepted; client IP used for per-IP governance") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
      .withProxyProtocolMode(ProxyProtocolMode.Required)
      .withPerIpGovernance(true)
      .withMaxConnectionsPerIp(100)
      .withAcceptRatePerIp(ratePerSec = 1000, burst = 1000)
      .withRequestRatePerIp(ratePerSec = 2, burst = 3)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            // Drain IP "7.7.7.7"'s bucket (burst=3).
            val ip7 = (1 to 4).map { _ =>
              sendWithPreamble(
                address.host,
                address.port,
                buildPreamble("7.7.7.7", "10.0.0.1", 12345, address.port),
                simpleGet
              )
            }
            assertEquals(ip7.take(3), Seq(200, 200, 200), s"first 3 for 7.7.7.7: $ip7")
            assertEquals(ip7(3), 429, s"4th for 7.7.7.7: $ip7")

            // Different IP via a fresh preamble — independent bucket, should succeed.
            val status8 = sendWithPreamble(
              address.host,
              address.port,
              buildPreamble("8.8.8.8", "10.0.0.1", 12345, address.port),
              simpleGet
            )
            assertEquals(status8, 200, "8.8.8.8 bucket must be independent")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // A.12 scenario 2: Required mode rejects plain HTTP (no preamble)
  // --------------------------------------------------------------------

  test("PROXY Required: connection with no preamble is rejected via TCP close") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
      .withProxyProtocolMode(ProxyProtocolMode.Required)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(2000)
              s.getOutputStream.write(simpleGet.getBytes)
              s.getOutputStream.flush()
              val in = s.getInputStream
              // Required mode must tear the connection down without sending any HTTP bytes.
              // Depending on OS/TCP timing the client may observe an orderly FIN (read → -1)
              // OR an abortive RST (read throws `SocketException: Connection reset`). Both are
              // acceptable; the invariant under test is "no HTTP response and connection
              // terminates promptly", NOT "specifically a FIN".
              val outcome =
                try Right(in.read())
                catch { case e: java.net.SocketException => Left(e) }
              outcome match {
                case Right(-1) => () // FIN — clean close, no bytes
                case Right(other) =>
                  fail(s"Required mode must not send any byte; got byte: $other (${other.toChar})")
                case Left(e) if e.getMessage.toLowerCase.contains("reset") => () // RST
                case Left(e) => fail(s"unexpected socket exception: ${e.getMessage}")
              }
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // A.12 scenario 3: Optional mode — plain HTTP served normally
  // --------------------------------------------------------------------

  test("PROXY Optional: plain HTTP request served normally (fallback to TCP peer)") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
      .withProxyProtocolMode(ProxyProtocolMode.Optional)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(2000)
              s.getOutputStream.write(simpleGet.getBytes)
              s.getOutputStream.flush()
              val in = new BufferedReader(new InputStreamReader(s.getInputStream))
              val statusLine = Option(in.readLine())
              assert(statusLine.exists(_.startsWith("HTTP/1.1 200")), s"got: ${statusLine.getOrElse("<EOF>")}")
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // A.12 scenario 4: Off mode — PROXY-like bytes are HTTP, get HTTP 400
  // --------------------------------------------------------------------

  test("PROXY Off (default): server does NOT interpret PROXY bytes; parser treats them as bad HTTP") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
    // default proxyProtocolMode = Off

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(2000)
              // Send the PROXY v2 signature as if it were HTTP. The server (in Off mode) treats
              // it as garbage HTTP — the parser should fail with 400 Bad Request (or close
              // connection). What MUST NOT happen is silent acceptance as PROXY.
              val preambleBytes = buildPreamble("1.2.3.4", "10.0.0.1", 12345, address.port)
              s.getOutputStream.write(preambleBytes)
              s.getOutputStream.flush()
              val in = s.getInputStream
              // Either 400 Bad Request or EOF — both are acceptable rejections. The point is
              // the server did NOT extract "1.2.3.4" as a client IP.
              val firstByte = in.read()
              // If we got bytes, parse status; if EOF, that's also fine.
              if firstByte < 0 then {
                // Server closed connection — acceptable.
                ()
              } else {
                val buf = new Array[Byte](512)
                buf(0) = firstByte.toByte
                val len = in.read(buf, 1, 511)
                val response = new String(buf, 0, 1 + math.max(0, len), "US-ASCII")
                assert(
                  response.startsWith("HTTP/1.1 400") || response.startsWith("HTTP/1.1 500"),
                  s"Off mode should reject PROXY bytes with 4xx/5xx or EOF; got: ${response.take(80)}"
                )
              }
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  // --------------------------------------------------------------------
  // CVE-2026-42055 class (nginx proxy_v2 buffer overflow): declared-length
  // abuse must never hang, over-read, or produce a response — the server
  // closes the connection.
  // --------------------------------------------------------------------

  test("PROXY Required: declared payload length exceeding the 1KB bound is rejected via TCP close") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
      .withProxyProtocolMode(ProxyProtocolMode.Required)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val sig = ProxyProtocol.Signature
              val header = new Array[Byte](4)
              header(0) = 0x21.toByte // v2, PROXY
              header(1) = 0x11.toByte // AF_INET, TCP
              header(2) = 0xff.toByte // length = 65535
              header(3) = 0xff.toByte
              s.getOutputStream.write(sig)
              s.getOutputStream.write(header)
              s.getOutputStream.flush()
              // The server must reject the oversized length immediately and close; no
              // HTTP response is expected, only EOF/reset within the timeout.
              val in = s.getInputStream
              val firstByte = in.read()
              if firstByte >= 0 then {
                fail("expected TCP close without a response for an oversized PROXY length")
              }
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("PROXY Required: declared payload length truncated by the peer is rejected via TCP close") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
      .withProxyProtocolMode(ProxyProtocolMode.Required)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val s = new Socket(address.host, address.port)
            try {
              s.setSoTimeout(3000)
              val sig = ProxyProtocol.Signature
              val header = new Array[Byte](4)
              header(0) = 0x21.toByte // v2, PROXY
              header(1) = 0x11.toByte // AF_INET, TCP
              header(2) = 0x02.toByte // declares 512 bytes of payload
              header(3) = 0x00.toByte
              s.getOutputStream.write(sig)
              s.getOutputStream.write(header)
              // Send only a fragment of the declared payload, then close the write side.
              s.getOutputStream.write(Array[Byte](1, 2, 3, 4))
              s.getOutputStream.flush()
              s.shutdownOutput()
              val in = s.getInputStream
              val firstByte = in.read()
              if firstByte >= 0 then {
                fail("expected TCP close without a response for a truncated PROXY payload")
              }
            } finally Try(s.close()): Unit
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }

  test("PROXY Required: TLV junk after the AF_INET core is ignored; HTTP is served normally") {
    requireHostileMode()

    val cfg = HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1)
      .withProxyProtocolMode(ProxyProtocolMode.Required)

    HttpServer
      .scoped(cfg)(handler) { server =>
        server.start.flatMap { address =>
          Eru.effect {
            val clean = buildPreamble("1.2.3.4", "10.0.0.1", 12345, address.port)
            // Append an 8-byte TLV (type 0x04, length 4, garbage) and adjust the declared length.
            val withTlv = java.util.Arrays.copyOf(clean, clean.length + 8)
            withTlv(14) = ((12 + 8) >>> 8).toByte
            withTlv(15) = ((12 + 8) & 0xff).toByte
            withTlv(clean.length) = 0x04
            withTlv(clean.length + 1) = 0x00
            withTlv(clean.length + 2) = 0x00
            withTlv(clean.length + 3) = 0x04
            val status = sendWithPreamble(address.host, address.port, withTlv, simpleGet)
            assertEquals(status, 200, s"TLV junk must be ignored and HTTP served, got $status")
          }.mapError(e => HttpError.NetworkError(s"test error: ${e.getMessage}", Some(e)))
        }
      }
      .assertSuccess
  }
}
