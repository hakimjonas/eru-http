import sbt._

object Dependencies {
  // Versions
  val eruVersion = "0.1.0"
  val munitVersion = "1.0.3"
  val nettyVersion = "4.1.115.Final"
  val brotli4jVersion = "1.16.0"

  // Eru effect system
  val eruCore = "net.ghoula" %% "eru-core" % eruVersion
  val eruRuntime = "net.ghoula" %% "eru-runtime" % eruVersion

  // Testing
  val munit = "org.scalameta" %% "munit" % munitVersion

  // HTTP Client/Server (Netty)
  val nettyHandler = "io.netty" % "netty-handler" % nettyVersion
  val nettyCodecHttp = "io.netty" % "netty-codec-http" % nettyVersion
  val nettyCodecHttp2 = "io.netty" % "netty-codec-http2" % nettyVersion

  // Compression (Brotli)
  val brotli4j = "com.aayushatharva.brotli4j" % "brotli4j" % brotli4jVersion
}
