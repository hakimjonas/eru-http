package net.ghoula.eru.http

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Regression guards for the ByteBuffer pools.
  *
  * The pool factories must allocate a distinct ByteBuffer per slot: an earlier implementation used
  * Eru.succeed(allocateDirect(n)), which eagerly allocates once and shares the same buffer across
  * all slots — under concurrent server load that manifested as interleaved writes and test
  * timeouts. These tests assert identity (not equality) of the acquired instances.
  */
class ObjectPoolSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  test("directByteBuffers allocates a distinct ByteBuffer per pool slot") {
    val pool = Pools.directByteBuffers(capacity = 4, bufferSize = 64).unsafeRunSync()

    val buf1 = pool.acquire.eru.unsafeRunSync()
    val buf2 = pool.acquire.eru.unsafeRunSync()
    val buf3 = pool.acquire.eru.unsafeRunSync()
    val buf4 = pool.acquire.eru.unsafeRunSync()

    val identities = Set(
      System.identityHashCode(buf1),
      System.identityHashCode(buf2),
      System.identityHashCode(buf3),
      System.identityHashCode(buf4)
    )
    assertEquals(identities.size, 4, "Pool must allocate distinct ByteBuffer instances per slot")

    pool.release(buf1).unsafeRunSync()
    pool.release(buf2).unsafeRunSync()
    pool.release(buf3).unsafeRunSync()
    pool.release(buf4).unsafeRunSync()
  }

  test("heapByteBuffers allocates a distinct ByteBuffer per pool slot") {
    val pool = Pools.heapByteBuffers(capacity = 3, bufferSize = 32).unsafeRunSync()

    val buf1 = pool.acquire.eru.unsafeRunSync()
    val buf2 = pool.acquire.eru.unsafeRunSync()
    val buf3 = pool.acquire.eru.unsafeRunSync()

    val identities = Set(
      System.identityHashCode(buf1),
      System.identityHashCode(buf2),
      System.identityHashCode(buf3)
    )
    assertEquals(identities.size, 3, "Pool must allocate distinct ByteBuffer instances per slot")
  }

  test("reset clears the buffer before release for reuse") {
    val pool = Pools.heapByteBuffers(capacity = 1, bufferSize = 16).unsafeRunSync()

    val buf = pool.acquire.eru.unsafeRunSync()
    buf.put("hello".getBytes): Unit
    assert(buf.position() > 0, "Buffer should have written bytes")

    pool.release(buf).unsafeRunSync()

    val bufAgain = pool.acquire.eru.unsafeRunSync()
    assertEquals(bufAgain.position(), 0, "Released buffer must be cleared before reuse")
  }
}
