# eru-http Current Status

*Quick overview of what's done and what's next*

## Overall Progress

```
Core Types      ████████████████████  100%
Body Handling   ████████████████████  100%
HTTP Parser     ████████████████████  100%
HTTP Writer     ████████████████████  100%
HTTP Server     ████████████████████  100%
HTTP Client     ████████████████████  100%
Connection Pool ████████████████████  100%
Compression     ████████████████████  100%
TLS/SSL         ████████████████████  100%
WebSocket       ░░░░░░░░░░░░░░░░░░░░    0%
HTTP/2          ░░░░░░░░░░░░░░░░░░░░    0%
Testing         ████████████████░░░░   80%
Documentation   ████████████░░░░░░░░   60%
```

**HTTP/1.1 Stack: 100% Complete**
**Overall (including HTTP/2, WebSocket): ~75% Complete**

---

## What's Done

### Core HTTP Types (100%)
- **Method**: All standard methods with semantic properties
- **StatusCode**: All standard codes with RFC-compliant behavior
- **Headers**: Case-insensitive, multi-value, TreeMap-based
- **Uri**: RFC 3986 parsing (authority, path, query, fragment)
- **Request/Response**: Full validation, builder methods
- **Body**: Text, Binary, Stream (ChunkStream), Empty
- **MediaType**: Full parsing with parameters, charset, boundary
- **Cookie**: RFC 6265 with domain/path matching, SameSite
- **CacheControl**: RFC 9111 directives
- **ETag**: Strong/weak ETags with matching
- **HttpDate**: RFC 9110 date parsing/formatting
- **Multipart**: RFC 7578 multipart/form-data
- **ServerSentEvent**: WHATWG SSE spec

### HTTP Parser (100%)
- Zero-allocation byte-level parsing with BufferedSocketReader
- Request/response line parsing
- Header parsing with pre-interned common names
- Chunked transfer encoding (streaming)
- Content-Length body reading
- 8KB direct ByteBuffer, TCP_QUICKACK support

### HTTP Writer (100%)
- Zero-allocation serialization with ByteBuffer pooling
- Request/response writing
- Chunked transfer encoding (write)
- Direct buffer writes for zero-copy I/O

### HTTP Server - NativeHttpServer (100%)
- Blocking NIO + Virtual Threads
- SO_REUSEPORT multi-threaded accept
- Per-connection request loop (HTTP keep-alive)
- BufferedSocketReader pooling per connection
- ByteBuffer pooling for zero-allocation response writing
- TCP_NODELAY and TCP_QUICKACK optimizations
- Structured concurrency with forkDaemon
- Automatic Content-Length/Transfer-Encoding headers

### HTTP Client - NativeHttpClient (100%)
- Request execution with encoding/decoding
- Interceptor support (request/response)
- Cookie jar integration with RFC 6265 matching
- Redirect following (configurable max redirects)
- Connection pooling with backpressure
- Automatic Accept-Encoding header injection
- **Automatic response decompression** (gzip, deflate, brotli)

### Connection Pool (100%)
- Semaphore-based backpressure (global + per-host)
- FIFO fairness
- BufferedSocketReader pooling (8KB + StringBuilder)
- ByteBuffer pooling (4KB direct)
- Connection reuse with HTTP/1.1 keep-alive
- TCP_NODELAY and TCP_QUICKACK optimizations

### Compression (100%)
- **Primitives**: gzip, deflate, brotli (via brotli4j)
- **Server Middleware**: `Middleware.compression()` with configurable minSize and encoding preferences
- **Client Decompression**: Automatic based on Content-Encoding header
- Stream compression/decompression support

### Server Middleware (100%)
- Logging (with and without error handling)
- CORS (configurable origins, methods, headers, credentials)
- Authentication (generic, Bearer token)
- Request ID generation
- Error handling (generic, default)
- Conditional middleware (when, forPath, forMethod)
- **Compression middleware**
- Middleware composition (andThen, combine)

### TLS/SSL (100%)
- **SSLSocketChannel**: TLS wrapper implementing ReadableByteChannel/WritableByteChannel
- **SSLContextFactory**: Client and server context creation
- Client: SNI support, hostname verification, `trustAll` mode for dev
- Server: PKCS12/JKS keystore loading
- Connection reuse: SSL channels and readers pooled per connection
- Blocking handshake on Virtual Threads (efficient with VT)

---

## What's Next

### Priority 1: WebSocket Support (NEXT)

- RFC 6455 implementation
- Handshake, frame parsing, ping/pong
- Works over TLS (wss://)

### Priority 2: HTTP/2 Support

- HPACK compression
- Stream multiplexing
- Flow control
- ALPN negotiation

---

## Technical Notes

### Performance Achievements
- Memory: Reduced allocation from 35 GB/s to ~936 MB/s (97% reduction)
- Streaming: Constant memory for 100KB+ bodies
- Concurrency: ~10KB per Virtual Thread vs ~2MB for OS threads

### Architecture
- Build on Eru effect system, don't extend it
- Virtual Threads for all blocking I/O
- Zero-allocation parsing/writing where possible
- RFC compliance strictly followed

---

*Last updated: 2026-01-02*
*Use ROADMAP.md for detailed planning*
