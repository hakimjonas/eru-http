import sbt._

object Dependencies {
  /* Versions. */
  val munitVersion = "1.3.5"
  val brotli4jVersion = "1.23.0"
  val caffeineVersion = "3.2.4"

  /* Testing. */
  val munit = "org.scalameta" %% "munit" % munitVersion

  /* Compression (Brotli). */
  val brotli4j = "com.aayushatharva.brotli4j" % "brotli4j" % brotli4jVersion

  /* Bounded concurrent cache for per-IP tracking (edge-grade DoS defense).
   * Pure Java, no transitive deps. Used only by the server module.
   */
  val caffeine = "com.github.ben-manes.caffeine" % "caffeine" % caffeineVersion
}
