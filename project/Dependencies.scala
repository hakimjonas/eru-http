import sbt._

object Dependencies {
  // Versions
  val munitVersion = "1.0.3"
  val brotli4jVersion = "1.16.0"

  // Testing
  val munit = "org.scalameta" %% "munit" % munitVersion

  // Compression (Brotli)
  val brotli4j = "com.aayushatharva.brotli4j" % "brotli4j" % brotli4jVersion
}
