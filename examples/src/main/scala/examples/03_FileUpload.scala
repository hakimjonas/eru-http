package examples

import net.ghoula.eru.*
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.*

/** Multipart form data file upload example.
  *
  * Demonstrates:
  *   - Creating multipart form data
  *   - Uploading files with form fields
  *   - Working with the Part API
  *   - Handling binary data
  *   - Proper Content-Type headers
  */
object FileUpload {

  given runtime: EruRuntime = EruRuntime.shared

  def main(args: Array[String]): Unit = {
    // Example 1: Simple file upload
    simpleFileUpload()

    // Example 2: Multiple files with form fields
    multipleFilesUpload()
  }

  /** Uploads a single file with a form field.
    */
  def simpleFileUpload(): Unit = {
    println("\n=== Example 1: Simple File Upload ===")

    val program = for {
      client <- HttpClient.create(HttpClientConfig.default)

      // Create file content
      fileContent = "Hello, World! This is a test file.".getBytes("UTF-8")
      fileBytes = Bytes.fromArray(fileContent)

      // Create multipart parts
      parts <- Eru.succeed(
        List(
          Part.formField("description", "My uploaded file"),
          Part.formField("user", "john_doe")
        )
      )

      // Add file part
      filePart <- Part.fileFromBytes(
        name = "file",
        filename = "hello.txt",
        contentType = MediaType.textPlain,
        bytes = fileBytes
      )

      allParts = parts :+ filePart

      // Create multipart form data
      multipart <- Multipart.formData(allParts)

      // Convert to body
      body <- multipart.toBody

      // Create request
      uri <- Uri.parse("https://httpbin.org/post")
      request <- Request
        .post(uri, body)
        .setHeader("Content-Type", multipart.contentType.value)

      // Execute request
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"Upload status: ${response.status.value}")
        if response.isSuccess then
          println("File uploaded successfully")
          println(s"Response preview: ${response.body.take(300)}...")
        else
          println("Upload failed")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Simple file upload completed")
      case Result.Failure(error) =>
        println(s"File upload failed: $error")
    }
  }

  /** Uploads multiple files with various content types.
    */
  def multipleFilesUpload(): Unit = {
    println("\n=== Example 2: Multiple Files Upload ===")

    val program = for {
      client <- HttpClient.create(HttpClientConfig.default)

      // Create multiple files
      textFile = Bytes.fromArray("This is a text file.".getBytes("UTF-8"))
      jsonFile = Bytes.fromArray("""{"message":"Hello from JSON"}""".getBytes("UTF-8"))
      csvFile = Bytes.fromArray("name,age\nAlice,30\nBob,25".getBytes("UTF-8"))

      // Create form fields
      formFields = List(
        Part.formField("project", "demo-upload"),
        Part.formField("timestamp", java.time.Instant.now().toString)
      )

      // Create file parts
      textPart <- Part.fileFromBytes("textFile", "document.txt", MediaType.textPlain, textFile)
      jsonPart <- Part.fileFromBytes("jsonFile", "data.json", MediaType.applicationJson, jsonFile)
      csvPart <- Part.fileFromBytes(
        "csvFile",
        "data.csv",
        MediaType("text/csv").getOrElse(MediaType.textPlain),
        csvFile
      )

      allParts = formFields ::: List(textPart, jsonPart, csvPart)

      // Create multipart form data
      multipart <- Multipart.formData(allParts)
      body <- multipart.toBody

      // Create and send request
      uri <- Uri.parse("https://httpbin.org/post")
      request <- Request.post(uri, body).setHeader("Content-Type", multipart.contentType.value)
      response <- client.execute[Body, String](request)

      _ <- Eru.effect {
        println(s"Upload status: ${response.status.value}")
        if response.isSuccess then
          println("Multiple files uploaded successfully")
          println(s"Uploaded ${allParts.count(_.filename.isDefined)} files")
        else
          println("Upload failed")
      }.mapError(e => HttpError.NetworkError(e.getMessage, Some(e)))

      _ <- client.shutdown
    } yield ()

    program.attempt.unsafeRunSync() match {
      case Result.Success(_) =>
        println("Multiple files upload completed")
      case Result.Failure(error) =>
        println(s"Multiple files upload failed: $error")
    }
  }
}
