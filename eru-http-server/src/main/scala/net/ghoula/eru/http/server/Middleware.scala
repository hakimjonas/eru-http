package net.ghoula.eru.http.server

import java.util.UUID

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** Middleware transforms a request handler into another request handler.
  *
  * Middleware enables composable request/response processing:
  *   - Authentication and authorization
  *   - CORS headers
  *   - Logging and metrics
  *   - Error handling
  *   - Request/response transformation
  *
  * Middleware composes through function composition, with the outermost middleware executing first.
  *
  * @example
  *   {{{
  *   val handler: RequestHandler = req =>
  *     Eru.succeed(Response.ok(Body.text("Hello")))
  *
  *   val app = Middleware.logging(println)
  *     .andThen(Middleware.cors())
  *     .andThen(Middleware.auth(checkToken))
  *     .apply(handler)
  *
  *   HttpServer.create(config, app)
  *   }}}
  */
type Middleware = RequestHandler => RequestHandler

/** Handler for unauthorized requests.
  *
  * Using a trait instead of function type to avoid cyclic references during initialization.
  */
trait UnauthorizedHandler {
  def apply(): Eru[HttpError, Response[Body]]
}

object UnauthorizedHandler {

  /** Create an UnauthorizedHandler from a function. */
  def apply(f: () => Eru[HttpError, Response[Body]]): UnauthorizedHandler =
    new UnauthorizedHandler {
      def apply(): Eru[HttpError, Response[Body]] = f()
    }
}

/** Extension methods for composing middleware. */
extension (middleware: Middleware) {

  /** Compose this middleware with another.
    *
    * The resulting middleware applies this middleware first, then the next. This creates an "onion"
    * pattern where outer middleware wraps inner middleware.
    *
    * @example
    *   {{{
    *   val combined = logging.andThen(auth).andThen(cors)
    *   // Executes: logging -> auth -> cors -> handler -> cors -> auth -> logging
    *   }}}
    */
  inline def andThen(next: Middleware): Middleware = handler => middleware(next(handler))

  /** Compose with multiple middleware in sequence. */
  inline def andThenAll(middlewares: Middleware*): Middleware =
    middlewares.foldLeft(middleware)(_ andThen _)

  /** Apply this middleware to a handler, producing a new handler. */
  inline def apply(handler: RequestHandler): RequestHandler =
    middleware(handler)
}

/** Built-in middleware for common use cases. */
object Middleware {

  /** Logging middleware that logs requests and responses.
    *
    * @param log
    *   Logging function (e.g., println, logger.info)
    * @example
    *   {{{
    *   val app = Middleware.logging(println).apply(handler)
    *   }}}
    */
  inline def logging(log: String => Unit): Middleware = handler =>
    req =>
      for {
        _ <- Eru
          .effect(log(s"→ ${req.method} ${req.uri.path}"))
          .mapError(e => HttpError.NetworkError(s"Logging failed: ${e.getMessage}", Some(e)))
        resp <- handler(req)
        _ <- Eru
          .effect(log(s"← ${resp.status} (${resp.body.contentLength.getOrElse(0)} bytes)"))
          .mapError(e => HttpError.NetworkError(s"Logging failed: ${e.getMessage}", Some(e)))
      } yield resp

  /** Simple logging without error handling (fails silently). */
  inline def loggingSimple(log: String => Unit): Middleware = handler =>
    req => {
      Eru.effect(log(s"${req.method} ${req.uri.path}")).attempt.unsafeRunSync()
      handler(req).flatMap { resp =>
        Eru.effect(log(s"  -> ${resp.status}")).attempt.unsafeRunSync()
        Eru.succeed(resp)
      }
    }

  /** CORS (Cross-Origin Resource Sharing) middleware.
    *
    * @param config
    *   CORS configuration
    * @example
    *   {{{
    *   val corsConfig = CORSConfig(
    *     allowedOrigins = List("https://example.com"),
    *     allowedMethods = List(Method.GET, Method.POST),
    *     allowedHeaders = List("Content-Type", "Authorization"),
    *     allowCredentials = true
    *   )
    *   val app = Middleware.cors(corsConfig).apply(handler)
    *   }}}
    */
  inline def cors(config: CORSConfig = CORSConfig.default): Middleware = handler =>
    req =>
      // Handle preflight OPTIONS request
      if req.method == Method.OPTIONS then corsPreflightResponse(config)
      else handler(req).flatMap(addCorsHeaders(_, config))

  /** CORS middleware allowing all origins (permissive, for development). */
  inline def corsPermissive: Middleware =
    cors(CORSConfig.permissive)

  private def corsPreflightResponse(config: CORSConfig): Eru[HttpError, Response[Body]] =
    for {
      resp <- Eru.succeed(Response.noContent)
      withHeaders <- addCorsHeaders(resp, config)
    } yield withHeaders

  private def addCorsHeaders(
    resp: Response[Body],
    config: CORSConfig
  ): Eru[HttpError, Response[Body]] =
    for {
      r1 <- resp
        .setHeader("Access-Control-Allow-Origin", config.allowedOrigins.mkString(", "))
        .mapError {
          case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
      r2 <- r1
        .setHeader("Access-Control-Allow-Methods", config.allowedMethods.map(_.value).mkString(", "))
        .mapError {
          case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
      r3 <- r2
        .setHeader("Access-Control-Allow-Headers", config.allowedHeaders.mkString(", "))
        .mapError {
          case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
      r4 <-
        if config.allowCredentials then
          r3.setHeader("Access-Control-Allow-Credentials", "true").mapError {
            case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
            case e: HeaderValue.InvalidHeaderValue =>
              HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          }
        else Eru.succeed(r3)
      r5 <- config.maxAge.fold(Eru.succeed(r4)) { age =>
        r4.setHeader("Access-Control-Max-Age", age.toString).mapError {
          case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
      }
    } yield r5

  /** Authentication middleware that requires a predicate to pass.
    *
    * @param verify
    *   Function that returns true if request is authenticated
    * @param unauthorized
    *   Response to return if authentication fails
    * @example
    *   {{{
    *   val checkToken: Request[Body] => Boolean = req =>
    *     req.headers.getFirst("Authorization").exists(_.value.startsWith("Bearer "))
    *
    *   val app = Middleware.auth(checkToken).apply(handler)
    *   }}}
    */
  def auth(
    verify: Request[Body] => Boolean,
    unauthorized: UnauthorizedHandler
  ): Middleware = handler =>
    req => {
      if verify(req) then handler(req)
      else unauthorized()
    }

  /** Bearer token authentication middleware.
    *
    * @param verify
    *   Function that validates the bearer token
    * @example
    *   {{{
    *   val validateToken: String => Boolean = token =>
    *     token == "secret-token-123"
    *
    *   val app = Middleware.bearerAuth(validateToken).apply(handler)
    *   }}}
    */
  def bearerAuth(
    verify: String => Boolean,
    unauthorized: UnauthorizedHandler
  ): Middleware = handler =>
    req => {
      val token = req.headers
        .getFirst("Authorization")
        .flatMap { header =>
          val value = header.value
          if value.startsWith("Bearer ") then Some(value.drop(7))
          else None
        }

      token match {
        case Some(t) if verify(t) => handler(req)
        case _ => unauthorized()
      }
    }

  /** Create a default 401 Unauthorized response.
    *
    * Helper for auth middleware that creates a standard unauthorized response.
    */
  def defaultUnauthorized(challenge: String = "Bearer"): Eru[HttpError, Response[Body]] =
    Response.unauthorized(challenge, Body.empty).mapError {
      case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
      case e: HeaderValue.InvalidHeaderValue => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
    }

  /** Add request ID to all requests and responses.
    *
    * Generates a UUID for each request and includes it in response headers.
    *
    * @param headerName
    *   Name of the header (default: X-Request-ID)
    * @example
    *   {{{
    *   val app = Middleware.requestId().apply(handler)
    *   }}}
    */
  inline def requestId(headerName: String = "X-Request-ID"): Middleware = handler =>
    req => {
      val requestId = UUID.randomUUID().toString
      handler(req).flatMap { resp =>
        resp.setHeader(headerName, requestId).mapError {
          case e: HeaderName.InvalidHeaderName => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
          case e: HeaderValue.InvalidHeaderValue => HttpError.InvalidRequest(InvalidRequest(e.getMessage, "RFC 9110"))
        }
      }
    }

  /** Error handling middleware that catches handler errors.
    *
    * @param handle
    *   Function that transforms errors into responses
    * @example
    *   {{{
    *   val handleError: HttpError => Response[Body] = {
    *     case HttpError.InvalidRequest(err) =>
    *       Response.badRequest(Body.text(err.message))
    *     case _ =>
    *       Response.internalServerError(Body.empty)
    *   }
    *
    *   val app = Middleware.errorHandler(handleError).apply(handler)
    *   }}}
    */
  inline def errorHandler(handle: HttpError => Response[Body]): Middleware = handler =>
    req => handler(req).recover(error => handle(error))

  /** Default error handler that returns appropriate status codes. */
  inline def errorHandlerDefault: Middleware =
    errorHandler {
      case HttpError.InvalidRequest(err) =>
        Response.badRequest(Body.text(err.reason))
      case HttpError.InvalidMethod(_) =>
        Response.badRequest(Body.empty)
      case HttpError.InvalidUri(_) =>
        Response.badRequest(Body.empty)
      case HttpError.InvalidResponse(_) =>
        Response.internalServerError(Body.empty)
      case HttpError.NetworkError(msg, _) =>
        Response.internalServerError(Body.text(msg))
      case _ =>
        Response.internalServerError(Body.empty)
    }

  /** Conditional middleware - only apply if predicate is true.
    *
    * @param condition
    *   Function that determines whether to apply middleware
    * @param middleware
    *   Middleware to conditionally apply
    * @example
    *   {{{
    *   val onlyForApi = Middleware.when(_.uri.path.startsWith("/api")) {
    *     Middleware.auth(checkApiKey)
    *   }
    *   }}}
    */
  inline def when(condition: Request[Body] => Boolean)(middleware: Middleware): Middleware = handler =>
    req =>
      if condition(req) then middleware(handler)(req)
      else handler(req)

  /** Apply middleware only to specific paths. */
  inline def forPath(pathPrefix: String)(middleware: Middleware): Middleware =
    when(_.uri.path.startsWith(pathPrefix))(middleware)

  /** Apply middleware only to specific HTTP methods. */
  inline def forMethod(method: Method)(middleware: Middleware): Middleware =
    when(_.method == method)(middleware)

  /** Combine multiple middlewares into one.
    *
    * @param middlewares
    *   Middlewares to combine (applied in order)
    * @example
    *   {{{
    *   val stack = Middleware.combine(
    *     Middleware.logging(println),
    *     Middleware.cors(),
    *     Middleware.auth(checkToken)
    *   )
    *   }}}
    */
  inline def combine(middlewares: Middleware*): Middleware =
    middlewares.reduceLeftOption(_ andThen _).getOrElse(identity)

  /** Response compression middleware.
    *
    * Compresses response bodies based on Accept-Encoding request header.
    * Supports gzip, deflate, and brotli compression.
    *
    * @param config
    *   Compression configuration
    * @example
    *   {{{
    *   val app = Middleware.compression(CompressionConfig.default).apply(handler)
    *   }}}
    */
  def compression(config: CompressionConfig = CompressionConfig.default): Middleware = handler =>
    req => {
      handler(req).flatMap { resp =>
        // Skip compression if response is already encoded
        if resp.headers.contains(HeaderNames.ContentEncoding) then {
          Eru.succeed(resp)
        } else {
          // Get Accept-Encoding from request
          val acceptedEncodings = req.headers
            .getFirst(HeaderNames.AcceptEncoding)
            .map(_.value.split(',').map(_.trim.toLowerCase).toList)
            .getOrElse(List.empty)

          // Skip compression for small responses (below threshold)
          val shouldCompress = resp.body.contentLength match {
            case Some(len) => len >= config.minSize
            case None => true // Compress streaming responses
          }

          if !shouldCompress then {
            Eru.succeed(resp)
          } else {
            // Choose encoding based on client preferences and availability
            val encoding = chooseEncoding(acceptedEncodings, config.preferredEncodings)

            encoding match {
              case Some(enc) if enc != ContentEncoding.Identity =>
                compressResponse(resp, enc).mapError(e =>
                  HttpError.NetworkError(s"Compression failed: ${e.getMessage}", Some(e))
                )
              case _ =>
                Eru.succeed(resp)
            }
          }
        }
      }
    }

  /** Compression middleware with default configuration. */
  inline def compressionDefault: Middleware =
    compression(CompressionConfig.default)

  private def chooseEncoding(
    accepted: List[String],
    preferred: List[ContentEncoding]
  ): Option[ContentEncoding] = {
    // Find first preferred encoding that is accepted by client
    preferred.find(enc => accepted.contains(enc.value.toLowerCase) || accepted.contains("*"))
  }

  private def compressResponse(
    resp: Response[Body],
    encoding: ContentEncoding
  ): Eru[Compression.CompressionError, Response[Body]] = {
    resp.body match {
      case Body.Empty => Eru.succeed(resp)

      case Body.Text(text, mediaType, charset) =>
        val bytes = Bytes.fromString(text, charset)
        Compression.compress(bytes, encoding).flatMap { compressed =>
          resp
            .setHeader(HeaderNames.ContentEncoding, encoding.value)
            .flatMap(_.setHeader(HeaderNames.ContentLength, compressed.length.toString))
            .map(_.copy(body = Body.Binary(compressed, mediaType)))
            .mapError {
              case e: HeaderName.InvalidHeaderName =>
                Compression.CompressionError(e.getMessage, Some("Invalid header name"))
              case e: HeaderValue.InvalidHeaderValue =>
                Compression.CompressionError(e.getMessage, Some("Invalid header value"))
            }
        }

      case Body.Binary(bytes, mediaType) =>
        Compression.compress(bytes, encoding).flatMap { compressed =>
          resp
            .setHeader(HeaderNames.ContentEncoding, encoding.value)
            .flatMap(_.setHeader(HeaderNames.ContentLength, compressed.length.toString))
            .map(_.copy(body = Body.Binary(compressed, mediaType)))
            .mapError {
              case e: HeaderName.InvalidHeaderName =>
                Compression.CompressionError(e.getMessage, Some("Invalid header name"))
              case e: HeaderValue.InvalidHeaderValue =>
                Compression.CompressionError(e.getMessage, Some("Invalid header value"))
            }
        }

      case Body.Stream(chunks, contentLength, mediaType) =>
        // For streaming: compress the stream and use chunked encoding
        chunks.flatMap { stream =>
          Compression.compressStream(stream, encoding).flatMap { compressedStream =>
            resp
              .setHeader(HeaderNames.ContentEncoding, encoding.value)
              .flatMap { r =>
                // Remove Content-Length since compression changes size
                val headersWithoutLength = r.headers.remove(HeaderNames.ContentLength)
                Eru.succeed(r.copy(
                  headers = headersWithoutLength,
                  body = Body.Stream(Eru.succeed(compressedStream), None, mediaType)
                ))
              }
              .mapError {
                case e: HeaderName.InvalidHeaderName =>
                  Compression.CompressionError(e.getMessage, Some("Invalid header name"))
                case e: HeaderValue.InvalidHeaderValue =>
                  Compression.CompressionError(e.getMessage, Some("Invalid header value"))
              }
          }
        }.mapError(e => Compression.CompressionError(e.toString, None))
    }
  }
}

/** CORS configuration. */
final case class CORSConfig(
  allowedOrigins: List[String] = List("*"),
  allowedMethods: List[Method] = List(
    Method.GET,
    Method.POST,
    Method.PUT,
    Method.DELETE,
    Method.OPTIONS
  ),
  allowedHeaders: List[String] = List(
    "Content-Type",
    "Authorization",
    "X-Requested-With"
  ),
  allowCredentials: Boolean = false,
  maxAge: Option[Int] = Some(86400) // 24 hours
)

object CORSConfig {

  /** Default CORS configuration (restrictive). */
  val default: CORSConfig = CORSConfig()

  /** Permissive CORS configuration (allows all origins, for development). */
  val permissive: CORSConfig = CORSConfig(
    allowedOrigins = List("*"),
    allowCredentials = false
  )

  /** CORS configuration for specific origins. */
  def forOrigins(origins: String*): CORSConfig =
    default.copy(allowedOrigins = origins.toList)
}

/** Compression middleware configuration. */
final case class CompressionConfig(
  /** Minimum response size (in bytes) to compress. Responses smaller than this are not compressed. */
  minSize: Long = 1024,
  /** Preferred compression encodings in order of preference. */
  preferredEncodings: List[ContentEncoding] = List(
    ContentEncoding.Brotli,
    ContentEncoding.Gzip,
    ContentEncoding.Deflate
  )
)

object CompressionConfig {

  /** Default compression configuration. */
  val default: CompressionConfig = CompressionConfig()

  /** Aggressive compression (compress everything, prefer brotli). */
  val aggressive: CompressionConfig = CompressionConfig(
    minSize = 0,
    preferredEncodings = List(ContentEncoding.Brotli, ContentEncoding.Gzip, ContentEncoding.Deflate)
  )

  /** Conservative compression (only large responses, prefer gzip for compatibility). */
  val conservative: CompressionConfig = CompressionConfig(
    minSize = 4096,
    preferredEncodings = List(ContentEncoding.Gzip, ContentEncoding.Deflate)
  )
}
