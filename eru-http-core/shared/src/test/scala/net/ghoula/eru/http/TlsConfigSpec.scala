package net.ghoula.eru.http

import munit.FunSuite

class TlsConfigSpec extends FunSuite {

  // ===== TlsVersion Tests =====

  test("TlsVersion.TLSv1_2 - has correct value") {
    assertEquals(TlsVersion.TLSv1_2.value, "TLSv1.2")
  }

  test("TlsVersion.TLSv1_3 - has correct value") {
    assertEquals(TlsVersion.TLSv1_3.value, "TLSv1.3")
  }

  // ===== TlsConfig Default Tests =====

  test("TlsConfig.default - has secure defaults") {
    val config = TlsConfig.default

    assert(config.enabled, "TLS should be enabled by default")
    assertEquals(config.protocols, List(TlsVersion.TLSv1_3, TlsVersion.TLSv1_2))
    assert(!config.trustAll, "Should not trust all certificates by default")
    assert(config.verifyHostname, "Should verify hostname by default")
  }

  test("TlsConfig.default - prefer TLS 1.3 over TLS 1.2") {
    val config = TlsConfig.default

    assertEquals(config.protocols.head, TlsVersion.TLSv1_3)
    assertEquals(config.protocols.tail.head, TlsVersion.TLSv1_2)
  }

  // ===== TlsConfig Insecure Tests =====

  test("TlsConfig.insecure - trusts all certificates") {
    val config = TlsConfig.insecure

    assert(config.enabled, "TLS should still be enabled in insecure mode")
    assert(config.trustAll, "Insecure mode should trust all certificates")
    assert(!config.verifyHostname, "Insecure mode should not verify hostname")
  }

  test("TlsConfig.insecure - uses default protocols") {
    val config = TlsConfig.insecure

    assertEquals(config.protocols, List(TlsVersion.TLSv1_3, TlsVersion.TLSv1_2))
  }

  // ===== TlsConfig TLS 1.3 Only Tests =====

  test("TlsConfig.tls13Only - uses only TLS 1.3") {
    val config = TlsConfig.tls13Only

    assertEquals(config.protocols.length, 1)
    assertEquals(config.protocols.head, TlsVersion.TLSv1_3)
  }

  test("TlsConfig.tls13Only - maintains secure settings") {
    val config = TlsConfig.tls13Only

    assert(config.enabled, "TLS should be enabled")
    assert(!config.trustAll, "Should not trust all certificates")
    assert(config.verifyHostname, "Should verify hostname")
  }

  // ===== TlsConfig Disabled Tests =====

  test("TlsConfig.disabled - disables TLS") {
    val config = TlsConfig.disabled

    assert(!config.enabled, "TLS should be disabled")
  }

  test("TlsConfig.disabled - maintains other secure defaults") {
    val config = TlsConfig.disabled

    assert(!config.trustAll, "Should not trust all certificates even when disabled")
    assert(config.verifyHostname, "Should verify hostname even when disabled")
  }

  // ===== Custom TlsConfig Tests =====

  test("TlsConfig - custom configuration with TLS 1.2 only") {
    val config = TlsConfig(
      enabled = true,
      protocols = List(TlsVersion.TLSv1_2),
      trustAll = false,
      verifyHostname = true
    )

    assertEquals(config.protocols.length, 1)
    assertEquals(config.protocols.head, TlsVersion.TLSv1_2)
    assert(config.enabled)
    assert(!config.trustAll)
    assert(config.verifyHostname)
  }

  test("TlsConfig - custom configuration with both protocols reversed") {
    val config = TlsConfig(
      protocols = List(TlsVersion.TLSv1_2, TlsVersion.TLSv1_3)
    )

    assertEquals(config.protocols.head, TlsVersion.TLSv1_2)
    assertEquals(config.protocols.tail.head, TlsVersion.TLSv1_3)
  }

  test("TlsConfig - can disable hostname verification while keeping certificate validation") {
    val config = TlsConfig(
      trustAll = false,
      verifyHostname = false
    )

    assert(!config.trustAll, "Should validate certificates")
    assert(!config.verifyHostname, "Should not verify hostname")
  }

  // ===== TlsConfig Equality Tests =====

  test("TlsConfig - equality works correctly") {
    val config1 = TlsConfig.default
    val config2 = TlsConfig.default
    val config3 = TlsConfig.insecure

    assertEquals(config1, config2)
    assert(config1 != config3)
  }

  test("TlsConfig - copy works correctly") {
    val original = TlsConfig.default
    val modified = original.copy(trustAll = true)

    assert(!original.trustAll)
    assert(modified.trustAll)
    assertEquals(original.protocols, modified.protocols)
    assertEquals(original.enabled, modified.enabled)
  }

  // ===== TlsConfig Protocol Conversion Tests =====

  test("TlsConfig - protocols can be converted to string values") {
    val config = TlsConfig.default
    val protocolValues = config.protocols.map(_.value)

    assertEquals(protocolValues, List("TLSv1.3", "TLSv1.2"))
  }

  test("TlsConfig - single protocol converts correctly") {
    val config = TlsConfig.tls13Only
    val protocolValues = config.protocols.map(_.value)

    assertEquals(protocolValues, List("TLSv1.3"))
  }

  // ===== Security Configuration Tests =====

  test("TlsConfig - default is more secure than insecure") {
    val defaultConfig = TlsConfig.default
    val insecureConfig = TlsConfig.insecure

    assert(!defaultConfig.trustAll && insecureConfig.trustAll)
    assert(defaultConfig.verifyHostname && !insecureConfig.verifyHostname)
  }

  test("TlsConfig - tls13Only is most secure protocol-wise") {
    val config = TlsConfig.tls13Only

    // Only TLS 1.3, no fallback to older versions
    assertEquals(config.protocols, List(TlsVersion.TLSv1_3))
    assert(!config.trustAll)
    assert(config.verifyHostname)
  }
}
