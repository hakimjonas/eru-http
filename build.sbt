import Dependencies.*
// import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
// import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

ThisBuild / scalaVersion := "3.8.2"
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

// Compiler options
ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Werror",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wconf:any:warning",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:higherKinds",
  "-indent",
  "-new-syntax",
  "-no-indent",
  "-source:future",
  "-release:25", // Target Java 25+ for Virtual Threads support
  "-Yexplicit-nulls"
)

// Java options - target Java 25 for Virtual Threads support
ThisBuild / javacOptions ++= Seq(
  "--release",
  "25"
)

// Test settings
ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "+l")
ThisBuild / Test / parallelExecution := false
ThisBuild / Test / fork := true // Enable fork to use javaOptions

// JVM options for running servers (can be configured via environment variables for benchmarking)
// Applies to all projects and all forked JVM tasks (run, Test / run, Test / runMain, test)
//
// IMPORTANT: This project requires ZGC (default). G1GC has known SIGSEGV crashes with
// JDK 25 + Virtual Threads + heavy class loading. See .sbtopts for sbt's own JVM config.
val jvmRunOptions = {
  val heapSize = sys.env.getOrElse("HEAP_SIZE", sys.props.getOrElse("heap.size", "2g"))

  // ZGC generational is the only supported GC. G1GC/ParallelGC have known SIGSEGV
  // crashes with Virtual Threads under heavy load. ZGC is generational by default
  // since JDK 23.
  val gcOptions = Seq("-XX:+UseZGC", "-server")

  gcOptions ++ Seq(
    s"-Xms$heapSize", // Initial heap size
    s"-Xmx$heapSize", // Max heap size
    "-XX:MaxDirectMemorySize=4g" // Direct memory for NIO buffers (8KB per connection)
  )
}

ThisBuild / run / javaOptions ++= jvmRunOptions
ThisBuild / Test / javaOptions ++= jvmRunOptions

// Publishing settings
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / Test / publishArtifact := false

// GitHub Packages resolver for Eru dependencies (CI only)
ThisBuild / resolvers ++= {
  if (sys.env.contains("GITHUB_TOKEN") && sys.env("GITHUB_TOKEN").nonEmpty) {
    Seq("GitHub Package Registry (hakimjonas/eru)" at "https://maven.pkg.github.com/hakimjonas/eru")
  } else {
    Seq.empty
  }
}

ThisBuild / credentials ++= {
  if (sys.env.contains("GITHUB_TOKEN") && sys.env("GITHUB_TOKEN").nonEmpty) {
    Seq(
      Credentials(
        "GitHub Package Registry",
        "maven.pkg.github.com",
        sys.env.getOrElse("GITHUB_ACTOR", "hakimjonas"),
        sys.env("GITHUB_TOKEN")
      )
    )
  } else {
    Seq.empty
  }
}

// Assembly merge strategy (applied to all subprojects)
ThisBuild / assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case "scala-collection-compat.properties" => MergeStrategy.first
  case _ => MergeStrategy.first
}

// Shared settings
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    munit % Test
  ),
  testFrameworks += new TestFramework("munit.Framework")
)

// Eru dependency version (for CI)
val eruVersion = "0.0.0+348-cdca2cd4"

// Check if we're in CI (GITHUB_TOKEN is set) or local development
val useLocalEru = !sys.env.contains("GITHUB_TOKEN") || sys.env("GITHUB_TOKEN").isEmpty

// Root project
lazy val root = (project in file("."))
  .settings(
    name := "eru-http",
    publish / skip := true
  )
  .aggregate(coreJVM, client, server, examples, benchmarks) // Skip coreJS until Eru has JS support

// Local Eru project references (only when not in CI)
lazy val eruCoreRef = if (useLocalEru) Some(ProjectRef(file("../eru"), "eruCoreJVM")) else None
lazy val eruRuntimeRef = if (useLocalEru) Some(ProjectRef(file("../eru"), "eruRuntimeJVM")) else None

// Core module with HTTP types and standards
// Note: JS support pending Eru JS implementation
lazy val coreJVM = (project in file("eru-http-core/jvm"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-core",
    description := "Core HTTP types and standards for Eru-based applications",
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "shared" / "src" / "main" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / ".." / "shared" / "src" / "test" / "scala",
    libraryDependencies ++= {
      if (useLocalEru) {
        // Local development: no published dependencies needed, use local projects via dependsOn
        Seq(
          "net.ghoula" % "valar-core_3" % "0.6.0",
          brotli4j
        )
      } else {
        // CI: use published artifacts from GitHub Packages
        Seq(
          "net.ghoula" % "eru-core_3" % eruVersion,
          "net.ghoula" % "eru-runtime_3" % eruVersion,
          "net.ghoula" % "valar-core_3" % "0.6.0",
          brotli4j
        )
      }
    }
  )
  .configure(p =>
    (eruCoreRef, eruRuntimeRef) match {
      case (Some(core), Some(runtime)) => p.dependsOn(core, runtime)
      case _ => p
    }
  )

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

// Examples and Benchmarks
lazy val examples = (project in file("examples"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-examples",
    description := "Examples and benchmarks for eru-http",
    publish / skip := true
  )
  .dependsOn(coreJVM % "compile->compile;test->test", client, server)

// Standalone benchmarks with fat JAR assembly
lazy val benchmarks = (project in file("benchmarks"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-benchmarks",
    description := "Standalone benchmark server for eru-http",
    publish / skip := true,
    assembly / assemblyJarName := "eru-http-benchmark-server.jar",
    assembly / mainClass := Some("benchmarks.HttpBenchmarkServer")
  )
  .dependsOn(coreJVM, server)

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
