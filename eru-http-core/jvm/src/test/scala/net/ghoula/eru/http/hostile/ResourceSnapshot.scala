package net.ghoula.eru.http.hostile

import java.lang.management.ManagementFactory

/** A point-in-time snapshot of JVM / OS resource usage, taken around a hostile workload to detect
  * leaks.
  *
  * Fields are Longs so tests can diff two snapshots with simple subtraction. All values represent
  * "current" usage at the time of capture — they are not deltas. Compute deltas with
  * `after.minus(before)`.
  */
final case class ResourceSnapshot(
  heapUsedBytes: Long,
  nonHeapUsedBytes: Long,
  openFileDescriptors: Long,
  threadCount: Int,
  peakThreadCount: Int
) {
  def minus(other: ResourceSnapshot): ResourceSnapshot =
    ResourceSnapshot(
      heapUsedBytes = heapUsedBytes - other.heapUsedBytes,
      nonHeapUsedBytes = nonHeapUsedBytes - other.nonHeapUsedBytes,
      openFileDescriptors = openFileDescriptors - other.openFileDescriptors,
      threadCount = threadCount - other.threadCount,
      peakThreadCount = peakThreadCount - other.peakThreadCount
    )

  override def toString: String =
    f"ResourceSnapshot(heap=${heapUsedBytes / 1024 / 1024}%dMB, " +
      f"nonHeap=${nonHeapUsedBytes / 1024 / 1024}%dMB, " +
      f"fds=$openFileDescriptors, threads=$threadCount, peakThreads=$peakThreadCount)"
}

object ResourceSnapshot {

  /** Capture current resource usage. Forces a GC first so heap deltas reflect retained (not
    * collectible) allocations.
    *
    * Two GC passes are issued (one to trigger, one to compact) — not authoritative, but it reduces
    * noise versus no hinting at all. A short settle follows because GC returns before finalizers
    * actually run. On non-Unix JVMs the FD count is -1; tests should check `fdCountSupported`
    * before asserting on FD deltas.
    */
  def capture(): ResourceSnapshot = {
    System.gc()
    System.gc()
    Thread.sleep(50)

    val memoryBean = ManagementFactory.getMemoryMXBean
    val threadBean = ManagementFactory.getThreadMXBean
    val osBean = ManagementFactory.getOperatingSystemMXBean

    val fds: Long = osBean match {
      case unix: com.sun.management.UnixOperatingSystemMXBean => unix.getOpenFileDescriptorCount
      case _ => -1L
    }

    ResourceSnapshot(
      heapUsedBytes = memoryBean.getHeapMemoryUsage.getUsed,
      nonHeapUsedBytes = memoryBean.getNonHeapMemoryUsage.getUsed,
      openFileDescriptors = fds,
      threadCount = threadBean.getThreadCount,
      peakThreadCount = threadBean.getPeakThreadCount
    )
  }

  /** True on platforms where FD counting is supported. Tests asserting FD deltas should check this
    * first.
    */
  def fdCountSupported: Boolean =
    ManagementFactory.getOperatingSystemMXBean match {
      case _: com.sun.management.UnixOperatingSystemMXBean => true
      case _ => false
    }
}
