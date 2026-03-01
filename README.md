# eru-http

[![CI](https://github.com/hakimjonas/eru-http/workflows/CI/badge.svg)](https://github.com/hakimjonas/eru-http/actions/workflows/ci.yml)
[![Scala 3.8.2](https://img.shields.io/badge/scala-3.8.2-red.svg)](https://www.scala-lang.org/)
[![Java 25](https://img.shields.io/badge/java-25-blue.svg)](https://openjdk.org/projects/jdk/25/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Standards-compliant HTTP client and server built on Eru with Virtual Threads**

eru-http is a modern, type-safe HTTP library for Scala 3 that leverages the [Eru effect system](https://github.com/ghoula/eru) for elegant, composable HTTP programming. Built on **native blocking NIO + Virtual Threads** for simplicity and performance, eru-http provides zero-cost abstractions through Scala 3's inline methods and opaque types.

> **Architecture Note**: eru-http uses blocking NIO with Eru's Virtual Thread backend, eliminating the complexity of event loops and callbacks while maintaining excellent performance.

## Features

### 🚀 **HTTP Client**
- Standards-compliant HTTP/1.1 and HTTP/2 client
- Composable request/response interceptors
- Automatic redirect handling
- Cookie jar with domain/path matching (RFC 6265)
- Compression support (gzip, deflate, brotli)
- TLS/SSL configuration (TLS 1.2/1.3)
- Connection pooling and timeout management
- Type-safe request/response handling

### 🌐 **HTTP Server**
- High-performance native NIO server (blocking + Virtual Threads)
- Each connection on its own Virtual Thread (scalable to 100K+ connections)
- Composable middleware with zero-cost abstractions
- Built-in middleware: CORS, auth, logging, error handling
- Request routing with pattern matching
- WebSocket support (RFC 6455, ws:// and wss://)
- Server-Sent Events (SSE) support
- Multipart form data handling (RFC 7578)
- Structured concurrency for automatic cleanup

### 📦 **Core HTTP Types**
- Complete HTTP types: `Method`, `StatusCode`, `Headers`, `Uri`
- RFC-compliant implementations:
  - RFC 9110: HTTP Semantics
  - RFC 6265: Cookies
  - RFC 7578: Multipart Forms
  - RFC 9111: HTTP Caching (ETags, Cache-Control)
  - WHATWG: Server-Sent Events
- Content negotiation and encoding
- Functional body encoding/decoding

### ⚡ **Eru Integration**
- Pure functional effects with `Eru[E, A]`
- Resource-safe operations with automatic cleanup
- Composable error handling
- Type-safe error channels
- Effect transformations via interceptors/middleware

## Quick Start

### Installation

```scala
// build.sbt
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-http-core" % "1.0.0",
  "net.ghoula" %% "eru-http-client" % "1.0.0",
  "net.ghoula" %% "eru-http-server" % "1.0.0"
)
```

### Simple HTTP Client

```scala
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*

given runtime: EruRuntime = EruRuntime.shared

// Resource-safe client with automatic cleanup
val program = HttpClient.scoped() { client =>
  for {
    uri <- Uri.parse("https://api.github.com/users/ghoula")
    request = Request.get(uri)
    response <- client.send(request)
    body <- BodyDecoder[String].decode(response.body)
  } yield {
    println(s"Status: ${response.status}")
    println(s"Body: $body")
  }
}

program.unsafeRunSync()
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
      val json = """{"message":"Hello, World!"}"""
      Response.ok(Body.text(json, MediaType.applicationJson))
        .withContentType(MediaType.applicationJson)

    case _ =>
      Response.notFound(Body.text("Not Found"))
  }

HttpServer.scoped(HttpServerConfig.localhost.withPort(8080))(handler) { server =>
  Eru.effect {
    println(s"Server running at http://${server.address}")
    scala.io.StdIn.readLine("Press ENTER to stop...")
  }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
}.unsafeRunSync()
```

## Core Concepts

### Eru Effects

All operations return `Eru[E, A]`, a pure functional effect that represents:
- Success with value `A`
- Failure with error `E`
- Resource management via `bracket`
- Composition via `flatMap`, `map`, etc.

```scala
val program: Eru[HttpError, String] = for {
  client <- HttpClient.create(HttpClientConfig.default)
  uri <- Uri.parse("https://example.com")
  request = Request.get(uri)
  response <- client.send(request)
  body <- BodyDecoder[String].decode(response.body)
  _ <- client.shutdown
} yield body
```

### Interceptors (Client)

Interceptors transform requests and responses in a composable way:

```scala
import net.ghoula.eru.http.client.Interceptor

val client = HttpClient.create(HttpClientConfig.default)
  .flatMap { baseClient =>
    Eru.succeed(
      baseClient
        .withRequestInterceptor(Interceptor.bearerAuth("your-token"))
        .withRequestInterceptor(Interceptor.userAgent("MyApp/1.0"))
        .withRequestInterceptor(Interceptor.logRequest(println))
        .withResponseInterceptor(Interceptor.logResponse(println))
    )
  }
```

**Built-in Interceptors:**
- `addHeader` - Add custom headers
- `bearerAuth` / `basicAuth` - Authentication
- `userAgent` - Set User-Agent
- `logRequest` / `logResponse` - Logging
- `when` - Conditional application

**Zero-cost composition** via Scala 3 `inline` methods:

```scala
val auth = Interceptor.bearerAuth("token")
val logging = Interceptor.logging(println)
val combined = auth andThen logging // Inlined at compile time
```

### Middleware (Server)

Middleware wraps request handlers to add cross-cutting concerns:

```scala
import net.ghoula.eru.http.server.*

val app = Middleware
  .logging(println)
  .andThen(Middleware.corsPermissive)
  .andThen(Middleware.requestId())
  .andThen(Middleware.errorHandlerDefault)
  .apply(handler)

HttpServer.scoped(HttpServerConfig.localhost.withPort(8080))(app) { server =>
  // Server is running with full middleware stack
}
```

**Built-in Middleware:**
- `logging` / `loggingSimple` - Request/response logging
- `cors` / `corsPermissive` - CORS headers
- `auth` / `bearerAuth` - Authentication
- `requestId` - Unique request IDs
- `errorHandler` / `errorHandlerDefault` - Error handling
- `when` / `forPath` / `forMethod` - Conditional application

**Type signature:**
```scala
type Middleware = RequestHandler => RequestHandler
type RequestHandler = Request[Body] => Eru[HttpError, Response[Body]]
```

### HTTP Types

```scala
// Methods
Method.GET
Method.POST
Method.PUT
Method.DELETE
// ... all standard methods

// Status Codes
StatusCode.Ok              // 200
StatusCode.Created         // 201
StatusCode.BadRequest      // 400
StatusCode.NotFound        // 404
StatusCode.InternalServerError // 500

// Headers
Headers.empty
  .add("Content-Type", "application/json")
  .add("Authorization", "Bearer token")

// URIs
Uri.parse("https://example.com/path?query=value#fragment")
  .map { uri =>
    uri.scheme // Some("https")
    uri.host   // Some("example.com")
    uri.path   // "/path"
    uri.query  // Some("query=value")
  }

// Bodies
Body.Empty
Body.text("Hello, World!")
Body.text("""{"key":"value"}""", MediaType.applicationJson)
Body.Binary(bytes)
```

### Request Building

```scala
// GET request
val getReq = Request.get(uri)

// POST with JSON
val postReq = Request.post(uri, Body.text(json, MediaType.applicationJson))

// With headers
val authedReq = Request.get(uri)
  .flatMap(_.setHeader("Authorization", "Bearer token"))

// With query parameters
val searchReq = Request.get(uri.withQuery("q=scala"))
```

### Response Handling

```scala
// Create responses
Response.ok(Body.text("Success"))
Response.created(location, Body.text("Created"))
Response.notFound(Body.text("Not Found"))

// With headers
Response.ok(body)
  .flatMap(_.setHeader("Cache-Control", "max-age=3600"))
  .withContentType(MediaType.applicationJson)

// Status checking
response.status.isSuccess      // 2xx
response.status.isRedirection  // 3xx
response.status.isClientError  // 4xx
response.status.isServerError  // 5xx
```

## Advanced Features

### Cookie Jar

```scala
val clientWithCookies = HttpClientConfig.default
  .withCookieJar(CookieJar.inMemory.unsafeRunSync())

// Cookies are automatically:
// - Sent with matching requests (domain/path)
// - Stored from Set-Cookie headers
// - Filtered by expiration
```

### Content Encoding

```scala
// Compression
val compressed = Compression.compress(bytes, ContentEncoding.gzip)

// Decompression
val decompressed = Compression.decompress(bytes, ContentEncoding.gzip)

// Supported: gzip, deflate, brotli
```

### Multipart Forms

```scala
import net.ghoula.eru.http.Multipart

val parts = List(
  Part.formField("name", "John Doe"),
  Part.formField("email", "john@example.com"),
  Part.fileFromBytes("avatar", "photo.jpg", imageBytes, MediaType.imageJpeg)
)

Multipart.formData(parts).flatMap { multipart =>
  val body = multipart.toBody
  val contentType = multipart.contentType
  Request.post(uri, body).flatMap(_.setHeader("Content-Type", contentType.value))
}
```

### Server-Sent Events

```scala
import net.ghoula.eru.http.ServerSentEvent

// Create events
val event1 = ServerSentEvent.data("Hello, World!")
val event2 = ServerSentEvent.event("notification", """{"type":"update"}""")
val event3 = ServerSentEvent(
  data = "Message",
  event = Some("chat"),
  id = Some("123"),
  retry = Some(5000)
)

// Serialize
val sseText = event1.toSSE // "data: Hello, World!\n\n"

// Parse
ServerSentEvent.parse(sseText)
```

### ETag Caching

```scala
// Generate ETag from content
val etag = ETag.fromBytes(responseBytes)

// Conditional requests
request.setHeader("If-None-Match", etag.headerValue)

// Response
if (requestEtag.exists(_.matches(currentEtag))) {
  Response.notModified
} else {
  Response.ok(body).setHeader("ETag", currentEtag.headerValue)
}
```

### TLS Configuration

```scala
// Secure defaults (TLS 1.3 + 1.2, verify certificates and hostname)
HttpClientConfig.default.withTls(TlsConfig.default)

// TLS 1.3 only
HttpClientConfig.default.withTls(TlsConfig.tls13Only)

// Insecure (testing only!)
HttpClientConfig.default.withTls(TlsConfig.insecure)

// Custom
HttpClientConfig.default.withTls(
  TlsConfig(
    enabled = true,
    protocols = List(TlsVersion.TLSv1_3),
    trustAll = false,
    verifyHostname = true
  )
)
```

## Examples

See [examples/README.md](examples/README.md) for compliance test servers and usage examples.

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
├── eru-http-client    # HTTP client implementation
│   ├── HttpClient (NIO + Virtual Threads)
│   ├── Interceptors
│   ├── CookieJar
│   └── HttpClientConfig
│
└── eru-http-server    # HTTP server implementation
    ├── HttpServer (NIO + Virtual Threads)
    ├── Middleware
    ├── RequestHandler
    └── HttpServerConfig
```

**Design Principles:**
- **Effect-oriented**: All operations return `Eru[E, A]`
- **Resource-safe**: Automatic cleanup via `scoped`
- **Type-safe**: Compile-time guarantees
- **Standards-compliant**: RFC adherence
- **Zero-cost abstractions**: Inline methods, extension methods
- **Composable**: Interceptors and middleware

## Performance

eru-http uses blocking NIO with Virtual Threads — each connection runs on its own lightweight thread (~10KB stack), eliminating event-loop complexity while scaling to 100K+ concurrent connections. Combined with Eru's zero-cost effect transformations, eru-http delivers excellent performance.

### Benchmark Results (Actual)

**Measured on October 3, 2025:**

**Throughput:**
- **Plaintext: 68-71k req/s** (baseline, no tuning)
- **JSON: 74k req/s** (baseline, no tuning)

**Latency:**
- **Average: ~1ms** (medium load, 100 connections)
- **P95: <7ms** (high load, 400 connections)
- **Max: <20ms** (medium load)

**Configuration:** Default settings, no JVM tuning, no middleware applied

See [BENCHMARKING.md](BENCHMARKING.md) for testing guide.

## Project Status

**Current Version:** 1.0.0

**Maturity:** Production Ready
- ✅ ~990 tests passing
- ✅ Zero scalafix violations
- ✅ Zero compiler warnings
- ✅ Complete RFC compliance
- ✅ Comprehensive documentation
- ✅ Production-ready examples

**Supported Platforms:**
- ✅ JVM (Scala 3.8.2+)
- ⏳ Scala.js (pending Eru JS support)
- ⏳ Scala Native (pending Eru Native support)

**Shipped in 1.0:**
- ✅ WebSocket support (RFC 6455, ws:// and wss://)
- ✅ HTTP/2 support (RFC 9113, HPACK, stream multiplexing)

**Planned (1.x):**
- Resilience patterns (eru-http-resilience)
- Metrics and monitoring (eru-http-metrics)

## Documentation

- **[Examples README](examples/README.md)** - Usage examples
- **[Benchmarking Guide](BENCHMARKING.md)** - Performance testing
- **[Roadmap](ROADMAP.md)** - Project roadmap

## Requirements

- **Scala:** 3.8.2 or higher
- **JVM:** Java 25 or higher (ZGC recommended)
- **Dependencies:**
  - Eru (effect system)
  - Valar (validation)
  - Brotli4j (compression)

## Building from Source

```bash
git clone https://github.com/hakimjonas/eru-http.git
cd eru-http
sbt test
```

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Write tests for new functionality
4. Ensure all tests pass: `sbt test`
5. Run scalafmt: `sbt fmt`
6. Run scalafix: `sbt fix`
7. Submit a pull request

See the contributing section above for guidelines.

## License

eru-http is licensed under the [MIT License](LICENSE).

## Acknowledgments

- **[Eru](https://github.com/ghoula/eru)** - The effect system powering eru-http
- **[http4s](https://http4s.org/)** - Inspiration for functional HTTP
- **[sttp](https://sttp.softwaremill.com/)** - Inspiration for client API design

## Support

- **Issues:** [GitHub Issues](https://github.com/ghoula/eru-http/issues)
- **Discussions:** [GitHub Discussions](https://github.com/ghoula/eru-http/discussions)
- **Eru Project:** [Eru on GitHub](https://github.com/ghoula/eru)

---

**Built with ❤️ using Scala 3 and Eru**