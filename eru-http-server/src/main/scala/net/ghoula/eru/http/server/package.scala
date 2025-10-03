package net.ghoula.eru.http

import net.ghoula.eru.*
import net.ghoula.eru.http.*

package object server {

  /** Handler function that processes HTTP requests.
    *
    * Takes a request and returns a response wrapped in an Eru effect. The handler can fail with any
    * HttpError.
    */
  type RequestHandler = Request[Body] => Eru[HttpError, Response[Body]]

  /** Address where the server is listening.
    *
    * @param host
    *   The hostname or IP address
    * @param port
    *   The port number
    */
  final case class ServerAddress(host: String, port: Int) {
    override def toString: String = s"$host:$port"
  }

  // Export middleware functions for easy access
  export Middleware.{
    logging,
    loggingSimple,
    cors,
    corsPermissive,
    auth,
    bearerAuth,
    requestId,
    errorHandler,
    errorHandlerDefault,
    when,
    forPath,
    forMethod,
    combine
  }
}
