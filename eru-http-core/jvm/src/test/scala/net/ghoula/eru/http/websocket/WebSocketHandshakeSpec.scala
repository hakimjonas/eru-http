package net.ghoula.eru.http.websocket

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.TestHelpers.*

class WebSocketHandshakeSpec extends FunSuite {

  test("generateKey creates base64-encoded 16-byte key") {
    val key = WebSocketHandshake.generateKey()

    assertEquals(key.length, 24)

    val decoded = java.util.Base64.getDecoder.decode(key)
    assertEquals(decoded.length, 16)
  }

  test("generateKey creates unique keys") {
    val keys = (1 to 100).map(_ => WebSocketHandshake.generateKey())
    val uniqueKeys = keys.toSet

    assertEquals(uniqueKeys.size, 100)
  }

  test("calculateAccept produces correct accept key per RFC 6455") {
    // Test vector from RFC 6455 Section 4.2.2
    // Key: dGhlIHNhbXBsZSBub25jZQ==
    // Expected Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
    val key = "dGhlIHNhbXBsZSBub25jZQ=="
    val accept = WebSocketHandshake.calculateAccept(key)
    assertEquals(accept, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
  }

  test("validateAccept verifies correct accept key") {
    val key = WebSocketHandshake.generateKey()
    val accept = WebSocketHandshake.calculateAccept(key)

    assert(WebSocketHandshake.validateAccept(key, accept))
    assert(!WebSocketHandshake.validateAccept(key, "wrong_accept_key"))
  }

  test("isUpgradeRequest validates WebSocket upgrade requests") {
    val validRequest = (for {
      h <- Headers.empty
        .add("Host", "example.com")
        .flatMap(_.add("Upgrade", "websocket"))
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey()))
        .flatMap(_.add("Sec-WebSocket-Version", "13"))
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(WebSocketHandshake.isUpgradeRequest(validRequest))
  }

  test("isUpgradeRequest rejects a request without Host (RFC 6455 Section 4.2.1)") {
    val noHost = (for {
      h <- Headers.empty
        .add("Upgrade", "websocket")
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey()))
        .flatMap(_.add("Sec-WebSocket-Version", "13"))
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(!WebSocketHandshake.isUpgradeRequest(noHost))
  }

  test("isUpgradeRequest rejects non-GET methods") {
    val postRequest = (for {
      h <- Headers.empty
        .add("Host", "example.com")
        .flatMap(_.add("Upgrade", "websocket"))
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey()))
        .flatMap(_.add("Sec-WebSocket-Version", "13"))
    } yield Request(
      method = Method.POST,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(!WebSocketHandshake.isUpgradeRequest(postRequest))
  }

  test("isUpgradeRequest rejects missing Upgrade header") {
    val noUpgrade = (for {
      h <- Headers.empty
        .add("Host", "example.com")
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey()))
        .flatMap(_.add("Sec-WebSocket-Version", "13"))
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(!WebSocketHandshake.isUpgradeRequest(noUpgrade))
  }

  test("isUpgradeRequest rejects wrong Sec-WebSocket-Version") {
    val wrongVersion = (for {
      h <- Headers.empty
        .add("Host", "example.com")
        .flatMap(_.add("Upgrade", "websocket"))
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey()))
        .flatMap(_.add("Sec-WebSocket-Version", "8"))
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(!WebSocketHandshake.isUpgradeRequest(wrongVersion))
  }

  test("isUpgradeRequest rejects invalid Sec-WebSocket-Key") {
    val invalidKey = (for {
      h <- Headers.empty
        .add("Host", "example.com")
        .flatMap(_.add("Upgrade", "websocket"))
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Key", "too-short"))
        .flatMap(_.add("Sec-WebSocket-Version", "13"))
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(!WebSocketHandshake.isUpgradeRequest(invalidKey))
  }

  test("extractKey returns the key from a valid request") {
    val key = WebSocketHandshake.generateKey()
    val request = (for {
      h <- Headers.empty.add("Sec-WebSocket-Key", key)
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    val extractedKey = WebSocketHandshake.extractKey(request).assertSuccess
    assertEquals(extractedKey, key)
  }

  test("extractKey fails for missing key") {
    val request = Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = Headers.empty,
      body = Body.Empty
    )

    assert(WebSocketHandshake.extractKey(request).isFailure)
  }

  test("extractSubprotocols parses comma-separated protocols") {
    val request = (for {
      h <- Headers.empty.add("Sec-WebSocket-Protocol", "chat, superchat")
    } yield Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = h,
      body = Body.Empty
    )).assertSuccess

    val protocols = WebSocketHandshake.extractSubprotocols(request)
    assertEquals(protocols, List("chat", "superchat"))
  }

  test("extractSubprotocols returns empty list for no protocol header") {
    val request = Request(
      method = Method.GET,
      uri = Uri.apply(path = "/ws"),
      headers = Headers.empty,
      body = Body.Empty
    )

    val protocols = WebSocketHandshake.extractSubprotocols(request)
    assertEquals(protocols, Nil)
  }

  test("createUpgradeResponse creates valid 101 response") {
    val key = WebSocketHandshake.generateKey()
    val response = WebSocketHandshake.createUpgradeResponse(key, None).assertSuccess

    assertEquals(response.status, StatusCode.SwitchingProtocols)
    assertEquals(response.headers.getFirst("Upgrade").map(_.value), Some("websocket"))
    assert(response.headers.getFirst("Connection").map(_.value.toLowerCase).exists(_.contains("upgrade")))
    assertEquals(
      response.headers.getFirst("Sec-WebSocket-Accept").map(_.value),
      Some(WebSocketHandshake.calculateAccept(key))
    )
  }

  test("createUpgradeResponse includes subprotocol when specified") {
    val key = WebSocketHandshake.generateKey()
    val response = WebSocketHandshake.createUpgradeResponse(key, Some("chat")).assertSuccess

    assertEquals(response.headers.getFirst("Sec-WebSocket-Protocol").map(_.value), Some("chat"))
  }

  test("createUpgradeRequest creates valid upgrade request") {
    val uri = Uri.parse("ws://example.com/ws").assertSuccess
    val key = WebSocketHandshake.generateKey()
    val request = WebSocketHandshake.createUpgradeRequest(uri, key).assertSuccess

    assertEquals(request.method, Method.GET)
    assertEquals(request.headers.getFirst("Upgrade").map(_.value), Some("websocket"))
    assert(request.headers.getFirst("Connection").map(_.value.toLowerCase).exists(_.contains("upgrade")))
    assertEquals(request.headers.getFirst("Sec-WebSocket-Key").map(_.value), Some(key))
    assertEquals(request.headers.getFirst("Sec-WebSocket-Version").map(_.value), Some("13"))
  }

  test("createUpgradeRequest converts ws:// to http://") {
    val uri = Uri.parse("ws://example.com/ws").assertSuccess
    val key = WebSocketHandshake.generateKey()
    val request = WebSocketHandshake.createUpgradeRequest(uri, key).assertSuccess

    assertEquals(request.uri.scheme, Some("http"))
  }

  test("createUpgradeRequest converts wss:// to https://") {
    val uri = Uri.parse("wss://example.com/ws").assertSuccess
    val key = WebSocketHandshake.generateKey()
    val request = WebSocketHandshake.createUpgradeRequest(uri, key).assertSuccess

    assertEquals(request.uri.scheme, Some("https"))
  }

  test("createUpgradeRequest includes subprotocols") {
    val uri = Uri.parse("ws://example.com/ws").assertSuccess
    val key = WebSocketHandshake.generateKey()
    val request = WebSocketHandshake.createUpgradeRequest(uri, key, List("chat", "superchat")).assertSuccess

    assertEquals(request.headers.getFirst("Sec-WebSocket-Protocol").map(_.value), Some("chat, superchat"))
  }

  test("validateUpgradeResponse accepts valid 101 response") {
    val key = WebSocketHandshake.generateKey()
    val accept = WebSocketHandshake.calculateAccept(key)

    val response = (for {
      h <- Headers.empty
        .add("Upgrade", "websocket")
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Accept", accept))
    } yield Response(
      status = StatusCode.SwitchingProtocols,
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(WebSocketHandshake.validateUpgradeResponse(response, key).isSuccess)
  }

  test("validateUpgradeResponse rejects non-101 status") {
    val key = WebSocketHandshake.generateKey()
    val response = Response(
      status = StatusCode.Ok,
      headers = Headers.empty,
      body = Body.Empty
    )

    assert(WebSocketHandshake.validateUpgradeResponse(response, key).isFailure)
  }

  test("validateUpgradeResponse rejects wrong accept key") {
    val key = WebSocketHandshake.generateKey()

    val response = (for {
      h <- Headers.empty
        .add("Upgrade", "websocket")
        .flatMap(_.add("Connection", "Upgrade"))
        .flatMap(_.add("Sec-WebSocket-Accept", "wrong_accept_key"))
    } yield Response(
      status = StatusCode.SwitchingProtocols,
      headers = h,
      body = Body.Empty
    )).assertSuccess

    assert(WebSocketHandshake.validateUpgradeResponse(response, key).isFailure)
  }
}
