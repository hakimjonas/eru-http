package net.ghoula.eru.http.server

import java.lang.foreign.{MemoryLayout, MemorySegment, ValueLayout}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import net.ghoula.eru.Eru

private[server] object Watchdog {

  private lazy val systemdInstance: Watchdog = new Watchdog(sys.env.get("NOTIFY_SOCKET"))

  def isAvailable: Boolean = systemdInstance.isAvailable

  def ready(): Eru[Throwable, Unit] = systemdInstance.ready()
  def heartbeat(): Eru[Throwable, Unit] = systemdInstance.heartbeat()
  def stopping(): Eru[Throwable, Unit] = systemdInstance.stopping()

  /** Build an instance targeting an explicit socket path (tests). */
  private[server] def withSocketPath(path: Option[String]): Watchdog = new Watchdog(path)
}

/** Sends heartbeat messages over systemd's notification socket via POSIX `sendto(2)`.
  *
  * The JDK's `java.nio.channels.DatagramChannel` does not support Unix domain datagram sockets —
  * JEP 380 only added stream channels (SocketChannel / ServerSocketChannel). The Foreign Function &
  * Memory API (JDK 22+) provides direct access to libc's `socket` / `sendto` / `close` without any
  * external JNI or JNA dependencies.
  *
  * [[https://www.freedesktop.org/software/systemd/man/sd_notify.html sd_notify(3)]]
  */
private[server] final class Watchdog private (socketPath: Option[String]) {

  private val readyMsg =
    ByteBuffer.wrap("READY=1\n".getBytes(StandardCharsets.UTF_8))
  private val watchdogMsg =
    ByteBuffer.wrap("WATCHDOG=1".getBytes(StandardCharsets.UTF_8))

  private val linker: java.lang.foreign.Linker = java.lang.foreign.Linker.nativeLinker().nn
  private val libc: java.lang.foreign.SymbolLookup =
    java.lang.foreign.SymbolLookup.libraryLookup("libc.so.6", java.lang.foreign.Arena.global()).nn

  private val AF_UNIX: Int = 1
  private val SOCK_DGRAM: Int = 2

  private val sockaddrLayout: MemoryLayout = MemoryLayout
    .structLayout(
      ValueLayout.JAVA_SHORT.withName("family"),
      MemoryLayout.sequenceLayout(108, ValueLayout.JAVA_BYTE).withName("path")
    )
    .nn

  private val socketFn: java.lang.invoke.MethodHandle =
    linker
      .downcallHandle(
        libc.find("socket").nn.get(),
        java.lang.foreign.FunctionDescriptor
          .of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
      )
      .nn

  private val sendtoFn: java.lang.invoke.MethodHandle =
    linker
      .downcallHandle(
        libc.find("sendto").nn.get(),
        java.lang.foreign.FunctionDescriptor.of(
          ValueLayout.JAVA_LONG,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_LONG,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT
        )
      )
      .nn

  private val closeFn: java.lang.invoke.MethodHandle =
    linker
      .downcallHandle(
        libc.find("close").nn.get(),
        java.lang.foreign.FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
      )
      .nn

  def isAvailable: Boolean = socketPath.isDefined

  def ready(): Eru[Throwable, Unit] = socketPath.fold(Eru.unit)(send(_, readyMsg.duplicate()))
  def heartbeat(): Eru[Throwable, Unit] = socketPath.fold(Eru.unit)(send(_, watchdogMsg.duplicate()))
  def stopping(): Eru[Throwable, Unit] = socketPath.fold(Eru.unit) { path =>
    send(path, ByteBuffer.wrap("STOPPING=1\n".getBytes(StandardCharsets.UTF_8)))
  }

  private def send(path: String, msg: ByteBuffer): Eru[Throwable, Unit] = Eru.effect {
    val fd: Int = socketFn.invokeExact(AF_UNIX, SOCK_DGRAM, 0)
    try {
      if fd < 0 then throw new java.io.IOException("socket() failed: " + fd)
      val arena: java.lang.foreign.Arena = java.lang.foreign.Arena.ofConfined().nn
      try {
        val addr: MemorySegment = buildSockaddr(arena, path)
        val raw: Array[Byte] = new Array[Byte](msg.remaining())
        msg.get(raw)
        val buf: MemorySegment = arena.allocate(ValueLayout.JAVA_BYTE, raw.length).nn
        MemorySegment.copy(MemorySegment.ofArray(raw), 0, buf, 0, raw.length)
        val sent: Long =
          sendtoFn.invokeExact(fd, buf, raw.length.toLong, 0, addr, sockaddrLayout.byteSize().toInt)
        if sent < 0 then throw new java.io.IOException("sendto() returned " + sent)
      } finally arena.close()
    } finally closeFn.invoke(fd)
  }

  private def buildSockaddr(arena: java.lang.foreign.Arena, path: String): MemorySegment = {
    val seg: MemorySegment = arena.allocate(sockaddrLayout).nn
    seg.set(ValueLayout.JAVA_SHORT, 0, AF_UNIX.toShort)
    val raw: Array[Byte] =
      if path.startsWith("@") then Array(0.toByte) ++ path.substring(1).getBytes(StandardCharsets.UTF_8)
      else path.getBytes(StandardCharsets.UTF_8)
    val n: Int = Math.min(raw.length, 108)
    val dst: MemorySegment = seg.asSlice(2).nn
    val src: MemorySegment = MemorySegment.ofArray(raw).nn
    MemorySegment.copy(src, 0, dst, 0, n)
    seg
  }
}
