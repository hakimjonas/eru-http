package net.ghoula.eru.http.server

import munit.FunSuite

import java.net.InetAddress

class IpKeySpec extends FunSuite {

  test("IPv4 parse round-trips to dotted-decimal") {
    val key = IpKey.parse("192.0.2.1").get
    assert(key.isIpv4)
    assertEquals(key.toString, "192.0.2.1")
    assertEquals(key.bytes.toList, List[Byte](192.toByte, 0, 2, 1))
  }

  test("IPv6 parse round-trips") {
    val key = IpKey.parse("2001:db8::1").get
    assert(!key.isIpv4)
    assertEquals(key.bytes.length, 16)
  }

  test("invalid input returns None") {
    assertEquals(IpKey.parse("not-an-ip"), None)
    assertEquals(IpKey.parse(""), None)
  }

  test("equals + hashCode are content-based (safe as HashMap key)") {
    val a = IpKey.parse("10.0.0.1").get
    val b = IpKey.parse("10.0.0.1").get
    val c = IpKey.parse("10.0.0.2").get
    assert(a == b, "equal IPs must be equal")
    assert(a.hashCode == b.hashCode, "equal IPs must hash-equal")
    assert(a != c)
  }

  test("IPv4 and IPv6 with same prefix bytes do NOT collide") {
    val v4 = IpKey.parse("10.0.0.1").get
    val v6 = IpKey.parse("::a00:1").get
    assert(v4 != v6)
  }

  test("bytes() returns a defensive copy") {
    val key = IpKey.parse("1.2.3.4").get
    val bytes = key.bytes
    bytes(0) = 99.toByte
    assertEquals(key.toString, "1.2.3.4")
  }

  test("fromInetAddress matches parse") {
    val a = IpKey.fromInetAddress(InetAddress.getByName("8.8.8.8"))
    val b = IpKey.parse("8.8.8.8").get
    assertEquals(a, b)
  }

  test("fromBytes rejects invalid length") {
    intercept[IllegalArgumentException](IpKey.fromBytes(new Array[Byte](5)))
    intercept[IllegalArgumentException](IpKey.fromBytes(new Array[Byte](0)))
  }
}
