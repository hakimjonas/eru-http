# eru-http Roadmap

*Standards-compliant HTTP implementation on Eru with Virtual Threads*

## Project Status: 🟢 Active Development

**Current Version**: 0.1.0-SNAPSHOT
**Scala Version**: 3.7.4
**Java Version**: 21 (Virtual Threads)
**Progress**: ~75% complete for v1.0.0

---

## Architecture Overview

eru-http uses **native blocking NIO + Virtual Threads** built directly on Eru effects:
- ✅ No Netty, no event loops, no callbacks
- ✅ Simple blocking I/O (efficient on Virtual Threads)
- ✅ Direct SocketChannel/ServerSocketChannel usage
- ✅ One Virtual Thread per connection (server) or request (client)
- ✅ Structured concurrency with automatic cleanup

**Performance**: 170-210k req/sec (HTTP/1.1 server, real-world benchmarks)

---

## Phase 1: Core HTTP Types ✅ **COMPLETE**

### HTTP Fundamentals ✅
- [x] Method (RFC 9110 Section 9) with semantic properties
- [x] StatusCode (RFC 9110 Section 15) with validation
- [x] HttpVersion (HTTP/1.0, 1.1, 2.0, 3.0)
- [x] Port opaque type with validation

### Headers System ✅
- [x] Headers collection (case-insensitive, multi-value)
- [x] HeaderValue with RFC 9110 validation
- [x] Common header constants (HeaderNames)
- [x] Special parsers (Cookie, SetCookie, ETag, CacheControl)

### URI/URL ✅
- [x] Uri opaque type with RFC 3986 compliance
- [x] URI components (scheme, authority, path, query, fragment)
- [x] Percent encoding/decoding
- [x] URI building DSL

### Content Types ✅
- [x] MediaType with parameters
- [x] Common media type constants
- [x] Media type parsing and wildcard matching
- [x] Charset support

### Request/Response ✅
- [x] Request[A] type with validation
- [x] Response[A] type with validation
- [x] Builder methods
- [x] Convenience constructors (Request.get, Response.ok, etc.)

### Body Handling ✅
- [x] Body ADT (Empty, Text, Binary, Stream)
- [x] BodyEncoder/BodyDecoder type classes
- [x] Compression support (gzip, deflate, brotli)
- [x] Content encoding/decoding

### Standards Compliance ✅
- [x] Cookie handling (RFC 6265)
- [x] CookieJar with domain/path matching
- [x] ETag support (RFC 9111)
- [x] Cache-Control parsing
- [x] Multipart form data (RFC 7578)
- [x] HTTP Date formatting

### Error Model ✅
- [x] HttpError ADT with RFC references
- [x] Validation errors with context
- [x] Network errors with cause tracking

---

## Phase 2: HTTP/1.1 Server ✅ **COMPLETE**

### Server Core ✅
- [x] NativeHttpServer using blocking NIO + Virtual Threads
- [x] HTTP/1.1 protocol implementation
- [x] Request parsing (HttpParser)
- [x] Response writing (HttpWriter)
- [x] Keep-alive support (connection reuse)
- [x] Connection header management

### Concurrency & Performance ✅
- [x] Virtual Thread per connection via `.fork`
- [x] Structured concurrency with FiberTracker
- [x] Accept loop on Virtual Thread
- [x] Automatic cleanup on shutdown
- [x] **Performance**: 170-210k req/sec (bombardier/rewrk)
- [x] **Performance**: 285k req/sec (wrk with pipelining)

### Server Features ✅
- [x] Composable middleware system
- [x] Request timeout handling
- [x] Error responses with proper status codes
- [x] Idle timeout for keep-alive
- [x] Graceful shutdown

### Production Features ✅
- [x] Configurable backlog, timeouts
- [x] Host/port binding
- [x] TLS/SSL configuration (stubbed, needs implementation)
- [x] ZGC garbage collector configuration
- [x] JVM tuning for Virtual Threads

### Benchmarking & Validation ✅
- [x] Benchmark server with multiple endpoints
- [x] Stress testing (64k concurrent connections)
- [x] Memory leak validation
- [x] GC overhead measurement
- [x] JVM crash debugging and fixes

---

## Phase 3: HTTP/1.1 Client 🟡 **IN PROGRESS (~80%)**

### Client Core ✅
- [x] NativeHttpClient using blocking NIO + Virtual Threads
- [x] HTTP/1.1 protocol implementation
- [x] Request writing (HttpWriter)
- [x] Response parsing (HttpParser)
- [x] Request execution with timeout
- [x] Error recovery and validation

### Client Features ✅
- [x] Request/Response interceptors (composable middleware)
- [x] Automatic redirect handling
- [x] Cookie jar with domain/path matching
- [x] Body encoding/decoding
- [x] Timeout management (connect, request)
- [x] Connection header handling (keep-alive)
- [x] Resource-safe with `HttpClient.scoped` pattern

### Current Sprint: Connection Pooling 🔄
**Goal**: Validate Eru client-side resource management through dogfooding

- [ ] Design connection pool with Eru `Ref`
- [ ] Implement pool with keep-alive support
- [ ] Connection lifecycle tracking
- [ ] Pool limits (max connections, acquire timeout)
- [ ] Structured concurrency for cleanup
- [ ] Benchmark HTTP performance (with/without pooling)
- [ ] Test against eru-http server
- [ ] Stress test (high concurrency, connection exhaustion)
- [ ] **Find and fix Eru bugs** (primary goal)

### Next: TLS/SSL Implementation ❌
**Goal**: Production-ready HTTPS client

- [ ] Implement `wrapWithTLS` with SSLEngine
- [ ] Implement `createSSLContext` with proper trust store
- [ ] TLS handshake (blocking on Virtual Thread)
- [ ] Hostname verification
- [ ] Protocol configuration (TLS 1.2/1.3)
- [ ] Test against HTTPS APIs (GitHub, httpbin.org)
- [ ] Validate TLS + pooling interaction
- [ ] Benchmark HTTPS performance

### Later: Complete Streaming ❌
**Goal**: Full HTTP/1.1 compliance

- [ ] Implement `Body.Stream` reading/writing
- [ ] Chunked transfer encoding (RFC 9112)
- [ ] Backpressure handling with Eru fibers
- [ ] Large file upload/download testing
- [ ] Stream composition (map, filter, etc.)

---

## Phase 4: Advanced Features 🔮 **FUTURE**

### Server-Sent Events (SSE) ❌
- [ ] SSE event stream support
- [ ] Event formatting (WHATWG spec)
- [ ] Keep-alive with comments
- [ ] Retry configuration
- [ ] Multi-client broadcasting

### WebSocket Support ❌ (Optional)
- [ ] WebSocket handshake (RFC 6455)
- [ ] Frame encoding/decoding
- [ ] Ping/Pong handling
- [ ] Message streaming

### HTTP/2 Decision Point ⏸️
**Status**: Deferred - HTTP/1.1 is sufficient

**If needed later**:
- Server: Native implementation aligned with Eru philosophy
- Client: Consider wrapping Java's `java.net.http.HttpClient` (has HTTP/2 support)
- Research: Monitor Java roadmap for HTTP/2 server support

---

## Phase 5: Ecosystem Libraries 🔮 **FUTURE**

### eru-circe (Separate Repository) ❌
**Goal**: Ergonomic JSON handling for Eru ecosystem

**eru-circe-core**:
- [ ] EruEncoder[A] - encode to Eru[EncodeError, Json]
- [ ] EruDecoder[A] - decode from Json to Eru[DecodeError, A]
- [ ] Syntax helpers
- [ ] Reusable across Eru ecosystem (not HTTP-specific)

**eru-circe-http**:
- [ ] BodyEncoder[A] instances using EruEncoder
- [ ] BodyDecoder[A] instances using EruDecoder
- [ ] Request/Response helpers (.asJson, .withJsonBody)
- [ ] Depends on eru-circe-core + eru-http-core

**Dependencies**:
```scala
// eru-circe-core
"net.ghoula" %% "eru-core" % eruVersion
"io.circe" %% "circe-core" % circeVersion

// eru-circe-http
"net.ghoula" %% "eru-circe-core" % version
"net.ghoula" %% "eru-http-core" % eruHttpVersion
"io.circe" %% "circe-parser" % circeVersion
```

### Other Potential Libraries ❌
- eru-http-xml (XML support)
- eru-http-protobuf (Protocol Buffers)
- eru-http-msgpack (MessagePack)

---

## Phase 6: Observability & Tooling ⚠️ **PARTIAL**

### Built-in Observability ✅
**Status**: Leverage Eru's excellent built-in system

- [x] EruObserver for events (fiber lifecycle, tracing)
- [x] RuntimeMetrics for performance stats
- [x] EruTrace for distributed tracing
- [ ] HTTP-specific middleware (tracing, metrics, logging)
- [ ] Custom HttpMetricsObserver
- [ ] Request/response correlation IDs

### Testing Infrastructure ⚠️
- [x] Basic unit tests for core types
- [x] Server integration tests
- [ ] Client integration tests
- [ ] Property-based tests for RFC compliance
- [ ] Mock client/server for testing
- [ ] Performance regression tests

### Benchmarking ✅
- [x] wrk benchmarking
- [x] bombardier benchmarking (Go-based)
- [x] rewrk benchmarking (Rust-based, HTTP/2 capable)
- [x] Stress testing under extreme load
- [ ] JMH microbenchmarks
- [ ] Comparison benchmarks vs competitors

### Documentation ⚠️
- [x] README with quick start
- [x] Architecture documentation
- [x] Manifesto and design principles
- [ ] Complete ScalaDoc for all public APIs
- [ ] User guide
- [ ] Client examples (beyond basic)
- [ ] Server examples (beyond basic)
- [ ] Migration guide from other libraries

---

## Phase 7: Production Readiness 🔮 **FUTURE**

### Performance Optimization ❌
- [ ] JMH benchmarks for critical paths
- [ ] Memory profiling
- [ ] Zero-copy optimizations (where applicable)
- [ ] Object pooling (if needed)
- [ ] GC tuning documentation

### Production Features ❌
- [x] Graceful shutdown (server)
- [ ] Graceful shutdown (client)
- [ ] Health check endpoints
- [ ] Metrics export (Prometheus format)
- [ ] Rate limiting middleware
- [ ] Circuit breaker pattern

### Platform Support ❌
- [ ] GraalVM native image support
- [ ] Scala Native support (client only, when Eru supports it)
- [ ] Module system (JPMS) compatibility

---

## Release Milestones

### v0.1.0 - Foundation ✅ **ACHIEVED**
- [x] All core HTTP types
- [x] Complete server implementation
- [x] Partial client implementation
- [x] HTTP/1.1 support
- [x] World-class performance (170-210k req/sec)

### v0.2.0 - Client Complete 🎯 **CURRENT TARGET**
**ETA**: Q1 2026

- [ ] Connection pooling
- [ ] TLS/SSL support
- [ ] Complete streaming support
- [ ] Client integration tests
- [ ] Client documentation and examples
- [ ] Dogfooding validation complete

### v0.3.0 - Production Ready
**ETA**: Q2 2026

- [ ] Server TLS/SSL implementation
- [ ] Complete observability integration
- [ ] Performance optimization
- [ ] Comprehensive test suite
- [ ] Complete documentation
- [ ] Battle-tested in production

### v1.0.0 - Stable Release
**ETA**: Q3 2026

- [ ] API stability guarantee
- [ ] Full RFC compliance validation
- [ ] Performance benchmarks published
- [ ] Migration guides
- [ ] Long-term support commitment

---

## Current Sprint Focus (Week of 2025-11-13)

### Immediate Next Steps
1. [x] Upgrade to Scala 3.7.4
2. [x] Performance validation after upgrade
3. [x] Install better benchmarking tools (bombardier, rewrk)
4. [ ] **Design connection pool with Eru primitives**
5. [ ] Implement connection pooling
6. [ ] Benchmark and stress test
7. [ ] Fix any Eru bugs discovered

### Dogfooding Strategy
eru-http serves as **validation for Eru** by:
- Testing Eru under real-world HTTP workloads
- Discovering edge cases in fiber management
- Validating structured concurrency patterns
- Finding performance bottlenecks
- Proving Eru is production-ready

**Recent Discoveries**:
- ✅ Found and fixed fiber tracking bug (missing FiberTracker API)
- ✅ Discovered JVM bugs (G1 GC, C2 compiler) under extreme load
- ✅ Validated ZGC + Virtual Threads configuration

---

## Design Decisions

### Decisions Made ✅
- ✅ Native blocking NIO + Virtual Threads (not Netty)
- ✅ HTTP/1.1 focus (HTTP/2 deferred)
- ✅ Opaque types for type safety
- ✅ Strict RFC compliance over convenience
- ✅ No built-in JSON/XML (separate modules)
- ✅ Effects via Eru only (no Future/IO)
- ✅ Connection pooling before TLS (for validation)
- ✅ Scala 3 only (no baggage from older versions)
- ✅ Virtual Threads for all concurrency
- ✅ ZGC for garbage collection

### Open Questions ⏸️
1. WebSocket support - needed for v1.0?
2. HTTP/3 - wait for Java ecosystem maturity?
3. Native image support - priority level?
4. eru-circe - separate repository or monorepo?

---

## Contributing Areas

### Active Development
- Connection pooling implementation
- Client TLS/SSL support
- Integration testing

### Help Wanted
- Documentation writing
- Example applications
- RFC compliance testing
- Performance optimization
- GraalVM native image support

### Good First Issues
- Add more media type constants
- Write additional unit tests
- Improve ScalaDoc coverage
- Create tutorial examples

---

## Project Philosophy

### The Eru Way
eru-http demonstrates what's possible when you:
1. Build on stable, emerging technologies (Scala 3 + Virtual Threads)
2. Avoid framework baggage (no Netty, no reactive streams)
3. Use simple primitives (blocking NIO is efficient on VTs)
4. Prioritize correctness over convenience
5. Validate through real-world dogfooding

### Performance Through Design
- Server: 170-210k req/sec (real-world), 285k req/sec (pipelined)
- Ranking: #1 among VT-based servers, #2 in JVM ecosystem
- Memory: ~49% heap usage under load, 0.03% GC overhead
- Stability: Zero crashes, zero memory leaks

### Strategic Position
**"Not on the extreme cutting edge, but out in front of established systems that have baggage"**
- ✅ Scala 3 (stable since 2021, cutting edge features)
- ✅ Virtual Threads (stable since JDK 21 2023)
- ✅ Native NIO (decades-proven primitive)
- ❌ HTTP/2 (unnecessary complexity for now)
- ❌ Reactive streams (Virtual Threads are simpler)

---

## Notes

- Each phase validates Eru through real-world usage
- Performance optimization comes after correctness
- Standards compliance is non-negotiable
- Simplicity over features
- Zero external dependencies (except Valar for validation, brotli4j for compression)

**Last Updated**: November 13, 2025
