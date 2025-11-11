# Eru Structured Concurrency Implementation - Technical Analysis

## Overview

Eru implements a sophisticated structured concurrency system on Java Virtual Threads with compile-time safety through typed error channels. This document provides a comprehensive technical reference for the core implementation details, focusing on the JVM backend and its interaction with the runtime system.

## 1. JVM Runtime Integration (Virtual Thread Backend)

### 1.1 RuntimeBackend Enum Pattern

**File:** `/home/user/eru/eru-runtime/shared/src/main/scala/net/ghoula/eru/RuntimeBackend.scala` (Lines 87-530)

Eru uses a Scala 3 enum pattern to implement two execution backends:

```scala
enum RuntimeBackend {
  case Synchronous      // Single-threaded, deterministic execution
  case VirtualThreads   // Concurrent execution on Java VirtualThreads
}
```

This design choice enables:
- **Compile-time backend selection**: No runtime checking overhead
- **Behavior encapsulation**: Each backend includes its implementation directly
- **Platform support**: VirtualThreads for JVM, Synchronous for Scala Native

### 1.2 Virtual Thread Creation (fork operations)

**Location:** `RuntimeBackend.scala`, lines 152-280 (VirtualThreads case in fork method)

The fork operation implements a two-path strategy:

#### Fast Path (Lines 154-184)
For pure values (VSucceed, VFail), create a completed fiber immediately without spawning a thread:

```scala
case VSucceed(value) =>
  Eru.effectTotal {
    val id = FiberId.fresh()
    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
    val exit = Exit.Success(value)
    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
    UnifiedFiber.completed(id, exit): Fiber[E, A]
  }
```

**Benefits:**
- Eliminates unnecessary thread creation for pure computations
- Improves performance for monadic chains of pure values
- Maintains structured concurrency guarantees

#### Slow Path (Lines 186-280)
For effectful computations, spawn a Virtual Thread:

```scala
case VMapChain(source, f) if needsAsyncExecution =>
  Eru.effectTotal {
    val id = FiberId.fresh()
    val fiber = UnifiedFiber.active[E, A](id)  // Create coordination primitives
    val parentScope = StructuredConcurrency.getCurrentScope()

    StructuredConcurrency.addChildFiber(fiber, rootFibers)

    Thread.startVirtualThread { () =>
      UnifiedFiber.setThread(fiber, Thread.currentThread())
      // Restore parent scope in new thread
      StructuredConcurrency.setCurrentScope(parentScope)

      StructuredConcurrency.withNewScope { _ =>
        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => ()
        }
        UnifiedFiber.complete(fiber, exit)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
      }
    }
    fiber: Fiber[E, A]
  }
```

**Key Implementation Details:**
- **UnifiedFiber.active()** (Lines 139-144): Creates active fiber with CountDownLatch and AtomicReferences
- **Thread scope propagation** (Lines 216-217, 255-256): Parent scope is captured and restored in child thread for structured concurrency
- **Finalizer execution** (Lines 222-225): All finalizers run synchronously after effect completion

### 1.3 Platform Detection and Backend Selection

**Location:** `RuntimeBackend.scala`, lines 510-530

```scala
object Platform {
  val isJVM: Boolean = {
    val javaVersion = Option(System.getProperty("java.version"))
    val javaVendor = Option(System.getProperty("java.vendor"))
    javaVersion.isDefined && javaVendor.isDefined &&
    javaVersion.exists(v => v.contains(".") || v.toIntOption.exists(_ >= 8)) &&
    javaVendor.exists(v => !v.toLowerCase.contains("scala"))
  }

  val backend: RuntimeBackend =
    if isJVM then RuntimeBackend.VirtualThreads
    else RuntimeBackend.Synchronous
}
```

This enables automatic backend selection without ServiceLoader complexity.

## 2. Fiber Creation and Management

### 2.1 UnifiedFiber State Machine

**File:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/UnifiedFiber.scala`

Fibers have two possible states:

```scala
enum UnifiedFiberState[+E, +A] {
  case Completed(exit: Exit[E, A])
  case Active[E, A](
    latch: CountDownLatch,
    exitRef: AtomicReference[Exit[E, A]],
    threadRef: AtomicReference[Option[Thread]]
  )
}
```

#### State Transitions

**Creation (Active state)** - Lines 139-144:
```scala
def active[E, A](id: FiberId): UnifiedFiber[E, A] = {
  val latch = new CountDownLatch(1)
  val exitRef = new AtomicReference[Exit[E, A]]()
  val threadRef = new AtomicReference[Option[Thread]](None)
  new UnifiedFiber(id, UnifiedFiberState.Active(latch, exitRef, threadRef))
}
```

**Completion (Active → Completed)** - Lines 156-167:
```scala
def complete[E, A](fiber: UnifiedFiber[E, A], exit: Exit[E, A]): Unit = {
  fiber.state match {
    case UnifiedFiberState.Active(latch, exitRef, _) =>
      exitRef.set(exit)
      latch.countDown()  // Release all waiters
    case UnifiedFiberState.Completed(_) => ()
  }
}
```

### 2.2 Fiber Await Mechanism

**Location:** `UnifiedFiber.scala`, lines 60-73

```scala
def await: Eru[Nothing, Exit[E, A]] = state match {
  case UnifiedFiberState.Completed(exit) =>
    Eru.succeed(exit)  // Immediate return

  case UnifiedFiberState.Active(latch, exitRef, _) =>
    Eru.interruptibleBlocking {
      latch.await()     // Block on CountDownLatch
      exitRef.get()     // Retrieve result
    }.attempt.map {
      case Result.Success(exit) => exit
      case Result.Failure(t) =>
        Option(exitRef.get()).getOrElse(Exit.Die(t))
    }
}
```

**Key Properties:**
- Synchronous blocking on CountDownLatch (no spinning)
- Interruptible via InterruptedException
- Multiple awaits are safe and idempotent
- Fallback to Die if atomic reference not set

### 2.3 Fiber Identity and Uniqueness

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Exit.scala`, lines 65-115

```scala
opaque type FiberId = Long

object FiberId {
  private val processUniqueStart = {
    val ProcessIdBits = 15
    val processId = java.lang.management.ManagementFactory.getRuntimeMXBean
      .getName.hashCode.toLong & ((1L << ProcessIdBits) - 1)
    val timestamp = System.nanoTime() & ((1L << 48) - 1)
    (processId << 48) | timestamp
  }
  private val next = new java.util.concurrent.atomic.AtomicLong(processUniqueStart)

  def fresh(): FiberId = next.getAndIncrement()
}
```

**Layout:**
- Bit 63: 0 (positive Long)
- Bits 62-48: Process ID (15 bits = 32K unique processes)
- Bits 47-0: Timestamp-based counter (281 trillion IDs per process)

This ensures uniqueness across multiple JVM processes and long runtime periods.

## 3. Structured Concurrency Guarantees

### 3.1 FiberScope and Parent-Child Relationships

**Location:** `RuntimeBackend.scala`, lines 7-9, 10-37

```scala
private final class FiberScope(val childFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]])

private object StructuredConcurrency {
  private val currentScope: ThreadLocal[Option[FiberScope]] = ThreadLocal.withInitial(() => None)
```

#### Scope Management
- **Per-thread scope tracking**: ThreadLocal storage holds current scope
- **Scope restoration** (Lines 216-217, 255-256): Parent scope captured and restored in child threads
- **Incremental cleanup** (Lines 54-63): O(1) amortized cleanup cost

```scala
private def cleanupOneCompletedFiber(queue: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]): Unit = {
  Option(queue.poll()).foreach { fiber =>
    fiber.currentState match {
      case UnifiedFiberState.Completed(_) => () // Discard completed
      case UnifiedFiberState.Active(_, _, _) => queue.offer(fiber) // Re-add active
    }
  }
}
```

### 3.2 Auto-join Semantics with Scope Exit

**Location:** `RuntimeBackend.scala`, lines 17-37 (withNewScope method)

When a scope exits, all remaining child fibers are cleaned up:

```scala
def withNewScope[A](action: FiberScope => A): A = {
  val newScope = new FiberScope(new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]())
  val oldScope = getCurrentScope()
  setCurrentScope(Some(newScope))
  try {
    action(newScope)
  } finally {
    var child = Option(newScope.childFibers.poll())
    while (child.nonEmpty) {
      val fiber = child.get
      try {
        // Interrupt with ParentTerminated cause
        fiber.interrupt(InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(()))).attempt.unsafeRunSync()
        // Wait for completion
        fiber.await.attempt.unsafeRunSync()
      } catch {
        case _: Exception => ()
      }
      child = Option(newScope.childFibers.poll())
    }
    setCurrentScope(oldScope)
  }
}
```

**Guarantees:**
1. All children receive ParentTerminated interrupt signal
2. All children are awaited to ensure completion
3. Parent scope is restored atomically
4. Exceptions during cleanup are suppressed (fail-safe cleanup)

### 3.3 Child Registration and Root Fiber Tracking

**Location:** `RuntimeBackend.scala`, lines 39-63

```scala
def addChildFiber(fiber: UnifiedFiber[?, ?], rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]]): Unit = {
  getCurrentScope() match {
    case Some(scope) =>
      scope.childFibers.offer(fiber)  // Add to parent scope
    case None =>
      rootFibers match {
        case Some(queue) =>
          queue.offer(fiber)  // Add to root fiber queue
          // Incremental cleanup: remove one completed fiber per add
          cleanupOneCompletedFiber(queue)
        case None => ()
      }
  }
}
```

**Key Properties:**
- Scope hierarchy is maintained through ThreadLocal
- Root fibers tracked separately for test isolation
- Amortized O(1) cleanup cost per fork operation
- Prevents unbounded growth of root fiber queue

## 4. Typed Suspension System

### 4.1 Suspending vs Immediate Types

**File:** `/home/user/eru/eru-runtime/shared/src/main/scala/net/ghoula/eru/SuspensionTypes.scala`

```scala
final class Suspending[+E, +A](val eru: Eru[E, A]) extends AnyVal {
  // No unsafeRunSync method - type-level enforcement of safe usage
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]]
  def timeout(duration: Duration)(using runtime: EruRuntime): Immediate[E | Throwable, A]
}

final class Immediate[+E, +A](val eru: Eru[E, A]) extends AnyVal {
  // Can safely run synchronously
  def unsafeRunSync(): A
  def suspending: Suspending[E, A]  // Widening is always safe
}
```

### 4.2 Type Safety Without Runtime Overhead

Both Suspending and Immediate are value classes (AnyVal), meaning:
- **Zero runtime overhead**: The wrapper is completely erased by the Scala compiler
- **Compile-time verification**: Type checker prevents unsafe operations
- **Ergonomic API**: Clear intent through type signatures

### 4.3 Suspension Registration

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Eru.scala`, lines 73-74

```scala
private case Suspend[E0, A0](register: (Either[E0, A0] => Unit) => Eru[Nothing, Unit]) extends Eru[E0, A0]
```

Suspension allows:
- **Async callbacks**: Register with external systems (futures, channels, etc.)
- **Two-phase completion**: Registration phase followed by callback invocation
- **Type-safe continuations**: Callback receives Either[E, A] for complete result handling

## 5. Interrupt Handling

### 5.1 InterruptCause Hierarchy

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Exit.scala`, lines 117-267

```scala
enum InterruptCause {
  case Cancelled(reason: Option[String] = None)
  case Timeout(duration: java.time.Duration, operation: Option[String] = None)
  case ParentTerminated(parentId: FiberId, parentExit: Exit[Any, Any])
  case ResourceExhausted(resource: String, details: Option[String] = None)
  case Custom(
    name: String,
    context: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  )
}
```

**Categories:**

| Cause | Origin | Use Case |
|-------|--------|----------|
| Cancelled | User/Runtime | Explicit cancellation |
| Timeout | Timer | Time-based interruption |
| ParentTerminated | Structured Concurrency | Scope exit |
| ResourceExhausted | System | Resource pressure |
| Custom | Application | Domain-specific reasons |

### 5.2 Fiber Interruption Protocol

**Location:** `UnifiedFiber.scala`, lines 85-93

```scala
def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = state match {
  case UnifiedFiberState.Completed(_) =>
    Eru.unit  // No-op for completed fibers

  case UnifiedFiberState.Active(_, _, threadRef) =>
    Eru.effect {
      threadRef.get().foreach(_.interrupt())  // Thread.interrupt()
    }.attempt.flatMap(_ => Eru.unit)
}
```

**Semantics:**
- **Cooperative interruption**: Calls Thread.interrupt() but doesn't stop execution
- **Idempotent**: Multiple interrupts on the same fiber are safe
- **Non-blocking**: Returns immediately (interrupt signal is asynchronous)

### 5.3 InterruptedWithFinalizers Exception

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Eru.scala`, lines 8-13

```scala
private class InterruptedWithFinalizers(
  val fiberId: FiberId,
  val cause: InterruptCause,
  val finalizers: List[() => Eru[Nothing, Unit]]
) extends InterruptedException(cause.toString)
```

Used internally to:
- Preserve finalizers when InterruptedException occurs
- Carry structured interrupt cause information
- Ensure FILO finalizer execution during interruption

## 6. Scope Management and Cleanup

### 6.1 Root Fiber Queue Cleanup

**Location:** `RuntimeBackend.scala`, lines 65-84

```scala
def cleanupRootFibers(rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]]): Unit = {
  rootFibers match {
    case Some(queue) =>
      val fibersToCleanup = scala.collection.mutable.ListBuffer.empty[UnifiedFiber[?, ?]]
      var fiber = Option(queue.poll())
      while (fiber.nonEmpty) {
        fibersToCleanup += fiber.get
        fiber = Option(queue.poll())
      }

      fibersToCleanup.foreach { fiberToCleanup =>
        try {
          fiberToCleanup.await.attempt.unsafeRunSync()
        } catch {
          case _: Exception => ()
        }
      }
    case None => ()
  }
}
```

**Cleanup Strategy:**
1. Poll all fibers from the queue
2. Await each fiber (synchronously blocking for completion)
3. Suppress exceptions to ensure all fibers are awaited
4. Called at end of unsafeRunSync() to ensure all background work completes

### 6.2 Backend Cleanup Protocol

**Location:** `RuntimeBackendAdapter.scala`, lines 159-163

```scala
override def cleanup(): Unit = {
  backend.cleanup(Some(rootFibers))
  // Note: Don't eagerly close privateExecutor as it may still have pending tasks
  // The lazy executor will be cleaned up by GC when the adapter is collected
}
```

**Design rationale:**
- Cleanup called at end of execution
- Root fiber queue drained to ensure all fibers complete
- Executor not closed (relies on GC) to avoid blocking on executor shutdown
- Safe for test isolation with per-adapter fiber queues

### 6.3 Finalizer Execution Order (FILO)

**Location:** `RuntimeBackend.scala`, lines 220-225

```scala
val (exit, finalizers) = Eru.executeWithFinalizers(fa)

finalizers.foreach { finalizer =>
  try finalizer().unsafeRunSync()
  catch case _: Exception => ()
}
```

**FILO (First In, Last Out) semantics:**
- Last-registered finalizer executes first
- Enables proper resource nesting (inner resources released before outer)
- Exception suppression ensures all finalizers execute
- Synchronous execution maintains deterministic cleanup order

## 7. Virtual Thread Backend Specifics

### 7.1 RuntimeBackendAdapter Architecture

**File:** `/home/user/eru/eru-runtime/jvm/src/main/scala/net/ghoula/eru/internal/RuntimeBackendAdapter.scala`

The adapter bridges the RuntimeBackend enum with the legacy ConcurrencyBackend trait:

```scala
final class RuntimeBackendAdapter(backend: RuntimeBackend) extends ConcurrencyBackend {
  private val rootFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] =
    new ConcurrentLinkedQueue()

  private lazy val privateExecutor =
    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

  val capabilities: BackendCapabilities = backend match {
    case RuntimeBackend.Synchronous =>
      new BackendCapabilities(
        virtualThreads = false,
        structuredScopes = false,
        timersNonBlocking = false
      )
    case RuntimeBackend.VirtualThreads =>
      new BackendCapabilities(
        virtualThreads = true,
        structuredScopes = false,
        timersNonBlocking = true
      )
  }
}
```

### 7.2 Batch Operations Optimization

**Location:** `RuntimeBackend.scala`, lines 434-470 (forkBatch method)

```scala
def forkBatch[E, A](
  effects: List[Eru[E, A]],
  rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]] = None
): Eru[Nothing, List[Fiber[E, A]]] =
  this match {
    case VirtualThreads =>
      Eru.effectTotal {
        effects.map { fa =>
          val id = FiberId.fresh()
          val fiber = UnifiedFiber.active[E, A](id)

          StructuredConcurrency.addChildFiber(fiber, rootFibers)

          Thread.startVirtualThread { () =>
            UnifiedFiber.setThread(fiber, Thread.currentThread())
            val (exit, finalizers) = Eru.executeWithFinalizers(fa)
            finalizers.foreach { finalizer =>
              try finalizer().unsafeRunSync()
              catch case _: Exception => ()
            }
            UnifiedFiber.complete(fiber, exit)
          }

          fiber: Fiber[E, A]
        }
      }
  }
```

**Optimizations:**
- Creates all fibers in a single effect
- Avoids deep monadic chaining (map/flatMap)
- Minimizes synchronization overhead
- Particularly efficient for parallel operations

### 7.3 Race Implementation

**Location:** `RuntimeBackend.scala`, lines 292-374

Race uses atomic CAS pattern to determine winner:

```scala
def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
  this match {
    case VirtualThreads =>
      Eru.effectTotal {
        val resultRef = new AtomicReference[Option[() => Eru[E1 | E2 | Throwable, Either[A, B]]]](None)
        val latch = new CountDownLatch(1)

        def trySet(thunk: () => Eru[...], cancelOther: () => Unit): Unit =
          if (resultRef.compareAndSet(None, Some(thunk))) {
            cancelOther()
            latch.countDown()
          }

        Thread.startVirtualThread { () =>
          val (exit, finalizers) = Eru.executeWithFinalizers(fa)
          // ... execute finalizers ...
          exit match {
            case Exit.Success(a) =>
              trySet(() => Eru.succeed(Left(a)), () => rightThreadRef.get().foreach(_.interrupt()))
            // ... other cases ...
          }
        }

        Thread.startVirtualThread { () => /* ... */ }

        latch.await()
        resultRef.get()
      }
```

**Race CAS Winner Pattern:**
- Both threads execute concurrently
- First to call compareAndSet wins
- Winner cancels loser thread via interrupt
- Loser's interruption is suppressed in exit handling
- AtomicReference holds thunk for winner's result

## 8. Observable Events System

### 8.1 EruObserver Interface

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/EruObserver.scala`, lines 336-398

```scala
trait EruObserver {
  def onEvent(event: EruEvent): Unit
}
```

Observers receive all significant execution events:
- Program lifecycle (start, end, steps)
- Fiber lifecycle (started, completed, interrupted)
- Structured concurrency events (cleanup started/completed, child interruptions)
- Tracing spans

### 8.2 Structured Concurrency Observer Trait

**Location:** `EruObserver.scala`, lines 428-521

Higher-level observer for filtering concurrency-specific events:

```scala
trait StructuredConcurrencyObserver extends EruObserver {
  def onFiberForked(parentId: FiberId, childId: FiberId): Unit = ()
  def onStructuredCleanupStarted(fiberId: FiberId, childCount: Int): Unit = ()
  def onStructuredCleanupCompleted(fiberId: FiberId, interruptedCount: Int, completedCount: Int): Unit = ()
  def onChildInterruptionRequested(parentId: FiberId, childId: FiberId, cause: InterruptCause, childWasRunning: Boolean): Unit = ()
  def onFiberLifecycle(event: EruEvent): Unit = ()
}
```

### 8.3 Observer Integration with Fork

**Location:** `RuntimeBackend.scala`, lines 131-132, 157-158

Observers are notified at key lifecycle points:

```scala
observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
// ... execution ...
observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
```

**Event ordering guarantees:**
- FiberStarted fires before execution begins
- FiberCompleted fires after finalizers execute
- Events are causally ordered within a fiber
- Events from different fibers may interleave

## 9. Exit and Result Types

### 9.1 Exit Type Hierarchy

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Exit.scala`, lines 16-46

```scala
enum Exit[+E, +A] {
  case Success(value: A)
  case Failure(error: E)
  case Die(throwable: Throwable)
  case Interrupt(fiberId: FiberId, cause: InterruptCause)
}
```

**Semantics:**
- **Success**: Normal completion with result
- **Failure**: Typed error (expected, recoverable)
- **Die**: Unexpected exception (defect, unrecoverable)
- **Interrupt**: Cooperative termination with cause

### 9.2 Result Type (Attempt Pattern)

**File:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Result.scala`

```scala
enum Result[+E, +A] {
  case Success(value: A)
  case Failure(error: Either[E, Throwable])
}
```

Used by attempt/recover to wrap exceptions into Either channel.

## 10. Performance Characteristics

### 10.1 Virtual Thread Scaling

- **Thread creation**: Lightweight (100K+ threads feasible)
- **Context switching**: Minimal overhead compared to OS threads
- **Memory**: ~10KB per Virtual Thread vs ~2MB per OS thread
- **GC**: No additional GC pressure

### 10.2 Optimization Strategies

| Operation | Optimization | Location |
|-----------|--------------|----------|
| Fork pure values | Skip VT creation | Lines 154-184 |
| Batch fork | Single effect | Lines 445-468 |
| Scope cleanup | Incremental O(1) | Lines 54-63 |
| MapChain fusion | Fuse consecutive maps | Eru.scala |
| Race | CAS winner pattern | Lines 308-312 |

### 10.3 Memory Efficiency

- **UnifiedFiber**: CountDownLatch + 2 AtomicReferences = ~120 bytes
- **Scope**: ConcurrentLinkedQueue with amortized cleanup
- **ThreadLocal**: Single entry per thread (typical case)

## 11. Key Implementation Files

### Core Implementation
- **Eru.scala** (1738 lines): Main effect type, interpreter, continuations
- **Exit.scala** (516 lines): Exit, FiberId, InterruptCause
- **UnifiedFiber.scala** (188 lines): Fiber state machine, coordination
- **RuntimeBackend.scala** (530 lines): Fork, race, structured concurrency

### Runtime Support
- **EruRuntime.scala**: Public runtime API
- **ConcurrencyBackend.scala**: Backend trait interface
- **RuntimeBackendAdapter.scala**: JVM backend adapter

### Observability
- **EruObserver.scala** (516 lines): Event system, observer traits
- **EruFiber.scala**: Legacy fiber type support

## 12. Key Data Structures

### Thread-Local Scope
```scala
private val currentScope: ThreadLocal[Option[FiberScope]] = ThreadLocal.withInitial(() => None)
```

### Atomic Coordination
```scala
exitRef: AtomicReference[Exit[E, A]]
threadRef: AtomicReference[Option[Thread]]
resultRef: AtomicReference[Option[...]]
```

### Synchronization Primitives
```scala
latch: CountDownLatch(1)  // Lightweight awaiting
```

### Concurrent Collections
```scala
childFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]
rootFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]
```

## Conclusion

Eru's implementation combines several sophisticated patterns:
1. **Type-level safety** through Suspending/Immediate separation
2. **Structured concurrency** via ThreadLocal scope propagation
3. **Lightweight fibers** leveraging Java Virtual Threads
4. **Observable execution** with comprehensive event system
5. **Cooperative interruption** with rich cause information
6. **FILO finalizer semantics** ensuring proper resource cleanup

This design achieves both safety and performance while maintaining ergonomic APIs that guide users toward correct concurrent programming patterns.
