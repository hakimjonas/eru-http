# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha.2] - 2026-09

Ecosystem findings from the melian work (`ecosystem-findings-from-melian.md`), items 1-8. Adds a
fourth module: `eru-http-acme`.

### Core (`eru-http-core`)

- Full RFC status registry: every registered status code (1xx-5xx, RFC 9110 plus 102/207/208/226/
  421/422/423/424/425/428/431/451/505/506/507/508/510/511), with `requiredHeaders(426) = {Upgrade}`
  and 205 excluded from body-allowing statuses. 203/206/300/407/408/416 gained their missing reason
  phrases.
- `Response.tooManyRequests(retryAfter: Duration, body)` and a `Retry-After` HTTP-date overload —
  renders non-negative `delay-seconds`, fails on negative delays (RFC 9110 Sections 15.5.14, 14.2).
- `Response.addCookie(cookie)` — appends to `Set-Cookie`, the one field RFC 9110 Section 5.5 exempts
  from list combination, so cookie-issuing middlewares no longer clobber each other.
- Client address mechanism (both surfaces): a typed attributes channel
  (`Request.AttributeKey[A]` + `Request.withAttribute`/`attribute`) and
  `Request.clientAddress: Option[ClientAddress]` carrying the resolved address with its
  provenance (`TcpPeer`, `ProxyProtocol`, `ForwardedFor`).
- `CanEqual` givens next to the comparable value types (`Method`, `StatusCode`, `Port`, `Uri`,
  `HeaderName`, `HeaderValue`, `MediaType`, `Cookie`, `ETag`, `SameSite`, `HttpVersion`,
  `ContentEncoding`, `StatusClass`, `WebSocketCloseCode`) for `-language:strictEquality` consumers.

### Server (`eru-http-server`)

- `Request.clientAddress` is populated for HTTP/1.1 and HTTP/2 requests from the same resolution
  `PerIpGovernor` uses: TCP peer, PROXY-v2-derived, or leftmost-untrusted `X-Forwarded-For` behind
  `trustedProxies` — rate limiting and this surface can never disagree.
- WebSocket pending-handler registry leak fixed: entries are inserted only after the handshake
  validates, claimed before the 101 is written, and reclaimed (`dropPendingFor`) when a wrapping
  middleware discards a marked 101; a bounded registry (`MaxPendingHandlers`) caps pathological
  composition. Covered by the new hostile `PendingHandlerRegistrySpec` (mid-handshake abort bursts).
- `HttpServer.shutdown` doc states the CAS idempotency contract ("safe to call multiple times and
  concurrently").
- Hostile tests run again: sbt 2 forks test JVMs with a clean environment, so `HOSTILE=true` was
  silently ignored; the build now forwards the opt-in flag to forked JVMs.

### ACME (`eru-http-acme`, new module)

- RFC 8555 client (ES256 JWS, embedded-JWK account creation, `kid` references, nonce management
  with badNonce retry, POST-as-GET) producing `TlsConfig` from a persisted PKCS12 keystore.
- HTTP-01 challenge responder (`AcmeHttp01`) as a handler wrapper, plus a redirect helper.
- Provisioner (`AcmeProvisioner.start`) with cached-cert reuse and a renewal-before-expiry daemon
  loop; staging/production/stub directory URLs via `AcmeConfig`.
- Hand-rolled PKCS#10 CSR (SAN via `extensionRequest`) — validated by the JDK's own parser; PEM
  encode/decode; minimal JSON for the protocol. No new external dependencies.

### Dependencies

- Eru bumped to `1.0.0-alpha.1` (was `1.0.0-alpha`), matching the published pairing.

## [1.0.0-alpha] - 2026-09

Initial public release. The library ships in three modules.

### Core (`eru-http-core`)

- Validated HTTP model: `Method`, `StatusCode`, `Uri`, `Port`, `HeaderName`, `HeaderValue`, `Headers`, `Request`, `Response`, `HttpVersion`, `MediaType`, `Body`, `Chunk`, `ChunkStream`, `Bytes`, `Charset`.
- `BodyEncoder` / `BodyDecoder` typeclasses with givens for `String`, `Bytes`, `Unit`, and `Body`.
- HTTP semantics per RFC 9110: method/body validation, required headers, forbidden header combinations, cacheable statuses, and the QUERY method (RFC 10008) — a safe, idempotent method whose request content carries the query, framed like POST with a required `Content-Type`, and the `Accept-Query` response field (RFC 9651 Structured Fields) with client-side discovery.
- RFC 9111 `CacheControl` and `CacheDirective` with parse/serialize round-trips; `ETag` and `HttpDate`.
- RFC 6265 `Cookie` parsing and serialization; RFC 7578 `Multipart` and `Part`.
- `ServerSentEvent` construction, parsing, and serialization.
- `ContentEncoding` (gzip, deflate, brotli) with `Compression` codecs.
- TLS configuration (`TlsConfig`, `TlsVersion`) and `SSLContextFactory`, `SSLSocketChannel`.
- HTTP/1.1 parser and writer with smuggling-class defenses: duplicate `Content-Length`, conflicting `Content-Length`/`Transfer-Encoding`, duplicate `Host`/`Transfer-Encoding`, missing `Host`, and body-bearing safe methods are rejected with 400 before dispatch.
- HTTP/2 (RFC 9113): frame codec, HPACK encoder/decoder, client and server connection state machines.
- WebSocket (RFC 6455): frame parser/writer, handshake, error model.

### Client (`eru-http-client`)

- `HttpClient` with `send` and decoded `execute`, redirect handling, request/response interceptors.
- Connection pooling with per-host limits, pooled buffers and readers, TLS session and HTTP/2 connection reuse.
- `CookieJar` with domain/path matching and expiration.
- Automatic decompression (gzip, deflate, brotli).
- `WebSocketClient` with subprotocol negotiation and connect/handshake timeouts.

### Server (`eru-http-server`)

- `HttpServer` on blocking NIO with one virtual thread per connection; `SO_REUSEPORT` multi-acceptor mode.
- Per-connection timeouts: idle keep-alive bound and Slowloris-defeating header read timeout.
- `Middleware` library: logging, CORS, auth, request IDs, error handling, compression, conditional application.
- Per-IP governance: connection caps, accept-rate and request-rate token buckets with a fail-closed tracked-IP cap.
- PROXY protocol v2 parsing (`Off` / `Optional` / `Required`) with trusted-proxy CIDR allowlisting.
- TLS with ALPN-based HTTP/2 negotiation; graceful shutdown bounded by `gracefulShutdownTimeout`.
- Per-phase socket-level read deadlines: the header phase of every request is bounded by `readHeaderTimeout` and answers 408; the keep-alive gap is bounded by `idleTimeout`.
- PROXY protocol v2 over TLS: the preamble is parsed on the raw socket before the handshake, so a PROXY-emitting LB works with TLS termination at the server.
- WebSocket `closeTimeout` is enforced: a peer that ignores the server-initiated Close is force-closed at TCP level.
- `Middleware.bodyLimit` answers 413 with per-content-type limits before the handler runs.
- Body-stream framing failures (malformed chunk size, forbidden trailer, over-limit chunked bodies) surface as 400 instead of silently truncating; strict path validation is on by default; `HttpServerConfig.edge` ships per-IP governance for edge-exposed deployments.
- systemd watchdog integration (`READY=1`, `WATCHDOG=1`, `STOPPING=1`).
- WebSocket server with a ping/pong watchdog that closes silent peers.

### Tests

The release suite covers unit and integration tests per module, plus an opt-in hostile suite (`HOSTILE=true sbt testAll`) covering request smuggling, slowloris, connection floods, oversized frames, TLS handshake stalls, and rate-limit abuse. Compliance harnesses: Autobahn 247/247, h2spec 145 passed / 1 skipped, HPACK vector suite. See [HOSTILE_TESTING.md](HOSTILE_TESTING.md).

### Security hardening

Request validation runs on the HTTP/2 path as well as HTTP/1.1 (QUERY without `Content-Type` and bodyless-method violations answer 400 on the stream); credentials are stripped on cross-origin redirects per RFC 9110 Section 11.5; absolute-form request targets are normalized per RFC 9112 Section 3.2.2; PROXY v2 payload bounds are enforced; client streaming bodies declare their framing (Content-Length or Transfer-Encoding: chunked). QUERY carries its own matrix: framing edges, 413 size limits, chunked truncation pinning, trailers, 301/302/303/307 redirect semantics with credential hygiene, redirect-budget bounds, streaming bodies, and large-body flow control over HTTP/2. See [SECURITY.md](SECURITY.md) for the advisory cross-check.
