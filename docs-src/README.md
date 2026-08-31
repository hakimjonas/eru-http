# eru-http

eru-http is a standards-compliant HTTP client and server for Scala 3, built on the [Eru effect system](https://github.com/hakimjonas/eru). Requests, responses, headers, URIs, and bodies are validated types; every I/O operation is a value of type `Eru[E, A]` with a typed error channel. The client and server run on Java virtual threads over blocking NIO, one fiber per connection.

Read the [Manifesto](MANIFESTO.md) for the design principles.

## Installation

The modules are published to Maven Central:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-http-core" % "@VERSION@",
  "net.ghoula" %% "eru-http-client" % "@VERSION@",
  "net.ghoula" %% "eru-http-server" % "@VERSION@"
)
```

eru-http builds against Scala @SCALA_VERSION@ on JDK 25.

## Quick start

The following program starts a server on an ephemeral port, sends it a request with the client, and prints the response. This sample is compiled and executed by mdoc against the published API, so it reflects the current signatures.

```scala mdoc
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

given runtime: EruRuntime = EruRuntime.create()

val handler: RequestHandler = req =>
  Eru.succeed(
    Response(
      status = StatusCode.Ok,
      headers = Headers.empty,
      body = Body.text(s"hello from ${req.uri.path}")
    )
  )

val program: Eru[HttpError, Unit] =
  HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
    for {
      address <- server.start
      uri <- Uri.parse(s"http://${address.host}:${address.port}/greeting").mapError(HttpError.InvalidUri.apply)
      response <- HttpClient.scoped { client => client.send(Request.get(uri)) }
    } yield println(s"${response.status.value} ${response.body.asString(Charset.UTF8)}")
  }

program.unsafeRunSync()
```

## Design

The core module contains the validated HTTP model. `Method`, `StatusCode`, `HeaderName`, `HeaderValue`, `Uri`, and `Port` are opaque types with constructors that reject invalid values; there is no unvalidated way to build them outside the `private[http]` boundary. `Request` and `Response` validate method/body combinations and forbidden header combinations before transmission.

The client and server are thin layers over the model. The server accepts connections on virtual threads, frames HTTP/1.1 and HTTP/2 (over TLS with ALPN), and hands each request to a `RequestHandler`. Middleware transforms handlers: `Middleware.compression`, `cors`, `auth`, `bearerAuth`, `requestId`, `errorHandlerDefault`, and conditional application via `when`, `forPath`, and `forMethod`. The client pools connections per host, reuses TLS sessions and HTTP/2 connections across requests, and applies request and response interceptors.

Streaming bodies are `ChunkStream` values with backpressure, shared by client and server. WebSocket support (RFC 6455) covers both sides, including the server-side ping/pong watchdog.

## Documentation

- [Quick Start](QUICKSTART.md)
- [API reference](API.md)
- [Manifesto](MANIFESTO.md)
- [Security](SECURITY.md)
- [Hostile test suite](HOSTILE_TESTING.md)
- [Contributing](CONTRIBUTING.md)

## Status

eru-http is at version @VERSION@. The API may change before 1.0.0.

## Contributing

eru-http is designed and developed by Hakim Jonas Ghoula and licensed under the GNU General Public License v3.0 or later. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and build commands.
