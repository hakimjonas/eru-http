package net.ghoula.eru.http.server

import munit.FunSuite

import java.io.ByteArrayInputStream

class ProxyProtocolSpec extends FunSuite {

  private def buildFrame(
    versionCommand: Int,
    familyProto: Int,
    payload: Array[Byte]
  ): Array[Byte] = {
    val out = new Array[Byte](16 + payload.length)
    System.arraycopy(ProxyProtocol.Signature, 0, out, 0, 12)
    out(12) = versionCommand.toByte
    out(13) = familyProto.toByte
    out(14) = ((payload.length >>> 8) & 0xff).toByte
    out(15) = (payload.length & 0xff).toByte
    System.arraycopy(payload, 0, out, 16, payload.length)
    out
  }

  private def ipv4Payload(
    src: Array[Byte],
    dst: Array[Byte],
    srcPort: Int,
    dstPort: Int
  ): Array[Byte] = {
    require(src.length == 4 && dst.length == 4, "IPv4 addresses must be 4 bytes")
    val out = new Array[Byte](12)
    System.arraycopy(src, 0, out, 0, 4)
    System.arraycopy(dst, 0, out, 4, 4)
    out(8) = ((srcPort >>> 8) & 0xff).toByte
    out(9) = (srcPort & 0xff).toByte
    out(10) = ((dstPort >>> 8) & 0xff).toByte
    out(11) = (dstPort & 0xff).toByte
    out
  }

  private def parseFromBytes(bytes: Array[Byte]): ProxyProtocol.ParseResult =
    ProxyProtocol.parse(Array.empty, new ByteArrayInputStream(bytes))

  test("parse AF_INET PROXY v2 frame extracts source IPv4") {
    val payload = ipv4Payload(
      src = Array(1, 2, 3, 4).map(_.toByte),
      dst = Array(10, 0, 0, 1).map(_.toByte),
      srcPort = 12345,
      dstPort = 80
    )
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x11, payload = payload)

    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.Ipv4(src, dst, sp, dp), consumed) =>
        assertEquals(src.getHostAddress, "1.2.3.4")
        assertEquals(dst.getHostAddress, "10.0.0.1")
        assertEquals(sp, 12345)
        assertEquals(dp, 80)
        assertEquals(consumed, 16 + 12)
      case other => fail(s"Expected Ipv4 parse result, got $other")
    }
  }

  test("parse AF_INET6 PROXY v2 frame extracts source IPv6") {
    val srcBytes: Array[Byte] = Array(
      0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01
    ).map(_.toByte)
    val dstBytes: Array[Byte] = Array(
      0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01
    ).map(_.toByte)
    val payload = new Array[Byte](36)
    System.arraycopy(srcBytes, 0, payload, 0, 16)
    System.arraycopy(dstBytes, 0, payload, 16, 16)
    payload(32) = 0x30; payload(33) = 0x39
    payload(34) = 0; payload(35) = 0x50

    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x21, payload = payload)
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.Ipv6(src, _, sp, dp), consumed) =>
        assertEquals(src.getHostAddress, "2001:db8:0:0:0:0:0:1")
        assertEquals(sp, 12345)
        assertEquals(dp, 80)
        assertEquals(consumed, 16 + 36)
      case other => fail(s"Expected Ipv6 parse result, got $other")
    }
  }

  test("parse LOCAL command returns NoAddr regardless of family payload") {
    val frame = buildFrame(
      versionCommand = 0x20,
      familyProto = 0x11,
      payload = ipv4Payload(
        src = Array(1, 2, 3, 4).map(_.toByte),
        dst = Array(5, 6, 7, 8).map(_.toByte),
        srcPort = 1,
        dstPort = 2
      )
    )
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.NoAddr, 28) => ()
      case other => fail(s"Expected LOCAL→NoAddr, got $other")
    }
  }

  test("AF_UNSPEC (family 0) returns NoAddr") {
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x00, payload = Array.empty)
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.NoAddr, 16) => ()
      case other => fail(s"Expected NoAddr for AF_UNSPEC, got $other")
    }
  }

  test("AF_UNIX returns NoAddr") {
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x31, payload = new Array[Byte](216))
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.NoAddr, _) => ()
      case other => fail(s"Expected NoAddr for AF_UNIX, got $other")
    }
  }

  test("extra TLV bytes after AF_INET core are ignored, consumed tracks total") {
    val core = ipv4Payload(
      src = Array(1, 2, 3, 4).map(_.toByte),
      dst = Array(10, 0, 0, 1).map(_.toByte),
      srcPort = 1,
      dstPort = 2
    )
    val tlv = Array[Byte](0x03, 0, 5, 'h', 'e', 'l', 'l', 'o')
    val payload = core ++ tlv
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x11, payload = payload)

    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.Ipv4(src, _, _, _), consumed) =>
        assertEquals(src.getHostAddress, "1.2.3.4")
        assertEquals(consumed, 16 + 20)
      case other => fail(s"Expected Ipv4 with TLV, got $other")
    }
  }

  test("plain HTTP bytes are rejected as NotProxyProtocol") {
    val http = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("US-ASCII")
    parseFromBytes(http) match {
      case ProxyProtocol.ParseResult.NotProxyProtocol(peeked) =>
        assertEquals(peeked.length, 12)
        assertEquals(new String(peeked, "US-ASCII"), "GET / HTTP/1")
      case other => fail(s"Expected NotProxyProtocol for HTTP bytes, got $other")
    }
  }

  test("random binary data that doesn't start with signature → NotProxyProtocol") {
    val bytes = new Array[Byte](32)
    java.util.Arrays.fill(bytes, 0xab.toByte)
    parseFromBytes(bytes) match {
      case ProxyProtocol.ParseResult.NotProxyProtocol(peeked) =>
        assertEquals(peeked.length, 12)
      case other => fail(s"Expected NotProxyProtocol, got $other")
    }
  }

  test("bad version (not 2) is Invalid") {
    val frame = buildFrame(versionCommand = 0x11, familyProto = 0x11, payload = new Array[Byte](12))
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Invalid(msg) => assert(msg.contains("version"))
      case other => fail(s"Expected Invalid for bad version, got $other")
    }
  }

  test("bad command (neither LOCAL nor PROXY) is Invalid") {
    val frame = buildFrame(versionCommand = 0x25, familyProto = 0x11, payload = new Array[Byte](12))
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Invalid(msg) => assert(msg.contains("command"))
      case other => fail(s"Expected Invalid for bad command, got $other")
    }
  }

  test("oversize length is Invalid (malicious length=65535 rejected)") {
    val head = new Array[Byte](16)
    System.arraycopy(ProxyProtocol.Signature, 0, head, 0, 12)
    head(12) = 0x21
    head(13) = 0x11
    head(14) = 0xff.toByte
    head(15) = 0xff.toByte
    parseFromBytes(head) match {
      case ProxyProtocol.ParseResult.Invalid(msg) => assert(msg.contains("length"))
      case other => fail(s"Expected Invalid for oversize length, got $other")
    }
  }

  test("AF_INET with payload < 12 bytes is Invalid") {
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x11, payload = new Array[Byte](8))
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Invalid(msg) => assert(msg.contains("too short"))
      case other => fail(s"Expected Invalid for short AF_INET, got $other")
    }
  }

  test("unknown address family is Invalid") {
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x71, payload = new Array[Byte](12))
    parseFromBytes(frame) match {
      case ProxyProtocol.ParseResult.Invalid(msg) => assert(msg.contains("family"))
      case other => fail(s"Expected Invalid for unknown family, got $other")
    }
  }

  test("truncated frame (EOF mid-payload) is Invalid") {
    val head = new Array[Byte](16)
    System.arraycopy(ProxyProtocol.Signature, 0, head, 0, 12)
    head(12) = 0x21
    head(13) = 0x11
    head(14) = 0x00
    head(15) = 0x0c
    parseFromBytes(head) match {
      case ProxyProtocol.ParseResult.Invalid(msg) => assert(msg.contains("read failed") || msg.contains("truncated"))
      case other => fail(s"Expected Invalid for truncated frame, got $other")
    }
  }

  test("parse consumes exactly header + payload bytes, no more") {
    val payload = ipv4Payload(
      src = Array(1, 2, 3, 4).map(_.toByte),
      dst = Array(10, 0, 0, 1).map(_.toByte),
      srcPort = 1,
      dstPort = 2
    )
    val proxyFrame = buildFrame(versionCommand = 0x21, familyProto = 0x11, payload = payload)
    val httpAfter = "GET / HTTP/1.1\r\n\r\n".getBytes("US-ASCII")
    val combined = proxyFrame ++ httpAfter

    val stream = new ByteArrayInputStream(combined)
    val result = ProxyProtocol.parse(Array.empty, stream)

    result match {
      case ProxyProtocol.ParseResult.Parsed(_: ProxyProtocol.Ipv4, consumed) =>
        assertEquals(consumed, 28)
        assertEquals(stream.available(), httpAfter.length)
      case other => fail(s"Expected Ipv4 parse result, got $other")
    }
  }

  test("parse accepts already-peeked bytes and continues from stream") {
    val payload = ipv4Payload(
      src = Array(1, 2, 3, 4).map(_.toByte),
      dst = Array(10, 0, 0, 1).map(_.toByte),
      srcPort = 1,
      dstPort = 2
    )
    val frame = buildFrame(versionCommand = 0x21, familyProto = 0x11, payload = payload)
    val peeked = frame.take(5)
    val remaining = frame.drop(5)

    ProxyProtocol.parse(peeked, new ByteArrayInputStream(remaining)) match {
      case ProxyProtocol.ParseResult.Parsed(ProxyProtocol.Ipv4(src, _, _, _), 28) =>
        assertEquals(src.getHostAddress, "1.2.3.4")
      case other => fail(s"Expected Ipv4 with partial peek, got $other")
    }
  }

  test("signature check returns NotProxyProtocol before consuming any additional stream bytes") {
    val badSig = new Array[Byte](12)
    badSig(0) = 'G'; badSig(1) = 'E'; badSig(2) = 'T'; badSig(3) = ' '

    val remainingStream = new ByteArrayInputStream("rest-of-http".getBytes("US-ASCII"))
    ProxyProtocol.parse(badSig, remainingStream) match {
      case ProxyProtocol.ParseResult.NotProxyProtocol(peeked) =>
        assertEquals(peeked.toList, badSig.toList)
        assertEquals(remainingStream.available(), "rest-of-http".length)
      case other => fail(s"Expected NotProxyProtocol without further reads, got $other")
    }
  }

  test("sanity: Signature bytes match HAProxy spec") {
    val expected = Array[Byte](0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a)
    assertEquals(ProxyProtocol.Signature.toList, expected.toList)
    assertEquals(ProxyProtocol.Signature.length, 12)
  }
}
