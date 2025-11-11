package net.ghoula.eru.http.server

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** HTTP server that handles incoming requests.
  *
  * The server listens on a configured host and port, processing incoming HTTP requests through a
  * user-provided handler function.
  *
  * @example
  *   {{{
  *   import net.ghoula.eru.prelude.*
  *
  *   given runtime: EruRuntime = EruRuntime.shared
  *
  *   val handler: RequestHandler = request =>
  *     Eru.succeed(Response(
  *       status = StatusCode.Ok,
  *       headers = Headers.empty,
  *       body = Body.Text("Hello, World!")
  *     ))
  *
  *   HttpServer.scoped(HttpServerConfig.default)(handler) { server =>
  *     server.start.flatMap { address =>
  *       // Server is now running at address
  *       Eru.succeed(())
  *     }
  *   }
  *   }}}
  */
trait HttpServer {

  /** Starts the server and begins accepting requests.
    *
    * @return
    *   The address the server is bound to (host:port)
    */
  def start: Eru[HttpError, ServerAddress]

  /** Stops the server gracefully, waiting for in-flight requests to complete.
    */
  def shutdown: Eru[HttpError, Unit]

  /** Returns true if the server is currently running.
    */
  def isRunning: Boolean
}

object HttpServer {

  /** Creates a new HTTP server with the given configuration and handler.
    *
    * @param config
    *   Server configuration
    * @param handler
    *   Function to handle incoming requests
    * @param runtime
    *   Implicit Eru runtime for execution
    * @return
    *   A new HTTP server instance
    */
  def create(
    config: HttpServerConfig,
    handler: RequestHandler
  )(using runtime: EruRuntime): Eru[HttpError, HttpServer] = {
    NativeHttpServer.create(config, handler)
  }

  /** Creates a server using default configuration.
    */
  def create(
    handler: RequestHandler
  )(using runtime: EruRuntime): Eru[HttpError, HttpServer] = {
    create(HttpServerConfig.default, handler)
  }

  /** Creates a server that is automatically shut down after use.
    *
    * @param config
    *   Server configuration
    * @param handler
    *   Function to handle incoming requests
    * @param use
    *   Function that uses the server
    * @return
    *   The result of the use function
    */
  def scoped[A](
    config: HttpServerConfig
  )(handler: RequestHandler)(
    use: HttpServer => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] = {
    create(config, handler).bracket(server => server.shutdown)(use)
  }

  /** Creates a server with default configuration that is automatically shut down after use.
    */
  def scoped[A](
    handler: RequestHandler
  )(
    use: HttpServer => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] = {
    scoped(HttpServerConfig.default)(handler)(use)
  }
}
