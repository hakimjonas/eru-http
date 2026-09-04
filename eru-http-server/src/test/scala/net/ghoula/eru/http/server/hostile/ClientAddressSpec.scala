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

/** `Request.clientAddress` resolution against the real server.
  *
  * The handler echoes the resolved client address, so the tests see exactly what a downstream
  * framework would see. The resolution policy must match `PerIpGovernor`'s (same helper), so these
  * tests mirror the XFF/PROXY scenarios:
  *   1. direct connection → `TcpPeer` with the loopback address;
  *   2. trusted TCP peer + `X-Forwarded-For` → `ForwardedFor` with the leftmost untrusted entry;
  *   3. all-XFF-entries-trusted → falls back to the connection-level address;
  *   4. untrusted TCP peer + `X-Forwarded-For` → the header is ignored (`TcpPeer`);
  *   5. PROXY v2 preamble (Optional mode) → `ProxyProtocol` with the preamble's client address.
  */
class ClientAddressSpec extends HostileTestBase {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val handler: RequestHandler = request => {
    val text = request.clientAddress.fold("none")(ca => s"${ca.source}/${ca.hostAddress}")
    Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.Text(text)))
  }

  private def cfg(trusted: List[Cidr], proxyMode: ProxyProtocolMode = ProxyProtocolMode.Off): HttpServerConfig =
    HttpServerConfig.localhost
      .withPort(0)
      .copy(maxConnections = 64, acceptorThreads = 1, proxyProtocolMode = proxyMode)
      .withTrustedProxies(trusted)

  private def sendAndReadBody(host: String, port: Int, request: String): String = {
    val s = new Socket(host, port)
    try {
      s.setSoTimeout(3000)
      s.getOutputStream.write(request.getBytes)
      s.getOutputStream.flush()
      val in = new BufferedReader(new InputStreamReader(s.getInputStream))
      var line: Option[String] = Option(in.readLine())
      var contentLength = 0
      while line.exists(_.nonEmpty) do {
        val lower = line.get.toLowerCase
        if lower.startsWith("content-length:") then
          contentLength = lower.dropWhile(!_.isDigit).takeWhile(_.isDigit).toIntOption.getOrElse(0)
        line = Option(in.readLine())
      }
      if contentLength > 0 then {
        val buf = new Array[Char](contentLength)
        var read = 0
        while read < contentLength do {
          val n = in.read(buf, read, contentLength - read)
          if n < 0 then fail("connection closed mid-body")
          read += n
        }
        new String(buf)
      } else ""
    } finally Try(s.close()): Unit
  }

  private def get(xff: Option[String] = None): String = {
    val xffHeader = xff.map(v => s"X-Forwarded-For: $v\r\n").getOrElse("")
    s"GET / HTTP/1.1\r\nHost: x\r\n${xffHeader}Connection: close\r\n\r\n"
  }

  /** Build a valid PROXY v2 AF_INET preamble declaring `src` as the real client (same encoding as
    * ProxyProtocolIntegrationSpec).
    */
  private def buildPreamble(src: String, dst: String, srcPort: Int, dstPort: Int): Array[Byte] = {
    val sig = Array(0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a).map(_.toByte)
    val payload = new Array[Byte](12)
    System.arraycopy(IpKey.parse(src).get.bytes, 0, payload, 0, 4)
    System.arraycopy(IpKey.parse(dst).get.bytes, 0, payload, 4, 4)
    payload(8) = ((srcPort >>> 8) & 0xff).toByte
    payload(9) = (srcPort & 0xff).toByte
    payload(10) = ((dstPort >>> 8) & 0xff).toByte
    payload(11) = (dstPort & 0xff).toByte
    val out = new Array[Byte](16 + payload.length)
    System.arraycopy(sig, 0, out, 0, 12)
    out(12) = 0x21.toByte // version=2, command=PROXY
    out(13) = 0x11.toByte // AF_INET | TCP
    out(14) = ((payload.length >>> 8) & 0xff).toByte
    out(15) = (payload.length & 0xff).toByte
    System.arraycopy(payload, 0, out, 16, payload.length)
    out
  }

  private def withServer[A](config: HttpServerConfig)(body: ServerAddress => Eru[HttpError, A]): Eru[HttpError, A] =
    HttpServer.scoped(config)(handler) { server =>
      server.start.flatMap(body)
    }

  test("clientAddress: direct connection is the TCP peer") {
    requireHostileMode()

    withServer(cfg(List.empty)) { addr =>
      Eru.effect {
        val body = sendAndReadBody(addr.host, addr.port, get())
        assertEquals(body, s"${ClientAddress.Source.TcpPeer}/127.0.0.1")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("clientAddress: trusted peer + XFF resolves the leftmost untrusted entry") {
    requireHostileMode()

    withServer(cfg(List(Cidr.unsafeParse("127.0.0.0/8")))) { addr =>
      Eru.effect {
        val body = sendAndReadBody(addr.host, addr.port, get(Some("203.0.113.7, 10.10.10.10")))
        assertEquals(body, s"${ClientAddress.Source.ForwardedFor}/203.0.113.7")

        // A first-hop proxy that is itself trusted: skip to the next untrusted entry.
        val throughTrusted = sendAndReadBody(addr.host, addr.port, get(Some("127.0.0.5, 203.0.113.9")))
        assertEquals(throughTrusted, s"${ClientAddress.Source.ForwardedFor}/203.0.113.9")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("clientAddress: all XFF entries trusted falls back to the connection address") {
    requireHostileMode()

    withServer(cfg(List(Cidr.unsafeParse("127.0.0.0/8")))) { addr =>
      Eru.effect {
        val body = sendAndReadBody(addr.host, addr.port, get(Some("127.0.0.5, 127.0.0.6")))
        assertEquals(body, s"${ClientAddress.Source.TcpPeer}/127.0.0.1")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("clientAddress: untrusted peer ignores XFF") {
    requireHostileMode()

    withServer(cfg(List(Cidr.unsafeParse("10.0.0.0/8")))) { addr =>
      Eru.effect {
        val body = sendAndReadBody(addr.host, addr.port, get(Some("203.0.113.7")))
        assertEquals(body, s"${ClientAddress.Source.TcpPeer}/127.0.0.1")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }

  test("clientAddress: PROXY v2 preamble declares ProxyProtocol source") {
    requireHostileMode()

    withServer(cfg(List.empty, ProxyProtocolMode.Optional)) { addr =>
      Eru.effect {
        val preamble = buildPreamble("198.51.100.23", "10.0.0.1", 12345, addr.port)
        val s = new Socket(addr.host, addr.port)
        val body = try {
          s.setSoTimeout(3000)
          s.getOutputStream.write(preamble)
          s.getOutputStream.write(get().getBytes)
          s.getOutputStream.flush()
          val in = new BufferedReader(new InputStreamReader(s.getInputStream))
          var line: Option[String] = Option(in.readLine())
          var contentLength = 0
          while line.exists(_.nonEmpty) do {
            val lower = line.get.toLowerCase
            if lower.startsWith("content-length:") then
              contentLength = lower.dropWhile(!_.isDigit).takeWhile(_.isDigit).toIntOption.getOrElse(0)
            line = Option(in.readLine())
          }
          val buf = new Array[Char](contentLength)
          var read = 0
          while read < contentLength do {
            val n = in.read(buf, read, contentLength - read)
            if n < 0 then fail("connection closed mid-body")
            read += n
          }
          new String(buf)
        } finally Try(s.close()): Unit
        assertEquals(body, s"${ClientAddress.Source.ProxyProtocol}/198.51.100.23")
      }.mapError(e => HttpError.NetworkError(s"test: ${e.getMessage}", Some(e)))
    }.assertSuccess
  }
}
