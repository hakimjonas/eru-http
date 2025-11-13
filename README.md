# eru-http

[![CI](https://github.com/hakimjonas/eru-http/workflows/CI/badge.svg)](https://github.com/hakimjonas/eru-http/actions/workflows/ci.yml)
[![Scala 3.7.4](https://img.shields.io/badge/scala-3.7.4-red.svg)](https://www.scala-lang.org/)
[![Java 21](https://img.shields.io/badge/java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**High-performance HTTP/1.1 server and client built on Eru with Virtual Threads**

eru-http is a modern, standards-compliant HTTP library for Scala 3 that leverages the [Eru effect system](https://github.com/hakimjonas/eru) for elegant, composable HTTP programming. Built on **native blocking NIO + Virtual Threads** for simplicity and exceptional performance, eru-http provides zero-cost abstractions through Scala 3's inline methods and opaque types.

## Why eru-http?

### 🚀 **Exceptional Performance**
- **170-210k req/sec** (real-world benchmarks)
- **285k req/sec** (with HTTP/1.1 pipelining)
- **#1 among Virtual Thread-based servers**
- **#2 in JVM ecosystem** (92.5% of pure Netty's performance)
- **55% less code** than Netty-based implementations

### 🎯 **Simple Architecture**
- No Netty, no event loops, no callbacks
- Direct `SocketChannel` / `ServerSocketChannel` usage
- Blocking I/O is **efficient** on Virtual Threads (~10KB per thread)
- One Virtual Thread per connection (server) or request (client)
- Easy to understand, debug, and maintain

### ⚡ **Modern Stack**
- **Scala 3.7.4** - Latest language features
- **Java 21 Virtual Threads** - Lightweight concurrency
- **Eru effects** - Pure functional error handling
- **ZGC garbage collector** - <0.03% GC overhead under load

### 📦 **Standards-Compliant**
- RFC 9110 (HTTP Semantics)
- RFC 6265 (Cookies)
- RFC 7578 (Multipart Forms)
- RFC 9111 (HTTP Caching)
- RFC 3986 (URIs)

## Quick Start

### Installation

**Note**: eru-http is not yet published to Maven Central. For now, clone and build locally:

```bash
git clone https://github.com/hakimjonas/eru-http.git
cd eru-http
sbt publishLocal
```

Then in your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-http-core" % "0.1.0-SNAPSHOT",
  "net.ghoula" %% "eru-http-client" % "0.1.0-SNAPSHOT",
  "net.ghoula" %% "eru-http-server" % "0.1.0-SNAPSHOT"
)
```

### Simple HTTP Server

```scala
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.*

given runtime: EruRuntime = EruRuntime.shared

val handler: RequestHandler = req =>
  req.uri.path match {
    case "/" =>
      Response.ok(Body.text("Hello, World!"))

    case "/json" =>
      val json = """{"message":"Hello from eru-http!"}"""
      Response.ok(Body.text(json, MediaType.applicationJson))
        .withContentType(MediaType.applicationJson)

    case _ =>
      Response.notFound(Body.text("Not Found"))
  }

val server = HttpServer.scoped(HttpServerConfig.localhost.withPort(8080))(handler) { server =>
  Eru.effect {
    println(s"Server running at http://${server.address}")
    println("Press ENTER to stop...")
    scala.io.StdIn.readLine()
  }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
}

server.unsafeRunSync()
```

### Simple HTTP Client

```scala
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*

given runtime: EruRuntime = EruRuntime.shared

val program = HttpClient.scoped() { client =>
  for {
    uri <- Uri.parse("http://localhost:8080/json")
    request = Request.get(uri)
    response <- client.send(request)
    _ <- Eru.effect {
      println(s"Status: ${response.status}")
      println(s"Body: ${String(response.body.toArray, "UTF-8")}")
    }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
  } yield ()
}

program.unsafeRunSync()
```

## Features

### 🌐 **HTTP Server** (Production Ready ✅)
- Native blocking NIO + Virtual Threads
- **170-210k req/sec** sustained throughput
- HTTP/1.1 with keep-alive support
- Composable middleware system
- Request/response interceptors
- Timeout handling (request, idle)
- Graceful shutdown
- Structured concurrency with automatic cleanup
- Multipart form data (RFC 7578)
- Cookie handling (RFC 6265)
- Compression (gzip, deflate, brotli)

### 🚀 **HTTP Client** (In Development 🔄)
- Native blocking NIO + Virtual Threads
- HTTP/1.1 protocol support
- Request/response interceptors
- Automatic redirect handling
- Cookie jar with domain/path matching
- Timeout management (connect, request)
- Body encoding/decoding
- Resource-safe with `HttpClient.scoped`
- **Coming soon**: Connection pooling, TLS/SSL, streaming

### 📦 **Core HTTP Types** (Complete ✅)
- `Method`, `StatusCode`, `Headers`, `Uri`
- `Request[A]`, `Response[A]` with type-safe bodies
- `Body` (Empty, Text, Binary, Stream)
- `Cookie`, `CookieJar`, `ETag`, `CacheControl`
- `MediaType`, `ContentEncoding`, `Charset`
- `Multipart`, `ServerSentEvent`
- Opaque types for validated values
- RFC-compliant parsing and validation

### ⚡ **Eru Integration**
- Pure functional effects with `Eru[E, A]`
- Resource-safe operations via `scoped`
- Composable error handling
- Type-safe error channels
- Structured concurrency
- Built-in observability (EruObserver, RuntimeMetrics, EruTrace)

## Architecture

```
eru-http
├── eru-http-core      # Core HTTP types and standards
│   ├── Method, StatusCode, Headers
│   ├── Request, Response, Body
│   ├── Uri, Cookie, ETag
│   ├── Multipart, ServerSentEvent
│   └── Compression, ContentEncoding
│
├── eru-http-client    # HTTP/1.1 client (native NIO + VT)
│   ├── NativeHttpClient
│   ├── Interceptors
│   ├── CookieJar
│   └── HttpClientConfig
│
└── eru-http-server    # HTTP/1.1 server (native NIO + VT)
    ├── NativeHttpServer
    ├── Middleware
    ├── RequestHandler
    └── HttpServerConfig
```

**Key Design Decisions:**
- ✅ Native blocking NIO (not Netty)
- ✅ Virtual Threads (Java 21)
- ✅ HTTP/1.1 focus (HTTP/2 deferred)
- ✅ Scala 3 only (no legacy support)
- ✅ Effects via Eru only (no Future/IO)
- ✅ Zero external dependencies (except Valar validation, brotli4j compression)

## Performance

eru-http achieves exceptional performance through architectural simplicity:

### Benchmark Results (Scala 3.7.4, Java 21, ZGC)

**Server Performance** (HTTP/1.1):
- **bombardier**: 172k req/sec @ 16k connections
- **rewrk**: 211k req/sec @ 16k connections
- **wrk** (pipelined): 285k req/sec @ 16k connections
- **Real-world estimate**: **170-210k req/sec**

**Stability**:
- Zero crashes under extreme load (64k connections)
- Zero memory leaks
- 0.03% GC overhead (ZGC)
- ~49% heap usage under load

**Latency** (16k connections):
- **p50**: 45-48ms
- **p95**: 55-65ms
- **p99**: 75-95ms

### Competitive Position

| Framework | Req/sec | Architecture | Code Complexity |
|-----------|---------|--------------|-----------------|
| Pure Netty | ~200k | Event loop | High |
| **eru-http** | **170-210k** | **VT + blocking NIO** | **Low** |
| http4s | ~150k | Cats Effect + Netty | High |
| ZIO HTTP | ~200k+ | ZIO + Netty | High |

**eru-http ranks #1 among Virtual Thread-based servers and #2 in the JVM ecosystem**, while being dramatically simpler to understand and maintain.

See [ROADMAP.md](ROADMAP.md) for detailed performance analysis.

## Core Concepts

### Eru Effects

All operations return `Eru[E, A]` - a pure functional effect:

```scala
val program: Eru[HttpError, String] = for {
  client <- HttpClient.create(HttpClientConfig.default)
  uri <- Uri.parse("http://example.com")
  request = Request.get(uri)
  response <- client.send(request)
  _ <- client.shutdown
} yield String(response.body.toArray, "UTF-8")
```

### Middleware (Server)

Composable request transformation:

```scala
val app = Middleware
  .logging(println)
  .andThen(Middleware.cors)
  .andThen(Middleware.requestId())
  .apply(handler)
```

**Built-in Middleware:**
- `logging` - Request/response logging
- `cors` / `corsPermissive` - CORS headers
- `auth` / `bearerAuth` - Authentication
- `requestId` - Unique request IDs
- `errorHandler` - Error handling
- `when` / `forPath` / `forMethod` - Conditional application

### Interceptors (Client)

Composable request/response transformation:

```scala
val client = baseClient
  .withRequestInterceptor(Interceptor.bearerAuth("token"))
  .withRequestInterceptor(Interceptor.userAgent("MyApp/1.0"))
  .withResponseInterceptor(Interceptor.logResponse(println))
```

**Built-in Interceptors:**
- `addHeader` - Custom headers
- `bearerAuth` / `basicAuth` - Authentication
- `userAgent` - User-Agent header
- `logRequest` / `logResponse` - Logging
- `when` - Conditional application

## Examples

The `examples/` directory contains comprehensive examples:

1. **Simple Client** - Basic HTTP client usage
2. **Client with Auth** - Interceptors and authentication
3. **File Upload** - Multipart form data
4. **Simple Server** - Basic HTTP server
5. **Server with Middleware** - Middleware composition
6. **REST API** - Complete CRUD API
7. **Server-Sent Events** - SSE streaming
8. **Complete App** - Production-ready application

Run examples:
```bash
sbt "examples/runMain examples.SimpleServer"
```

## Project Status

**Current Version:** 0.1.0-SNAPSHOT
**Progress:** ~75% complete for v1.0.0

### What's Complete ✅
- Core HTTP types (100%)
- HTTP/1.1 Server (100%)
- Compression (gzip, deflate, brotli)
- Cookie handling (RFC 6265)
- Multipart forms (RFC 7578)
- Extensive benchmarking and validation

### In Progress 🔄
- **HTTP/1.1 Client** (80% complete)
  - Core functionality: ✅
  - Connection pooling: 🔄 **Current focus**
  - TLS/SSL: ⏳ Next
  - Streaming: ⏳ After TLS

### Planned for v1.0 📋
- Client connection pooling
- TLS/SSL (client & server)
- Complete streaming support
- Server-Sent Events (SSE)
- Comprehensive test suite
- Complete documentation

### Future (Post v1.0) 🔮
- **eru-circe** - JSON integration (separate library)
- WebSocket support
- HTTP/2 consideration
- GraalVM native image support

See [ROADMAP.md](ROADMAP.md) for detailed roadmap and [STATUS.md](STATUS.md) for current status.

## Requirements

- **Scala:** 3.7.4
- **JVM:** Java 21+ (Virtual Threads required)
- **Dependencies:**
  - [Eru](https://github.com/hakimjonas/eru) (effect system)
  - [Valar](https://github.com/hakimjonas/valar) (validation)
  - brotli4j (compression)

## Building from Source

```bash
# Clone repository
git clone https://github.com/hakimjonas/eru-http.git
cd eru-http

# Ensure Eru is available (local development)
cd ../eru && sbt publishLocal && cd ../eru-http

# Build eru-http
sbt compile

# Run tests
sbt test

# Run benchmarks
sbt "server/Test/runMain net.ghoula.eru.http.server.BenchmarkServer"
```

## Benchmarking

Install benchmarking tools:
```bash
# wrk (HTTP/1.1 with pipelining)
sudo apt install wrk

# bombardier (Go-based, no pipelining bias)
# Download from https://github.com/codesenberg/bombardier

# rewrk (Rust-based, HTTP/2 capable)
cargo install rewrk
```

Run benchmarks:
```bash
# Start server
sbt "server/Test/runMain net.ghoula.eru.http.server.BenchmarkServer"

# In another terminal
wrk -t 4 -c 16000 -d 3m --latency http://localhost:8080/plaintext
bombardier -c 16000 -d 3m -l http://localhost:8080/plaintext
rewrk -h http://localhost:8080/plaintext -c 16000 -d 3m -t 4 --pct
```

See [ROADMAP.md](ROADMAP.md) for detailed benchmarking results.

## Contributing

Contributions welcome! This project is under active development.

**Current Focus:**
- Connection pooling implementation (client)
- TLS/SSL support (client & server)
- Integration testing

**How to Contribute:**
1. Fork the repository
2. Create a feature branch
3. Write tests
4. Ensure `sbt test` passes
5. Run `sbt prepare` (formats and fixes code)
6. Submit a pull request

See [ROADMAP.md](ROADMAP.md) for areas needing help.

## Documentation

- **[ROADMAP.md](ROADMAP.md)** - Detailed project roadmap and progress
- **[STATUS.md](STATUS.md)** - Current status and recent progress
- **[MANIFESTO.md](MANIFESTO.md)** - Design principles and philosophy
- **[ARCHITECTURE-FIX.md](ARCHITECTURE-FIX.md)** - Architecture decisions
- **[Examples](examples/)** - Code examples

## Comparison with Other Libraries

### vs. ZIO HTTP
- **eru-http**: Simpler, blocking NIO + VT, 170-210k req/sec
- **ZIO HTTP**: Full ZIO ecosystem, Netty-based, ~200k req/sec
- **Trade-off**: eru-http prioritizes simplicity over features

### vs. http4s
- **eru-http**: Native NIO, Eru effects, 170-210k req/sec
- **http4s**: Netty-based, Cats Effect, ~150k req/sec
- **Trade-off**: eru-http is younger but simpler

### vs. Akka HTTP
- **eru-http**: Scala 3 native, no actors, Virtual Threads
- **Akka HTTP**: Mature, battle-tested, actor-based
- **Trade-off**: eru-http is more modern, Akka HTTP more proven

**Choose eru-http if:**
- You want a modern, Scala 3-first HTTP library
- You prefer simple architecture over complex abstractions
- You value compile-time performance
- You're building Eru-based applications
- You want exceptional performance with minimal code

## Philosophy

eru-http demonstrates the power of:
1. Building on stable, emerging technologies (Scala 3 + Virtual Threads)
2. Avoiding framework baggage (no Netty, no reactive streams)
3. Using simple primitives (blocking NIO is efficient on VTs)
4. Prioritizing correctness over convenience
5. Validating through real-world dogfooding

**"Not on the extreme cutting edge, but out in front of established systems that have baggage."**

## Acknowledgments

- **[Eru](https://github.com/hakimjonas/eru)** - The effect system powering eru-http
- **[Valar](https://github.com/hakimjonas/valar)** - Validation library
- **[http4s](https://http4s.org/)** - Inspiration for functional HTTP
- **[ZIO HTTP](https://github.com/zio/zio-http)** - Competitive inspiration

## License

eru-http is licensed under the [MIT License](LICENSE).

## Support

- **Issues:** [GitHub Issues](https://github.com/hakimjonas/eru-http/issues)
- **Eru Project:** [Eru on GitHub](https://github.com/hakimjonas/eru)

---

**Built with Scala 3, Eru, and Virtual Threads**
