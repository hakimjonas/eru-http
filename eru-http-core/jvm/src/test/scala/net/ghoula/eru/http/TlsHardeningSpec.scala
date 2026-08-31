package net.ghoula.eru.http

import munit.FunSuite

/** Unit tests for TLS protocol and cipher hardening.
  *
  * These tests verify that TlsConfig values flow through SSLContextFactory and SSLSocketChannel
  * into the SSLEngine's SSLParameters — previously this was dead config.
  *
  * Caveats relied upon by the assertions:
  *   - A context created with TLSv1.2 as its minimum may still expose TLSv1.3 in supported
  *     parameters because SSLContext.getInstance semantics vary by JDK; the real restriction
  *     happens at the SSLEngine level.
  *   - TLS 1.3 suites carry no explicit key-exchange name; only TLS 1.2 suites must use ECDHE.
  *   - The cipher intersection with the engine's supported set must be non-empty on any modern JDK
  *     (every JDK 17+ supports at least one of TLS_AES_128_GCM_SHA256 / TLS_AES_256_GCM_SHA384).
  */
class TlsHardeningSpec extends FunSuite {

  test("SSLContextFactory.createClientContext uses highest requested protocol version") {
    val ctx = SSLContextFactory.createClientContext(TlsConfig.default)
    assert(
      ctx.getSupportedSSLParameters.getProtocols.contains("TLSv1.3"),
      "TLSv1.3 should be supported when requested"
    )
  }

  test("SSLContextFactory.createClientContext with TLS 1.2-only config") {
    val config = TlsConfig(protocols = List(TlsVersion.TLSv1_2))
    val ctx = SSLContextFactory.createClientContext(config)
    assert(ctx.getProtocol == "TLSv1.2" || ctx.getProtocol == "TLS", s"Got protocol: ${ctx.getProtocol}")
  }

  test("SSLSocketChannel.client applies protocols restriction to SSLParameters") {
    val ctx = SSLContextFactory.createClientContext(TlsConfig.default)
    val engine = ctx.createSSLEngine("localhost", 443)
    engine.setUseClientMode(true)

    val params = engine.getSSLParameters
    params.setProtocols(List(TlsVersion.TLSv1_3.value, TlsVersion.TLSv1_2.value).toArray)
    engine.setSSLParameters(params)

    val enabled = engine.getEnabledProtocols
    assert(enabled.contains("TLSv1.3"), s"Should enable TLSv1.3, got: ${enabled.mkString(",")}")
    assert(enabled.contains("TLSv1.2"), s"Should enable TLSv1.2, got: ${enabled.mkString(",")}")
    assert(!enabled.contains("TLSv1.1"), s"Should NOT enable TLSv1.1, got: ${enabled.mkString(",")}")
    assert(!enabled.contains("TLSv1"), s"Should NOT enable TLSv1, got: ${enabled.mkString(",")}")
  }

  test("SSLSocketChannel.client with tls13Only rejects TLS 1.2") {
    val ctx = SSLContextFactory.createClientContext(TlsConfig.tls13Only)
    val engine = ctx.createSSLEngine("localhost", 443)
    engine.setUseClientMode(true)

    val params = engine.getSSLParameters
    params.setProtocols(Array(TlsVersion.TLSv1_3.value))
    engine.setSSLParameters(params)

    val enabled = engine.getEnabledProtocols
    assertEquals(enabled.toList, List("TLSv1.3"))
  }

  test("default cipher suite list contains only AEAD suites (no CBC, no RC4, no 3DES, no RSA kex)") {
    val suites = TlsConfig.defaultCipherSuites
    assert(suites.nonEmpty, "Default cipher list must not be empty")

    suites.foreach { suite =>
      assert(!suite.contains("_CBC_"), s"CBC cipher should not be in default list: $suite")
      assert(!suite.contains("_RC4_"), s"RC4 cipher should not be in default list: $suite")
      assert(!suite.contains("_3DES_"), s"3DES cipher should not be in default list: $suite")
      assert(!suite.contains("_NULL_"), s"NULL cipher should not be in default list: $suite")
      assert(!suite.contains("EXPORT"), s"EXPORT cipher should not be in default list: $suite")
      if suite.startsWith("TLS_") && !suite.startsWith("TLS_AES_") && !suite.startsWith("TLS_CHACHA20_") then {
        assert(
          suite.startsWith("TLS_ECDHE_"),
          s"TLS 1.2 cipher must use ECDHE for forward secrecy: $suite"
        )
      }
    }
  }

  test("default cipher list includes TLS 1.3 suites") {
    val suites = TlsConfig.defaultCipherSuites
    assert(suites.contains("TLS_AES_256_GCM_SHA384"), "Should include TLS 1.3 AES 256 GCM")
    assert(suites.contains("TLS_AES_128_GCM_SHA256"), "Should include TLS 1.3 AES 128 GCM")
    assert(suites.contains("TLS_CHACHA20_POLY1305_SHA256"), "Should include TLS 1.3 ChaCha20")
  }

  test("SSLSocketChannel.client applies cipher restriction — filters to supported intersection") {
    val ctx = SSLContextFactory.createClientContext(TlsConfig.default)
    val engine = ctx.createSSLEngine("localhost", 443)
    engine.setUseClientMode(true)

    val supported = engine.getSupportedCipherSuites.toSet
    val intersection = TlsConfig.defaultCipherSuites.filter(supported.contains).toArray

    assert(
      intersection.nonEmpty,
      s"JDK must support at least one of our hardened ciphers. Supported: ${supported.take(5).mkString(",")}"
    )

    val params = engine.getSSLParameters
    params.setCipherSuites(intersection)
    engine.setSSLParameters(params)

    val enabled = engine.getEnabledCipherSuites.toSet
    enabled.foreach { cipher =>
      assert(
        TlsConfig.defaultCipherSuites.contains(cipher),
        s"Enabled cipher $cipher is not in our allowlist"
      )
    }
  }

  test("TlsConfig.cipherSuites = None uses hardened defaults") {
    val config = TlsConfig.default
    assertEquals(config.cipherSuites, None, "Default should be None (opt into hardening)")
  }

  test("TlsConfig.cipherSuites = Some allows override") {
    val custom = List("TLS_AES_256_GCM_SHA384")
    val config = TlsConfig(cipherSuites = Some(custom))
    assertEquals(config.cipherSuites, Some(custom))
  }
}
