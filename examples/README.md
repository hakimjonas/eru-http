# eru-http Examples

Runnable examples for eru-http on Scala 3 and the Eru effect system. Every
example compiles against the published module APIs and starts a real local
server, so nothing here depends on an external network.

## Running

From the repository root:

```bash
sbt "examples/runMain examples.SimpleClient"
```

The client-based examples start a server on an ephemeral port, talk to it,
and exit. The server-based examples listen on port 8080 until you stop them
with Ctrl+C.

Prerequisites: a JDK (the build targets JDK 25) and sbt 2.

## Examples

### SimpleClient

The smallest complete round trip: a GET and a POST with `HttpClient.scoped`,
against a server started inside the same program.

```bash
sbt "examples/runMain examples.SimpleClient"
```

### ClientWithAuth

Request and response interceptors: `Interceptor.bearerAuth`,
`Interceptor.userAgent`, and request/response logging composed onto one
client. A server rejects the unauthenticated request with 401 and accepts the
authenticated one with 200.

```bash
sbt "examples/runMain examples.ClientWithAuth"
```

### FileUpload

Multipart form data: `Part.formField` and `Part.fileFromBytes` build the
form, `Multipart.formData` produces the body, and the server parses it back
with `Multipart.parse` using the boundary from the Content-Type header.

```bash
sbt "examples/runMain examples.FileUpload"
```

### SimpleServer

Three routes on one handler: a root page, a health check, and a POST echo
that decodes the request body with `BodyDecoder[String]`.

```bash
sbt "examples/runMain examples.SimpleServer"
curl http://localhost:8080/
curl -X POST http://localhost:8080/echo -d 'Hello'
```

### ServerWithMiddleware

Middleware composition: `logging`, `corsPermissive`, `requestId`, a custom
timing middleware, and `errorHandlerDefault`. The `/boom` route fails on
purpose so you can watch the error handler answer 500.

```bash
sbt "examples/runMain examples.ServerWithMiddleware"
curl http://localhost:8080/api/data
curl -o /dev/null -w '%{http_code}\n' http://localhost:8080/boom
```

### RestApi

A small in-memory REST API with the full CRUD set: list, get, create (201
with a Location header), update, and delete (204). Request bodies use a
plain `name,email` text format because eru-http ships no JSON codec on
purpose; `BodyEncoder`/`BodyDecoder` are the integration points for whatever
serialization you prefer.

```bash
sbt "examples/runMain examples.RestApi"
curl http://localhost:8080/users
curl -X POST http://localhost:8080/users -d 'David Brown,david@example.com'
```

### ServerSentEventsExample

Streams Server-Sent Events with `Response.sse` and
`ServerSentEvent.toChunkStream`, including named events and reconnection ids
in the WHATWG SSE wire format.

```bash
sbt "examples/runMain examples.ServerSentEventsExample"
curl -N http://localhost:8080/events
```

### CompleteApp

An application sketch that combines the pieces: bearer auth protecting
`/articles`, ETag-based conditional GET (`If-None-Match` answers 304), an SSE
feed, logging, request ids, and a default error handler.

```bash
sbt "examples/runMain examples.CompleteApp"
curl -o /dev/null -w '%{http_code}\n' http://localhost:8080/articles
curl -H 'Authorization: Bearer demo-token' http://localhost:8080/articles
```

## Compliance test servers

The examples module also carries the compliance harnesses used by the test
suite:

- `net.ghoula.eru.http.h2spec.H2ComplianceServer` and `H2SpecServer` for
  [h2spec](https://github.com/summerwind/h2spec) (HTTP/2 conformance)
- `net.ghoula.eru.http.autobahn.AutobahnEchoServer` for the
  [Autobahn WebSocket suite](https://github.com/crossbario/autobahn-testsuite);
  results live in [autobahn/AUTOBAHN_RESULTS.md](../autobahn/AUTOBAHN_RESULTS.md)

## Documentation

- Root [README](../README.md), [Quick Start](../QUICKSTART.md), and
  [API reference](../API.md)
- [Manifesto](../MANIFESTO.md) for the design principles
- [Security posture](../SECURITY.md) for the hardening details
