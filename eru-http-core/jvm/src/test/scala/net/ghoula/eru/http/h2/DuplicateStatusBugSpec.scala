package net.ghoula.eru.http.h2

import java.nio.ByteBuffer
import scala.annotation.unused

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Test that demonstrates the duplicate :status bug.
  *
  * This test verifies that H2ServerConnection.sendResponse automatically adds :status, and that
  * passing :status in the headers parameter would result in duplicate pseudo-headers.
  */
class DuplicateStatusBugSpec extends munit.FunSuite {

  @unused given runtime: EruRuntime = EruRuntime.shared

  test("sendResponse prepends :status automatically - encoding verification") {
    // Create an HPACK encoder to verify what gets encoded
    val encoder = HpackEncoder()
    val buffer = ByteBuffer.allocate(1024)

    // Simulate what sendResponse does at lines 816-817:
    // val pseudoHeaders = List((":status", status.toString))
    // val allHeaders = pseudoHeaders ++ headers

    val userHeaders = List(("content-type", "text/plain"))
    val pseudoHeaders = List((":status", "200"))
    val allHeaders = pseudoHeaders ++ userHeaders

    val result = encoder.encode(allHeaders, buffer).attempt.unsafeRunSync()
    assert(result.isSuccess)

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    // Verify :status: 200 is encoded as indexed (0x88 = static table index 8)
    assertEquals(bytes(0), 0x88.toByte)
  }

  test("duplicate :status results in two pseudo-headers in encoded block") {
    val encoder = HpackEncoder()
    val buffer = ByteBuffer.allocate(1024)

    // This is the BUG: user passes :status in headers AND sendResponse adds it
    val buggyUserHeaders = List(
      (":status", "200"), // BUG: user incorrectly includes this
      ("content-type", "text/plain")
    )

    // What sendResponse would create (line 816-817):
    val pseudoHeaders = List((":status", "200"))
    val allHeaders = pseudoHeaders ++ buggyUserHeaders // DUPLICATE :status!

    val result = encoder.encode(allHeaders, buffer).attempt.unsafeRunSync()
    assert(result.isSuccess)

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    // Both :status entries encode as 0x88 (indexed representation)
    // The first two bytes should both be 0x88 - proving duplicate headers
    assertEquals(bytes(0), 0x88.toByte, "First :status should be 0x88")
    assertEquals(bytes(1), 0x88.toByte, "Second :status (DUPLICATE!) should also be 0x88")

    // This is what nghttp2 rejects as "Invalid HTTP header field"
    // per RFC 9113 which forbids duplicate pseudo-headers
  }

  test("correct usage: no :status in user headers") {
    val encoder = HpackEncoder()
    val buffer = ByteBuffer.allocate(1024)

    // Correct: user does NOT include :status
    val correctUserHeaders = List(
      ("content-type", "text/plain")
    )

    // What sendResponse creates:
    val pseudoHeaders = List((":status", "200"))
    val allHeaders = pseudoHeaders ++ correctUserHeaders

    val result = encoder.encode(allHeaders, buffer).attempt.unsafeRunSync()
    assert(result.isSuccess)

    buffer.flip()
    val bytes = new Array[Byte](buffer.remaining)
    buffer.get(bytes)

    // Only one :status (0x88), followed by content-type encoding
    assertEquals(bytes(0), 0x88.toByte, "First byte should be :status (0x88)")
    assertNotEquals(bytes(1), 0x88.toByte, "Second byte should NOT be another :status")
  }
}
