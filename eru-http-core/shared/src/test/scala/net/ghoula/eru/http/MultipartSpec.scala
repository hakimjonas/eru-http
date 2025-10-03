package net.ghoula.eru.http

import munit.*

import net.ghoula.eru.*

import TestHelpers.*

class MultipartSpec extends FunSuite {

  // Test data
  val sampleText = "Hello, World!"
  val sampleBytes: Bytes = Bytes.fromString("Binary data here", Charset.UTF8)
  val imageBytes: Bytes = Bytes.fromArray(Array[Byte](0xff.toByte, 0xd8.toByte, 0xff.toByte, 0xe0.toByte))

  test("Part.formField creates a text field part") {
    val part = Part.formField("username", "john_doe")

    assertEquals(part.name, "username")
    assertEquals(part.filename, None)

    part.body match {
      case textBody: Body.Text =>
        assertEquals(textBody.value, "john_doe")
      case other =>
        fail(s"Expected Body.Text but got ${other.getClass.getSimpleName}")
    }
  }

  test("Part.fileFromBytes creates a file part with correct metadata") {
    val part = Part.fileFromBytes("avatar", "photo.jpg", MediaType.imageJpeg, imageBytes).assertSuccess

    assertEquals(part.name, "avatar")
    assertEquals(part.filename, Some("photo.jpg"))

    part.body match {
      case binaryBody: Body.Binary =>
        assert(binaryBody.value === imageBytes)
        assertEquals(binaryBody.mediaType, Some(MediaType.imageJpeg))
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Part.file creates a file part from Body") {
    val body = Body.binary(sampleBytes, MediaType.applicationOctetStream)
    val part = Part.file("document", "doc.bin", MediaType.applicationOctetStream, body).assertSuccess

    assertEquals(part.name, "document")
    assertEquals(part.filename, Some("doc.bin"))
    assertEquals(part.contentType, Some(MediaType.applicationOctetStream))
  }

  test("Part.contentDispositionValue generates correct header for form field") {
    val part = Part.formField("email", "user@example.com")
    val cdValue = part.contentDispositionValue

    assertEquals(cdValue, """form-data; name="email"""")
  }

  test("Part.contentDispositionValue generates correct header for file") {
    val part = Part.fileFromBytes("upload", "test.txt", MediaType.textPlain, sampleBytes).assertSuccess
    val cdValue = part.contentDispositionValue

    assertEquals(cdValue, """form-data; name="upload"; filename="test.txt"""")
  }

  test("Part.contentDispositionValue escapes special characters") {
    val part = Part.formField("""field"with"quotes""", "value")
    val cdValue = part.contentDispositionValue

    assert(cdValue.contains("""field\"with\"quotes"""))
  }

  test("Part.parseContentDisposition parses simple form field") {
    val result = Part.parseContentDisposition("""form-data; name="username"""").assertSuccess

    assertEquals(result._1, "username")
    assertEquals(result._2, None)
  }

  test("Part.parseContentDisposition parses file with filename") {
    val result = Part.parseContentDisposition("""form-data; name="upload"; filename="test.txt"""").assertSuccess

    assertEquals(result._1, "upload")
    assertEquals(result._2, Some("test.txt"))
  }

  test("Part.parseContentDisposition handles unquoted values") {
    val result = Part.parseContentDisposition("form-data; name=fieldname").assertSuccess

    assertEquals(result._1, "fieldname")
  }

  test("Part.parseContentDisposition fails on non-form-data disposition") {
    val result = Part.parseContentDisposition("attachment; filename=test.txt")

    assert(result.isFailure)
  }

  test("Part.parseContentDisposition fails without name parameter") {
    val result = Part.parseContentDisposition("form-data; filename=test.txt")

    assert(result.isFailure)
  }

  test("Part.parseContentDisposition handles escaped quotes in filename") {
    val result = Part.parseContentDisposition("""form-data; name="file"; filename="test\"file.txt"""").assertSuccess

    assertEquals(result._1, "file")
    assertEquals(result._2, Some("""test"file.txt"""))
  }

  test("Multipart.generateBoundary creates unique boundaries") {
    val boundary1 = Multipart.generateBoundary
    val boundary2 = Multipart.generateBoundary

    assertNotEquals(boundary1, boundary2)
    assert(boundary1.startsWith("----EruHttpFormBoundary"))
    assert(boundary2.startsWith("----EruHttpFormBoundary"))
  }

  test("Multipart.formData creates multipart with generated boundary") {
    val parts = List(
      Part.formField("field1", "value1"),
      Part.formField("field2", "value2")
    )

    val multipart = Multipart.formData(parts).assertSuccess

    assertEquals(multipart.parts.length, 2)
    assert(multipart.boundary.nonEmpty)
    assert(multipart.boundary.startsWith("----EruHttpFormBoundary"))
  }

  test("Multipart.formData fails with empty parts list") {
    val result = Multipart.formData(List.empty)

    assert(result.isFailure)
  }

  test("Multipart.contentType includes boundary parameter") {
    val parts = List(Part.formField("test", "value"))
    val multipart = Multipart.formData(parts).assertSuccess

    val contentType = multipart.contentType
    assertEquals(contentType.mainType, "multipart")
    assertEquals(contentType.subType, "form-data")
    assertEquals(contentType.boundary, Some(multipart.boundary))
  }

  test("Multipart.toBody encodes simple form fields") {
    val parts = List(
      Part.formField("username", "john"),
      Part.formField("email", "john@example.com")
    )

    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess

    body match {
      case binary: Body.Binary =>
        val content = binary.asString(Charset.ISO_8859_1)

        // Check structure
        assert(content.contains(s"--${multipart.boundary}\r\n"))
        assert(content.contains(s"--${multipart.boundary}--\r\n"))
        assert(content.contains("Content-Disposition: form-data; name=\"username\"\r\n"))
        assert(content.contains("Content-Disposition: form-data; name=\"email\"\r\n"))
        assert(content.contains("john"))
        assert(content.contains("john@example.com"))
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart.toBody encodes file uploads with Content-Type") {
    val parts = List(
      Part.formField("description", "My photo"),
      Part.fileFromBytes("photo", "image.jpg", MediaType.imageJpeg, imageBytes).assertSuccess
    )

    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess

    body match {
      case binary: Body.Binary =>
        val content = binary.asString(Charset.ISO_8859_1)

        // Check file part structure
        assert(content.contains("""Content-Disposition: form-data; name="photo"; filename="image.jpg""""))
        assert(content.contains("Content-Type: image/jpeg"))
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart.toBody uses CRLF line endings") {
    val parts = List(Part.formField("test", "value"))
    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess

    body match {
      case binary: Body.Binary =>
        val content = binary.asString(Charset.ISO_8859_1)

        // Verify CRLF is used, not just LF
        assert(content.contains("\r\n"))
        // Make sure it's not just LF
        val crlfCount = content.split("\r\n", -1).length - 1
        val lfOnlyCount = content.count(_ == '\n')
        assertEquals(crlfCount, lfOnlyCount)
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart.toBody fails with empty parts") {
    val multipart = Multipart(List.empty, "boundary")
    val result = multipart.toBody

    assert(result.isFailure)
  }

  test("Multipart.parse parses simple form data") {
    val boundary = "----TestBoundary123"
    val content =
      s"""--$boundary\r
Content-Disposition: form-data; name="field1"\r
\r
value1\r
--$boundary\r
Content-Disposition: form-data; name="field2"\r
\r
value2\r
--$boundary--\r
"""

    val body = Body.text(content, MediaType.textPlain)
    val multipart = Multipart.parse(body, boundary).assertSuccess

    assertEquals(multipart.parts.length, 2)
    assertEquals(multipart.parts(0).name, "field1")
    assertEquals(multipart.parts(1).name, "field2")

    // Check body content
    multipart.parts(0).body match {
      case part1Body: Body.Binary =>
        assertEquals(part1Body.asString(Charset.ISO_8859_1), "value1")
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }

    multipart.parts(1).body match {
      case part2Body: Body.Binary =>
        assertEquals(part2Body.asString(Charset.ISO_8859_1), "value2")
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart.parse handles file uploads with Content-Type") {
    val boundary = "----TestBoundary456"
    val content =
      s"""--$boundary\r
Content-Disposition: form-data; name="file"; filename="test.txt"\r
Content-Type: text/plain\r
\r
File content here\r
--$boundary--\r
"""

    val body = Body.text(content, MediaType.textPlain)
    val multipart = Multipart.parse(body, boundary).assertSuccess

    assertEquals(multipart.parts.length, 1)
    val part = multipart.parts(0)

    assertEquals(part.name, "file")
    assertEquals(part.filename, Some("test.txt"))
    assertEquals(part.contentType, Some(MediaType.textPlain))
  }

  test("Multipart.parse handles binary data") {
    val boundary = "----TestBoundary789"
    val binaryContent = Array[Byte](0x00, 0x01, 0x02, 0xff.toByte, 0xfe.toByte)
    val binaryString = Bytes.fromArray(binaryContent).asString(Charset.ISO_8859_1)

    val content =
      s"""--$boundary\r
Content-Disposition: form-data; name="binary"; filename="data.bin"\r
Content-Type: application/octet-stream\r
\r
$binaryString\r
--$boundary--\r
"""

    val bodyBytes = Bytes.fromString(content, Charset.ISO_8859_1)
    val body = Body.binary(bodyBytes, MediaType.applicationOctetStream)
    val multipart = Multipart.parse(body, boundary).assertSuccess

    assertEquals(multipart.parts.length, 1)
    val part = multipart.parts(0)

    part.body match {
      case partBody: Body.Binary =>
        val parsedBytes = partBody.value

        // Verify binary data is preserved
        assertEquals(parsedBytes.length, binaryContent.length)
        assert(parsedBytes === Bytes.fromArray(binaryContent))
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart.parse fails with empty boundary") {
    val body = Body.text("content", MediaType.textPlain)
    val result = Multipart.parse(body, "")

    assert(result.isFailure)
  }

  test("Multipart.parse fails with no valid parts") {
    val boundary = "----TestBoundary"
    val content = s"--$boundary--\r\n"

    val body = Body.text(content, MediaType.textPlain)
    val result = Multipart.parse(body, boundary)

    assert(result.isFailure)
  }

  test("Multipart round-trip: encode then decode") {
    val originalParts = List(
      Part.formField("username", "alice"),
      Part.formField("email", "alice@example.com"),
      Part.fileFromBytes("avatar", "photo.png", MediaType.imagePng, imageBytes).assertSuccess
    )

    val multipart = Multipart.formData(originalParts).assertSuccess
    val body = multipart.toBody.assertSuccess
    val parsed = Multipart.parse(body, multipart.boundary).assertSuccess

    assertEquals(parsed.parts.length, originalParts.length)

    // Check first part
    assertEquals(parsed.parts(0).name, "username")
    parsed.parts(0).body match {
      case part0Body: Body.Binary =>
        assertEquals(part0Body.asString(Charset.UTF8), "alice")
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }

    // Check second part
    assertEquals(parsed.parts(1).name, "email")
    parsed.parts(1).body match {
      case part1Body: Body.Binary =>
        assertEquals(part1Body.asString(Charset.UTF8), "alice@example.com")
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }

    // Check third part (file)
    assertEquals(parsed.parts(2).name, "avatar")
    assertEquals(parsed.parts(2).filename, Some("photo.png"))
    parsed.parts(2).body match {
      case part2Body: Body.Binary =>
        assert(part2Body.value === imageBytes)
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart round-trip with special characters in values") {
    val specialValue = """Value with "quotes" and \backslashes\ and newlines
on multiple lines"""

    val originalParts = List(
      Part.formField("special", specialValue)
    )

    val multipart = Multipart.formData(originalParts).assertSuccess
    val body = multipart.toBody.assertSuccess
    val parsed = Multipart.parse(body, multipart.boundary).assertSuccess

    assertEquals(parsed.parts.length, 1)
    assertEquals(parsed.parts(0).name, "special")

    parsed.parts(0).body match {
      case partBody: Body.Binary =>
        assertEquals(partBody.asString(Charset.UTF8), specialValue)
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart with multiple files") {
    val file1 = Bytes.fromString("Content of file 1", Charset.UTF8)
    val file2 = Bytes.fromString("Content of file 2", Charset.UTF8)
    val file3 = Bytes.fromString("Content of file 3", Charset.UTF8)

    val parts = List(
      Part.fileFromBytes("file1", "doc1.txt", MediaType.textPlain, file1).assertSuccess,
      Part.fileFromBytes("file2", "doc2.txt", MediaType.textPlain, file2).assertSuccess,
      Part.fileFromBytes("file3", "doc3.txt", MediaType.textPlain, file3).assertSuccess
    )

    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess
    val parsed = Multipart.parse(body, multipart.boundary).assertSuccess

    assertEquals(parsed.parts.length, 3)
    assertEquals(parsed.parts(0).filename, Some("doc1.txt"))
    assertEquals(parsed.parts(1).filename, Some("doc2.txt"))
    assertEquals(parsed.parts(2).filename, Some("doc3.txt"))
  }

  test("Multipart encoder encodes correctly") {
    val parts = List(
      Part.formField("test", "value")
    )
    val multipart = Multipart.formData(parts).assertSuccess

    val encoded = multipartEncoder.encode(multipart, None).assertSuccess

    encoded match {
      case _: Body.Binary =>
        assertEquals(encoded.mediaType, Some(multipart.contentType))
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart encoder default media type") {
    assertEquals(multipartEncoder.defaultMediaType, MediaType.multipartFormData)
  }

  test("Multipart decoder decodes correctly") {
    val boundary = "----TestBoundary"
    val content =
      s"""--$boundary\r
Content-Disposition: form-data; name="test"\r
\r
value\r
--$boundary--\r
"""

    val body = Body.text(content, MediaType.textPlain)
    val decoder = multipartDecoder(boundary)
    val decoded = decoder.decode(body).assertSuccess

    assertEquals(decoded.parts.length, 1)
    assertEquals(decoded.parts(0).name, "test")
  }

  test("Multipart handles empty form field values") {
    val parts = List(
      Part.formField("empty", "")
    )

    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess
    val parsed = Multipart.parse(body, multipart.boundary).assertSuccess

    assertEquals(parsed.parts.length, 1)
    parsed.parts(0).body match {
      case partBody: Body.Binary =>
        assertEquals(partBody.asString(Charset.UTF8), "")
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart handles form fields with only whitespace") {
    val parts = List(
      Part.formField("whitespace", "   \t\n   ")
    )

    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess
    val parsed = Multipart.parse(body, multipart.boundary).assertSuccess

    assertEquals(parsed.parts.length, 1)
    parsed.parts(0).body match {
      case partBody: Body.Binary =>
        assertEquals(partBody.asString(Charset.UTF8), "   \t\n   ")
      case other =>
        fail(s"Expected Body.Binary but got ${other.getClass.getSimpleName}")
    }
  }

  test("Multipart boundary generation is URL-safe") {
    val boundary = Multipart.generateBoundary

    // Check that it contains only URL-safe characters (alphanumeric, -, _)
    val urlSafePattern = "^[a-zA-Z0-9\\-_]+$".r
    assert(urlSafePattern.matches(boundary))
  }

  test("Multipart with filename containing special characters") {
    val filename = """my "special" file (1).txt"""
    val part = Part.fileFromBytes("upload", filename, MediaType.textPlain, sampleBytes).assertSuccess

    val cdValue = part.contentDispositionValue
    assert(cdValue.contains("""filename="my \"special\" file (1).txt""""))
  }

  test("Multipart mixed form fields and files") {
    val parts = List(
      Part.formField("title", "My Document"),
      Part.formField("description", "A test document"),
      Part.fileFromBytes("document", "test.pdf", MediaType.applicationPdf, sampleBytes).assertSuccess,
      Part.formField("tags", "test,document,sample")
    )

    val multipart = Multipart.formData(parts).assertSuccess
    val body = multipart.toBody.assertSuccess
    val parsed = Multipart.parse(body, multipart.boundary).assertSuccess

    assertEquals(parsed.parts.length, 4)
    assertEquals(parsed.parts(0).name, "title")
    assertEquals(parsed.parts(1).name, "description")
    assertEquals(parsed.parts(2).name, "document")
    assertEquals(parsed.parts(2).filename, Some("test.pdf"))
    assertEquals(parsed.parts(3).name, "tags")
  }
}
