import sbt._

object Dependencies {
  // Versions
  val munitVersion = "1.0.3"
  val nettyVersion = "4.1.115.Final"
  val brotli4jVersion = "1.16.0"

  // Testing
  val munit = "org.scalameta" %% "munit" % munitVersion

  // HTTP Client/Server (Netty)
  val nettyHandler = "io.netty" % "netty-handler" % nettyVersion
  val nettyCodecHttp = "io.netty" % "netty-codec-http" % nettyVersion
  val nettyCodecHttp2 = "io.netty" % "netty-codec-http2" % nettyVersion

  // Compression (Brotli)
  val brotli4j = "com.aayushatharva.brotli4j" % "brotli4j" % brotli4jVersion
}
