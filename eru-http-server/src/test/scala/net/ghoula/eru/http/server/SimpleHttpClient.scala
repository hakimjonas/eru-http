package net.ghoula.eru.http.server

import java.io.{BufferedReader, InputStreamReader}
import java.net.{Socket, URI}
import scala.util.Using

/** Simple synchronous HTTP client for testing the server.
  *
  * Uses raw sockets instead of Java's HttpClient to have full control over headers, particularly
  * the Connection header for testing keep-alive behavior.
  */
object SimpleHttpClient {

  case class Response(
    status: Int,
    headers: Map[String, String],
    body: String
  )

  def get(url: String, headers: Map[String, String] = Map.empty, connectionClose: Boolean = true): Response = {
    sendRequest("GET", url, None, headers, connectionClose)
  }

  def post(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    connectionClose: Boolean = true
  ): Response = {
    sendRequest("POST", url, Some(body), headers, connectionClose)
  }

  def put(
    url: String,
    body: String,
    headers: Map[String, String] = Map.empty,
    connectionClose: Boolean = true
  ): Response = {
    sendRequest("PUT", url, Some(body), headers, connectionClose)
  }

  def delete(url: String, headers: Map[String, String] = Map.empty, connectionClose: Boolean = true): Response = {
    sendRequest("DELETE", url, None, headers, connectionClose)
  }

  private def sendRequest(
    method: String,
    url: String,
    body: Option[String],
    headers: Map[String, String],
    connectionClose: Boolean
  ): Response = {
    val uri = URI.create(url)
    val host = uri.getHost
    val port = if uri.getPort == -1 then 80 else uri.getPort

    val pathWithQuery = {
      val p = if uri.getPath.isEmpty then "/" else uri.getPath
      Option(uri.getQuery) match {
        case Some(q) => s"$p?$q"
        case None => p
      }
    }

    Using.resource(new Socket(host, port)) { socket =>
      val out = socket.getOutputStream
      val in = new BufferedReader(new InputStreamReader(socket.getInputStream))

      val request = new StringBuilder

      request.append(s"$method $pathWithQuery HTTP/1.1\r\n")

      request.append(s"Host: $host:$port\r\n")

      if connectionClose then {
        request.append("Connection: close\r\n")
      } else {
        request.append("Connection: keep-alive\r\n")
      }

      headers.foreach { case (name, value) =>
        request.append(s"$name: $value\r\n")
      }

      body.foreach { b =>
        request.append(s"Content-Length: ${b.getBytes.length}\r\n")
      }

      request.append("\r\n")

      out.write(request.toString.getBytes)

      body.foreach { b =>
        out.write(b.getBytes)
      }
      out.flush()

      parseResponse(in)
    }
  }

  private def parseResponse(in: BufferedReader): Response = {
    val statusLine = Option(in.readLine()).getOrElse {
      throw new RuntimeException("Empty response from server")
    }

    val statusParts = statusLine.split(" ", 3)
    val status = statusParts(1).toInt

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

    val body = contentLength match {
      case Some(length) if length > 0 =>
        val buffer = new Array[Char](length)
        var totalRead = 0
        while totalRead < length do {
          val read = in.read(buffer, totalRead, length - totalRead)
          if read == -1 then {
            throw new RuntimeException("Connection closed before reading full body")
          }
          totalRead += read
        }
        new String(buffer, 0, totalRead)

      case _ if connectionClose =>
        val bodyBuilder = new StringBuilder
        var line = Option(in.readLine())
        while line.isDefined do {
          bodyBuilder.append(line.get)
          line = Option(in.readLine())
          if line.isDefined then {
            bodyBuilder.append("\n")
          }
        }
        bodyBuilder.toString

      case _ =>
        ""
    }

    Response(
      status = status,
      headers = headersMap.toMap,
      body = body
    )
  }
}
