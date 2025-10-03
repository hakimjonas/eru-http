# eru-http Roadmap

*Building a standards-compliant HTTP implementation on Eru*

## Project Status: 🟡 Early Development

**Current Version**: 0.0.1-SNAPSHOT
**Progress**: ~30% core types, 0% implementation

---

## Phase 1: Core Types and Models *(~30% Complete)*

### HTTP Fundamentals ✅ Complete
- [x] Method (RFC 9110 Section 9)
  - [x] Standard methods with properties (safe, idempotent, cacheable)
  - [x] Custom method validation
  - [x] Request body restrictions
- [x] StatusCode (RFC 9110 Section 15)
  - [x] All standard status codes
  - [x] Semantic properties (cacheable, retryable, etc.)
  - [x] Required headers per status
  - [x] Response body restrictions
- [x] HttpVersion enum
  - [x] HTTP/1.0, 1.1, 2.0, 3.0 support
- [x] Port opaque type
  - [x] Validation (1-65535)
  - [x] Well-known port constants
  - [x] Privilege requirements

### Headers System ✅ Complete
- [x] Headers collection
  - [x] Case-insensitive names (CIString)
  - [x] Multi-value support
  - [x] Common header constants
- [x] HeaderValue opaque type
  - [ ] Value validation per RFC 9110 Section 5.5
  - [ ] Special header parsers (Date, ETag, etc.)

### URI/URL ⚠️ Partial
- [x] Uri opaque type
- [x] Basic URI components (scheme, authority, path, query, fragment)
- [x] URI building DSL
- [ ] Full RFC 3986 parser
- [ ] Percent encoding/decoding
- [ ] Relative URI resolution
- [ ] URI templates (RFC 6570)

### Content Types ✅ Complete
- [x] MediaType with parameters
- [x] Common media type constants
- [x] Media type parsing
- [x] Wildcard matching
- [ ] Quality values (q-values) for content negotiation

### Request/Response ✅ Complete
- [x] Request[A] type
- [x] Response[A] type
- [x] Builder methods
- [x] Validation against RFC rules
- [x] Convenience constructors

### Error Model ✅ Complete
- [x] HttpError enum
- [x] Conversion to exceptions
- [x] RFC references in errors

---

## Phase 2: Body Handling *(0% Complete)*

### Body Types ❌ Not Started
- [ ] Body trait/type class
- [ ] EmptyBody implementation
- [ ] ByteBody for raw bytes
- [ ] StringBody with charset
- [ ] StreamBody for streaming
- [ ] FileBody for file uploads/downloads
- [ ] MultipartBody for forms

### Encoders/Decoders ❌ Not Started
- [ ] BodyEncoder type class
- [ ] BodyDecoder type class
- [ ] Built-in encoders (String, Bytes, JSON)
- [ ] Built-in decoders (String, Bytes, JSON)
- [ ] Streaming encoder/decoder support
- [ ] Chunked transfer encoding
- [ ] Gzip/Deflate compression

### Content Negotiation ❌ Not Started
- [ ] Accept header parsing with q-values
- [ ] Content-Type negotiation
- [ ] Language negotiation
- [ ] Encoding negotiation

---

## Phase 3: HTTP Client *(0% Complete)*

### Connection Management ❌ Not Started
- [ ] Connection pool
- [ ] Keep-alive support
- [ ] Connection timeout
- [ ] SSL/TLS support
- [ ] Proxy support

### Client Core ❌ Not Started
- [ ] HttpClient trait
- [ ] Default client implementation
- [ ] Request execution
- [ ] Response handling
- [ ] Error recovery

### Client Features ❌ Not Started
- [ ] Retry policies
- [ ] Circuit breaker
- [ ] Request/Response interceptors
- [ ] Metrics collection
- [ ] Logging middleware

### Protocol Support ❌ Not Started
- [ ] HTTP/1.1 full implementation
- [ ] HTTP/2 support (RFC 9113)
- [ ] WebSocket upgrade (RFC 6455)
- [ ] Server-Sent Events

---

## Phase 4: HTTP Server *(0% Complete)*

### Server Core ❌ Not Started
- [ ] HttpServer trait
- [ ] Request handler type
- [ ] Default server implementation
- [ ] Virtual Thread integration

### Request Processing ❌ Not Started
- [ ] Request parsing
- [ ] Body streaming
- [ ] Multipart parsing
- [ ] Form parsing
- [ ] Cookie parsing

### Response Generation ❌ Not Started
- [ ] Response serialization
- [ ] Chunked responses
- [ ] File serving
- [ ] Range requests (RFC 7233)

### Server Features ❌ Not Started
- [ ] Request size limits
- [ ] Timeout handling
- [ ] Error handlers
- [ ] Static file serving
- [ ] WebSocket support

---

## Phase 5: Standards Compliance *(0% Complete)*

### Caching (RFC 9111) ❌ Not Started
- [ ] Cache-Control parsing
- [ ] ETag generation/validation
- [ ] Last-Modified handling
- [ ] Conditional requests
- [ ] Vary header support

### Authentication ❌ Not Started
- [ ] Basic Authentication (RFC 7617)
- [ ] Bearer Token (RFC 6750)
- [ ] Digest Authentication (RFC 7616)
- [ ] Authentication framework

### CORS ❌ Not Started
- [ ] CORS preflight handling
- [ ] CORS header validation
- [ ] Configurable CORS policies

### Security Headers ❌ Not Started
- [ ] HSTS support
- [ ] CSP parsing/validation
- [ ] X-Frame-Options
- [ ] X-Content-Type-Options

### Cookies (RFC 6265) ❌ Not Started
- [ ] Cookie parsing
- [ ] Cookie jar
- [ ] SameSite support
- [ ] Secure/HttpOnly flags

---

## Phase 6: Testing & Documentation *(~10% Complete)*

### Testing ⚠️ Minimal
- [x] Basic unit tests for core types
- [ ] Property-based tests for RFC compliance
- [ ] Integration tests
- [ ] Performance benchmarks
- [ ] RFC compliance test suite
- [ ] Mock client/server for testing

### Documentation ❌ Not Started
- [ ] ScalaDoc for all public APIs
- [ ] User guide
- [ ] Client examples
- [ ] Server examples
- [ ] Migration guide from other libraries
- [ ] RFC compliance documentation

### Tooling ❌ Not Started
- [ ] sbt plugin for code generation
- [ ] OpenAPI integration
- [ ] Curl-like CLI tool
- [ ] Debug proxy

---

## Phase 7: Performance & Production *(0% Complete)*

### Performance ❌ Not Started
- [ ] JMH benchmarks
- [ ] Memory profiling
- [ ] Zero-copy optimizations
- [ ] Object pooling
- [ ] Direct buffers

### Production Features ❌ Not Started
- [ ] Graceful shutdown
- [ ] Health checks
- [ ] Metrics export (Prometheus, etc.)
- [ ] Distributed tracing
- [ ] Rate limiting

### Platform Support ❌ Not Started
- [ ] GraalVM native image support
- [ ] Scala Native support (client only)
- [ ] Module system (JPMS) support

---

## Release Milestones

### v0.1.0 - Core Types
- [ ] All Phase 1 items complete
- [ ] Basic body handling
- [ ] Minimal documentation
- [ ] Published to Maven Central

### v0.2.0 - HTTP Client
- [ ] Phase 2 complete
- [ ] Phase 3 complete
- [ ] Client documentation and examples

### v0.3.0 - HTTP Server
- [ ] Phase 4 complete
- [ ] Server documentation and examples
- [ ] WebSocket support

### v0.4.0 - Standards Complete
- [ ] Phase 5 complete
- [ ] Full RFC compliance
- [ ] Comprehensive test suite

### v1.0.0 - Production Ready
- [ ] All phases complete
- [ ] Performance optimized
- [ ] Battle-tested in production
- [ ] Complete documentation
- [ ] Stability guarantee

---

## Current Sprint Focus

### Immediate Next Steps
1. [ ] Complete URI parsing per RFC 3986
2. [ ] Implement basic body types
3. [ ] Create body encoder/decoder framework
4. [ ] Start HTTP/1.1 client implementation
5. [ ] Add property-based tests for existing types

### Blocked/Waiting
- Eru 0.1.0 release (dependency)

---

## Design Decisions Needed

### Open Questions
1. Should we support HTTP/3 from the start or add later?
2. How should we handle async file I/O?
3. Should connection pooling be pluggable?
4. Do we need our own TLS implementation or use JDK?

### Decisions Made
- ✅ Use opaque types for type safety
- ✅ Virtual Threads for concurrency (no callbacks)
- ✅ Strict RFC compliance over convenience
- ✅ No built-in JSON/XML (separate modules)
- ✅ Effects via Eru (no Future/IO)

---

## Contributing Areas

### Good First Issues
- [ ] Add more status codes from registries
- [ ] Implement Date header parser
- [ ] Add more media type constants
- [ ] Write unit tests for Uri

### Help Wanted
- [ ] HTTP/2 implementation expertise
- [ ] Performance optimization
- [ ] RFC compliance testing
- [ ] Documentation writing

---

## Notes

- Each phase builds on the previous one
- We prioritize correctness over features
- Performance optimization comes after correctness
- The library should be useful even at v0.1.0
- With focused effort and lessons from Valar/Eru, progress should be rapid

**Last Updated**: October 2025