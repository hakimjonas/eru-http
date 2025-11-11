package net.ghoula.eru.http.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{HttpVersion as NettyHttpVersion, *}
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.{SslContext, SslContextBuilder}
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** Netty-based HTTP server implementation.
  */
private[server] final class NettyHttpServer(
  config: HttpServerConfig,
  @annotation.unused handler: RequestHandler,
  bossGroup: EventLoopGroup,
  workerGroup: EventLoopGroup,
  channel: Channel
)(using @annotation.unused runtime: EruRuntime)
    extends HttpServer {

  private val running = new AtomicBoolean(true)

  def start: Eru[HttpError, ServerAddress] = {
    Eru.succeed {
      channel.localAddress() match {
        case addr: java.net.InetSocketAddress =>
          ServerAddress(addr.getHostString, addr.getPort)
        case other =>
          throw new IllegalStateException(s"Unexpected address type: ${other.getClass}")
      }
    }
  }

  def shutdown: Eru[HttpError, Unit] = {
    if running.compareAndSet(true, false) then {
      Eru.effect {
        // Close server channel
        channel.close().sync()

        // Shutdown event loops gracefully
        workerGroup.shutdownGracefully(
          0,
          config.gracefulShutdownTimeout.toMillis,
          TimeUnit.MILLISECONDS
        )
        bossGroup.shutdownGracefully(
          0,
          config.gracefulShutdownTimeout.toMillis,
          TimeUnit.MILLISECONDS
        )
        ()
      }.mapError { case e: Exception =>
        HttpError.NetworkError(s"Error during shutdown: ${e.getMessage}", Some(e))
      }
    } else {
      Eru.succeed(())
    }
  }

  def isRunning: Boolean = running.get()
}

private[server] object NettyHttpServer {

  def create(
    config: HttpServerConfig,
    handler: RequestHandler
  )(using runtime: EruRuntime): Eru[HttpError, HttpServer] = {
    Eru.effect {
      val bossGroup = new NioEventLoopGroup(1)
      val workerGroup = new NioEventLoopGroup()

      try {
        // Create SSL context if TLS is configured
        val sslContext: Option[SslContext] = config.tlsConfig.map { tlsConfig =>
          // For development/testing: use self-signed certificate
          // In production, users should configure proper certificates
          // TODO: Allow users to provide custom certificate and private key files
          val ssc = new SelfSignedCertificate()

          val builder = SslContextBuilder
            .forServer(ssc.certificate(), ssc.privateKey())

          // Configure supported TLS protocols
          val protocols = tlsConfig.protocols.map(_.value).toArray
          builder.protocols(protocols*).build()
        }

        val bootstrap = new ServerBootstrap()
        bootstrap
          .group(bossGroup, workerGroup)
          .channel(classOf[NioServerSocketChannel])
          .option(ChannelOption.SO_BACKLOG, config.backlog: java.lang.Integer)
          .option(ChannelOption.SO_REUSEADDR, false: java.lang.Boolean)
          .childOption(ChannelOption.SO_KEEPALIVE, true: java.lang.Boolean)
          .childHandler(new ChannelInitializer[SocketChannel] {
            override def initChannel(ch: SocketChannel): Unit = {
              val pipeline = ch.pipeline()

              // SSL/TLS handler (if configured)
              sslContext.foreach { ctx =>
                pipeline.addLast("ssl", ctx.newHandler(ch.alloc()))
              }

              // HTTP codec
              pipeline.addLast(new HttpServerCodec())
              pipeline.addLast(new HttpObjectAggregator(config.maxRequestSize))

              // Idle timeout handler
              val idleSeconds = config.idleTimeout.toSeconds
              if idleSeconds > 0 then {
                pipeline.addLast(new IdleStateHandler(idleSeconds, 0, 0, TimeUnit.SECONDS))
                ()
              }

              // Request handler
              pipeline.addLast(new RequestChannelHandler(handler))
              ()
            }
          })

        val channelFuture = bootstrap.bind(config.host, config.port).sync()
        val channel = channelFuture.channel()

        new NettyHttpServer(config, handler, bossGroup, workerGroup, channel)
      } catch {
        case e: Exception =>
          workerGroup.shutdownGracefully()
          bossGroup.shutdownGracefully()
          throw e
      }
    }.mapError {
      case e: java.net.BindException =>
        HttpError.NetworkError(s"Failed to bind to ${config.host}:${config.port}: ${e.getMessage}", Some(e))
      case e: Exception =>
        HttpError.NetworkError(s"Failed to start server: ${e.getMessage}", Some(e))
    }
  }

  /** Channel handler that processes HTTP requests.
    *
    * TODO: This currently uses unsafeRunSync() which blocks the Netty event loop.
    * Once Eru provides unsafeRunAsync, this should be refactored to use async execution.
    * See docs/ERU_ASYNC_REQUIREMENTS.md for details.
    */
  private class RequestChannelHandler(handler: RequestHandler)(using runtime: EruRuntime)
      extends SimpleChannelInboundHandler[FullHttpRequest] {

    override def channelRead0(ctx: ChannelHandlerContext, nettyRequest: FullHttpRequest): Unit = {
      // Convert Netty request to our Request type
      val requestEru = convertRequest(nettyRequest)

      // Execute handler and send response
      val responseEru = requestEru.flatMap(handler)

      // Determine if we should keep the connection alive
      val keepAlive = HttpUtil.isKeepAlive(nettyRequest)

      // TEMPORARY: Using unsafeRunSync() - this blocks the event loop!
      // This will be replaced with unsafeRunAsync() once available in Eru.
      //
      // Target implementation (requires Eru changes):
      // runtime.unsafeRunAsync(responseEru.attempt) {
      //   case Result.Success(response) =>
      //     val nettyResponse = convertResponse(response, nettyRequest.protocolVersion(), keepAlive)
      //     val future = ctx.writeAndFlush(nettyResponse)
      //     if !keepAlive then future.addListener(ChannelFutureListener.CLOSE)
      //
      //   case Result.Failure(error) =>
      //     val errorResponse = errorToResponse(error, nettyRequest.protocolVersion(), keepAlive)
      //     val future = ctx.writeAndFlush(errorResponse)
      //     if !keepAlive then future.addListener(ChannelFutureListener.CLOSE)
      // }

      responseEru.attempt.unsafeRunSync() match {
        case Result.Success(response) =>
          val nettyResponse = convertResponse(response, nettyRequest.protocolVersion(), keepAlive)
          val future = ctx.writeAndFlush(nettyResponse)
          if !keepAlive then
            future.addListener(ChannelFutureListener.CLOSE): Unit

        case Result.Failure(error) =>
          // Convert error to HTTP response
          val errorResponse = errorToResponse(error, nettyRequest.protocolVersion(), keepAlive)
          val future = ctx.writeAndFlush(errorResponse)
          if !keepAlive then
            future.addListener(ChannelFutureListener.CLOSE): Unit
      }
    }

    @SuppressWarnings(Array("deprecation"))
    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      cause.printStackTrace()
      ctx.close()
      ()
    }

    private def convertRequest(nettyRequest: FullHttpRequest): Eru[HttpError, Request[Body]] = {
      for {
        // Parse method
        method <- Method
          .parse(nettyRequest.method().name())
          .mapError(e => HttpError.InvalidMethod(e))

        // Parse URI
        uri <- Uri
          .parse(nettyRequest.uri())
          .mapError(e => HttpError.InvalidUri(e))

        // Convert headers
        headers <- convertHeaders(nettyRequest.headers())

        // Convert body
        body = convertBody(nettyRequest.content())

        // Create request
        request = Request(method, uri, headers, body)
      } yield request
    }

    private def convertHeaders(nettyHeaders: HttpHeaders): Eru[HttpError, Headers] = {
      import scala.jdk.CollectionConverters.*

      val headerList = nettyHeaders.entries().asScala.toList
      val result = headerList.foldLeft[Eru[HttpError, Headers]](Eru.succeed(Headers.empty)) {
        case (headersEru, entry) =>
          headersEru.flatMap { headers =>
            headers
              .add(entry.getKey, entry.getValue)
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"Invalid header: $e", "RFC")))
          }
      }
      result
    }

    private def convertBody(content: ByteBuf): Body = {
      if content.readableBytes() == 0 then {
        Body.Empty
      } else {
        val bytes = new Array[Byte](content.readableBytes())
        content.getBytes(content.readerIndex(), bytes)
        Body.Binary(Bytes.fromArray(bytes), None)
      }
    }

    private def convertResponse(response: Response[Body], httpVersion: NettyHttpVersion, keepAlive: Boolean): FullHttpResponse = {
      // Convert body to ByteBuf
      val content = response.body match {
        case Body.Empty => Unpooled.EMPTY_BUFFER
        case Body.Text(text, _, charset) =>
          Unpooled.copiedBuffer(text, java.nio.charset.Charset.forName(charset.name))
        case Body.Binary(bytes, _) =>
          Unpooled.wrappedBuffer(bytes.toArray)
        case Body.Stream(_, _, _) =>
          // TODO: Handle streaming responses
          Unpooled.copiedBuffer("Streaming not yet supported", CharsetUtil.UTF_8)
      }

      // Create Netty response
      val nettyResponse = new DefaultFullHttpResponse(
        httpVersion,
        HttpResponseStatus.valueOf(response.status.value),
        content
      )

      // Convert headers
      response.headers.toList.foreach { case (name, value) =>
        nettyResponse.headers().set(name, value)
        ()
      }

      // Set content length if not already set
      if !nettyResponse.headers().contains(HttpHeaderNames.CONTENT_LENGTH) then {
        nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        ()
      }

      // Set Connection header based on keep-alive
      if keepAlive then {
        nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        ()
      } else {
        nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        ()
      }

      nettyResponse
    }

    private def errorToResponse(error: HttpError, httpVersion: NettyHttpVersion, keepAlive: Boolean): FullHttpResponse = {
      val (status, message) = error match {
        case HttpError.InvalidMethod(_) => (400, "Bad Request: Invalid HTTP method")
        case HttpError.InvalidUri(_) => (400, "Bad Request: Invalid URI")
        case HttpError.InvalidRequest(_) => (400, "Bad Request")
        case HttpError.InvalidResponse(_) => (500, "Internal Server Error")
        case HttpError.BodyDecodeError(_) => (400, "Bad Request: Invalid body")
        case HttpError.BodyEncodeError(_) => (500, "Internal Server Error: Failed to encode response")
        case _ => (500, "Internal Server Error")
      }

      val content = Unpooled.copiedBuffer(message, CharsetUtil.UTF_8)
      val response = new DefaultFullHttpResponse(
        httpVersion,
        HttpResponseStatus.valueOf(status),
        content
      )
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")

      // Set Connection header based on keep-alive
      if keepAlive then {
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      } else {
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
      }

      response
    }
  }
}
