package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*
import net.ghoula.eru.http.server.*
import net.ghoula.eru.prelude.*

/** Multipart upload: build a form with a text field and a file part, POST it, and have the server
  * parse the multipart body back out with the boundary from Content-Type.
  *
  * Run it: sbt "examples/runMain examples.FileUpload"
  */
object FileUpload {

  given runtime: EruRuntime = EruRuntime.create()

  val handler: RequestHandler = req =>
    if req.method == Method.POST && req.uri.path == "/upload" then {
      val boundary = req.headers
        .getFirst(HeaderNames.ContentType)
        .map(_.value)
        .flatMap(_.split("boundary=").lastOption)
        .getOrElse("")
      for
        multipart <- Multipart.parse(req.body, boundary)
        summary = multipart.parts.map { p =>
          s"${p.name}${p.filename.map(f => s" ($f)").getOrElse("")}: ${p.body.contentLength.getOrElse(0L)} bytes"
        }.mkString("; ")
      yield Response.ok(Body.text(s"received ${multipart.parts.size} parts: $summary"))
    } else {
      Eru.succeed(Response.badRequest(Body.text("POST /upload with multipart/form-data")))
    }

  val program: Eru[HttpError, Unit] =
    HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(handler) { server =>
      for
        address <- server.start
        uri <- Uri
          .parse(s"http://${address.host}:${address.port}/upload")
          .mapError(HttpError.InvalidUri.apply)
        _ <- HttpClient.scoped { client =>
          for
            filePart <- Part.fileFromBytes(
              name = "avatar",
              filename = "hello.txt",
              contentType = MediaType.textPlain,
              bytes = Bytes.fromArray("file content goes here".getBytes("UTF-8"))
            )
            multipart <- Multipart.formData(
              List(Part.formField("description", "my upload"), filePart)
            )
            body <- multipart.toBody
            request <- Request
              .post(uri, body)
              .addHeader(HeaderNames.ContentType, multipart.contentType.value)
              .mapError(e => HttpError.InvalidRequest(InvalidRequest(s"invalid request header: $e", "RFC 9110")))
            response <- client.send(request)
            _ <- Eru
              .effect(
                println(s"upload -> ${response.status.value} ${response.body.asString(Charset.UTF8)}")
              )
              .mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))
          yield ()
        }
      yield ()
    }

  def main(args: Array[String]): Unit =
    program.unsafeRunSync()
}
