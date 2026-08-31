# eru-http Manifesto

eru-http is a standards-compliant HTTP client and server for Scala 3, built on the Eru effect system.

## Principles

### Standards first

Every implementation decision traces to an RFC. Type signatures encode HTTP semantics, invalid states are rejected at the boundary where untrusted input enters, and error messages name the RFC section that was violated. Where a choice between the letter of the standard and convenience exists, the standard wins.

Examples:

- `StatusCode` is an opaque type constructed by a validating `apply`; values outside 100–599 cannot exist.
- `HeaderName` and `HeaderValue` reject CR/LF on construction, so a header cannot smuggle a second header line.
- `Method` has no case for a malformed method; `Method.parse` fails with `InvalidMethod` instead.

### Types model the protocol

The core module is the validated model. The client and server are thin layers that move `Request` and `Response` values over sockets. Types carry the rules:

- `Request.validate` rejects a body on GET, HEAD, DELETE, or TRACE, and rejects `Content-Length` combined with `Transfer-Encoding`.
- `Response.validate` rejects bodies on 1xx, 204, and 304 responses and enforces required headers such as `Location` on 201.
- `Uri` parses per RFC 3986 and rejects malformed authority, port, and percent-encoding.

### Effects everywhere

All I/O returns `Eru[E, A]`. The error channel is typed: `HttpError` is an enum of invalid-input, network, timeout, and protocol errors. Resources are managed by `bracket` and `scoped`; a connection, pool, or server acquired inside a scope cannot outlive it.

### Blocking NIO on virtual threads

Each connection runs on its own virtual thread and blocks on a `SocketChannel`. There is no event loop, no callback state machine, and no thread pool. Timeouts and limits are per-connection `setSoTimeout`-style bounds and per-IP token buckets, not a scheduler.

### Minimal scope

eru-http provides the model, the client, the server, and middleware. It does not provide routing, serialization formats, authentication schemes, templating, or session management. These are application or framework concerns, and the handler function is the integration point.

## What is deliberately absent

- A routing DSL. Handlers pattern-match on `req.uri.path` and `req.method`.
- JSON or other payload codecs in the core. `BodyEncoder` and `BodyDecoder` typeclasses are the extension point.
- Automatic retries. `StatusCode.isRetryable` exists for callers that want them; the client does not retry on its own.
- Caching. The model knows which statuses are cacheable by default; the client and server do not cache.
- An HTTP/3 transport. The model carries `HttpVersion.HTTP_3_0`; a QUIC transport is planned for after the first stable release.

## Testing

The suite has three tiers:

1. Unit tests per module, covering the model, parser, writer, and middleware.
2. Integration tests that run real servers and clients over loopback, including TLS and HTTP/2.
3. An opt-in hostile suite (`-Dhostile=true`) that sends smuggling, slowloris, connection-flood, oversized-frame, and malformed-protocol traffic at the server. It is opt-in because it is slow and deliberately violates protocol assumptions. See [HOSTILE_TESTING.md](HOSTILE_TESTING.md).

The repository ships the modules a release must publish: core, client, server, and examples.

## Versioning

Versioning follows early-semver. Breaking changes to the public API are only made before 1.0.0 or across a major version.

## The promise

eru-http exists because HTTP libraries that accept malformed input quietly produce malformed output. The alternative is a library where the model is the standard: what the types accept, the wire sends, and what the wire receives, the types reject when it is invalid.
