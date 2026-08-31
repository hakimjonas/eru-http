package net.ghoula.eru.http.server

import munit.FunSuite

import java.net.InetAddress

class CidrSpec extends FunSuite {

  test("parse valid IPv4 CIDR") {
    val cidr = Cidr.parse("10.0.0.0/8").toOption.get
    assertEquals(cidr.toString, "10.0.0.0/8")
  }

  test("parse valid IPv6 CIDR") {
    val cidr = Cidr.parse("2001:db8::/32").toOption.get
    assertEquals(cidr.prefixBits, 32)
  }

  test("IPv4 /0 accepts everything") {
    val cidr = Cidr.parse("0.0.0.0/0").toOption.get
    assert(cidr.contains(InetAddress.getByName("1.2.3.4")))
    assert(cidr.contains(InetAddress.getByName("255.255.255.255")))
    assert(!cidr.contains(InetAddress.getByName("::1")))
  }

  test("IPv4 /32 only matches exact address") {
    val cidr = Cidr.parse("1.2.3.4/32").toOption.get
    assert(cidr.contains(InetAddress.getByName("1.2.3.4")))
    assert(!cidr.contains(InetAddress.getByName("1.2.3.5")))
  }

  test("IPv4 containment — typical private range") {
    val cidr = Cidr.parse("10.0.0.0/8").toOption.get
    assert(cidr.contains(InetAddress.getByName("10.0.0.1")))
    assert(cidr.contains(InetAddress.getByName("10.255.255.255")))
    assert(!cidr.contains(InetAddress.getByName("11.0.0.0")))
    assert(!cidr.contains(InetAddress.getByName("9.255.255.255")))
  }

  test("IPv4 partial-byte prefix /12") {
    val cidr = Cidr.parse("172.16.0.0/12").toOption.get
    assert(cidr.contains(InetAddress.getByName("172.16.0.0")))
    assert(cidr.contains(InetAddress.getByName("172.31.255.255")))
    assert(!cidr.contains(InetAddress.getByName("172.32.0.0")))
    assert(!cidr.contains(InetAddress.getByName("172.15.255.255")))
  }

  test("IPv6 /64 prefix containment") {
    val cidr = Cidr.parse("2001:db8::/64").toOption.get
    assert(cidr.contains(InetAddress.getByName("2001:db8::1")))
    assert(cidr.contains(InetAddress.getByName("2001:db8::ffff")))
    assert(!cidr.contains(InetAddress.getByName("2001:db9::1")))
  }

  test("IPv4 and IPv6 CIDRs do not cross-match") {
    val v4 = Cidr.parse("10.0.0.0/8").toOption.get
    assert(!v4.contains(InetAddress.getByName("::1")))
    val v6 = Cidr.parse("::/0").toOption.get
    assert(!v6.contains(InetAddress.getByName("10.0.0.1")))
  }

  test("rejects host bits set") {
    assert(Cidr.parse("10.0.0.1/8").isLeft)
    assert(Cidr.parse("172.17.0.0/12").isLeft)
    assert(Cidr.parse("172.16.0.0/12").isRight)
  }

  test("rejects invalid prefix") {
    assert(Cidr.parse("10.0.0.0/33").isLeft)
    assert(Cidr.parse("10.0.0.0/-1").isLeft)
    assert(Cidr.parse("::/129").isLeft)
    assert(Cidr.parse("10.0.0.0/abc").isLeft)
    assert(Cidr.parse("10.0.0.0").isLeft)
    assert(Cidr.parse("10.0.0.0/").isLeft)
  }

  test("rejects invalid IP") {
    assert(Cidr.parse("not-an-ip/24").isLeft)
    assert(Cidr.parse("/24").isLeft)
  }

  test("contains accepts IpKey") {
    val cidr = Cidr.parse("10.0.0.0/8").toOption.get
    assert(cidr.contains(IpKey.parse("10.1.2.3").get))
    assert(!cidr.contains(IpKey.parse("11.0.0.0").get))
  }

  test("unsafeParse throws on invalid input") {
    intercept[IllegalArgumentException](Cidr.unsafeParse("10.0.0.0/33"))
  }

  test("unsafeParse succeeds on valid input") {
    val cidr = Cidr.unsafeParse("127.0.0.0/8")
    assert(cidr.contains(InetAddress.getByName("127.0.0.1")))
  }
}
