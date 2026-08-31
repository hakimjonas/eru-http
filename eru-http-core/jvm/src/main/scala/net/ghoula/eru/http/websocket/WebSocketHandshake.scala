package net.ghoula.eru.http.websocket

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** WebSocket handshake utilities as defined in RFC 6455 Section 4.
  *
  * The WebSocket opening handshake is based on HTTP Upgrade. The client sends a GET request with
  * specific headers, and the server responds with 101 Switching Protocols if accepted.
  *
  * Key calculation per RFC 6455 Section 4.2.2: Sec-WebSocket-Accept =
  * base64(SHA-1(Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
  */
object WebSocketHandshake {

  /** The magic GUID used in WebSocket accept key calculation per RFC 6455 Section 1.3.
    */
  private val WebSocketGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** Thread-local SecureRandom for key generation to avoid contention.
    */
  private val secureRandom = new ThreadLocal[SecureRandom] {
    override def initialValue(): SecureRandom = new SecureRandom()
  }

  /** Generate a random Sec-WebSocket-Key for client handshake.
    *
    * Per RFC 6455 Section 4.1, the key must be a nonce consisting of a randomly selected 16-byte
    * value that has been base64-encoded.
    *
    * @return
    *   base64-encoded 16-byte random key
    */
  def generateKey(): String = {
    val bytes = new Array[Byte](16)
    secureRandom.get().nextBytes(bytes)
    Base64.getEncoder.encodeToString(bytes)
  }

  /** Calculate the Sec-WebSocket-Accept value from a Sec-WebSocket-Key.
    *
    * Per RFC 6455 Section 4.2.2, step 4: Sec-WebSocket-Accept = base64(SHA-1(Sec-WebSocket-Key +
    * GUID))
    *
    * @param key
    *   the Sec-WebSocket-Key from the client
    * @return
    *   the Sec-WebSocket-Accept value to send in the server response
    */
  def calculateAccept(key: String): String = {
    val combined = key + WebSocketGUID
    val sha1 = MessageDigest.getInstance("SHA-1")
    val hash = sha1.digest(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    Base64.getEncoder.encodeToString(hash)
  }

  /** Validate the Sec-WebSocket-Accept value from a server response.
    *
    * @param key
    *   the Sec-WebSocket-Key that was sent
    * @param accept
    *   the Sec-WebSocket-Accept received from the server
    * @return
    *   true if the accept value is valid
    */
  def validateAccept(key: String, accept: String): Boolean = {
    calculateAccept(key) == accept
  }

  /** Check if an HTTP request is a valid WebSocket upgrade request.
    *
    * Per RFC 6455 Section 4.2.1, a valid upgrade request must have:
    *   - GET method
    *   - HTTP/1.1 or higher
    *   - Host header (the RFC makes this a MUST; matching the Host against the served origin is a
    *     deployment concern behind L7 proxies and is left to the operator)
    *   - Upgrade: websocket (case-insensitive)
    *   - Connection: Upgrade (case-insensitive, may contain other tokens)
    *   - Sec-WebSocket-Key header (base64-encoded 16 bytes)
    *   - Sec-WebSocket-Version: 13
    *
    * @param request
    *   the HTTP request to check
    * @return
    *   true if this is a valid WebSocket upgrade request
    */
  def isUpgradeRequest(request: Request[?]): Boolean = {
    request.method == Method.GET &&
    request.version != HttpVersion.HTTP_1_0 && {
      val headers = request.headers
      val host = headers.getFirst(HeaderNames.Host)
      val upgrade = headers.getFirst(HeaderNames.Upgrade).map(_.value.toLowerCase)
      val connection = headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
      val key = headers.getFirst(HeaderNames.SecWebSocketKey).map(_.value)
      val version = headers.getFirst(HeaderNames.SecWebSocketVersion).map(_.value)

      host.isDefined &&
      upgrade.contains("websocket") &&
      connection.exists(_.contains("upgrade")) &&
      key.exists(isValidKey) &&
      version.contains("13")
    }
  }

  /** Validate a Sec-WebSocket-Key.
    *
    * The key must be a base64-encoded 16-byte value, which results in a 24-character string (after
    * base64 encoding).
    */
  private def isValidKey(key: String): Boolean = {
    key.length == 24 && {
      try {
        val decoded = Base64.getDecoder.decode(key)
        decoded.length == 16
      } catch {
        case _: IllegalArgumentException => false
      }
    }
  }

  /** Extract the Sec-WebSocket-Key from an upgrade request.
    *
    * @param request
    *   the HTTP request
    * @return
    *   the key if present, or an error
    */
  def extractKey(request: Request[?]): Eru[WebSocketError, String] = {
    request.headers.getFirst(HeaderNames.SecWebSocketKey) match {
      case Some(value) =>
        if isValidKey(value.value) then Eru.succeed(value.value)
        else
          Eru.fail(
            WebSocketError.HandshakeFailed(
              s"Invalid Sec-WebSocket-Key: ${value.value}",
              "RFC 6455 Section 4.2.1"
            )
          )
      case None =>
        Eru.fail(
          WebSocketError.HandshakeFailed(
            "Missing Sec-WebSocket-Key header",
            "RFC 6455 Section 4.2.1"
          )
        )
    }
  }

  /** Extract requested subprotocols from an upgrade request.
    *
    * @param request
    *   the HTTP request
    * @return
    *   list of requested subprotocols (may be empty)
    */
  def extractSubprotocols(request: Request[?]): List[String] = {
    request.headers
      .get(HeaderNames.SecWebSocketProtocol)
      .toList
      .flatten
      .flatMap(_.value.split(",").map(_.trim))
      .filter(_.nonEmpty)
  }

  /** Create a WebSocket upgrade response (101 Switching Protocols).
    *
    * @param key
    *   the Sec-WebSocket-Key from the client request
    * @param subprotocol
    *   optional subprotocol to confirm
    * @return
    *   the 101 response to send to the client
    */
  def createUpgradeResponse(
    key: String,
    subprotocol: Option[String] = None
  ): Eru[HttpError, Response[Body]] = {
    val accept = calculateAccept(key)

    for {
      headers <- {
        val baseHeaders = Headers.empty
          .add(HeaderNames.Upgrade, "websocket")
          .flatMap(_.add(HeaderNames.Connection, "Upgrade"))
          .flatMap(_.add(HeaderNames.SecWebSocketAccept, accept))

        subprotocol match {
          case Some(proto) => baseHeaders.flatMap(_.add(HeaderNames.SecWebSocketProtocol, proto))
          case None => baseHeaders
        }
      }.mapError(e => HttpError.InvalidRequest(InvalidRequest(e.toString, "RFC 6455 Section 4")))
    } yield Response(
      status = StatusCode.SwitchingProtocols,
      headers = headers,
      body = Body.Empty
    )
  }

  /** Create a WebSocket upgrade request for clients.
    *
    * @param uri
    *   the WebSocket URI (ws:// or wss://)
    * @param key
    *   the Sec-WebSocket-Key (generate with generateKey())
    * @param subprotocols
    *   optional list of subprotocols to request
    * @param additionalHeaders
    *   additional headers to include
    * @return
    *   the upgrade request to send to the server
    */
  def createUpgradeRequest(
    uri: Uri,
    key: String,
    subprotocols: List[String] = Nil,
    additionalHeaders: Headers = Headers.empty
  ): Eru[HttpError, Request[Body]] = {
    for {
      httpUri <- uri.scheme match {
        case Some("ws") => uri.withScheme("http").mapError(e => HttpError.InvalidUri(e))
        case Some("wss") => uri.withScheme("https").mapError(e => HttpError.InvalidUri(e))
        case _ => Eru.succeed(uri)
      }

      baseHeaders <- Headers.empty
        .add(HeaderNames.Upgrade, "websocket")
        .flatMap(_.add(HeaderNames.Connection, "Upgrade"))
        .flatMap(_.add(HeaderNames.SecWebSocketKey, key))
        .flatMap(_.add(HeaderNames.SecWebSocketVersion, "13"))
        .mapError(e => HttpError.InvalidRequest(InvalidRequest(e.toString, "RFC 6455 Section 4")))

      withProtocol <-
        if subprotocols.nonEmpty then
          baseHeaders
            .add(HeaderNames.SecWebSocketProtocol, subprotocols.mkString(", "))
            .mapError(e => HttpError.InvalidRequest(InvalidRequest(e.toString, "RFC 6455 Section 4")))
        else Eru.succeed(baseHeaders)

      headers = withProtocol ++ additionalHeaders
    } yield Request(
      method = Method.GET,
      uri = httpUri,
      headers = headers,
      body = Body.Empty
    )
  }

  /** Validate a server's upgrade response.
    *
    * @param response
    *   the server's HTTP response
    * @param expectedKey
    *   the Sec-WebSocket-Key that was sent
    * @return
    *   success if valid, or a handshake error
    */
  def validateUpgradeResponse(
    response: Response[?],
    expectedKey: String
  ): Eru[WebSocketError, Unit] = {
    val upgrade = response.headers.getFirst(HeaderNames.Upgrade).map(_.value.toLowerCase)
    val connection = response.headers.getFirst(HeaderNames.Connection).map(_.value.toLowerCase)
    val accept = response.headers.getFirst(HeaderNames.SecWebSocketAccept).map(_.value)

    if response.status != StatusCode.SwitchingProtocols then
      Eru.fail(
        WebSocketError.HandshakeFailed(
          s"Expected 101 Switching Protocols, got ${response.status.value} ${response.status.reasonPhrase}",
          "RFC 6455 Section 4.2.2"
        )
      )
    else if !upgrade.contains("websocket") then
      Eru.fail(
        WebSocketError.HandshakeFailed(
          "Missing or invalid Upgrade header in response",
          "RFC 6455 Section 4.2.2"
        )
      )
    else if !connection.exists(_.contains("upgrade")) then
      Eru.fail(
        WebSocketError.HandshakeFailed(
          "Missing or invalid Connection header in response",
          "RFC 6455 Section 4.2.2"
        )
      )
    else
      accept match {
        case Some(a) if validateAccept(expectedKey, a) => Eru.unit
        case Some(_) =>
          Eru.fail(
            WebSocketError.HandshakeFailed(
              "Invalid Sec-WebSocket-Accept value",
              "RFC 6455 Section 4.2.2"
            )
          )
        case None =>
          Eru.fail(
            WebSocketError.HandshakeFailed(
              "Missing Sec-WebSocket-Accept header",
              "RFC 6455 Section 4.2.2"
            )
          )
      }
  }
}
