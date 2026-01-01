# eru-http Infrastructure Roadmap

*Building world-class HTTP infrastructure on Eru - from the ground up*

## Project Status: 🟢 HTTP/1.1 Complete, TLS Next

**Current Version**: 0.0.1-SNAPSHOT
**Progress**: HTTP/1.1 stack complete with compression, TLS implementation next

---

## Design Principles

1. **Build enablers first, build them correctly** - Foundational infrastructure before features
2. **Leverage Eru's infrastructure** - Effects, Ref, Semaphore, fork/forkDaemon
3. **Zero-allocation where possible** - Buffer pooling, byte-level parsing, pre-interned strings
4. **Virtual Threads everywhere** - Blocking I/O is efficient (~10KB vs ~2MB OS threads)
5. **RFC compliance** - Follow HTTP standards strictly
6. **Streaming-first** - Constant memory usage regardless of body size

---

## ✅ Completed Infrastructure

### HTTP/1.1 Core (RFC 9110, 9111, 9112)
- **HttpParser** - Zero-allocation byte-level parser with BufferedSocketReader
  - Request/response line parsing
  - Header parsing with pre-interned common names
  - Chunked transfer encoding (read)
  - Content-Length body reading
  - Connection pooling optimizations
- **HttpWriter** - Zero-allocation serialization with ByteBuffer pooling
  - Request/response writing
  - Chunked transfer encoding (write)
  - Direct buffer writes for zero-copy I/O
- **BufferedSocketReader** - 8KB direct ByteBuffer, TCP_QUICKACK support

### HTTP Type System (Complete)
- **Method** - All standard methods with semantic properties
- **StatusCode** - All standard status codes with validation
- **Headers** - Case-insensitive, multi-value, TreeMap-based
- **Uri** - RFC 3986 parsing (authority, path, query, fragment)
- **Request/Response** - Full validation, builder methods
- **Body** - Text, Binary, Stream (ChunkStream), Empty
- **MediaType** - Full parsing with parameters, charset, boundary
- **Cookie** - RFC 6265 with domain/path matching, SameSite
- **CacheControl** - RFC 9111 directives (no-cache, max-age, etc.)
- **ETag** - Strong/weak ETags with matching and hashing
- **HttpDate** - RFC 9110 date parsing/formatting
- **Multipart** - RFC 7578 multipart/form-data encoding/decoding
- **ServerSentEvent** - WHATWG SSE spec implementation

### HTTP Server (NativeHttpServer)
- Blocking NIO + Virtual Threads
- SO_REUSEPORT multi-threaded accept
- Per-connection request loop (HTTP keep-alive)
- BufferedSocketReader pooling per connection
- ByteBuffer pooling for zero-allocation response writing
- TCP_NODELAY and TCP_QUICKACK optimizations
- Structured concurrency with forkDaemon
- Automatic Content-Length/Transfer-Encoding headers
- Connection: keep-alive/close handling

### HTTP Client (NativeHttpClient)
- Request execution with encoding/decoding
- Interceptor support (request/response)
- Cookie jar integration with RFC 6265 matching
- Redirect following (configurable max redirects)
- Connection pooling with backpressure

### Connection Pool (Production-Grade)
- Semaphore-based backpressure (global + per-host)
- FIFO fairness
- BufferedSocketReader pooling (8KB + StringBuilder)
- ByteBuffer pooling (4KB direct)
- Connection reuse with HTTP/1.1 keep-alive
- TCP_NODELAY and TCP_QUICKACK optimizations
- Proper cleanup on errors

### Compression (Primitives Ready)
- Compression.scala with gzip, deflate, brotli support
- Using java.util.zip and brotli4j

### Performance Achievements
- **Memory**: Reduced allocation from 35 GB/s to ~936 MB/s (97% reduction)
- **Streaming**: Constant memory for 100KB+ request/response bodies
- **Concurrency**: Efficient Virtual Thread usage (~10KB per connection)

---

## ✅ Completed Enterprise Enablers

### Compression Middleware ✅

**Completed**: Full implementation in `Middleware.scala:356-467`

- ✅ Inspects `Accept-Encoding` request header
- ✅ Compresses response body (gzip, deflate, brotli)
- ✅ Adds `Content-Encoding` response header
- ✅ Skips already-compressed content
- ✅ Handles Text, Binary, and Stream bodies
- ✅ Configurable minSize threshold and encoding preferences
- ✅ `CompressionConfig` with default, aggressive, conservative presets

**Test Coverage**: `MiddlewareSpec.scala` (compression tests), `CompressionSpec.scala`

---

### Client Decompression ✅

**Completed**: Full implementation in `NativeHttpClient.scala:278-366`

- ✅ Inspects `Content-Encoding` response header
- ✅ Decompresses gzip, deflate, brotli automatically
- ✅ Removes `Content-Encoding` header after decompression
- ✅ Updates `Content-Length` appropriately
- ✅ Handles streaming responses
- ✅ Configurable via `HttpClientConfig.automaticDecompression`
- ✅ Adds `Accept-Encoding` header automatically when enabled

**Test Coverage**: `CompressionIntegrationSpec.scala` (end-to-end tests)

---

## 🎯 Next: Enterprise Enablers (Priority Order)

### Priority 1: TLS/SSL Support (NEXT) 🔄

**Goal**: HTTPS for both client and server

**Why now**:
- Critical blocker for production use
- Required foundation for WebSocket/HTTP/2
- Medium complexity (Java SSLEngine API)
- Enables secure communication

**Current state**: Stubs exist that return unwrapped sockets
- `NativeHttpClient.scala:389-405` - `wrapWithTLS()` TODO
- `NativeHttpClient.scala:548-558` - `createSSLContext()` TODO
- `NativeHttpServer.scala:320-331` - `wrapWithTLS()` TODO
- `NativeHttpServer.scala:484-495` - `createSSLContext()` TODO

**Requirements**:
- Create `SSLEngineSocketWrapper` class (~200-300 lines)
- Implement `wrapWithTLS()` in NativeHttpServer
- Implement `wrapWithTLS()` in NativeHttpClient
- SSLEngine integration with blocking NIO
- TLS handshake on Virtual Threads (blocking is fine)
- Certificate/key loading from existing TlsConfig
- Support TLS 1.2 and 1.3
- Proper error handling for SSL errors
- SNI support (Server Name Indication)

**Acceptance Criteria**:
- ☐ Server accepts HTTPS connections
- ☐ Client makes HTTPS requests
- ☐ Certificate validation works
- ☐ Self-signed certs work in dev (with `TlsConfig.insecure`)
- ☐ Performance comparable to HTTP (TLS overhead only)

---

### Priority 2: WebSocket Support 📋

**Goal**: Real-time bidirectional communication via WebSocket

**Why second**:
- High enterprise value
- Builds on TLS foundation
- Medium complexity (RFC 6455)
- More straightforward than HTTP/2

**Requirements**:
- WebSocket handshake (HTTP Upgrade)
- Frame parsing (text, binary, control frames)
- Frame writing with masking (client) and unmasked (server)
- Ping/pong heartbeat
- Close handshake
- Streaming messages via ChunkStream
- Backpressure handling with Eru effects

**Acceptance Criteria**:
- ☐ Client connects to WebSocket server
- ☐ Server accepts WebSocket upgrade
- ☐ Bidirectional text/binary messages
- ☐ Ping/pong keeps connection alive
- ☐ Graceful close handshake
- ☐ Works over TLS (wss://)

---

### Priority 3: HTTP/2 Support 📋

**Goal**: Modern protocol with multiplexing

**Why third**:
- High complexity (HPACK, streams, flow control)
- Less critical than WebSocket for many use cases
- Can be deferred until other enablers complete
- Config flags already exist

**Requirements**:
- HTTP/2 connection preface
- HPACK header compression/decompression
- Stream multiplexing with priority
- Flow control (connection and stream level)
- Server push capability
- ALPN negotiation over TLS
- Upgrade from HTTP/1.1 (h2c)

**Acceptance Criteria**:
- ☐ Client negotiates HTTP/2 via ALPN
- ☐ Server accepts HTTP/2 connections
- ☐ Multiple concurrent streams per connection
- ☐ HPACK reduces header overhead
- ☐ Flow control prevents overwhelming
- ☐ Performance improvement over HTTP/1.1 (multiplexing)

---

## 📦 Deferred (Nice-to-Have, Not Blockers)

- **Connection pool metrics** - Observability (size, wait times, reuse rate)
- **Circuit breaker** - Failure isolation between hosts
- **Retry logic** - Automatic retry with exponential backoff
- **Request tracing** - Correlation IDs, distributed tracing
- **Body size limits** - Config exists but not enforced in parser
- **Idle connection eviction** - Pool doesn't evict stale connections
- **DNS caching** - New socket per connection, no DNS cache
- **Rate limiting middleware** - Request throttling
- **CORS middleware** - Cross-origin resource sharing
- **Auth middleware** - JWT, OAuth, Basic Auth

---

## 🎯 Performance Targets

- **Plaintext**: Competitive with top JVM frameworks on TechEmpower
- **JSON**: Competitive encoding/decoding performance
- **Streaming**: Constant memory (<1 GB/s allocation) for 100KB+ responses
- **Connections**: Support 10,000+ concurrent with Virtual Threads
- **Latency**: Sub-millisecond p50, single-digit ms p99 for simple requests

---

## 📚 Documentation Status

- ✅ ScalaDoc for all public types
- ✅ Inline RFC references in code
- ❌ User guide (pending)
- ❌ Client examples (pending)
- ❌ Server examples (pending)

---

**Last updated**: 2026-01-01
