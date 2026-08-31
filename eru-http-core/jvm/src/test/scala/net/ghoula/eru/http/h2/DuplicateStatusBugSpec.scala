package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import scala.annotation.unused

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Test that demonstrates the duplicate :status bug.
  *
  * This test verifies that H2ServerConnection.sendResponse automatically adds :status, and that
  * passing :status in the headers parameter would result in duplicate pseudo-headers.
  *
  * `:status: 200` encodes as the indexed representation 0x88 (static table index 8). RFC 9113
  * forbids duplicate pseudo-headers, which is what nghttp2 rejects as "Invalid HTTP header field".
  */
class DuplicateStatusBugSpec extends munit.FunSuite {

  @unused given runtime: EruRuntime = EruRuntime.shared

  test("sendResponse prepends :status automatically - encoding verification") {
    val encoder = HpackEncoder()
    val buffer = ByteBuffer.allocate(1024)

    val userHeaders = List(("content-type", "text/plain"))
    val pseudoHeaders = List((":status", "200"))
    val allHeaders = pseudoHeaders ++ userHeaders

    val result = encoder.encode(allHeaders, buffer).attempt.unsafeRunSync()
    assert(result.isSuccess)

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    assertEquals(bytes(0), 0x88.toByte)
  }

  test("duplicate :status results in two pseudo-headers in encoded block") {
    val encoder = HpackEncoder()
    val buffer = ByteBuffer.allocate(1024)

    val buggyUserHeaders = List(
      (":status", "200"),
      ("content-type", "text/plain")
    )

    val pseudoHeaders = List((":status", "200"))
    val allHeaders = pseudoHeaders ++ buggyUserHeaders

    val result = encoder.encode(allHeaders, buffer).attempt.unsafeRunSync()
    assert(result.isSuccess)

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    assertEquals(bytes(0), 0x88.toByte, "First :status should be 0x88")
    assertEquals(bytes(1), 0x88.toByte, "Second :status (DUPLICATE!) should also be 0x88")
  }

  test("correct usage: no :status in user headers") {
    val encoder = HpackEncoder()
    val buffer = ByteBuffer.allocate(1024)

    val correctUserHeaders = List(
      ("content-type", "text/plain")
    )

    val pseudoHeaders = List((":status", "200"))
    val allHeaders = pseudoHeaders ++ correctUserHeaders

    val result = encoder.encode(allHeaders, buffer).attempt.unsafeRunSync()
    assert(result.isSuccess)

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    assertEquals(bytes(0), 0x88.toByte, "First byte should be :status (0x88)")
    assertNotEquals(bytes(1), 0x88.toByte, "Second byte should NOT be another :status")
  }
}
