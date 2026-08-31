# eru-http Quickstart

This guide builds a small HTTP service with the server and calls it with the client. Each sample is compiled and executed by mdoc against the published API.

## Setup

Add the modules to `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-http-core" % "@VERSION@",
  "net.ghoula" %% "eru-http-client" % "@VERSION@",
  "net.ghoula" %% "eru-http-server" % "@VERSION@"
)
```

Every program that touches the network needs an `EruRuntime`. Create one for the application and run programs with `unsafeRunSync()`, or use the `scoped` helpers that manage resource lifetimes for you.

```scala mdoc
import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.prelude.*

given runtime: EruRuntime = EruRuntime.create()
```

## A handler

A server is a function from `Request[Body]` to `Eru[HttpError, Response[Body]]`. Route by method and path, and build responses with the `Response` constructor or its factories.

```scala mdoc
import net.ghoula.eru.http.server.*

val handler: RequestHandler = req =>
  req.uri.path match {
    case "/" =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("root")
        )
      )
    case "/hello" if req.method == Method.GET =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("Hello, World!")
        )
      )
    case "/ping" =>
      Eru.succeed(
        Response(
          status = StatusCode.Ok,
          headers = Headers.empty,
          body = Body.text("pong")
        )
      )
    case _ =>
      Eru.succeed(
        Response(
          status = StatusCode.NotFound,
          headers = Headers.empty,
          body = Body.text("Not Found")
        )
      )
  }
```

`RequestHandler` is a type alias in `net.ghoula.eru.http.server`. The response body is a `Body`: `Body.text`, `Body.binary`, `Body.Empty`, or a streaming `Body.Stream`.

## Starting the server

`HttpServer.create` builds a server; `start` binds the port and returns the bound address. Port 0 asks the OS for an ephemeral port.

```scala mdoc
val server: HttpServer = HttpServer.create(HttpServerConfig.localhost.withPort(0), handler).unsafeRunSync()
val address: ServerAddress = server.start.unsafeRunSync()
println(s"listening on ${address.host}:${address.port}")
```

`HttpServer.scoped(config)(handler) { server => ... }` shuts the server down when the block ends, including on failure. Prefer it over manual `shutdown`.

## Calling the server

`HttpClient.scoped { client => ... }` acquires a client, runs the block, and releases the connection pool when the block ends.

```scala mdoc
import net.ghoula.eru.http.client.*

val request: Eru[HttpError, String] =
  HttpClient.scoped { client =>
    for {
      uri <- Uri.parse(s"http://localhost:${address.port}/hello").mapError(HttpError.InvalidUri.apply)
      response <- client.send(Request.get(uri))
    } yield s"${response.status.value} ${response.body.asString(Charset.UTF8)}"
  }

println(request.unsafeRunSync())
```

`client.send` returns the raw `Response[Bytes]`. `client.execute[Body, String]` decodes the body with a `BodyDecoder`.

```scala mdoc
val decoded: Eru[HttpError, String] =
  HttpClient.scoped { client =>
    for {
      uri <- Uri.parse(s"http://localhost:${address.port}/ping").mapError(HttpError.InvalidUri.apply)
      response <- client.execute[Body, String](Request.get(uri))
    } yield s"${response.status.value} ${response.body}"
  }

println(decoded.unsafeRunSync())
```

## Shutting down

Keep the `HttpServer` value when you need to shut it down manually. `shutdown` stops accepting connections, closes client sockets, and interrupts in-flight handlers, awaiting their cleanup bounded by `gracefulShutdownTimeout`.

```scala mdoc
val lifecycle: Eru[HttpError, Unit] =
  HttpServer.create(HttpServerConfig.localhost.withPort(0), handler).flatMap { server =>
    for {
      bound <- server.start
      _ <- Eru.effect(println(s"bound to ${bound.port}"))
        .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
      _ <- server.shutdown
    } yield ()
  }

lifecycle.unsafeRunSync()
```

## Middleware

Middleware wraps a handler. Composition runs left to right, outermost first.

```scala mdoc
val app: RequestHandler =
  Middleware
    .logging(println)
    .andThen(Middleware.requestId())
    .andThen(Middleware.errorHandlerDefault)
    .apply(handler)
```

`Middleware` also provides `compression`, `cors`, `corsPermissive`, `auth`, `bearerAuth`, `loggingSimple`, and `combine`. Use `when`, `forPath`, and `forMethod` to apply a middleware only where it belongs.

## Errors

The error channel of the client and server is `HttpError`, an enum of invalid-input errors, network errors, timeouts, and protocol errors. `errorHandlerDefault` converts an `HttpError` into a `Response` with the matching status code; without it, a failing handler fails the connection fiber.

## QUERY

`QUERY` (RFC 10008) runs a safe, idempotent query whose request content is the query input. The request must carry a `Content-Type`; `Request.validate` enforces that on both sides. Redirects follow Section 2.5: 303 switches to a bodyless GET, every other redirect repeats the QUERY with its content.

```scala mdoc
val queryRequest: Request[Body] =
  Request
    .query(
      Uri.parse("http://localhost:8080/search").unsafeRunSync(),
      Body.text("""{"q": "scala"}""", MediaType.applicationJson)
    )
    .addHeader(HeaderNames.Host, "localhost:8080")
    .unsafeRunSync()
    .addHeader(HeaderNames.ContentType, "application/json")
    .unsafeRunSync()

assert(queryRequest.method == Method.QUERY)
assert(queryRequest.method.isIdempotent)
assert(queryRequest.method.isCacheable)
val _: Request[Body] = queryRequest.validate.unsafeRunSync()
```

A resource can advertise the query media types it accepts with the `Accept-Query` response field, and the client can discover them with one OPTIONS probe:

```scala mdoc
val discoveryHandler: RequestHandler = req =>
  req.method match {
    case Method.OPTIONS =>
      Response[Body](status = StatusCode.Ok, headers = Headers.empty, body = Body.Empty)
        .withAcceptQuery(AcceptQuery.fromMediaTypes(List(MediaType.applicationJson, MediaType.applicationXml)))
        .mapError(e => HttpError.InvalidResponse(InvalidResponse(s"Invalid Accept-Query header: $e", "RFC 9110")))
    case _ =>
      Eru.succeed(
        Response(status = StatusCode.NotFound, headers = Headers.empty, body = Body.text("Not Found"))
      )
  }

val discoveredFormats: Option[AcceptQuery] =
  HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(discoveryHandler) { server =>
    for {
      address <- server.start
      uri <- Uri.parse(s"http://localhost:${address.port}/search").mapError(HttpError.InvalidUri.apply)
      formats <- HttpClient.scoped(_.queryFormats(uri))
    } yield formats
  }.unsafeRunSync()

println(discoveredFormats.map(_.value))
```

## Next

```scala mdoc
// Shut down the server bound earlier in this guide.
server.shutdown.unsafeRunSync()
```

- [API reference](API.md) — the complete public surface
- [Manifesto](MANIFESTO.md) — design principles and what the library deliberately does not do
- [Security](SECURITY.md) — hardening posture and limits
