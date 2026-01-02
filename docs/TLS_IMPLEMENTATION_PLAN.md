# TLS/SSL Implementation Plan for eru-http

**Status**: In Progress
**Created**: 2026-01-01

## Overview

Implement TLS/SSL support enabling HTTPS for both client and server. The approach uses a wrapper class that implements `ReadableByteChannel`/`WritableByteChannel`, allowing transparent substitution for existing `SocketChannel` usage.

---

## Files to Create

### 1. `eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/SSLSocketChannel.scala`

TLS wrapper that implements `ReadableByteChannel` and `WritableByteChannel`:

- **Constructor**: Takes `SocketChannel`, `SSLEngine`, mode flags
- **`doHandshake()`**: Blocking TLS handshake (fine on Virtual Threads)
- **`read(ByteBuffer)`**: Decrypt data from network
- **`write(ByteBuffer)`**: Encrypt data to network
- **`close()`**: TLS shutdown with close_notify alert
- **Companion object**: Factory methods `client()` and `server()`

Key implementation details:
- Allocate buffers based on `sslEngine.getSession.getPacketBufferSize`
- Handle `NEED_WRAP`, `NEED_UNWRAP`, `NEED_TASK` states in handshake loop
- Support renegotiation during read/write

### 2. `eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/SSLContextFactory.scala`

Factory for SSLContext creation:

- **`createClientContext(TlsConfig)`**: Configure trust managers, handle `trustAll`
- **`createServerContext(TlsConfig, certPath, password)`**: Load keystore, configure key managers

---

## Files to Modify

### 1. `eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/BufferedSocketReader.scala`

Change constructor parameter type:
```scala
// From:
private[http] final class BufferedSocketReader(socket: SocketChannel, ...)

// To:
private[http] final class BufferedSocketReader(channel: ReadableByteChannel, ...)
```

### 2. `eru-http-core/jvm/src/main/scala/net/ghoula/eru/http/HttpWriter.scala`

Change method signatures:
```scala
// From:
def writeRequestWithBuffer(socket: SocketChannel, ...)
def writeResponseWithBuffer(socket: SocketChannel, ...)

// To:
def writeRequestWithBuffer(channel: WritableByteChannel, ...)
def writeResponseWithBuffer(channel: WritableByteChannel, ...)
```

### 3. `eru-http-client/src/main/scala/net/ghoula/eru/http/client/NativeHttpClient.scala`

**Lines 389-405** - Implement `wrapWithTLS()`:
```scala
private def wrapWithTLS(socket: SocketChannel, host: String, port: Int, ctx: SSLContext): Eru[HttpError, SSLSocketChannel] =
  Eru.effect {
    val sslChannel = SSLSocketChannel.client(socket, ctx, host, port)
    sslChannel.doHandshake()
    sslChannel
  }.mapError(...)
```

**Lines 548-558** - Implement `createSSLContext()`:
```scala
private def createSSLContext(tlsConfig: TlsConfig): Eru[HttpError, SSLContext] =
  Eru.effect {
    SSLContextFactory.createClientContext(tlsConfig)
  }.mapError(...)
```

### 4. `eru-http-server/src/main/scala/net/ghoula/eru/http/server/NativeHttpServer.scala`

**Lines 320-331** - Implement `wrapWithTLS()`:
```scala
private def wrapWithTLS(socket: SocketChannel, ctx: SSLContext): Eru[HttpError, SSLSocketChannel] =
  Eru.effect {
    val sslChannel = SSLSocketChannel.server(socket, ctx)
    sslChannel.doHandshake()
    sslChannel
  }.mapError(...)
```

**Lines 484-495** - Implement `createSSLContext()`:
```scala
private def createSSLContext(tlsConfig: TlsConfig): Eru[HttpError, SSLContext] =
  Eru.effect {
    SSLContextFactory.createServerContext(tlsConfig, tlsConfig.keyStorePath.get, tlsConfig.keyStorePassword.get)
  }.mapError(...)
```

### 5. `eru-http-core/shared/src/main/scala/net/ghoula/eru/http/TlsConfig.scala`

Add server keystore fields:
```scala
final case class TlsConfig(
  enabled: Boolean = true,
  protocols: List[TlsVersion] = List(TlsVersion.TLSv1_3, TlsVersion.TLSv1_2),
  trustAll: Boolean = false,
  verifyHostname: Boolean = true,
  // Server-specific:
  keyStorePath: Option[String] = None,
  keyStorePassword: Option[String] = None
)
```

---

## Implementation Order

- [ ] **Step 1**: Generalize type signatures - Update `BufferedSocketReader` and `HttpWriter` to use channel interfaces
- [ ] **Step 2**: Create `SSLContextFactory` - Simple, testable in isolation
- [ ] **Step 3**: Create `SSLSocketChannel` - Core wrapper with handshake logic
- [ ] **Step 4**: Wire up client - Implement stubs in `NativeHttpClient`
- [ ] **Step 5**: Wire up server - Implement stubs in `NativeHttpServer`, extend `TlsConfig`
- [ ] **Step 6**: Integration tests - Client-server TLS communication

---

## Testing Strategy

- Unit tests for `SSLContextFactory` (context creation, trustAll mode)
- Unit tests for `SSLSocketChannel` (use Java's `SSLServerSocket` as test peer)
- Integration test: Start HTTPS server, connect with client using `TlsConfig.insecure`
- Generate self-signed cert programmatically for tests (no external files needed)

---

## Key Design Decisions

1. **Wrapper approach**: New class implementing channel interfaces, not modifying existing classes
2. **Blocking handshake**: Virtual Threads make this efficient and simple
3. **Buffer sizing**: Use SSLEngine session recommendations (~16KB each)
4. **SNI support**: Client sets `SNIHostName` in SSL parameters
5. **Hostname verification**: Enabled by default via `setEndpointIdentificationAlgorithm("HTTPS")`

---

## Progress Log

*Updates will be recorded here as implementation proceeds.*
