# eru-http Infrastructure Roadmap

*Building world-class HTTP infrastructure on Eru - from the ground up*

## Project Status: 🟢 HTTP/1.1 + TLS + WebSocket Complete, HTTP/2 Next

**Current Version**: 0.0.1-SNAPSHOT
**Progress**: Full HTTP/1.1 stack with TLS/HTTPS and RFC 6455 WebSocket support

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

### ✅ Priority 1: TLS/SSL Support (COMPLETE)

**Goal**: HTTPS for both client and server

**Implementation**:
- `SSLSocketChannel` - TLS wrapper implementing `ReadableByteChannel`/`WritableByteChannel` (~400 lines)
- `SSLContextFactory` - SSL context creation for client and server
- Connection pool SSL channel management for TLS session reuse
- Blocking TLS handshake (efficient on Virtual Threads)

**Features Delivered**:
- ✅ SSLEngine integration with blocking NIO
- ✅ TLS 1.2 and 1.3 support
- ✅ SNI (Server Name Indication) for virtual hosting
- ✅ Hostname verification (configurable)
- ✅ Certificate/key loading from PKCS12 keystores
- ✅ `TlsConfig.insecure` for self-signed certs in dev
- ✅ TLS connection reuse across HTTP keep-alive requests

**Acceptance Criteria** (All Met):
- ✅ Server accepts HTTPS connections
- ✅ Client makes HTTPS requests
- ✅ Certificate validation works
- ✅ Self-signed certs work in dev (with `TlsConfig.insecure`)
- ✅ Connection reuse works over TLS (4 integration tests pass)

---

### ✅ Priority 2: WebSocket Support (COMPLETE)

**Goal**: Real-time bidirectional communication via WebSocket

**Implementation**:
- `WebSocketFrameParser` - Frame parsing with fragmentation and control frame interleaving
- `WebSocketFrameWriter` - Frame writing with masking (client) and unmasked (server)
- `WebSocketHandshake` - HTTP Upgrade handshake with key/accept validation
- `WebSocketClient` / `NativeWebSocketClient` - Client API with `connect` and `scoped`
- `WebSocketServer` / `NativeServerWebSocketConnection` - Server upgrade handler

**Features Delivered**:
- ✅ WebSocket handshake (HTTP Upgrade) per RFC 6455 Section 4
- ✅ Frame parsing (text, binary, ping, pong, close)
- ✅ Message fragmentation with interleaved control frames (Section 5.4)
- ✅ Client frames masked, server frames unmasked (Section 5.3)
- ✅ Automatic ping/pong handling during receive
- ✅ Close handshake with code validation (Section 7.4)
- ✅ UTF-8 validation on reassembled messages (Section 5.6)
- ✅ Subprotocol negotiation
- ✅ Works over TLS (wss://)

**Acceptance Criteria** (All Met):
- ✅ Client connects to WebSocket server
- ✅ Server accepts WebSocket upgrade
- ✅ Bidirectional text/binary messages
- ✅ Ping/pong keeps connection alive
- ✅ Graceful close handshake
- ✅ Works over TLS (wss://)
- ✅ **Autobahn Test Suite: 247/247 tests pass (100% RFC 6455 compliance)**

---

### Priority 3: HTTP/2 Support 📋 (IN PROGRESS)

**Goal**: Modern protocol with multiplexing (RFC 9113)

**Why HTTP/2 before HTTP/3**:
- HTTP/2 adoption: ~70% of websites
- HTTP/3 requires QUIC which needs TLS APIs not fully exposed in Java 25
- JEP 517 (HTTP/3) in Java 26 is for JDK's HttpClient, not custom implementations
- Oracle LTS policy: Features not backported to LTS versions
- HTTP/2 over TCP works naturally with Virtual Threads

**Implementation Plan**: See `HTTP2_IMPLEMENTATION_PLAN.md`

**Key Components**:
- ✅ HPACK header compression (built from scratch, 8 files, 89 tests)
- ✅ Binary frame layer (10 frame types, 27 tests)
- ✅ Stream multiplexing with 7-state machine (40 tests)
- ✅ Connection management with dual-level flow control (52 tests)
- ☐ ALPN negotiation for TLS
- ☐ h2c upgrade for cleartext
- ☐ Client integration
- ☐ Server integration

**Progress**: Phases 1-4 complete (208 HTTP/2-specific tests)

**Acceptance Criteria**:
- ☐ Client negotiates HTTP/2 via ALPN
- ☐ Server accepts HTTP/2 connections
- ☐ Multiple concurrent streams per connection
- ☑ HPACK reduces header overhead (Phase 1 complete)
- ☑ Flow control primitives implemented (Phases 3-4 complete)
- ☐ h2spec conformance tests pass

---

## 📦 Deferred (Nice-to-Have, Not Blockers)

### HTTP/3 Support (Deferred to Java 29 LTS or later)

**Why deferred**:
- QUIC requires TLS APIs not exposed in Java 25 (`ExtendedSSLSession.exportKeyingMaterial*` exists but handshake-level secrets not available)
- Pure Java QUIC implementations (Kwik) require custom TLS 1.3 handshake code
- JEP 517 in Java 26 adds HTTP/3 to JDK's HttpClient only, not APIs for custom implementations
- Oracle LTS policy: Features not backported (verified via support roadmap)
- HTTP/3 adoption: ~37% (growing but HTTP/2 covers majority)

**Revisit when**:
- Java 29 LTS (expected ~2027) if it includes QUIC APIs
- Pure Java QUIC ecosystem matures
- HTTP/3 adoption exceeds HTTP/2

### Other Deferred Items

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

**Last updated**: 2026-01-23
