# Eru Async Execution Requirements

## Overview

For eru-http to become truly async/non-blocking, Eru needs to provide an async execution primitive. Currently, only `unsafeRunSync()` exists, which blocks the calling thread.

## Required API

### 1. Async Execution Method

Add to `EruRuntime`:

```scala
trait EruRuntime {
  // Existing
  def unsafeRunSync[E, A](eru: Eru[E, A]): A

  // NEW - Required for async HTTP
  def unsafeRunAsync[E, A](
    eru: Eru[E, A]
  )(callback: Either[E, A] => Unit): Unit
}
```

### 2. Example Implementation

```scala
final class EruRuntimeImpl(executionContext: ExecutionContext) extends EruRuntime {

  def unsafeRunAsync[E, A](
    eru: Eru[E, A]
  )(callback: Either[E, A] => Unit): Unit = {
    executionContext.execute { () =>
      try {
        val result = eru.attempt.unsafeRunSync()
        result match {
          case Result.Success(a) => callback(Right(a))
          case Result.Failure(e) => callback(Left(e))
        }
      } catch {
        case ex: Throwable =>
          // Handle unexpected errors
          ex.printStackTrace()
          // Optionally: callback(Left(error))
      }
    }
  }
}
```

### 3. Alternative: Using Result Type

If you prefer to use Eru's `Result` type:

```scala
def unsafeRunAsync[E, A](
  eru: Eru[E, A]
)(callback: Result[E, A] => Unit): Unit = {
  executionContext.execute { () =>
    val result = eru.attempt.unsafeRunSync()
    callback(result)
  }
}
```

## Why This Is Critical

### Current Problem (Server Blocking)

```scala
// NettyHttpServer.scala:167 - BLOCKS Netty event loop!
override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit = {
  val responseEru = convertRequest(req).flatMap(handler)

  // This blocks the event loop thread waiting for the effect to complete
  responseEru.attempt.unsafeRunSync() match {
    case Result.Success(response) => ctx.writeAndFlush(...)
    case Result.Failure(error) => ctx.writeAndFlush(errorResponse(error))
  }
}
```

Under load with 100 concurrent connections, if each request takes 100ms to handle:
- Event loop thread is blocked for 100ms per request
- Can only process ~10 requests/second per thread
- Thread pool exhaustion under load

### With Async Execution (Target)

```scala
override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit = {
  val responseEru = convertRequest(req).flatMap(handler)

  // Run async - event loop thread returns immediately
  runtime.unsafeRunAsync(responseEru.attempt) {
    case Result.Success(response) =>
      val nettyResponse = convertResponse(response, ...)
      ctx.writeAndFlush(nettyResponse)
    case Result.Failure(error) =>
      val errorResponse = errorToResponse(error, ...)
      ctx.writeAndFlush(errorResponse)
  }
  // Event loop thread is now free to handle next request
}
```

Benefits:
- Event loop never blocks
- Can handle 1000s of concurrent connections
- Expected throughput: 50k-150k req/s (vs current ~10k req/s)

## Implementation Priority

**CRITICAL** - Without this, eru-http server will always be blocking and slow under load.

The client side already works correctly because it uses `EruRuntime.suspend`, which is the reverse direction (wrapping callbacks INTO effects). We need the opposite direction (running effects and calling back).

## Testing the Implementation

Once added to Eru, verify with:

```scala
@main def testAsync(): Unit = {
  given runtime: EruRuntime = EruRuntime.shared

  val effect = Eru.effect {
    println(s"Running on thread: ${Thread.currentThread().getName}")
    Thread.sleep(1000) // Simulate work
    "done"
  }

  println(s"Main thread: ${Thread.currentThread().getName}")

  runtime.unsafeRunAsync(effect.attempt) {
    case Result.Success(value) =>
      println(s"Callback on thread: ${Thread.currentThread().getName}")
      println(s"Result: $value")
    case Result.Failure(error) =>
      println(s"Error: $error")
  }

  println("Main thread returned immediately")
  Thread.sleep(2000) // Wait for async completion
}
```

Expected output:
```
Main thread: main
Main thread returned immediately
Running on thread: scala-execution-context-1
Callback on thread: scala-execution-context-1
Result: done
```

## Questions?

If you need help implementing this in Eru, let me know. The core pattern is:
1. Get an ExecutionContext (or thread pool)
2. Submit the effect execution as a task
3. Invoke the callback when done
4. Handle errors gracefully
