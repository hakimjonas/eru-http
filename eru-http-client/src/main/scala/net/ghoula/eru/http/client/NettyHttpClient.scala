package net.ghoula.eru.http.client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{SslContext, SslContextBuilder}
import io.netty.handler.timeout.{ReadTimeoutHandler, WriteTimeoutHandler}
import io.netty.util.concurrent.GenericFutureListener

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.http.{HttpVersion as EruHttpVersion, *}
import net.ghoula.eru.prelude.*

/** Netty-based HTTP client implementation.
  */
private[client] final class NettyHttpClient(
  config: HttpClientConfig,
  bootstrap: Bootstrap,
  eventLoopGroup: EventLoopGroup,
  sslContext: Option[SslContext],
  requestInterceptors: List[RequestInterceptor] = List.empty,
  responseInterceptors: List[ResponseInterceptor] = List.empty
) extends HttpClient {

  override def execute[A, B](
    request: Request[A]
  )(using encoder: BodyEncoder[A], decoder: BodyDecoder[B]): Eru[HttpError, Response[B]] =
    for {
      responseBytes <- send(request)
      body = Body.Binary(responseBytes.body)
      decoded <- decoder.decode(body).mapError(HttpError.BodyDecodeError.apply)
    } yield responseBytes.copy(body = decoded)

  override def send[A](request: Request[A])(using encoder: BodyEncoder[A]): Eru[HttpError, Response[Bytes]] =
    for {
      _ <- request.validate.mapError(HttpError.InvalidRequest.apply)
      encodedBody <- encoder.encode(request.body).mapError(HttpError.BodyEncodeError.apply)
      encodedRequest = request.copy(body = encodedBody)
      // Apply request interceptors
      interceptedRequest <- requestInterceptors.foldLeft(Eru.succeed(encodedRequest)) { (req, interceptor) =>
        req.flatMap(interceptor)
      }
      response <- sendInternal(interceptedRequest, redirectCount = 0)
      // Apply response interceptors (convert Bytes to Body first)
      responseAsBody: Response[Body] = response.copy(body = Body.Binary(response.body))
      interceptedResponse <- responseInterceptors.foldLeft(
        Eru.succeed(responseAsBody): Eru[HttpError, Response[Body]]
      ) { (resp, interceptor) =>
        resp.flatMap(interceptor)
      }
      // Convert back to Response[Bytes]
      finalResponse = interceptedResponse.body match {
        case Body.Empty => interceptedResponse.copy(body = Bytes.empty)
        case Body.Text(text, _, charset) =>
          interceptedResponse.copy(body = Bytes.fromArray(text.getBytes(charset.toJavaCharset)))
        case Body.Binary(bytes, _) => interceptedResponse.copy(body = bytes)
        case Body.Stream(_, _, _) =>
          // Should not happen in response interceptors for client
          interceptedResponse.copy(body = Bytes.empty)
      }
    } yield finalResponse

  private def sendInternal(request: Request[Body], redirectCount: Int): Eru[HttpError, Response[Bytes]] =
    for {
      host <- Eru.fromOption(
        request.uri.host,
        HttpError.InvalidRequest(InvalidRequest("Missing host in URI", "RFC 9110"))
      )
      port <- getPort(request.uri)
      scheme <- Eru.fromOption(
        request.uri.scheme,
        HttpError.InvalidRequest(InvalidRequest("Missing scheme in URI", "RFC 9110"))
      )

      // Create Netty request
      nettyRequest <- createNettyRequest(request)

      // Execute request
      response <- executeRequest(host, port, scheme == "https", nettyRequest)

      // Process Set-Cookie headers if cookie jar is present
      _ <- config.cookieJar match {
        case Some(jar) =>
          val setCookieHeaders = response.headers.get(HeaderNames.SetCookie).getOrElse(List.empty)
          Eru
            .foreach(setCookieHeaders) { headerValue =>
              net.ghoula.eru.http.Cookie
                .parseSetCookie(headerValue.value)
                .mapError(HttpError.InvalidCookie.apply)
                .flatMap(cookie => jar.add(request.uri, cookie))
            }
            .map(_ => ())
        case None =>
          Eru.succeed(())
      }

      // Handle redirects
      result <-
        if config.followRedirects && response.status.isRedirection && redirectCount < config.maxRedirects then {
          handleRedirect(request, response, redirectCount)
        } else {
          Eru.succeed(response)
        }
    } yield result

  private def getPort(uri: Uri): Eru[HttpError, Int] =
    Eru.succeed {
      uri.port.map(_.value).getOrElse {
        uri.scheme match {
          case Some("http") => 80
          case Some("https") => 443
          case _ => 80
        }
      }
    }

  private def createNettyRequest(request: Request[Body]): Eru[HttpError, FullHttpRequest] =
    Eru.effect {
      val pathAndQuery = request.uri.path + request.uri.query.map(q => s"?$q").getOrElse("")
      val nettyMethod = HttpMethod.valueOf(request.method.value)
      val nettyVersion = request.version match {
        case v if v == EruHttpVersion.HTTP_1_0 => io.netty.handler.codec.http.HttpVersion.HTTP_1_0
        case v if v == EruHttpVersion.HTTP_1_1 => io.netty.handler.codec.http.HttpVersion.HTTP_1_1
        case v if v == EruHttpVersion.HTTP_2_0 => io.netty.handler.codec.http.HttpVersion.HTTP_1_1 // HTTP/2 via ALPN
        case _ => io.netty.handler.codec.http.HttpVersion.HTTP_1_1 // Fallback
      }

      // Convert body to ByteBuf
      val content = request.body match {
        case Body.Empty => io.netty.buffer.Unpooled.EMPTY_BUFFER
        case Body.Text(text, _, charset) =>
          val bytes = text.getBytes(charset.toJavaCharset)
          io.netty.buffer.Unpooled.wrappedBuffer(bytes)
        case Body.Binary(bytes, _) =>
          io.netty.buffer.Unpooled.wrappedBuffer(bytes.toArray)
        case Body.Stream(_, _, _) =>
          throw new UnsupportedOperationException("Streaming request bodies not yet implemented")
      }

      val nettyRequest = new DefaultFullHttpRequest(nettyVersion, nettyMethod, pathAndQuery, content)

      // Add headers
      request.headers.toList.foreach { case (name, value) =>
        nettyRequest.headers().add(name, value)
      }

      // Add User-Agent if not present
      if !request.headers.contains(HeaderNames.UserAgent) then {
        config.userAgent.foreach { ua =>
          nettyRequest.headers().add(HeaderNames.UserAgent, ua)
        }
      }

      // Add cookies from cookie jar if present
      config.cookieJar.foreach { jar =>
        val cookies = jar.getCookies(request.uri).unsafeRunSync()
        if cookies.nonEmpty then {
          val cookieHeader = cookies.map(_.toCookieHeader).mkString("; ")
          nettyRequest.headers().add(HeaderNames.Cookie, cookieHeader): Unit
        }
      }

      // Add Content-Length if body is present
      if content.readableBytes() > 0 then {
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes()): Unit
      }

      nettyRequest
    }.mapError(e => HttpError.NetworkError(s"Failed to create request: ${e.getMessage}", Some(e)))

  private def executeRequest(
    host: String,
    port: Int,
    useSsl: Boolean,
    nettyRequest: FullHttpRequest
  ): Eru[HttpError, Response[Bytes]] =
    EruRuntime.shared
      .suspend[HttpError, Response[Bytes]] { callback =>
        Eru.effectTotal {
          val handler = new HttpClientHandler(callback, config)
          val channel = bootstrap
            .clone()
            .handler(new ChannelInitializer[SocketChannel] {
              override def initChannel(ch: SocketChannel): Unit = {
                val pipeline = ch.pipeline()

                // SSL handler
                if useSsl then {
                  sslContext.foreach { ctx =>
                    val sslHandler = ctx.newHandler(ch.alloc(), host, port)

                    // Disable hostname verification if configured
                    if !config.tlsConfig.verifyHostname then {
                      val sslEngine = sslHandler.engine()
                      val sslParams = sslEngine.getSSLParameters
                      sslParams.setEndpointIdentificationAlgorithm(null) // scalafix:ok DisableSyntax.null
                      sslEngine.setSSLParameters(sslParams)
                    }

                    pipeline.addLast("ssl", sslHandler)
                  }
                }

                // HTTP codec
                pipeline.addLast(new HttpClientCodec())
                pipeline.addLast(new HttpObjectAggregator(10 * 1024 * 1024)) // 10 MB max response size

                // Timeout handlers
                val timeoutMillis = config.requestTimeout.toMillis
                pipeline.addLast(new ReadTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS)): Unit
                pipeline.addLast(new WriteTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS)): Unit

                // Our handler
                pipeline.addLast(handler): Unit
              }
            })
            .connect(host, port)

          channel.addListener(new GenericFutureListener[ChannelFuture] {
            override def operationComplete(future: ChannelFuture): Unit = {
              if future.isSuccess then {
                channel
                  .channel()
                  .writeAndFlush(nettyRequest)
                  .addListener(new GenericFutureListener[ChannelFuture] {
                    override def operationComplete(writeFuture: ChannelFuture): Unit = {
                      if !writeFuture.isSuccess then {
                        channel.channel().close(): Unit
                        callback(
                          Left(
                            HttpError.NetworkError(
                              s"Failed to write request: ${writeFuture.cause().getMessage}",
                              Some(writeFuture.cause())
                            )
                          )
                        )
                      }
                    }
                  }): Unit
              } else {
                callback(
                  Left(
                    HttpError.ConnectionError(
                      s"Failed to connect to $host:$port: ${future.cause().getMessage}",
                      Some(future.cause())
                    )
                  )
                )
              }
            }
          })
          (): Unit
        }
      }
      .mapError {
        case e: HttpError => e
        case t: Throwable => HttpError.NetworkError(s"Unexpected error: ${t.getMessage}", Some(t))
      }

  private def handleRedirect(
    originalRequest: Request[Body],
    response: Response[Bytes],
    redirectCount: Int
  ): Eru[HttpError, Response[Bytes]] =
    for {
      locationHeader <- Eru.fromOption(
        response.headers.getFirst(HeaderNames.Location).map(_.value),
        HttpError.ProtocolError("Redirect response missing Location header", "RFC 9110 Section 15.4")
      )
      newUri <- Uri.parse(locationHeader).mapError(HttpError.InvalidUri.apply)
      newRequest = originalRequest.copy(uri = newUri)
      result <- sendInternal(newRequest, redirectCount + 1)
    } yield result

  override def shutdown: Eru[Nothing, Unit] =
    Eru.effectTotal {
      eventLoopGroup.shutdownGracefully().sync()
      ()
    }

  override def withRequestInterceptor(interceptor: RequestInterceptor): HttpClient =
    new NettyHttpClient(
      config,
      bootstrap,
      eventLoopGroup,
      sslContext,
      requestInterceptors :+ interceptor,
      responseInterceptors
    )

  override def withResponseInterceptor(interceptor: ResponseInterceptor): HttpClient =
    new NettyHttpClient(
      config,
      bootstrap,
      eventLoopGroup,
      sslContext,
      requestInterceptors,
      responseInterceptors :+ interceptor
    )
}

private[client] object NettyHttpClient {

  def create(config: HttpClientConfig): Eru[HttpError, NettyHttpClient] =
    Eru.effect {
      val eventLoopGroup = new NioEventLoopGroup()
      val bootstrap = new Bootstrap()
        .group(eventLoopGroup)
        .channel(classOf[NioSocketChannel])
        .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeout.toMillis.toInt: java.lang.Integer)

      // Create SSL context for HTTPS based on TLS configuration
      val sslContext = if config.tlsConfig.enabled then {
        val builder = if config.tlsConfig.trustAll then {
          // Insecure mode: trust all certificates
          SslContextBuilder
            .forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
        } else {
          // Secure mode: use system trust store
          SslContextBuilder.forClient()
        }

        // Configure supported TLS protocols
        val protocols = config.tlsConfig.protocols.map(_.value).toArray
        val ctx = builder.protocols(protocols*).build()

        Some(ctx)
      } else {
        None
      }

      new NettyHttpClient(config, bootstrap, eventLoopGroup, sslContext)
    }.mapError(e => HttpError.NetworkError(s"Failed to create HTTP client: ${e.getMessage}", Some(e)))
}

/** Netty channel handler for HTTP responses.
  */
private class HttpClientHandler(
  callback: Either[HttpError, Response[Bytes]] => Unit,
  config: HttpClientConfig
) extends SimpleChannelInboundHandler[FullHttpResponse] {

  override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse): Unit = {
    try {
      // Parse status code
      val statusCode = StatusCode(msg.status().code()).unsafeRunSync()

      // Parse headers
      var headers = Headers.empty
      val headersIterator = msg.headers().iteratorAsString().asScala
      headersIterator.foreach { entry =>
        headers = headers.add(entry.getKey, entry.getValue()).unsafeRunSync()
      }

      // Read body
      val content = msg.content()
      val bytes = if content.readableBytes() > 0 then {
        val array = new Array[Byte](content.readableBytes())
        content.readBytes(array)
        Bytes.fromArray(array)
      } else {
        Bytes.empty
      }

      val response = Response(statusCode, headers, bytes)
      callback(Right(response))
      ctx.close(): Unit

    } catch {
      case e: StatusCode.InvalidStatusCode =>
        callback(Left(HttpError.InvalidStatusCode(e)))
        ctx.close(): Unit
      case e: HeaderName.InvalidHeaderName =>
        callback(Left(HttpError.InvalidResponse(InvalidResponse(s"Invalid header name: ${e.getMessage}", "RFC 9110"))))
        ctx.close(): Unit
      case e: HeaderValue.InvalidHeaderValue =>
        callback(Left(HttpError.InvalidResponse(InvalidResponse(s"Invalid header value: ${e.getMessage}", "RFC 9110"))))
        ctx.close(): Unit
      case e: Exception =>
        callback(Left(HttpError.NetworkError(s"Error processing response: ${e.getMessage}", Some(e))))
        ctx.close(): Unit
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    cause match {
      case _: io.netty.handler.timeout.ReadTimeoutException =>
        callback(Left(HttpError.TimeoutError(s"Read timeout after ${config.requestTimeout}")))
      case _: io.netty.handler.timeout.WriteTimeoutException =>
        callback(Left(HttpError.TimeoutError(s"Write timeout after ${config.requestTimeout}")))
      case e =>
        callback(Left(HttpError.NetworkError(s"Network error: ${e.getMessage}", Some(e))))
    }
    ctx.close(): Unit
  }
}
