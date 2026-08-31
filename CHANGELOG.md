# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
