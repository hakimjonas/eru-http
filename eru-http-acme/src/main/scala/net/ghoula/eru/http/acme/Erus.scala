package net.ghoula.eru.http.acme

import net.ghoula.eru.*

/** Local conversions from Either into Eru's typed error channel. */
extension [A](either: Either[AcmeError, A]) {
  def erum: Eru[AcmeError, A] = either match {
    case Right(value) => Eru.succeed(value)
    case Left(error) => Eru.fail(error)
  }
}
