package net.ghoula.eru.http.client

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.util.CharsetUtil

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** Simple HTTP server for testing HTTP client.
  *
  * Supports configurable responses, headers, delays, redirects, and error conditions.
  */
final class TestHttpServer(
  val port: Int,
  private val bossGroup: EventLoopGroup,
  private val workerGroup: EventLoopGroup,
  private val channel: Channel
) {

  def shutdown(): Unit = {
    channel.close().sync(): Unit
    workerGroup.shutdownGracefully(): Unit
    bossGroup.shutdownGracefully(): Unit
  }

  def url(path: String = "/"): String = s"http://localhost:$port$path"
}

object TestHttpServer {

  /** Handler builder for configuring test responses.
    */
  case class ResponseConfig(
    status: HttpResponseStatus = HttpResponseStatus.OK,
    body: String = "",
    headers: Map[String, String] = Map.empty,
    delay: Duration = Duration.Zero,
    redirectTo: Option[String] = None
  )

  private val requestCounter = new AtomicInteger(0)

  /** Creates a test server with configurable response behavior.
    *
    * @param port
    *   Port to bind to (0 for random port)
    * @param handler
    *   Function that maps (method, path) to ResponseConfig
    */
  def create(
    port: Int = 0,
    handler: (String, String) => ResponseConfig = (_, _) => ResponseConfig()
  ): TestHttpServer = {
    val bossGroup = new NioEventLoopGroup(1)
    val workerGroup = new NioEventLoopGroup()

    try {
      val bootstrap = new ServerBootstrap()
      bootstrap
        .group(bossGroup, workerGroup)
        .channel(classOf[NioServerSocketChannel])
        .childHandler(new ChannelInitializer[SocketChannel] {
          override def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline().addLast(new HttpServerCodec())
            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024))
            ch.pipeline().addLast(new TestServerHandler(handler)): Unit
          }
        })

      val channelFuture = bootstrap.bind(port).sync()
      val actualPort = channelFuture.channel().localAddress() match {
        case addr: java.net.InetSocketAddress => addr.getPort
        case other => throw new IllegalStateException(s"Unexpected address type: ${other.getClass}")
      }

      new TestHttpServer(actualPort, bossGroup, workerGroup, channelFuture.channel())
    } catch {
      case e: Exception =>
        workerGroup.shutdownGracefully(): Unit
        bossGroup.shutdownGracefully(): Unit
        throw e
    }
  }

  /** Creates a simple test server that returns the same response for all requests.
    */
  def simple(
    port: Int = 0,
    status: HttpResponseStatus = HttpResponseStatus.OK,
    body: String = "",
    headers: Map[String, String] = Map.empty
  ): TestHttpServer = {
    create(port, (_, _) => ResponseConfig(status, body, headers))
  }

  /** Creates a test server that echoes back request information.
    */
  def echo(port: Int = 0): TestHttpServer = {
    create(
      port,
      (method, path) => {
        val body = s"""{"method":"$method","path":"$path","request":"${requestCounter.incrementAndGet()}"}"""
        ResponseConfig(
          status = HttpResponseStatus.OK,
          body = body,
          headers = Map("Content-Type" -> "application/json")
        )
      }
    )
  }

  /** Handler builder that provides access to the request.
    */
  type FullEchoHandler = FullHttpRequest => ResponseConfig

  /** Creates a test server that allows full access to the request for echoing.
    */
  def echoWithHeaders(port: Int = 0): TestHttpServer = {
    val bossGroup = new NioEventLoopGroup(1)
    val workerGroup = new NioEventLoopGroup()

    try {
      val bootstrap = new ServerBootstrap()
      bootstrap
        .group(bossGroup, workerGroup)
        .channel(classOf[NioServerSocketChannel])
        .childHandler(new ChannelInitializer[SocketChannel] {
          override def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline().addLast(new HttpServerCodec())
            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024))
            ch.pipeline().addLast(new TestServerHandlerWithRequest()): Unit
          }
        })

      val channelFuture = bootstrap.bind(port).sync()
      val actualPort = channelFuture.channel().localAddress() match {
        case addr: java.net.InetSocketAddress => addr.getPort
        case other => throw new IllegalStateException(s"Unexpected address type: ${other.getClass}")
      }

      new TestHttpServer(actualPort, bossGroup, workerGroup, channelFuture.channel())
    } catch {
      case e: Exception =>
        workerGroup.shutdownGracefully(): Unit
        bossGroup.shutdownGracefully(): Unit
        throw e
    }
  }

  private class TestServerHandlerWithRequest() extends SimpleChannelInboundHandler[FullHttpRequest] {

    override def channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest): Unit = {
      import scala.jdk.CollectionConverters.*

      val method = request.method().name()
      val path = request.uri()

      // Build JSON with headers
      val headersMap = request.headers().iteratorAsString().asScala
        .map(entry => s""""${entry.getKey.toLowerCase}":"${entry.getValue}"""")
        .mkString(",")

      val bodyContent = if request.content().readableBytes() > 0 then {
        val bytes = new Array[Byte](request.content().readableBytes())
        request.content().readBytes(bytes)
        request.content().resetReaderIndex()
        new String(bytes, "UTF-8")
      } else {
        ""
      }

      val responseBody = s"""{"method":"$method","path":"$path",$headersMap${if bodyContent.nonEmpty then s""","body":"${bodyContent.replaceAll("\"", "\\\\\"")}"""" else ""}}"""

      val content = Unpooled.copiedBuffer(responseBody, CharsetUtil.UTF_8)
      val response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.OK,
        content
      )

      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE): Unit
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      cause.printStackTrace()
      ctx.close(): Unit
    }
  }

  private class TestServerHandler(
    handler: (String, String) => ResponseConfig
  ) extends SimpleChannelInboundHandler[FullHttpRequest] {

    override def channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest): Unit = {
      val method = request.method().name()
      val path = request.uri()

      val config = handler(method, path)

      // Apply delay if configured
      if config.delay > Duration.Zero then {
        Thread.sleep(config.delay.toMillis)
      }

      // Handle redirect
      config.redirectTo match {
        case Some(location) =>
          val response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.FOUND,
            Unpooled.EMPTY_BUFFER
          )
          response.headers().set(HttpHeaderNames.LOCATION, location)
          response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE): Unit

        case None =>
          // Build response
          val content = Unpooled.copiedBuffer(config.body, CharsetUtil.UTF_8)
          val response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            config.status,
            content
          )

          // Set headers
          response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
          config.headers.foreach { case (name, value) =>
            response.headers().set(name, value)
          }

          // Send response
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE): Unit
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      cause.printStackTrace()
      ctx.close(): Unit
    }
  }
}
