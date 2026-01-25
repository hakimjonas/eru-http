# HTTP/2 Implementation Plan for eru-http

## Overview

Implement RFC 9113 HTTP/2 support for eru-http, enabling multiplexed connections over a single TCP socket with header compression.

## Prerequisites

- HTTP/1.1 complete (done)
- TLS/SSL support (done)
- WebSocket support (done, demonstrates frame-based protocol handling)

## Why HTTP/2

### Adoption (Verified)
- HTTP/2: ~70% of websites ([Web Almanac 2024](https://almanac.httparchive.org/en/2024/http))
- HTTP/1.1: ~22%
- HTTP/3: ~37% ([W3Techs](https://w3techs.com/technologies/details/ce-http3))

### Why Not HTTP/3 Yet
- HTTP/3 requires QUIC which needs TLS APIs not fully exposed in Java 25
- JEP 517 adds HTTP/3 to Java 26's HttpClient, but:
  - Not backportable to Java 25 LTS (Oracle policy: LTS versions are feature-locked)
  - Only for JDK's HttpClient, not for building custom implementations
- Pure Java QUIC implementations (Kwik) require custom TLS handshake code
- HTTP/2 over TCP works naturally with Virtual Threads

## RFCs

- **RFC 9113**: HTTP/2 (obsoletes RFC 7540)
- **RFC 7541**: HPACK Header Compression

## Architecture Decisions

### Build HPACK From Scratch

**Decision**: Implement HPACK ourselves rather than using `com.twitter.hpack`

**Rationale**:
- Aligns with eru-http philosophy of building from the ground up
- Twitter HPACK last released March 2016 (version 1.0.2) - unmaintained
- HPACK is a fixed spec (RFC 7541) that won't change
- Similar to how we built WebSocket framing from scratch
- Precedent: We use external libs for compression algorithms (brotli4j) but HPACK is protocol-specific

### Virtual Threads + HTTP/2

**Investigation Required**: The interaction between Virtual Threads and HTTP/2's dual-level flow control needs prototyping.

From RFC 9113 Section 5.2:
> "Flow control is used for both individual streams and the connection as a whole"
> "failure to read promptly could lead to a deadlock when critical frames, such as WINDOW_UPDATE, are not read and acted upon"

**Potential Architecture**:
- One Virtual Thread per stream for request/response handling
- Dedicated reader thread for the connection (demultiplexes frames to streams)
- Shared connection state via Eru's `Ref` for flow control windows
- Frame writer with queue to serialize socket writes

**Risk**: Blocking reads on streams must not block connection-level control frames. Needs prototype to validate.

## Implementation Phases

### Phase 1: HPACK (Header Compression) ✅ COMPLETE

**Files**:
```
eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/h2/
  HpackStaticTable.scala    # 61 predefined entries per RFC 7541
  HpackDynamicTable.scala   # FIFO bounded table
  HpackHuffman.scala        # Static Huffman codec (257 symbols: 0-255 + EOS)
  HpackInteger.scala        # Variable-length integer encoding
  HpackString.scala         # String literal encoding (auto Huffman selection)
  HpackEncoder.scala        # Encodes headers to binary
  HpackDecoder.scala        # Decodes binary to headers
  HpackError.scala          # Typed error enum with RFC references
```

**Components per RFC 7541**:
- Static table: 61 entries (`:authority`, `:method GET`, etc.)
- Dynamic table: Bounded FIFO, default 4096 bytes
- Huffman encoding: Optional, 5-30 bits per symbol (257 symbols)
- Integer encoding: Variable length with N-bit prefix

**Status**: Complete. 89 tests pass including RFC 7541 Appendix C test vectors. All components return `Eru[HpackError, A]` for proper effect integration.

### Phase 2: Frame Layer ✅ COMPLETE

**Files**:
```
eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/h2/
  H2Frame.scala             # Frame types and structures (all 10 frame types)
  H2FrameCodec.scala        # Frame encoding/decoding from ByteBuffer
  H2Error.scala             # Error types with RFC references
  H2ErrorCode.scala         # HTTP/2 error codes per RFC 9113
```

**Frame Types (RFC 9113)**:
| Type | Code | Purpose | Status |
|------|------|---------|--------|
| DATA | 0x00 | Message body | ✅ |
| HEADERS | 0x01 | Opens stream, carries headers | ✅ |
| PRIORITY | 0x02 | Deprecated in RFC 9113 | ✅ |
| RST_STREAM | 0x03 | Terminates stream | ✅ |
| SETTINGS | 0x04 | Connection parameters | ✅ |
| PUSH_PROMISE | 0x05 | Server push | ✅ |
| PING | 0x06 | Liveness check | ✅ |
| GOAWAY | 0x07 | Graceful shutdown | ✅ |
| WINDOW_UPDATE | 0x08 | Flow control | ✅ |
| CONTINUATION | 0x09 | Header continuation | ✅ |

**Frame Header** (9 bytes):
```
+-----------------------------------------------+
|                 Length (24)                   |
+---------------+---------------+---------------+
|   Type (8)    |   Flags (8)   |
+-+-------------+---------------+---------------+
|R|         Stream Identifier (31)              |
+-----------------------------------------------+
```

**Status**: Complete. 27 tests covering all frame types, round-trip encoding/decoding, protocol validation (stream 0 restrictions, frame size limits), and unknown frame handling. Returns `Eru[H2Error, A]` for proper effect integration.

**Reference**: http4s H2Frame.scala is 899 lines

### Phase 3: Stream State Machine ✅ COMPLETE

**Files**:
```
eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/h2/
  H2Stream.scala            # Stream state machine and flow control
  H2StreamState.scala       # 7-state enum with helper methods
```

**States (RFC 9113 Section 5.1)**:
1. idle
2. reserved (local)
3. reserved (remote)
4. open
5. half-closed (local)
6. half-closed (remote)
7. closed

**Transitions**: Driven by HEADERS, PUSH_PROMISE, END_STREAM flag, RST_STREAM

**Features**:
- ✅ Full 7-state machine with all transitions
- ✅ State validation for frame operations (canSend, canReceive)
- ✅ Dual-level flow control (send/receive windows)
- ✅ Window consumption and replenishment with overflow detection
- ✅ Helper methods: canSendData, canReceiveData, isActive, isClosed, isReserved

**Status**: Complete. 40 tests covering all state transitions, flow control operations, and frame validation. Returns `Eru[H2Error, A]` for proper effect integration.

**Reference**: http4s H2Stream.scala is 507 lines

### Phase 4: Connection Management ✅ COMPLETE

**Files**:
```
eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/h2/
  H2Connection.scala        # Connection state and multiplexing (~380 lines)
  H2Settings.scala          # SETTINGS parameters with validation (~250 lines)
```

**Connection State**:
- ✅ Stream map (id -> H2Stream)
- ✅ Connection-level flow control windows (send/receive)
- ✅ HPACK encoder/decoder per connection
- ✅ Stream ID management (client odd, server even)
- ✅ SETTINGS (local and peer) with validation
- ✅ GOAWAY handling

**Features**:
- ✅ Client/server connection factories
- ✅ Automatic stream ID allocation
- ✅ Peer stream registration with validation
- ✅ Max concurrent streams enforcement
- ✅ Connection-level flow control
- ✅ Settings application with delta window adjustment
- ✅ GOAWAY send/receive with last stream ID tracking

**Flow Control** (RFC 9113 Section 5.2):
- Initial window: 65,535 bytes (connection and per-stream)
- DATA frames consume from both windows
- WINDOW_UPDATE replenishes
- Overflow detection on window updates

**Status**: Complete. 52 tests (23 H2Settings + 29 H2Connection) covering settings validation, stream management, flow control, and GOAWAY handling.

**Reference**: http4s H2Connection.scala is 598 lines

### Phase 5: Client Integration (IN PROGRESS)

**Files**:
```
eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/
  SSLSocketChannel.scala    # Added ALPN support (h2, http/1.1)

eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/h2/
  H2ClientConnection.scala  # HTTP/2 client connection handler (~500 lines)

eru-http-core/jvm/src/test/scala/net/ghoula/eru/http/h2/
  H2ClientConnectionSpec.scala  # 9 tests with mock channel
  AlpnIntegrationSpec.scala     # 6 end-to-end ALPN tests with real TLS
```

**Completed**:
- ✅ ALPN negotiation in SSLSocketChannel (client and server)
- ✅ Protocol detection methods (getApplicationProtocol, isHttp2)
- ✅ End-to-end ALPN verification with real TLS handshakes (6 tests)
- ✅ H2ClientConnection with connection preface exchange
- ✅ Frame reading/writing
- ✅ Request sending with HPACK encoding
- ✅ Response receiving with header/data handling
- ✅ Flow control (WINDOW_UPDATE send/receive)
- ✅ PING/PONG handling
- ✅ GOAWAY handling

**Test Coverage**: 15 tests (9 H2ClientConnectionSpec + 6 AlpnIntegrationSpec)

**Remaining**:
- ☐ Integrate with NativeHttpClient (protocol detection after TLS)
- ☐ Connection pool HTTP/2 support (one connection, many streams)
- ☐ End-to-end integration test with real HTTP/2 server

**ALPN**: `SSLParameters.setApplicationProtocols(Array("h2", "http/1.1"))`

**Reference**: http4s H2Client.scala is 427 lines

### Phase 6: Server Integration

**Files**:
```
eru-http-server/src/main/scala/net/ghoula/eru/http/server/
  H2Server.scala            # HTTP/2 server handler
  H2ServerConnection.scala  # Server-side connection
```

**Features**:
- ALPN negotiation
- h2c upgrade from HTTP/1.1 (cleartext)
- Connection preface validation
- Server push (optional, can defer)

**Reference**: http4s H2Server.scala is 327 lines

## Testing Strategy

### Unit Tests
- HPACK encoding/decoding with RFC test vectors
- Frame serialization/deserialization
- Stream state machine transitions
- Flow control window calculations

### Integration Tests
- Client connects to server over h2
- Request/response exchange
- Multiple concurrent streams
- Large body streaming with flow control
- Connection-level errors (GOAWAY)
- Stream-level errors (RST_STREAM)

### Conformance Testing
- h2spec: HTTP/2 conformance testing tool
- Similar to Autobahn for WebSocket

## Open Questions (Require Prototyping)

1. **Virtual Thread + Flow Control**: How to handle connection-level WINDOW_UPDATE without blocking stream reads?

2. **HPACK Size**: Actual lines of code for HPACK implementation?

3. **BufferedSocketReader Integration**: Does existing reader work for H2 frames, or need modifications?

4. **Connection Pool Changes**: HTTP/2 multiplexes on one connection - how does this interact with existing pool?

5. **Server Push**: Implement in initial version or defer?

## Reference Implementation Sizes (http4s Ember)

| File | Lines | Notes |
|------|-------|-------|
| H2Frame.scala | 899 | Frame types, parsing |
| H2Connection.scala | 598 | Connection management |
| H2Stream.scala | 507 | Stream state |
| H2Client.scala | 427 | Client |
| H2Server.scala | 327 | Server |
| PseudoHeaders.scala | 175 | :method, :path, etc. |
| H2Error.scala | 132 | Errors |
| Hpack.scala | 99 | Trait only (uses Twitter lib) |
| **Total** | **~3,300** | Excludes HPACK implementation |

Note: http4s uses Cats Effect and Twitter HPACK. eru-http will use Eru effects and build HPACK from scratch, so actual size will differ.

## Success Criteria

1. All unit tests pass
2. h2spec conformance tests pass
3. Client can make HTTPS requests to real HTTP/2 servers (e.g., google.com)
4. Server accepts HTTP/2 connections from browsers
5. Multiple concurrent streams work correctly
6. Flow control prevents memory exhaustion
7. Graceful connection shutdown works

## Timeline

Not estimated. Complexity depends on prototyping results for HPACK and Virtual Thread integration.
