package net.ghoula.eru.http.server

import munit.Assertions
import munit.Location

import net.ghoula.eru.*

object TestHelpers {

  extension [E, A](eru: Eru[E, A]) {
    def assertSuccess(using loc: Location): A = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(v) => v
        case Result.Failure(error) =>
          Assertions.fail(s"Expected success but got error: $error")(using loc)
      }
    }

    def assertFailure(using loc: Location): E = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(v) =>
          Assertions.fail(s"Expected failure but got success: $v")(using loc)
        case Result.Failure(error) => error
      }
    }

    def isSuccess: Boolean = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(_) => true
        case Result.Failure(_) => false
      }
    }

    def isFailure: Boolean = !isSuccess
  }
}
