package net.ghoula.eru.http.server

import munit.FunSuite

import java.lang.foreign.{Arena, MemoryLayout, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets

import net.ghoula.eru.*

/** Direct coverage of the systemd sd_notify sender.
  *
  * Binds a real AF_UNIX datagram socket via the Foreign Function & Memory API and asserts the exact
  * payloads the watchdog sends. Without this, the FFM socket/sendto path runs nowhere in the test
  * suite — the production instance only activates under systemd.
  */
class WatchdogSpec extends FunSuite {

  private val linker = java.lang.foreign.Linker.nativeLinker().nn
  private val libc =
    java.lang.foreign.SymbolLookup.libraryLookup("libc.so.6", java.lang.foreign.Arena.global()).nn

  private val AF_UNIX: Int = 1
  private val SOCK_DGRAM: Int = 2

  private val sockaddrLayout: MemoryLayout = MemoryLayout
    .structLayout(
      ValueLayout.JAVA_SHORT.withName("family"),
      MemoryLayout.sequenceLayout(108, ValueLayout.JAVA_BYTE).withName("path")
    )
    .nn

  private val socketFn = linker
    .downcallHandle(
      libc.find("socket").nn.get(),
      java.lang.foreign.FunctionDescriptor
        .of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    )
    .nn

  private val bindFn = linker
    .downcallHandle(
      libc.find("bind").nn.get(),
      java.lang.foreign.FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
      )
    )
    .nn

  private val recvfromFn = linker
    .downcallHandle(
      libc.find("recvfrom").nn.get(),
      java.lang.foreign.FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
      )
    )
    .nn

  private val closeFn = linker
    .downcallHandle(
      libc.find("close").nn.get(),
      java.lang.foreign.FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    )
    .nn

  private val unlinkFn = linker
    .downcallHandle(
      libc.find("unlink").nn.get(),
      java.lang.foreign.FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    )
    .nn

  /** Bind a unix datagram socket to `path` and return its fd. */
  private def bindUnixDatagram(path: String): Int = {
    val fd: Int = socketFn.invokeExact(AF_UNIX, SOCK_DGRAM, 0)
    assert(fd >= 0, s"socket() failed: $fd")
    val arena = Arena.ofConfined().nn
    try {
      val addr = arena.allocate(sockaddrLayout).nn
      addr.set(ValueLayout.JAVA_SHORT, 0, AF_UNIX.toShort)
      val raw = path.getBytes(StandardCharsets.UTF_8)
      val dst = addr.asSlice(2).nn
      MemorySegment.copy(MemorySegment.ofArray(raw), 0, dst, 0, raw.length)
      val rc: Int = bindFn.invokeExact(fd, addr, sockaddrLayout.byteSize().toInt)
      assert(rc == 0, s"bind() failed: $rc")
    } finally arena.close()
    fd
  }

  private def readDatagram(fd: Int): String = {
    val arena = Arena.ofConfined().nn
    try {
      val buf = arena.allocate(ValueLayout.JAVA_BYTE, 256).nn
      val n: Long = recvfromFn.invokeExact(fd, buf, 256L, 0, MemorySegment.NULL, MemorySegment.NULL)
      assert(n >= 0, s"recvfrom() failed: $n")
      new String(buf.asSlice(0L, n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
    } finally arena.close()
  }

  test("Watchdog - sends READY, WATCHDOG, and STOPPING messages to the notify socket") {
    assume(System.getProperty("os.name").toLowerCase.contains("linux"), "AF_UNIX datagram sockets")

    val dir = java.nio.file.Files.createTempDirectory("watchdog-spec")
    val sockPath = dir.resolve("notify.sock").toString
    val fd = bindUnixDatagram(sockPath)
    try {
      val watchdog = Watchdog.withSocketPath(Some(sockPath))
      assert(watchdog.isAvailable)

      watchdog.ready().attempt.unsafeRunSync() match {
        case Result.Success(_) => ()
        case Result.Failure(e) => fail(s"ready() failed: $e")
      }
      assertEquals(readDatagram(fd), "READY=1\n")

      watchdog.heartbeat().attempt.unsafeRunSync() match {
        case Result.Success(_) => ()
        case Result.Failure(e) => fail(s"heartbeat() failed: $e")
      }
      assertEquals(readDatagram(fd), "WATCHDOG=1")

      watchdog.stopping().attempt.unsafeRunSync() match {
        case Result.Success(_) => ()
        case Result.Failure(e) => fail(s"stopping() failed: $e")
      }
      assertEquals(readDatagram(fd), "STOPPING=1\n")
    } finally {
      val _: Int = closeFn.invokeExact(fd)
      val arena = Arena.ofConfined().nn
      try {
        val seg = arena.allocate(ValueLayout.JAVA_BYTE, sockPath.getBytes(StandardCharsets.UTF_8).length).nn
        MemorySegment.copy(MemorySegment.ofArray(sockPath.getBytes(StandardCharsets.UTF_8)), 0, seg, 0, seg.byteSize())
        val _: Int = unlinkFn.invokeExact(seg)
      } finally arena.close()
      java.nio.file.Files.deleteIfExists(dir): Unit
    }
  }

  test("Watchdog - send to a missing socket fails with an IOException") {
    assume(System.getProperty("os.name").toLowerCase.contains("linux"), "AF_UNIX datagram sockets")

    val dir = java.nio.file.Files.createTempDirectory("watchdog-spec")
    val missingPath = dir.resolve("does-not-exist.sock").toString
    try {
      val watchdog = Watchdog.withSocketPath(Some(missingPath))
      watchdog.ready().attempt.unsafeRunSync() match {
        case Result.Failure(_: java.io.IOException) => ()
        case other => fail(s"expected IOException, got: $other")
      }
    } finally {
      java.nio.file.Files.deleteIfExists(dir): Unit
    }
  }

  test("Watchdog - no socket configured means isAvailable=false and no-op effects") {
    val watchdog = Watchdog.withSocketPath(None)
    assert(!watchdog.isAvailable)
    watchdog.ready().attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) => fail(s"no-op ready() must succeed, got: $e")
    }
    watchdog.heartbeat().attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) => fail(s"no-op heartbeat() must succeed, got: $e")
    }
    watchdog.stopping().attempt.unsafeRunSync() match {
      case Result.Success(_) => ()
      case Result.Failure(e) => fail(s"no-op stopping() must succeed, got: $e")
    }
  }
}
