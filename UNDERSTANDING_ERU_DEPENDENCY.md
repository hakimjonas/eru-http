# Understanding Eru Dependency

## Current Situation

The eru-http project depends on Eru via local ProjectRef:

```scala
lazy val eruCore = ProjectRef(file("../eru"), "eruCoreJVM")
lazy val eruRuntime = ProjectRef(file("../eruRuntime"), "eruRuntimeJVM")
```

This means Eru source should be at `/home/user/eru` but it's not currently present.

## Questions for You

To properly understand and optimize eru-http, I need to know:

### 1. Eru's Threading Model

**Does Eru use:**
- A) `Executors.newVirtualThreadPerTaskExecutor()` - each effect on new virtual thread?
- B) `Executors.newCachedThreadPool()` - platform thread pool?
- C) `ForkJoinPool.commonPool()` - work-stealing pool?
- D) Something else?

**When `unsafeRunSync()` is called:**
- Does it create a new virtual thread to run the effect?
- Does it run on the calling thread?
- Does it submit to a thread pool?

### 2. Effect Execution Model

**For a chain like:**
```scala
for {
  a <- Eru.effect { doSomething() }
  b <- Eru.effect { doSomethingElse() }
} yield (a, b)
```

**Does each step:**
- Run on the same thread?
- Run on different threads from a pool?
- Create new virtual threads?

### 3. Suspend Implementation

**For `EruRuntime.suspend`:**
```scala
EruRuntime.shared.suspend[E, A] { callback =>
  Eru.effectTotal {
    // What thread does this run on?
  }
}
```

This is used successfully in NettyHttpClient - how does it bridge callbacks?

### 4. Access to Eru Source

Can you:
- Point me to where Eru is located (if it's been cloned)?
- Give me access to the Eru repository?
- Share the relevant Eru source files (EruRuntime.scala, Eru.scala)?
- Or explain the threading model directly?

## Why This Matters

The architecture decisions depend entirely on Eru's implementation:

**If Eru creates virtual threads per effect:**
- ✅ Blocking I/O is perfect
- ✅ Can simplify by removing Netty
- ✅ unsafeRunSync() in server is fine
- ✅ Simple sequential code = concurrent execution

**If Eru uses platform thread pool:**
- ⚠️ Blocking I/O would tie up platform threads
- ⚠️ Need Netty or async I/O
- ⚠️ Need unsafeRunAsync() to avoid blocking pool
- ⚠️ More complex architecture required

**If Eru runs on calling thread:**
- ⚠️ unsafeRunSync() would block caller
- ⚠️ Netty event loop blocking is a real problem
- ⚠️ Must use async patterns everywhere
- ⚠️ Most complex scenario

## Next Steps

Please either:
1. Clone Eru to `/home/user/eru` so I can read the source
2. Share the EruRuntime implementation details
3. Or run the diagnostic: `sbt "runMain examples.DiagnoseEruThreading"`

This will tell us exactly how Eru works and we can optimize accordingly!
