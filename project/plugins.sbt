// Code formatting
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// Code linting and refactoring
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.0")

// Cross-platform builds
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

// Scala.js
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.18.1")

// JMH for benchmarking (optional)
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

// Publishing
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.3")

// Assembly for fat JARs
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")
