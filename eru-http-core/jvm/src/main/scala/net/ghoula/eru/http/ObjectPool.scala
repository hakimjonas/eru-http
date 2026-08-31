package net.ghoula.eru.http

import java.nio.ByteBuffer

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** A concurrent object pool for reusing objects and reducing allocations.
  *
  * Built on Eru's Queue primitive for fair FIFO allocation with automatic backpressure.
  *
  * @tparam A
  *   the type of objects in the pool
  */
trait ObjectPool[A] {

  /** Acquires an object from the pool, blocking if none available.
    *
    * @return
    *   a suspending effect that completes with a pooled object
    */
  def acquire: Suspending[Nothing, A]

  /** Returns an object to the pool for reuse.
    *
    * @param obj
    *   the object to return
    * @return
    *   an immediate effect that completes when the object is returned
    */
  def release(obj: A): Immediate[Nothing, Unit]

  /** Acquires an object, runs an effect with it, and releases it afterward.
    *
    * Ensures the object is always released even if the effect fails.
    *
    * @param f
    *   the effect to run with the acquired object
    * @return
    *   a suspending effect that yields the result of f
    */
  def withResource[E, B](f: A => Eru[E, B]): Suspending[E, B]

  /** Gets the current number of objects available in the pool.
    *
    * @return
    *   an immediate effect yielding the pool size
    */
  def available: Immediate[Nothing, Int]
}

object ObjectPool {

  /** Creates a new object pool backed by a bounded Queue.
    *
    * The pool is pre-populated with `capacity` objects created by the factory.
    *
    * @param capacity
    *   the maximum number of objects in the pool
    * @param factory
    *   effect that creates a new object
    * @param reset
    *   effect that resets an object to reusable state (default: no-op)
    * @param runtime
    *   the Eru runtime
    * @return
    *   an effect that yields a new ObjectPool
    */
  def make[A](
    capacity: Int,
    factory: Eru[Nothing, A],
    reset: A => Eru[Nothing, Unit] = (_: A) => Eru.unit
  )(using runtime: EruRuntime): Eru[Nothing, ObjectPool[A]] = {
    for {
      queue <- Queue.bounded[A](capacity)(using runtime)

      _ <- Eru.foreach(1 to capacity) { _ =>
        factory.flatMap(obj => queue.put(obj).eru)
      }
    } yield new QueueBasedPool(queue, reset)
  }
}

/** Implementation of ObjectPool using Eru's Queue primitive.
  *
  * @param queue
  *   the underlying bounded queue
  * @param reset
  *   effect to reset objects before reuse
  */
private class QueueBasedPool[A](
  queue: Queue[A],
  reset: A => Eru[Nothing, Unit]
) extends ObjectPool[A] {

  def acquire: Suspending[Nothing, A] = queue.take

  def release(obj: A): Immediate[Nothing, Unit] = new Immediate(
    reset(obj).flatMap(_ => queue.put(obj).eru)
  )

  def withResource[E, B](f: A => Eru[E, B]): Suspending[E, B] = new Suspending(
    acquire.eru.bracket(obj => release(obj).eru)(f)
  )

  def available: Immediate[Nothing, Int] = queue.size
}

/** Pre-configured pools for common use cases. */
object Pools {

  /** Creates a pool of direct ByteBuffers for zero-copy I/O.
    *
    * The factory uses `Eru.effect`, which is lazy: the allocation runs on every invocation of the
    * factory effect, giving each pool slot its own distinct ByteBuffer. `Eru.succeed` would
    * evaluate once and share the same buffer across all slots — a silent data race.
    *
    * @param capacity
    *   number of buffers in the pool
    * @param bufferSize
    *   size of each buffer in bytes
    * @param runtime
    *   the Eru runtime
    * @return
    *   an effect that yields a ByteBuffer pool
    */
  def directByteBuffers(
    capacity: Int,
    bufferSize: Int
  )(using runtime: EruRuntime): Eru[Nothing, ObjectPool[ByteBuffer]] = {
    ObjectPool.make(
      capacity = capacity,
      factory = Eru.effect(ByteBuffer.allocateDirect(bufferSize)).attempt.map {
        case Result.Success(b) => b
        case Result.Failure(_) => ByteBuffer.allocateDirect(bufferSize)
      },
      reset = buffer => Eru.succeed { buffer.clear(); () }
    )
  }

  /** Creates a pool of heap ByteBuffers.
    *
    * The factory is lazy exactly as in `directByteBuffers`, so each pool slot gets its own distinct
    * buffer.
    *
    * @param capacity
    *   number of buffers in the pool
    * @param bufferSize
    *   size of each buffer in bytes
    * @param runtime
    *   the Eru runtime
    * @return
    *   an effect that yields a ByteBuffer pool
    */
  def heapByteBuffers(
    capacity: Int,
    bufferSize: Int
  )(using runtime: EruRuntime): Eru[Nothing, ObjectPool[ByteBuffer]] = {
    ObjectPool.make(
      capacity = capacity,
      factory = Eru.effect(ByteBuffer.allocate(bufferSize)).attempt.map {
        case Result.Success(b) => b
        case Result.Failure(_) => ByteBuffer.allocate(bufferSize)
      },
      reset = buffer => Eru.succeed { buffer.clear(); () }
    )
  }
}
