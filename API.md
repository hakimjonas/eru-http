# eru-http API reference

The public surface of 1.0.0-alpha. Types marked opaque have validating constructors; there is no public unvalidated constructor. All I/O returns `Eru[E, A]`.

## Core (`net.ghoula.eru.http`)

### Request model

| Type | Description |
|------|-------------|
| `Method` | Opaque HTTP method. `Method.GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE`, `CONNECT`, `PATCH`, `QUERY`; `Method.parse(String)` validates per RFC 9110 token rules. Extensions: `isSafe`, `isIdempotent`, `isCacheable`, `allowsRequestBody`. QUERY (RFC 10008) is safe, idempotent, and cacheable, and allows a body. |
| `Uri` | Opaque RFC 3986 URI. `Uri.parse(String)`. Accessors: `scheme`, `host`, `port`, `path`, `query`, `fragment`. Builders: `withPath`, `withQuery`, `withQueryParam`, `withScheme`, `withPort`. `resolve(String)` resolves a relative reference per RFC 3986 Section 5.2. |
| `Request[+A]` | Method, URI, headers, body, version. `Request.get`, `post`, `put`, `delete`, `query`; header builders `withContentType`, `withAuthorization`, `withBearerToken`, `withETag`, `withHost`, `addHeader`, `setHeader`; `validate` per RFC 9110 and RFC 10008 (QUERY requires `Content-Type`). |
| `Response[+A]` | Status, headers, body, version. Factories: `ok`, `created`, `noContent`, `movedPermanently`, `badRequest`, `unauthorized`, `methodNotAllowed`, `internalServerError`, `sse`. Builders: `withContentType`, `withLocation`, `withCacheControl`, `withETag`, `withSSE`. Checks: `isSuccess`, `isClientError`, `isServerError`, `isError`. `validate` per RFC 9110. |
| `HttpVersion` | `HTTP_1_0`, `HTTP_1_1`, `HTTP_2_0`, `HTTP_3_0`. |
| `StatusCode` | Opaque 100–599. Named constants for the common codes; `apply(Int)` validates. Extensions: `statusClass`, `isInformational`/`isSuccessful`/`isRedirection`/`isClientError`/`isServerError`/`isError`, `isRetryable`, `isCacheable`, `allowsResponseBody`, `requiredHeaders`, `reasonPhrase`. |
| `StatusClass` | `Informational`, `Successful`, `Redirection`, `ClientError`, `ServerError`. |

### Headers

| Type | Description |
|------|-------------|
| `Headers` | Case-insensitive header collection with validation. `Headers.empty`, `apply(pairs*)`, `add`, `set`, `remove`, `contains`, `get`, `getFirst`, `++`, `toList`, `foreach`. Typed accessors: `contentType`, `accept`, `contentTypeRaw`. |
| `HeaderName` / `HeaderValue` | Opaque validated header names and values. CR/LF is rejected at construction. |
| `HeaderNames` | String constants for standard header names. |

### Bodies

| Type | Description |
|------|-------------|
| `Body` | Sealed: `Empty`, `Text`, `Binary`, `Stream`. Factories: `Body.text`, `Body.binary`. |
| `Chunk` | Immutable byte chunk. `Chunk.fromString`, `fromBytes`. |
| `ChunkStream` | Lazy chunk stream with backpressure. `fromString`, `fromBytes`, `fromChunks`, `fromIterator`, `eval`, `single`, `++`, `map`, `filter`, `take`, `drop`, `fold`, `toBytes`. |
| `BodyEncoder[A]` / `BodyDecoder[A]` | Typeclasses with givens for `String`, `Bytes`, `Unit`, `Body`. |
| `Charset` / `Bytes` | Opaque charset and immutable byte arrays. |
| `ContentEncoding` | `Gzip`, `Deflate`, `Brotli`. |

### Semantics (RFC 9110, 9111, 6265)

| Type | Description |
|------|-------------|
| `MediaType` | Parsed MIME type with parameters. Constants: `applicationJson`, `textPlain`, `textHtml`, `textEventStream`, `multipartFormData`, and shortcuts `json`, `xml`, `html`, `text`, `binary`. |
| `CacheControl` / `CacheDirective` | RFC 9111 directives with parse/serialize round-trips. |
| `AcceptQuery` / `QueryMediaRange` | The `Accept-Query` response field (RFC 10008 Section 3): a validated Structured Fields List (RFC 9651) of query media ranges. `parse`, `value`, `fromMediaTypes`, `accepts`; emitted with `Response.withAcceptQuery`. |
| `ETag` | Strong/weak entity tags. `ETag.strong`, `weak`, `fromBytes`, `headerValue`, `matches`, `matchesAny`. |
| `HttpDate` | RFC 9110 date parsing and formatting. |
| `Cookie` / `SameSite` | RFC 6265 cookie parsing and serialization. |
| `Multipart` / `Part` | RFC 7578 form data. `Part.formField`, `Part.file`, `Part.fileFromBytes`; `Multipart.formData`, `toBody`, `contentType`. |
| `ServerSentEvent` | Event construction (`data`, `event`, `comment`), `parse`, `toSSE`, `toChunkStream`. |
| `TlsConfig` / `TlsVersion` | TLS settings: `default`, `insecure`, `tls13Only`, `disabled`; versions `TLSv1_2`, `TLSv1_3`. |
| `Port` | Opaque 1–65535. `Port.HTTP`, `HTTPS`; `apply(Int)` validates. |
| `HttpError` | The shared error enum: `InvalidRequest`, `InvalidResponse`, `InvalidUri`, `InvalidMethod`, `InvalidStatusCode`, `InvalidMediaType`, `InvalidCookie`, `BodyEncodeError`, `BodyDecodeError`, `NetworkError`, `TimeoutError`, `ConnectionError`, `ProtocolError`, `PayloadTooLarge`. |

## Client (`net.ghoula.eru.http.client`)

| Type | Description |
|------|-------------|
| `HttpClient` | The client. `create`, `scoped`; `send[A](Request[A])` returns `Response[Bytes]`, `execute[A, B](Request[A])` returns a decoded `Response[B]`; `queryFormats(uri)` probes a resource's `Accept-Query` field via OPTIONS; `shutdown`; `withRequestInterceptor`, `withResponseInterceptor`. Redirect handling per RFC 9110 Section 15.4 and RFC 10008 Section 2.5 (303 on QUERY switches to GET; other statuses repeat the QUERY with its content). |
| `HttpClientConfig` | Timeouts, pool limits, redirect policy, HTTP/2 switch, default User-Agent, cookie jar, compression, TLS. Presets: `default`, `lowLatency`, `highThroughput`. |
| `Interceptor` | Request and response interceptors: `addHeader`, `addHeaders`, `bearerAuth`, `basicAuth`, `userAgent`, `logRequest`, `logResponse`, `logging`, `when`. |
| `CookieJar` | RFC 6265 cookie storage. `inMemory`; `add`, `getCookies`, `remove`, `clear`. |
| `WebSocketClient` / `WebSocketConnection` | RFC 6455 client. `create`, `scoped`; `connect`, `sendText`, `sendBinary`, `sendPing`, `receive`, `close`, `isOpen`, `subprotocol`. |
| `WebSocketClientConfig` | Connect and handshake timeouts, message and frame size limits, TLS, requested subprotocols. |

## Server (`net.ghoula.eru.http.server`)

| Type | Description |
|------|-------------|
| `RequestHandler` | `Request[Body] => Eru[HttpError, Response[Body]]`. |
| `HttpServer` | The server. `create(config, handler)`, `scoped`; `start` returns `ServerAddress`; `shutdown`; `isRunning`. |
| `HttpServerConfig` | Host, port, backlog, connection caps, idle and header timeouts, request size limit, HTTP/2, graceful shutdown timeout, TLS, acceptors, PROXY protocol, trusted proxies, per-IP governance, watchdog interval, server observer. Presets: `default`, `localhost`, `highThroughput`, `microservice`. |
| `ServerAddress` | The bound `host:port`. |
| `Middleware` | Handler transforms: `logging`, `loggingSimple`, `cors`, `corsPermissive`, `auth`, `bearerAuth`, `requestId`, `errorHandler`, `errorHandlerDefault`, `compression`, `when`, `forPath`, `forMethod`, `combine`. Composition via `andThen` and `apply`. |
| `CORSConfig` / `CompressionConfig` | Configuration for the `cors` and `compression` middleware. |
| `UnauthorizedHandler` | Factory for the 401 handler used by `auth` middleware. |
| `ProxyProtocolMode` | `Off`, `Optional`, `Required` — PROXY protocol v2 handling. |
| `WebSocketServer` / `WebSocketHandler` | Upgrade handling. `create`, `upgradeHandler`; server-side connections expose `receive`, `sendText`, `sendBinary`, `close`. |
| `WebSocketServerConfig` | Message and frame size limits, ping/pong watchdog intervals, allowed subprotocols and origins. |

## HTTP/2 and WebSocket internals

`net.ghoula.eru.http.h2` contains the frame codec, HPACK codec, and connection state machines (`H2FrameCodec`, `HpackEncoder`, `HpackDecoder`, `H2Connection`, `H2Stream`). `net.ghoula.eru.http.websocket` contains `WebSocketHandshake`, `WebSocketFrameParser`, `WebSocketFrameWriter`, and the frame and error types. These packages are public because the client and server depend on them across modules; applications rarely use them directly.
