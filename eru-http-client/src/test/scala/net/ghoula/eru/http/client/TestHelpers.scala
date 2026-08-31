package net.ghoula.eru.http.client

import munit.Assertions
import munit.Location

import net.ghoula.eru.*

/** Test helpers for HTTP client tests.
  */
object TestHelpers {

  extension [E, A](eru: Eru[E, A]) {

    /** Asserts that the computation succeeds and returns the value.
      */
    def assertSuccess(using loc: Location): A = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(v) => v
        case Result.Failure(error) =>
          Assertions.fail(s"Expected success but got error: $error")(using loc)
      }
    }

    /** Asserts that the computation fails and returns the error.
      */
    def assertFailure(using loc: Location): E = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(v) =>
          Assertions.fail(s"Expected failure but got success: $v")(using loc)
        case Result.Failure(error) => error
      }
    }

    /** Checks if the computation succeeds.
      */
    def isSuccess: Boolean = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(_) => true
        case Result.Failure(_) => false
      }
    }

    /** Checks if the computation fails.
      */
    def isFailure: Boolean = !isSuccess
  }
}
