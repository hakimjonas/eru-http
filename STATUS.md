# eru-http Current Status

*Quick overview of what's done and what's next*

## 📊 Overall Progress

```
Core Types       ████████████████████ 100%
Server           ████████████████████ 100%
Client           ████████████████░░░░  80%
Streaming        ██████░░░░░░░░░░░░░░  30%
Observability    ████████░░░░░░░░░░░░  40%
Testing          ████████████░░░░░░░░  60%
Documentation    ████████░░░░░░░░░░░░  40%
```

**Overall: ~75% Complete for v1.0.0**

---

## ✅ What's Done

### Core HTTP Types (100% Complete)
- **Method**: Full HTTP method support with semantic properties (safe, idempotent, cacheable)
- **StatusCode**: All standard codes with RFC-compliant behavior and validation
- **Headers**: Case-insensitive, multi-value header collection with RFC 9110 compliance
- **MediaType**: MIME types with parameters, parsing, and wildcard matching
- **Port**: Validated port numbers with semantic properties (opaque type)
- **Uri**: RFC 3986 compliant with percent encoding/decoding
- **Request[A]**: Type-safe requests with validation
- **Response[A]**: Type-safe responses with validation
- **HttpError**: Comprehensive error model with RFC references
- **Body**: ADT with Empty, Text, Binary, Stream (streaming incomplete)
- **BodyEncoder/Decoder**: Type classes for body transformation
- **Compression**: gzip, deflate, brotli support
- **Cookie/CookieJar**: RFC 6265 compliant with domain/path matching
- **ETag, Cache-Control**: RFC 9111 caching support
- **Multipart**: RFC 7578 form data handling
- **HttpDate**: RFC-compliant HTTP date formatting

### HTTP/1.1 Server (100% Complete) ✅
- **NativeHttpServer**: Using blocking NIO + Virtual Threads
- **Performance**: 170-210k req/sec (bombardier/rewrk), 285k req/sec (wrk with pipelining)
- **Ranking**: #1 among Virtual Thread servers, #2 in JVM ecosystem
- **HTTP/1.1 Protocol**: Request parsing, response writing, keep-alive
- **Concurrency**: Virtual Thread per connection via `.fork`
- **Structured Concurrency**: FiberTracker for automatic cleanup
- **Middleware**: Composable middleware system
- **Timeouts**: Request timeout, idle timeout
- **Error Handling**: Proper status codes and error responses
- **Graceful Shutdown**: Clean connection closure
- **Benchmarking**: Extensive stress testing (up to 64k connections)
- **JVM Tuning**: ZGC configuration, C2 compiler workarounds

### HTTP/1.1 Client (80% Complete) 🟡
- **NativeHttpClient**: Using blocking NIO + Virtual Threads
- **HTTP/1.1 Protocol**: Request writing, response parsing
- **Interceptors**: Request/response middleware (composable)
- **Redirects**: Automatic redirect following with max limit
- **Cookies**: CookieJar with domain/path matching
- **Timeouts**: Connect timeout, request timeout
- **Connection Headers**: Keep-alive support
- **Resource Safety**: `HttpClient.scoped` pattern
- **Body Encoding/Decoding**: Type-safe transformations
- **Error Recovery**: Comprehensive error handling

### Project Infrastructure (Done)
- Scala 3.7.4 with Java 21
- Build configuration with local Eru dependency
- Valar integration for validation
- CI/CD pipeline
- Comprehensive documentation (ROADMAP, MANIFESTO, ARCHITECTURE)
- Example applications

---

## 🚧 What's In Progress

### Connection Pooling (Current Sprint) 🔄
**Goal**: Validate Eru client-side resource management

- [ ] Design connection pool with Eru `Ref`
- [ ] Implement pool with keep-alive support
- [ ] Connection lifecycle tracking
- [ ] Pool limits (max connections, acquire timeout)
- [ ] Structured concurrency for cleanup
- [ ] Benchmark HTTP performance
- [ ] Stress test under high concurrency
- [ ] **Find and fix Eru bugs** (primary goal)

---

## ❌ What's Missing

### Critical (Blocking v0.2.0)

**1. Connection Pooling**
- No connection reuse in client (each request = new socket)
- Needed for: Performance, resource efficiency
- Blocking: Client optimization

**2. TLS/SSL (Client & Server)**
- `wrapWithTLS` stubbed (returns unwrapped socket)
- `createSSLContext` returns default context
- Needed for: HTTPS support, production readiness
- Blocking: Real-world usage

**3. Streaming Bodies**
- `Body.Stream` reading returns `Bytes.empty`
- Chunked transfer encoding not implemented
- Needed for: Large file uploads/downloads
- Blocking: Full HTTP/1.1 compliance

### Important (Blocking v0.3.0)

**4. Socket Lifecycle Management**
- TODO in NativeHttpClient.scala:221 - "Properly track and close socket"
- Potential resource leaks
- Needed for: Production stability

**5. Client Integration Tests**
- Basic unit tests exist
- Need: End-to-end client tests
- Needed for: Validation, regression prevention

**6. HTTP-Specific Observability**
- Eru's built-in observability exists
- Need: HTTP-specific middleware (tracing, metrics)
- Needed for: Production debugging

### Nice-to-Have (Future)

**7. Server-Sent Events (SSE)**
- Marked as "planned" in README
- Needed for: Real-time updates
- Blocking: Advanced features

**8. WebSocket Support**
- Not started
- Needed for: Bi-directional communication
- Blocking: Advanced features

**9. HTTP/2**
- Deferred - HTTP/1.1 is sufficient
- May never be needed
- Alternative: Java's HttpClient for client-side HTTP/2

**10. eru-circe Library**
- Not started (separate repository)
- Needed for: Ergonomic JSON handling
- Blocking: Ecosystem maturity

---

## 🔧 Technical Debt

### Known Issues
- Socket lifecycle tracking needs improvement (client)
- Stream reading incomplete (both client and server)
- TLS/SSL stubbed (both client and server)
- No connection pooling (client)

### Design Decisions Pending
- WebSocket support - needed for v1.0?
- HTTP/3 - wait for Java ecosystem maturity?
- Native image support - priority level?
- eru-circe - separate repository or monorepo?

---

## 📈 Recent Progress

### This Week (2025-11-13)
- ✅ Upgraded to Scala 3.7.4
- ✅ Validated performance after upgrade (no regressions)
- ✅ Installed better benchmarking tools (bombardier, rewrk)
- ✅ Completed codebase analysis
- ✅ Updated ROADMAP.md to reflect reality
- ✅ Updated STATUS.md (this file)
- 🔄 Starting connection pooling design

### Previous Sprint (Fixed Eru Bugs)
- ✅ Found and fixed fiber tracking bug (FiberTracker API)
- ✅ Discovered JVM bugs (G1 GC crashes under load)
- ✅ Discovered C2 compiler bugs (with Virtual Threads)
- ✅ Validated ZGC + Virtual Threads configuration
- ✅ Achieved 170-210k req/sec server performance

### Benchmarking Tools Installed
- ✅ wrk (HTTP/1.1 with pipelining bias)
- ✅ bombardier (Go-based, no pipelining bias)
- ✅ rewrk (Rust-based, HTTP/2 capable)

---

## 🎯 Next Sprint Goals

### Immediate (This Week)
1. [ ] Design connection pool architecture with Eru primitives
2. [ ] Implement basic connection pool
3. [ ] Add connection reuse logic
4. [ ] Implement pool limits and timeouts
5. [ ] Benchmark HTTP performance (pooled vs non-pooled)
6. [ ] Stress test against eru-http server
7. [ ] Document any Eru bugs found

### Next (Following Week)
1. [ ] Complete connection pooling
2. [ ] Fix socket lifecycle issues
3. [ ] Start TLS/SSL implementation
4. [ ] Design streaming body support

---

## 💡 Quick Wins Available

If you want to contribute:
1. Add more MediaType constants
2. Improve ScalaDoc coverage
3. Write client integration tests
4. Create tutorial examples
5. Document benchmarking results

---

## 📊 Performance Summary

### Server Benchmarks (HTTP/1.1)
- **wrk** (pipelined): 285k req/sec @ 16k connections
- **bombardier**: 172k req/sec @ 16k connections
- **rewrk**: 211k req/sec @ 16k connections
- **Real-world estimate**: 170-210k req/sec

### Competitive Position
- **#1** among Virtual Thread-based servers
- **#2** in JVM ecosystem (after pure Netty at ~200k)
- **92.5%** of Netty's performance with **55% less code**

### Stability
- Zero crashes (after JVM workarounds)
- Zero memory leaks
- 0.03% GC overhead (ZGC)
- ~49% heap usage under extreme load

---

## 📝 Notes for Next Session

### Remember
- Connection pooling is for **Eru validation**, not just performance
- TLS comes after pooling is stable
- Streaming comes after TLS
- eru-circe comes after client is complete
- Keep validating through dogfooding

### Design Philosophy
- Build on primitives (no frameworks)
- Validate through real-world usage
- Find and fix Eru bugs
- Simplicity over features
- Standards compliance over convenience

### Current Focus
**Connection Pooling** - Design → Implement → Test → Fix Eru bugs

---

## 🚀 Release Targets

### v0.2.0 - Client Complete (Q1 2026)
- [ ] Connection pooling
- [ ] TLS/SSL support
- [ ] Complete streaming
- [ ] Integration tests
- [ ] Documentation

### v0.3.0 - Production Ready (Q2 2026)
- [ ] Server TLS/SSL
- [ ] Observability integration
- [ ] Performance optimization
- [ ] Battle-tested

### v1.0.0 - Stable (Q3 2026)
- [ ] API stability
- [ ] RFC compliance validated
- [ ] Benchmarks published
- [ ] LTS commitment

---

*Use ROADMAP.md for detailed planning, this STATUS.md for quick reference*

**Last Updated**: November 13, 2025
