package net.ghoula.eru.http.server

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient as JHttpClient, HttpRequest}
import scala.jdk.CollectionConverters.*

/** Simple synchronous HTTP client for testing the server.
  */
object SimpleHttpClient {

  private val client = JHttpClient.newHttpClient()

  case class Response(
    status: Int,
    headers: Map[String, String],
    body: String
  )

  def get(url: String, headers: Map[String, String] = Map.empty): Response = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .GET()

    headers.foreach { case (name, value) =>
      builder.header(name, value)
    }

    val request = builder.build()
    val response = client.send(request, BodyHandlers.ofString())

    Response(
      status = response.statusCode(),
      headers = response.headers().map().asScala.map { case (k, v) => (k, v.asScala.head) }.toMap,
      body = response.body()
    )
  }

  def post(url: String, body: String, headers: Map[String, String] = Map.empty): Response = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .POST(BodyPublishers.ofString(body))

    headers.foreach { case (name, value) =>
      builder.header(name, value)
    }

    val request = builder.build()
    val response = client.send(request, BodyHandlers.ofString())

    Response(
      status = response.statusCode(),
      headers = response.headers().map().asScala.map { case (k, v) => (k, v.asScala.head) }.toMap,
      body = response.body()
    )
  }

  def put(url: String, body: String, headers: Map[String, String] = Map.empty): Response = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .PUT(BodyPublishers.ofString(body))

    headers.foreach { case (name, value) =>
      builder.header(name, value)
    }

    val request = builder.build()
    val response = client.send(request, BodyHandlers.ofString())

    Response(
      status = response.statusCode(),
      headers = response.headers().map().asScala.map { case (k, v) => (k, v.asScala.head) }.toMap,
      body = response.body()
    )
  }

  def delete(url: String, headers: Map[String, String] = Map.empty): Response = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .DELETE()

    headers.foreach { case (name, value) =>
      builder.header(name, value)
    }

    val request = builder.build()
    val response = client.send(request, BodyHandlers.ofString())

    Response(
      status = response.statusCode(),
      headers = response.headers().map().asScala.map { case (k, v) => (k, v.asScala.head) }.toMap,
      body = response.body()
    )
  }
}
