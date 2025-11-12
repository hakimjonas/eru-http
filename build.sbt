import Dependencies.*
// import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
// import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

ThisBuild / scalaVersion := "3.7.3"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / organization := "net.ghoula"
ThisBuild / organizationName := "Hakim Ghoula"
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = url("https://hakim.ghoula.net")
  )
)

// Skip Scala Native configuration - eru-http only targets JVM (and eventually JS)
// Eru publishes separate artifacts (eruCoreJVM, eruCoreNative) so we only depend on JVM
// Note: The 'clang' not found error is expected and harmless - we don't use Scala Native

// Compiler options
ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wconf:any:warning",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:higherKinds",
  "-indent",
  "-new-syntax",
  "-no-indent",
  "-source:future"
)

// Java options
ThisBuild / javacOptions ++= Seq(
  "-source",
  "21",
  "-target",
  "21"
)

// Test settings
ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "+l")
ThisBuild / Test / parallelExecution := false
ThisBuild / Test / fork := true // Enable fork to use javaOptions

// Publishing settings
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / Test / publishArtifact := false

// Shared settings
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    munit % Test
  ),
  testFrameworks += new TestFramework("munit.Framework")
)

// Root project
lazy val root = (project in file("."))
  .settings(
    name := "eru-http",
    publish / skip := true
  )
  .aggregate(coreJVM, client, server) // Skip coreJS until Eru has JS support

// Reference local Eru project
lazy val eruCore = ProjectRef(file("../eru"), "eruCoreJVM")
lazy val eruRuntime = ProjectRef(file("../eru"), "eruRuntimeJVM")

// Core module with HTTP types and standards
// Note: JS support pending Eru JS implementation
lazy val coreJVM = (project in file("eru-http-core/jvm"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-core",
    description := "Core HTTP types and standards for Eru-based applications",
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "shared" / "src" / "main" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / ".." / "shared" / "src" / "test" / "scala",
    libraryDependencies ++= Seq(
      "net.ghoula" %% "valar-core" % "0.5.0",
      brotli4j
    )
  )
  .dependsOn(eruCore, eruRuntime)

// Future: Add coreJS when Eru supports Scala.js
// lazy val core = crossProject(JVMPlatform, JSPlatform)...

// HTTP Client (JVM-only for now - Native blocking NIO + Virtual Threads)
lazy val client = (project in file("eru-http-client"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-client",
    description := "Standards-compliant HTTP client built on Eru"
  )
  .dependsOn(coreJVM, server % "test->compile")

// HTTP Server (JVM-only for now - Native blocking NIO + Virtual Threads)
lazy val server = (project in file("eru-http-server"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-server",
    description := "Standards-compliant HTTP server built on Eru"
  )
  .dependsOn(coreJVM)

// Benchmarks - commented out until needed
// lazy val bench = (project in file("eru-http-bench"))
//   .settings(commonSettings)
//   .settings(
//     name := "eru-http-bench",
//     publish / skip := true
//   )
//   .enablePlugins(JmhPlugin)
//   .dependsOn(core, client, server)

// Commands
addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheckAll")
addCommandAlias("fix", "scalafixAll")
addCommandAlias("prepare", "fix; fmt")
addCommandAlias("testAll", "test")
addCommandAlias("build", "compile; test")
