# Security posture

This document is the operator-facing reference for `eru-http`'s security
surface. Each defense is listed with:

- **Status** — enforced by default, opt-in, or documented gap.
- **Config knob** (if any) — the `HttpServerConfig` /
  `WebSocketServerConfig` / `TlsConfig` field that controls it.
- **Defended by** — the hostile spec that exercises the mitigation
  against unpatched-adjacent traffic. These live under `**/hostile/`
  and only run with `HOSTILE=true sbt testAll`.
- **RFC / CVE** — the standards citation or CVE class the defense
  addresses.

---

## Transport layer

| Defense | Status | Config | Defended by | RFC / CVE |
|---|---|---|---|---|
| TLS 1.2 + 1.3 only; TLS ≤ 1.1 rejected at the engine | Enforced | `TlsConfig.protocols` | `TlsProtocolDowngradeSpec`, `TlsHardeningSpec` | RFC 8446 |
| AEAD-only cipher allowlist (no CBC / RC4 / 3DES / NULL / EXPORT) | Enforced | `TlsConfig.cipherSuites` | `TlsProtocolDowngradeSpec` | — |
| Hostname verification on client | Enforced | `TlsConfig.verifyHostname` (default `true`) | Client TLS path | RFC 9110 §4.3.4 |
| SNI + ALPN (`h2`, `http/1.1`) | Enforced | (automatic) | `H2IntegrationSpec` | RFC 7301, 6066 |
| TLS handshake timeout | Enforced (default 10s) | `HttpServerConfig.tlsHandshakeTimeout` | `TlsHandshakeTimeoutSpec` | — (Slowloris-over-TLS) |

---

## HTTP/1.1 framing

| Defense | Status | Config | Defended by | RFC / CVE |
|---|---|---|---|---|
| Pre-allocation `Content-Length` bound (OOM-safe) | Enforced | `HttpServerConfig.maxRequestSize` (default 10 MB) | `ContentLengthAttackSpec` | Memory-exhaustion class |
| Chunked cumulative-size cap | Enforced | `maxRequestSize` | `ContentLengthAttackSpec` | RFC 9112 §7.1 |
| CL + TE co-presence → 400 | Enforced | (always) | `RequestSmugglingSpec` | RFC 9112 §6.1 |
| Duplicate `Content-Length` → 400 | Enforced | (always) | `RequestSmugglingSpec` | RFC 9112 §6.2 |
| Duplicate `Host` / `Transfer-Encoding` → 400 | Enforced | (always) | `RequestSmugglingSpec` | RFC 9110 §7.2, RFC 9112 §6.1 |
| Comma-joined `Content-Length` → 400 | Enforced | (always) | `RequestSmugglingSpec` | RFC 9112 §6.2 |
| HTTP/1.1 without `Host` → 400 | Enforced | (always) | `RequestValidateSpec` | RFC 9110 §7.2 |
| GET/HEAD/DELETE/TRACE with body → 400 | Enforced | (always) | `RequestValidateSpec` | RFC 9110 §9 |
| Chunked trailer section fully consumed | Enforced | (always) | `ChunkedTrailerSpec` | RFC 9112 §7.1.2 |
| Forbidden trailer headers (CL, TE, Host, etc.) → parser rejects | Enforced — the rejection surfaces as a 400 (body-stream failure semantics); pipeline stays aligned | (always) | `ChunkedTrailerSpec` | RFC 9112 §7.1.3 |
| Parse errors return `400` + `Connection: close` (not silent TCP close) | Enforced | (always) | `ParseErrorResponseSpec` | — |
| Max header-block size 64 KiB; max line 8 KiB | Enforced | `HttpParser` constants | `HttpParserSpec` | — |
| CRLF injection blocked on inbound header-value parse | Enforced | (always) | `CoreTypesSpec` | RFC 9110 §5.5 |
| CRLF injection blocked on outbound via `Headers.add` / `.parse` | Enforced | (always) | `CoreTypesSpec` | RFC 9110 §5.5 |
| `Headers.unsafeApply` / `unsafeAdd` inaccessible from user code | Enforced (`private[http]`) | — | Compile-time | — |
| Strict path validation — reject control chars (0x00–0x1F, 0x7F) | **Enforced (default)** — byte-faithful delivery unchanged; opt out with `withStrictPathValidation(false)` | `HttpServerConfig.strictPathValidation` (default `true`) | `StrictPathSpec` | RFC 9110 §4 |

**Byte-faithful path contract** (default). The server does NOT
percent-decode or normalize request paths; they reach handlers exactly
as parsed from the wire. Handlers that dereference the path for
file-system or DB lookups MUST normalize themselves (e.g.
`Path.of(p).normalize().startsWith(base)`). Strict-mode rejects the
clearly-invalid byte sequences (control chars, `\0`, `\r`) but still
does not decode.

---

## HTTP/1.1 timeouts

| Defense | Status | Config | Defended by | RFC / CVE |
|---|---|---|---|---|
| Per-read header deadline (Slowloris) | Enforced (default 15s) | `readHeaderTimeout` | `SlowlorisAttackSpec`, `KeepAliveHeaderTimeoutSpec` | Slowloris class |
| Keep-alive idle gap deadline | Enforced (default 60s) | `idleTimeout` | `KeepAlivePipelineSpec`, `KeepAliveHeaderTimeoutSpec` | Resource-hold class |
| Connection-count ceiling | Enforced (default 1024) | `maxConnections` | `ConnectionFloodSpec` | Resource-exhaustion class |
| Graceful shutdown (handler-fiber drain) | Enforced | (automatic) | `ConcurrentShutdownSpec` | — |

---

## HTTP/2 specifics

| Defense | Status | Config | Defended by | RFC / CVE |
|---|---|---|---|---|
| Preface validated byte-for-byte | Enforced | (automatic) | `H2ServerConnectionSpec` | RFC 9113 §3.4 |
| HPACK header-list size cap (default 64 KiB) | Enforced | `H2Settings.maxHeaderListSize` | `H2ContinuationFloodSpec` | RFC 9113 §6.5.2 |
| CONTINUATION flood bounded + GOAWAY | Enforced | `maxHeaderListSize` | `H2ContinuationFloodSpec` | CVE-2024-27316 |
| Cross-stream CONTINUATION rejected | Enforced | (automatic) | `H2ContinuationFloodSpec` | RFC 9113 §6.10 |
| Rapid-reset budget (100 RST/10s) + GOAWAY(ENHANCE_YOUR_CALM) | Enforced | `H2ServerConnection.Reset{Budget,WindowNanos}` | `H2RapidResetSpec` | CVE-2023-44487 |
| Reset streams removed from state map (not just marked Closed) | Enforced | (automatic) | `H2RapidResetSpec` | CVE-2023-44487 |
| Max concurrent streams default (128) | Enforced | `H2Settings.maxConcurrentStreams` | `H2StreamExhaustionSpec` | Stream-exhaustion class |
| SETTINGS flood budget (50/10s) | Enforced | `H2ServerConnection.Settings{Budget,WindowNanos}` | `H2SettingsFloodSpec` | CVE-2019-9515 |
| PING flood budget (20/10s) | Enforced | `H2ServerConnection.Ping{Budget,WindowNanos}` | `H2PingFloodSpec` | CVE-2019-9512 |
| Frame-size bounds enforced at read time | Enforced | (automatic, from peer settings) | `H2ServerConnectionSpec` | RFC 9113 §4.2 |
| Pseudo-header validation (required + uniqueness + position) | Enforced | (automatic) | `H2ServerConnectionSpec` | RFC 9113 §8.3 |
| Connection-specific headers in H2 rejected | Enforced | (automatic) | `H2ServerConnectionSpec` | RFC 9113 §8.2.2 |
| Client PUSH_PROMISE rejected | Enforced | (automatic) | `H2ServerConnectionSpec` | RFC 9113 §8.4 |
| Content-Length vs DATA mismatch → RST_STREAM | Enforced | (automatic) | `H2ServerConnectionSpec` | RFC 9113 §8.1.1 |

---

## Per-IP governance

**On by default in the `edge` preset** (`HttpServerConfig.edge`) and opt-in otherwise via
`HttpServerConfig.perIpGovernanceEnabled = true`. The default config keeps it off because a library
cannot know whether it sits directly on untrusted traffic; an edge-exposed deployment should use
`HttpServerConfig.edge` or enable it explicitly. When enabled:

| Defense | Status | Config | Defended by |
|---|---|---|---|
| Per-IP concurrent connection cap | Enforced | `maxConnectionsPerIp` (default 10) | `IpConnectionLimitSpec` |
| Per-IP accept-rate bucket | Enforced | `acceptRatePerIp`, `acceptBurstPerIp` (default 20/20) | `IpConnectionLimitSpec` |
| Per-IP request-rate bucket → 429 | Enforced | `requestsPerSecondPerIp`, `burstSizePerIp` (default 10/20) | `RateLimitSpec` |
| Tracking-map hard cap, fail-closed | Enforced | `trackedIpCap` (default 100,000) | `PerIpGovernorSpec` |
| PROXY v2 preamble (Off / Optional / Required) | Configurable | `proxyProtocolMode` (default `Off`) | `ProxyProtocolSpec`, `ProxyProtocolIntegrationSpec` |
| `X-Forwarded-For` with trusted-proxies allowlist | Configurable | `trustedProxies: List[Cidr]` (default empty) | `XForwardedForSpec` |

429 responses include `Retry-After`, `X-RateLimit-Limit`,
`X-RateLimit-Remaining`, `X-RateLimit-Reset`. 429 does NOT close the
TCP connection — keep-alive is preserved across rate-limited requests.

PROXY-protocol + XFF resolution rules:
- PROXY v2 preamble is parsed on the raw socket before the TLS handshake
  for both plain and TLS connections (a PROXY-emitting LB sends the
  preamble first, so no LB-side TLS termination is required). With
  Optional mode and a non-PROXY TLS peer, the peeked ClientHello bytes are
  replayed into the TLS stream untouched.
- XFF is trusted only when the TCP peer falls inside a configured
  trusted-proxies CIDR; the leftmost **untrusted** value in the chain
  is the subject. Malformed / all-trusted chains fall back to TCP peer.

---

## WebSocket

| Defense | Status | Config | Defended by | RFC / CVE |
|---|---|---|---|---|
| Handshake byte-level validation (method, version, Host, key, Sec-WebSocket-Version: 13) | Enforced | (automatic) | `WebSocketHandshakeSpec` | RFC 6455 §4 |
| Inbound frame masking required | Enforced | (automatic) | `WebSocketFrameParserSpec`, Autobahn 247/247 | RFC 6455 §5.3 |
| RSV-bit rejection (no extensions negotiated) | Enforced | (automatic) | `WebSocketFrameParserSpec`, Autobahn | RFC 6455 §5.2 |
| Control-frame max payload (125 bytes) + FIN required | Enforced | (automatic) | `WebSocketFrameParserSpec`, Autobahn | RFC 6455 §5.5 |
| UTF-8 validation on reassembled text + close reason | Enforced | (automatic) | `WebSocketFrameParserSpec`, Autobahn | RFC 6455 §5.6, §7.1.6 |
| Close-code validation on receive | Enforced | (automatic) | `WebSocketFrameParserSpec`, Autobahn | RFC 6455 §7.4 |
| Max message size cap | Enforced | `WebSocketServerConfig.maxMessageSize` (default 10 MiB) | `WebSocketFrameParserSpec` | — |
| Origin-header allowlist | **Opt-in** | `WebSocketServerConfig.allowedOrigins: Option[List[String]]` (default `None`) | `WebSocketOriginSpec` | RFC 6455 §10.2 |
| Per-message-deflate (RFC 7692) | **Not implemented** — RSV rejects deflate | — | `WebSocketFrameParserSpec` | — (no decompression-bomb possible) |
| Proactive Ping on inbound silence | Enforced (default 30s) | `WebSocketServerConfig.pingInterval: Option[Duration]` | `WebSocketPongTimeoutSpec` | RFC 6455 §5.5.2 |
| Pong-timeout server-initiated close | Enforced (default 10s) | `WebSocketServerConfig.pongTimeout: Option[Duration]` | `WebSocketPongTimeoutSpec` | — (NAT/proxy idle-drop defense) |
| Per-connection frame write-lock | Enforced | (automatic when watchdog forked) | `WebSocketPongTimeoutSpec` | — (serializes writer + watchdog) |
| Autobahn conformance | 247/247 on cat. 1–7 + 10 | — | `autobahn/AUTOBAHN_RESULTS.md` | — |

---

## Client-side

| Defense | Status | Config | Defended by |
|---|---|---|---|
| Bounded direct-buffer pool (cap: `maxConnections` × 4 KB) | Enforced | `HttpClientConfig.maxConnections` | `ConnectionPool` |
| Per-host connection semaphore + global semaphore (FIFO fair) | Enforced | `maxConnections`, `maxConnectionsPerHost` | `RefContentionStressSpec` |
| TLS hostname verification on by default | Enforced | `TlsConfig.verifyHostname` | `TlsProtocolDowngradeSpec` |
| Response-body decompression with size guard | Enforced | (handler) | `CompressionIntegrationSpec` |
| Credentials stripped on cross-origin redirects (`Authorization`, `Proxy-Authorization`, explicit `Cookie`) | Enforced | (automatic) | `HttpClientSpec` redirect tests |
| Redirected requests regenerate Host from the new target URI | Enforced | (automatic) | `HttpClientSpec` redirect tests |

The redirect policy follows RFC 9110 Section 11.5: a protection space cannot
extend outside the scope of its server, so credentials are dropped when a
redirect resolves to a different origin (scheme, host, or port, with default
ports normalized per RFC 6454). Same-origin redirects keep them. QUERY content
is replayed on 301/302/307/308 per RFC 10008 Section 2.5 — query content
follows redirects exactly like POST bodies; treat query content that must not
leave an origin accordingly.

---

## 2026 advisory cross-check

Cross-check against the nginx 2026 advisory list (the classes most likely to
surface in general-purpose HTTP servers). Non-applicable entries are classes
that cannot arise in this codebase: it has no HTTP/3 implementation as of
1.0.0, no
mp4/SSI/slice/charset/rewrite/resolver modules, and all parsing runs on a
memory-safe runtime with explicit bounds (no C-style buffers).

| Advisory class | Verdict | Notes |
|---|---|---|
| CVE-2026-42055 — PROXY v2 buffer overflow | Mitigated | `ProxyProtocol` bounds the declared payload at 1 KB and reads exactly; hostile specs pin truncated and oversized payloads (`ProxyProtocolIntegrationSpec`). |
| CVE-2026-42926 — HTTP/2 request injection | Mitigated | H2 header values go through `HeaderValue.parse` (CR/LF rejected); `:scheme` restricted to http/https; control characters in `:path`/`:authority` rejected by `Uri.parse` (RFC 3986 §2). Pinned by `H2ValidationSpec`. |
| CVE-2026-28753 — auth header injection | Mitigated | All auth/CORS middleware writes headers through validated setters; CR/LF-bearing CORS origins fail response construction (`MiddlewareSpec`). |
| CVE-2025-23419 — TLS session reuse | Not applicable | One SSLContext per listener (single identity, no second vhost to confuse); the client keys pooled TLS sessions by host:port and JSSE only resumes sessions for the same peer. `verifyHostname=false` is opt-in, testing-only. |
| CVE-2026-42530 / HTTP/3 CVEs | Not applicable | No HTTP/3 implementation as of 1.0.0; a QUIC transport is planned for after the first stable release (see MANIFESTO.md). |
| CVE-2026-60005, CVE-2026-56434, CVE-2026-9256, CVE-2026-42945, mp4/charset/resolver classes | Not applicable | No such modules; JVM memory safety. |
| CVE-2013-4547 / §7.3 class — absolute-form request targets | Handled per spec | Accepted and normalized per RFC 9112 §3.2.2: the received Host is ignored and replaced with the request-target's authority; a missing Host still answers 400. Pinned by `RequestValidateSpec` and `HttpParserSpec`. |

---

## Design decisions

The security surface closed before 1.0.0: body-stream framing failures now
answer 400 instead of silently truncating, the PROXY v2 preamble is parsed
on the raw socket before the TLS handshake, header-read deadlines answer
408 on plain and TLS paths, `Middleware.bodyLimit` bounds uploads per
content type, and WebSocket `closeTimeout` is enforced. What follows are
the remaining deliberate design decisions.

1. **Byte-faithful URI path contract** — see "HTTP/1.1 framing" above.
   Strict mode (default as of 1.0.0) rejects control characters; it does
   NOT decode or normalize. Handlers that do filesystem / DB lookups MUST
   normalize themselves.

2. **Per-IP governance is off in the default config, on in `edge`.** The
   default config cannot know whether it faces untrusted traffic directly.
   Edge-exposed deployments should use `HttpServerConfig.edge` or enable
   governance explicitly; behind a trusted LB it stays optional. Dropped
   connections at the connection cap get a TCP close with no HTTP
   response — that is deliberate DoS economics for accept floods, while
   request-rate overruns do answer 429 with `Retry-After`.

3. **Per-read header deadlines, not a total-header deadline.** The header
   phase is bounded per read operation (nginx `client_header_timeout`
   semantics): a client whose inter-read gap exceeds `readHeaderTimeout`
   is cut with a 408, and each deadline fires at socket level so the
   channel stays open for that 408. A client that keeps sending header
   bytes faster than the deadline is making legitimate progress.

---

## Running the hostile suite

```sh
HOSTILE=true sbt testAll
```

Gated behind `HOSTILE=true` so it doesn't slow the default dev loop. `testAll` forces the run; a plain `test` skips suites whose cached results are fresh.
All scenarios are expected to pass on HEAD; any failure is either a
regression or a tightened invariant that requires spec updates.

See `HOSTILE_TESTING.md` for the test-writing conventions.

---

## Reporting a vulnerability

Open a security advisory on the repo rather than a public issue.
