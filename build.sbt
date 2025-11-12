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

// Shared settings
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    munit % Test
  ),
  testFrameworks += new TestFramework("munit.Framework")
)

// Eru dependency version (for CI)
val eruVersion = "0.0.0+336-30cc42da"

// Check if we're in CI (GITHUB_TOKEN is set) or local development
val useLocalEru = !sys.env.contains("GITHUB_TOKEN") || sys.env("GITHUB_TOKEN").isEmpty

// Root project
lazy val root = (project in file("."))
  .settings(
    name := "eru-http",
    publish / skip := true
  )
  .aggregate(coreJVM, client, server) // Skip coreJS until Eru has JS support

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
          "net.ghoula" % "valar-core_3" % "0.5.0",
          brotli4j
        )
      } else {
        // CI: use published artifacts from GitHub Packages
        Seq(
          "net.ghoula" % "eru-core_3" % eruVersion,
          "net.ghoula" % "eru-runtime_3" % eruVersion,
          "net.ghoula" % "valar-core_3" % "0.5.0",
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
