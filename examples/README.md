# eru-http Examples

Comprehensive, real-world usage examples demonstrating **Scala 3 + Eru elegance** with eru-http.

## Overview

These examples showcase best practices for building HTTP clients and servers using eru-http, a standards-compliant HTTP library built on the Eru effect system. Each example is self-contained, runnable, and demonstrates specific features and patterns.

## Quick Start

All examples are located in `src/main/scala/examples/` and can be run directly:

```bash
# From the examples directory
sbt "runMain examples.SimpleClient"
sbt "runMain examples.SimpleServer"
```

## Examples

### 1. Simple Client (`01_SimpleClient.scala`)

**Learn:** Basic HTTP client usage, GET/POST requests, resource management

Demonstrates:
- Creating an HTTP client
- Making GET and POST requests
- Handling responses with Eru effects
- Proper resource cleanup with `shutdown`
- Using scoped clients for automatic cleanup

**Run it:**
```bash
sbt "runMain examples.SimpleClient"
```

**Key patterns:**
```scala
// Simple GET request
val program = for {
  client <- HttpClient.create(HttpClientConfig.default)
  uri <- Uri.parse("https://api.github.com/zen")
  request = Request.get(uri)
  response <- client.execute[Body, String](request)
  _ <- client.shutdown
} yield response

// Scoped client (automatic cleanup)
HttpClient.scoped { client =>
  for {
    uri <- Uri.parse("https://example.com")
    response <- client.execute[Body, String](Request.get(uri))
  } yield response
}
```

---

### 2. Client with Authentication (`02_ClientWithAuth.scala`)

**Learn:** Request/response interceptors, authentication patterns, composability

Demonstrates:
- Bearer token authentication
- Basic authentication
- Custom interceptors
- Composing multiple interceptors
- Request/response logging

**Run it:**
```bash
sbt "runMain examples.ClientWithAuth"
```

**Key patterns:**
```scala
// Add interceptors to client
val client = baseClient
  .withRequestInterceptor(Interceptor.bearerAuth(token))
  .withRequestInterceptor(Interceptor.userAgent("MyApp/1.0"))
  .withRequestInterceptor(Interceptor.logRequest(println))
  .withResponseInterceptor(Interceptor.logResponse(println))

// Custom interceptor
val requestIdInterceptor: RequestInterceptor = req =>
  val requestId = java.util.UUID.randomUUID().toString
  Interceptor.addHeader("X-Request-ID", requestId)(req)
```

---

### 3. File Upload (`03_FileUpload.scala`)

**Learn:** Multipart form data, file uploads, binary handling

Demonstrates:
- Creating multipart form data
- Uploading files with form fields
- Working with the `Part` API
- Handling binary data
- Setting proper Content-Type headers

**Run it:**
```bash
sbt "runMain examples.FileUpload"
```

**Key patterns:**
```scala
// Create multipart form data
val fileBytes = Bytes.fromArray("Hello, World!".getBytes("UTF-8"))

val parts = List(
  Part.formField("description", "My file"),
  Part.formField("user", "john_doe")
)

val filePart = Part.fileFromBytes(
  name = "file",
  filename = "hello.txt",
  contentType = MediaType.textPlain,
  bytes = fileBytes
)

val multipart = Multipart.formData(parts :+ filePart)
val body = multipart.toBody
```

---

### 4. Simple Server (`04_SimpleServer.scala`)

**Learn:** HTTP server basics, request routing, response types

Demonstrates:
- Creating an HTTP server
- Pattern matching on routes
- Handling different HTTP methods
- Decoding request bodies
- Sending various response types

**Run it:**
```bash
sbt "runMain examples.SimpleServer"
```

**Test it:**
```bash
curl http://localhost:8080/
curl http://localhost:8080/health
curl -X POST http://localhost:8080/echo -d 'Hello'
```

**Key patterns:**
```scala
val handler: RequestHandler = req =>
  (req.method, req.uri.path) match {
    case (Method.GET, "/") =>
      Response.ok(Body.text("Hello, World!"))

    case (Method.POST, "/echo") =>
      for {
        bodyText <- BodyDecoder[String]
          .decode(req.body)
          .mapError(HttpError.BodyDecodeError.apply)
        response <- Response.ok(Body.text(s"Echo: $bodyText"))
      } yield response

    case _ =>
      Response.notFound(Body.text("Not found"))
  }

HttpServer.create(HttpServerConfig.localhost.withPort(8080), handler)
```

---

### 5. Server with Middleware (`05_ServerWithMiddleware.scala`)

**Learn:** Middleware composition, cross-cutting concerns, request/response transformation

Demonstrates:
- Creating custom middleware
- Composing multiple middleware
- CORS middleware
- Logging middleware
- Request ID tracking
- Timing measurements
- Error handling

**Run it:**
```bash
sbt "runMain examples.ServerWithMiddleware"
```

**Test it:**
```bash
curl http://localhost:8080/
curl http://localhost:8080/api/data
curl -X OPTIONS http://localhost:8080/  # CORS preflight
```

**Key patterns:**
```scala
// Compose middleware chain
val app = Middleware
  .logging(println)
  .andThen(Middleware.corsPermissive)
  .andThen(requestIdMiddleware)
  .andThen(timingMiddleware)
  .andThen(Middleware.errorHandlerDefault)
  .apply(handler)

// Custom middleware
val requestIdMiddleware: Middleware = handler => req =>
  for {
    requestId <- Eru.effect(UUID.randomUUID().toString).mapError(...)
    response <- handler(req)
    withId <- response.setHeader("X-Request-ID", requestId)
  } yield withId
```

---

### 6. REST API (`06_RestApi.scala`)

**Learn:** RESTful design, CRUD operations, resource management

Demonstrates:
- Complete REST API design
- CRUD operations (Create, Read, Update, Delete)
- Path parameter extraction
- Proper HTTP status codes
- JSON response handling
- Concurrent-safe storage

**Run it:**
```bash
sbt "runMain examples.RestApi"
```

**Test it:**
```bash
# List all users
curl http://localhost:8080/users

# Get user by ID
curl http://localhost:8080/users/1

# Create user
curl -X POST http://localhost:8080/users -d 'David Brown,david@example.com'

# Update user
curl -X PUT http://localhost:8080/users/1 -d 'Alice Johnson,alice.j@example.com'

# Delete user
curl -X DELETE http://localhost:8080/users/2
```

**Key patterns:**
```scala
// REST handler with path parameter extraction
val handler: RequestHandler = req =>
  (req.method, req.uri.path) match {
    case (Method.GET, "/users") =>
      handleListUsers()

    case (Method.GET, path) if path.startsWith("/users/") =>
      val id = path.drop("/users/".length).toIntOption
      id match {
        case Some(id) => handleGetUser(id)
        case None => Response.badRequest(...)
      }

    case (Method.POST, "/users") =>
      handleCreateUser(req)

    // ... PUT, DELETE
  }
```

---

### 7. Server-Sent Events (`07_ServerSentEvents.scala`)

**Learn:** Real-time streaming, SSE protocol, event-driven architecture

Demonstrates:
- Creating SSE event streams
- Different event types
- Event IDs for reconnection
- HTML client page
- Real-time server-to-client updates

**Run it:**
```bash
sbt "runMain examples.ServerSentEventsExample"
```

**Test it:**
Open http://localhost:8080 in your browser to see the interactive SSE demo.

**Key patterns:**
```scala
// Create SSE events
val events = List(
  ServerSentEvent.data("Welcome!").copy(id = Some("1")),
  ServerSentEvent.event("update", "New message").copy(id = Some("2"))
)

// Stream events
val stream = ServerSentEvent.toChunkStream(events)
Response.sse(stream)

// Client-side (JavaScript)
const source = new EventSource('/events');
source.onmessage = e => console.log(e.data);
source.addEventListener('update', e => console.log(e.data));
```

---

### 8. Complete Application (`08_CompleteApp.scala`)

**Learn:** Full-featured application architecture, integration patterns, production best practices

Demonstrates:
- Complete REST API with authentication
- Middleware chain (logging, CORS, auth, request ID)
- CRUD operations with validation
- ETag-based caching
- Server-sent events
- Error handling
- Comprehensive example combining all features

**Run it:**
```bash
sbt "runMain examples.CompleteApp"
```

**Test it:**
```bash
# Public endpoints (no auth)
curl http://localhost:8080/
curl http://localhost:8080/health
curl http://localhost:8080/stats

# Protected endpoints (require auth)
curl -H "Authorization: Bearer demo-token" http://localhost:8080/articles
curl -H "Authorization: Bearer demo-token" http://localhost:8080/articles/1

# Create article
curl -X POST \
  -H "Authorization: Bearer demo-token" \
  http://localhost:8080/articles \
  -d 'New Article,Great content here,charlie'

# SSE stream
curl -H "Authorization: Bearer demo-token" http://localhost:8080/events
```

**Key patterns:**
```scala
// Authentication middleware
val authMiddleware: Middleware = handler => req =>
  if isPublicEndpoint(req.uri.path) then handler(req)
  else {
    req.headers.getFirst(HeaderNames.Authorization) match {
      case Some(authHeader) if authHeader.value == "Bearer demo-token" =>
        handler(req)
      case _ =>
        Response.unauthorized("Bearer", Body.text("Unauthorized"))
    }
  }

// Full middleware chain
val app = authMiddleware
  .andThen(Middleware.logging(println))
  .andThen(Middleware.corsPermissive)
  .andThen(requestIdMiddleware)
  .andThen(Middleware.errorHandlerDefault)
  .apply(handler)
```

---

## Key Concepts

### Eru Effect System

All examples use the Eru effect system for:
- **Type-safe error handling**: Errors are tracked in the type system
- **Resource management**: Automatic cleanup with `bracket` and scoped resources
- **Composability**: Effects compose naturally with `flatMap` and for-comprehensions
- **Purity**: Side effects are contained and explicit

```scala
val program: Eru[HttpError, Response[String]] = for {
  client <- HttpClient.create(config)
  response <- client.execute[Body, String](request)
  _ <- client.shutdown
} yield response

// Run the program
program.attempt.unsafeRunSync() match {
  case Result.Success(value) => println(value)
  case Result.Failure(error) => println(s"Error: $error")
}
```

### Interceptors (Client)

Transform requests and responses:
- **Request interceptors**: Add headers, auth, logging before sending
- **Response interceptors**: Process responses after receiving
- **Composable**: Chain multiple interceptors together

### Middleware (Server)

Transform request handlers:
- **Wrap handlers**: Add behavior before/after request processing
- **Composable**: Build middleware chains
- **Reusable**: Share common patterns across applications

### Type Safety

eru-http leverages Scala 3 for maximum type safety:
- **Opaque types**: `Uri`, `Bytes`, `Charset` prevent invalid values
- **Union types**: Precise error types (e.g., `HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue`)
- **Pattern matching**: Exhaustive checks on methods, status codes

---

## Best Practices

### 1. Use Scoped Resources

Prefer scoped clients for automatic cleanup:

```scala
// Good: automatic cleanup
HttpClient.scoped { client =>
  client.execute(request)
}

// Also good: manual cleanup in for-comprehension
for {
  client <- HttpClient.create(config)
  response <- client.execute(request)
  _ <- client.shutdown
} yield response
```

### 2. Compose Middleware

Build middleware chains from simple, focused middleware:

```scala
val app = logging
  .andThen(cors)
  .andThen(auth)
  .andThen(errorHandler)
  .apply(handler)
```

### 3. Handle Errors Explicitly

Use `.mapError` to convert errors to the expected type:

```scala
for {
  bodyText <- BodyDecoder[String]
    .decode(req.body)
    .mapError(HttpError.BodyDecodeError.apply)
  response <- Response.ok(Body.text(bodyText))
} yield response
```

### 4. Leverage Pattern Matching

Use pattern matching for routing:

```scala
val handler: RequestHandler = req =>
  (req.method, req.uri.path) match {
    case (Method.GET, "/") => handleRoot()
    case (Method.POST, "/api/data") => handleData(req)
    case _ => Response.notFound(Body.text("Not found"))
  }
```

### 5. Use Proper Status Codes

eru-http provides helpers for all HTTP status codes:

```scala
Response.ok(body)
Response.created(location, body)
Response.noContent
Response.badRequest(body)
Response.notFound(body)
Response.unauthorized(challenge, body)
Response.internalServerError(body)
```

---

## Running Examples

### Prerequisites

- Scala 3.7.3+
- sbt 1.x
- Eru runtime

### Run an Example

```bash
cd examples
sbt "runMain examples.SimpleClient"
sbt "runMain examples.SimpleServer"
```

### Test with curl

```bash
# GET request
curl http://localhost:8080/

# POST request
curl -X POST http://localhost:8080/echo -d 'Hello, World!'

# With headers
curl -H "Authorization: Bearer token" http://localhost:8080/api/data

# JSON content
curl -H "Content-Type: application/json" -X POST http://localhost:8080/api -d '{"key":"value"}'
```

---

## Learn More

- **eru-http Documentation**: [../README.md](../README.md)
- **Eru Documentation**: [../../eru/README.md](../../eru/README.md)
- **HTTP RFCs**:
  - [RFC 9110 - HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html)
  - [RFC 9112 - HTTP/1.1](https://www.rfc-editor.org/rfc/rfc9112.html)
  - [RFC 7578 - Multipart Form Data](https://www.rfc-editor.org/rfc/rfc7578.html)

---

## Contributing

Found a bug or have an improvement? Contributions are welcome! These examples are meant to showcase best practices, so quality matters.

---

**Happy coding with Scala 3 + Eru!** 🚀
