package net.ghoula.eru.http.server

import java.io.{BufferedReader, InputStreamReader}
import java.net.{Socket, URI}
import scala.util.Using

/** Simple synchronous HTTP client for testing the server.
  *
  * Uses raw sockets instead of Java's HttpClient to have full control over headers,
  * particularly the Connection header for testing keep-alive behavior.
  */
object SimpleHttpClient {

  case class Response(
    status: Int,
    headers: Map[String, String],
    body: String
  )

  def get(url: String, headers: Map[String, String] = Map.empty): Response = {
    sendRequest("GET", url, None, headers)
  }

  def post(url: String, body: String, headers: Map[String, String] = Map.empty): Response = {
    sendRequest("POST", url, Some(body), headers)
  }

  def put(url: String, body: String, headers: Map[String, String] = Map.empty): Response = {
    sendRequest("PUT", url, Some(body), headers)
  }

  def delete(url: String, headers: Map[String, String] = Map.empty): Response = {
    sendRequest("DELETE", url, None, headers)
  }

  private def sendRequest(
    method: String,
    url: String,
    body: Option[String],
    headers: Map[String, String]
  ): Response = {
    val uri = URI.create(url)
    val host = uri.getHost
    val port = if uri.getPort == -1 then 80 else uri.getPort
    val path = if uri.getPath.isEmpty then "/" else uri.getPath

    Using.resource(new Socket(host, port)) { socket =>
      val out = socket.getOutputStream
      val in = new BufferedReader(new InputStreamReader(socket.getInputStream))

      // Build HTTP request with proper CRLF line endings (required by HTTP/1.1)
      val request = new StringBuilder

      // Request line
      request.append(s"$method $path HTTP/1.1\r\n")

      // Host header (required for HTTP/1.1)
      request.append(s"Host: $host:$port\r\n")

      // Connection: close to avoid keep-alive timeout in tests
      request.append("Connection: close\r\n")

      // Custom headers
      headers.foreach { case (name, value) =>
        request.append(s"$name: $value\r\n")
      }

      // Content-Length if body present
      body.foreach { b =>
        request.append(s"Content-Length: ${b.getBytes.length}\r\n")
      }

      // End headers with empty line
      request.append("\r\n")

      // Send request headers
      out.write(request.toString.getBytes)

      // Send body if present
      body.foreach { b =>
        out.write(b.getBytes)
      }
      out.flush()

      // Read response
      parseResponse(in)
    }
  }

  private def parseResponse(in: BufferedReader): Response = {
    // Read status line
    val statusLine = Option(in.readLine()).getOrElse {
      throw new RuntimeException("Empty response from server")
    }

    val statusParts = statusLine.split(" ", 3)
    val status = statusParts(1).toInt

    // Read headers
    val headersMap = scala.collection.mutable.Map[String, String]()
    var contentLength = Option.empty[Int]
    var connectionClose = false
    var continue = true

    while continue do {
      Option(in.readLine()) match {
        case Some(line) if line.nonEmpty =>
          val colonIdx = line.indexOf(':')
          if colonIdx > 0 then {
            val name = line.substring(0, colonIdx).trim.toLowerCase
            val value = line.substring(colonIdx + 1).trim
            headersMap(name) = value

            if name == "content-length" then {
              contentLength = Some(value.toInt)
            } else if name == "connection" then {
              connectionClose = value.toLowerCase == "close"
            }
          }
        case _ =>
          continue = false
      }
    }

    // Read body
    val bodyBuilder = new StringBuilder
    contentLength match {
      case Some(length) if length > 0 =>
        // Read exact number of bytes specified by Content-Length
        val buffer = new Array[Char](length)
        var totalRead = 0
        while totalRead < length do {
          val read = in.read(buffer, totalRead, length - totalRead)
          if read == -1 then {
            throw new RuntimeException("Connection closed before reading full body")
          }
          totalRead += read
        }
        bodyBuilder.append(buffer, 0, totalRead)

      case _ if connectionClose =>
        // No Content-Length but Connection: close - read until EOF
        var line = Option(in.readLine())
        while line.isDefined do {
          bodyBuilder.append(line.get)
          line = Option(in.readLine())
          if line.isDefined then {
            bodyBuilder.append("\n")
          }
        }

      case _ =>
        // No body to read
        ()
    }

    Response(
      status = status,
      headers = headersMap.toMap,
      body = bodyBuilder.toString
    )
  }
}
