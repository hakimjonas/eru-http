package net.ghoula.eru.http

import munit.*

import net.ghoula.eru.*

/** Test helpers for working with Eru effects in tests */
object TestHelpers {

  extension [E, A](eru: Eru[E, A]) {

    /** Run an Eru effect in tests, throwing on failure */
    def runTest: A = eru.unsafeRunSync()

    /** Assert that an Eru succeeds and return the value */
    def assertSuccess(using loc: Location): A = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) => value
        case Result.Failure(error) =>
          Assertions.fail(s"Expected success but got failure: $error")(using loc)
      }
    }

    /** Assert that an Eru fails and return the error */
    def assertFailure(using loc: Location): E = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) =>
          Assertions.fail(s"Expected failure but got success: $value")(using loc)
        case Result.Failure(error) => error
      }
    }

    /** Check if Eru succeeded */
    def isSuccess: Boolean = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(_) => true
        case Result.Failure(_) => false
      }
    }

    /** Check if Eru failed */
    def isFailure: Boolean = !isSuccess
  }

  /** Helper for chaining operations in tests */
  object EruOps {

    /** Chain multiple Eru-returning operations */
    def chain[E, A](initial: Eru[E, A])(ops: (A => Eru[E, A])*): Eru[E, A] = {
      ops.foldLeft(initial) { (acc, op) =>
        acc.flatMap(op)
      }
    }
  }
}
