import Dependencies.*

/* ===== Build-wide Settings ===== */
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / organization := "net.ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / organizationName := "Hakim Ghoula"
ThisBuild / licenses := Seq("GPL-3.0-or-later" -> url("https://www.gnu.org/licenses/gpl-3.0.txt"))
ThisBuild / homepage := Some(uri("https://github.com/hakimjonas/eru-http"))
ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = uri("https://github.com/hakimjonas")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(uri("https://github.com/hakimjonas/eru-http"), "scm:git@github.com:hakimjonas/eru-http.git")
)

/* ===== Publishing Settings =====
 *
 * Maven Central (Central Portal) is the single publication target. Releases are staged locally
 * and uploaded with `sonaRelease` (sbt 2.x built-in Central Portal support); artifacts are signed
 * by sbt-pgp (`publishSigned`). Credentials are read automatically from SONATYPE_USERNAME /
 * SONATYPE_PASSWORD. The Eru dependency resolves from Maven Central, so no extra resolvers.
 */
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
/* Keep test artifacts out of the published registry. */
ThisBuild / Test / publishArtifact := false

/* ===== Compiler Settings ===== */
lazy val sharedScalacOptions = Seq(
  "-encoding",
  "UTF-8",
  "-feature",
  "-Werror",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wrecurse-with-default",
  "-Yexplicit-nulls",
  "-new-syntax",
  "-no-indent",
  /* Kept (divergence from Eru): enables Matchable pattern-selector lints that two sites
   * deliberately suppress via @nowarn. */
  "-source:future",
  /* Target Java 25 for Virtual Threads support. */
  "-release:25"
)

/* Less strict for tests. */
lazy val testScalacOptions = Seq(
  "-Wunused:imports"
)

/* ===== Common Settings ===== */
lazy val commonSettings = Seq(
  scalacOptions ++= sharedScalacOptions,
  Test / scalacOptions ++= testScalacOptions,
  javacOptions ++= Seq("--release", "25"),
  libraryDependencies += munit % Test,
  testFrameworks += new TestFramework("munit.Framework")
)

/* ===== Test Settings =====
 *
 * Divergence from Eru: tests fork and run with MUnit's `+l` (leak detection) argument.
 */
ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "+l")
ThisBuild / Test / parallelExecution := false
ThisBuild / Test / fork := true

/* ===== JVM Options for forked run/test JVMs =====
 *
 * Divergence from Eru: Eru has no run/test javaOptions here. This project defaults to ZGC.
 * Virtual-thread stacks live on the heap, so many fibers mean many GC roots; ZGC scans roots
 * concurrently and its pauses do not scale with thread count. Heap sizing matters as much as
 * the collector: size -Xms/-Xmx to the workload.
 */
val jvmRunOptions = {
  val heapSize = sys.env.getOrElse("HEAP_SIZE", sys.props.getOrElse("heap.size", "2g"))

  /* ZGC is the project default. G1GC works with virtual threads on current JDKs
   * (early-JDK-21 crash bugs were fixed in 21.0.2), but its stop-the-world young
   * collections scale root scanning with thread count.
   */
  val gcOptions = Seq("-XX:+UseZGC", "-server")

  gcOptions ++ Seq(
    s"-Xms$heapSize",
    s"-Xmx$heapSize",
    /* Direct memory for NIO buffers (8KB per connection). */
    "-XX:MaxDirectMemorySize=4g"
  )
}

ThisBuild / run / javaOptions ++= jvmRunOptions
ThisBuild / Test / javaOptions ++= jvmRunOptions

/* sbt 2 forks test JVMs with a clean environment, so HOSTILE=true on the command line never
 * reached HostileTestBase. Forward both opt-in spellings (env var and system property) to the
 * forked JVM; HOSTILE_TESTING.md documents both.
 */
ThisBuild / Test / envVars :=
  Option(System.getenv("HOSTILE")).map(v => Map("HOSTILE" -> v)).getOrElse(Map.empty)
ThisBuild / Test / javaOptions ++=
  Option(System.getProperty("hostile")).map(v => s"-Dhostile=$v").toList

/* Eru dependency version (published to Maven Central). */
val eruVersion = "1.0.0-alpha.1"

/* Custom clean task. */
lazy val cleanAll = taskKey[Unit]("Clean all target directories including all subprojects")

/* Root project. */
lazy val root = (project in file("."))
  .aggregate(coreJVM, client, server, acme, examples, docs) /* Skip coreJS until Eru has JS support. */
  .settings(commonSettings)
  .settings(
    name := "eru-http",
    publish / skip := true,
    /* Clean task that properly removes all target directories. */
    cleanAll := {
      val log = streams.value.log
      log.info("Cleaning all target directories...")

      clean.value

      import java.nio.file.{Files, Path, Paths}
      import java.nio.file.attribute.BasicFileAttributes
      import java.nio.file.FileVisitResult
      import java.nio.file.SimpleFileVisitor
      import scala.util.Try

      val rootPath = Paths.get(baseDirectory.value.getAbsolutePath)

      Try {
        Files.walkFileTree(
          rootPath,
          new SimpleFileVisitor[Path] {
            override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
              if (dir.getFileName.toString == "target") {
                log.info(s"Removing: ${dir.toString}")
                deleteDirectory(dir)
                FileVisitResult.SKIP_SUBTREE
              } else if (dir.getFileName.toString.startsWith(".")) {
                FileVisitResult.SKIP_SUBTREE
              } else {
                FileVisitResult.CONTINUE
              }
            }

            def deleteDirectory(path: Path): Unit = {
              if (Files.exists(path)) {
                Files
                  .walk(path)
                  .sorted(java.util.Comparator.reverseOrder())
                  .forEach(p => Try(Files.delete(p)))
              }
            }
          }
        )
      }.fold(
        err => log.error(s"Failed to clean directories: ${err.getMessage}"),
        _ => log.info("Successfully cleaned all target directories")
      )
    }
  )
  .settings(
    /* Build and format commands. */
    addCommandAlias("prepare", "scalafmtAll; scalafmtSbt; scalafixAll; Test/compile"),
    addCommandAlias("check", "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck"),

    /* testFull: sbt 2 caches successful test runs, so a plain `test` skips
     * unchanged suites; testFull always runs every suite in the module.
     */
    addCommandAlias(
      "testAll",
      "coreJVM/Test/testFull;client/Test/testFull;server/Test/testFull;acme/Test/testFull;examples/Test/testFull"
    ),

    /* Documentation commands. */
    addCommandAlias("docs", "docs/mdoc"),
    addCommandAlias("docsWatch", "docs/mdoc --watch"),
    addCommandAlias("checkExamples", "examples/compile"),

    /* Extras kept from the pre-alignment build. */
    addCommandAlias("fmt", "all scalafmtSbt scalafmtAll"),
    addCommandAlias("fix", "scalafixAll"),
    addCommandAlias("build", "compile; testAll")
  )

/* Core module with HTTP types and standards. JS support pending Eru JS implementation. */
lazy val coreJVM = (project in file("eru-http-core/jvm"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-core",
    description := "Core HTTP types and standards for Eru-based applications",
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "shared" / "src" / "main" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / ".." / "shared" / "src" / "test" / "scala",
    libraryDependencies ++= Seq(
      "net.ghoula" % "eru-core_3" % eruVersion,
      "net.ghoula" % "eru-runtime_3" % eruVersion,
      brotli4j
    )
  )

/* Future: Add coreJS when Eru supports Scala.js. */
/* lazy val core = crossProject(JVMPlatform, JSPlatform)... */

/* HTTP Client (JVM-only for now - Native blocking NIO + Virtual Threads). */
lazy val client = (project in file("eru-http-client"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-client",
    description := "Standards-compliant HTTP client built on Eru"
  )
  .dependsOn(coreJVM % "compile->compile;test->test", server % "test->compile")

/* HTTP Server (JVM-only for now - Native blocking NIO + Virtual Threads). */
lazy val server = (project in file("eru-http-server"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-server",
    description := "Standards-compliant HTTP server built on Eru",
    /* Caffeine backs PerIpGovernor's bounded tracking map. First non-JDK runtime dep in this
     * repo — needed for correct hard-cap fail-closed eviction without hand-rolling it in a
     * security-critical path.
     */
    libraryDependencies += caffeine
  )
  .dependsOn(coreJVM % "compile->compile;test->test")

/* ACME / Let's Encrypt provisioning (JVM-only: JDK crypto, keystore, background renewal).
 * Pure JDK — no new external dependencies. Depends on server for the HTTP-01 challenge
 * responder (a Middleware over eru-http's handler shape) and for its own tests.
 */
lazy val acme = (project in file("eru-http-acme"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-acme",
    description := "ACME (RFC 8555) certificate provisioning producing TlsConfig for Eru servers"
  )
  .dependsOn(coreJVM % "compile->compile;test->test", server, client)

/* Examples and Benchmarks. */
lazy val examples = (project in file("examples"))
  .settings(commonSettings)
  .settings(
    name := "eru-http-examples",
    description := "Examples for eru-http",
    publish / skip := true
  )
  .dependsOn(coreJVM % "compile->compile;test->test", client, server)

/* Documentation validation (mdoc). Code samples in docs-src are compiled
 * against the real API; mdoc fails the build if any sample drifts.
 */
lazy val docs = project
  .in(file("eru-http-docs"))
  .enablePlugins(MdocPlugin)
  .dependsOn(coreJVM, client, server)
  .settings(
    name := "eru-http-docs",
    publish / skip := true,
    /* mdoc depends on Undertow for its --watch browser preview, which this build never uses
     * (docs are verified with plain `sbt docs/mdoc`). Excluding it drops two flagged CVEs from
     * the resolved dependency graph; re-add if watch-mode preview is ever wanted.
     */
    excludeDependencies += ExclusionRule("io.undertow", "*"),
    mdocIn := file("docs-src"),
    mdocOut := (ThisBuild / baseDirectory).value / "eru-http-docs" / "target" / "mdoc",
    mdocVariables := Map(
      "VERSION" -> version.value,
      "SCALA_VERSION" -> scalaVersion.value
    ),
    mdoc := {
      val result: Unit = mdoc.evaluated
      val baseDir = (ThisBuild / baseDirectory).value
      val mdocOutputDir = mdocOut.value

      /* Files that should be copied to root. */
      val rootFiles = Seq(
        "README.md",
        "QUICKSTART.md",
        "MANIFESTO.md",
        "API.md",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "HOSTILE_TESTING.md",
        "CHANGELOG.md"
      )

      rootFiles.foreach { fileName =>
        val source = mdocOutputDir / fileName
        val target = baseDir / fileName
        if (source.exists()) {
          IO.copyFile(source, target)
          streams.value.log.info(s"Copied $fileName to project root")
        }
      }

      result
    }
  )

/* ===== Global Settings ===== */
Global / onChangedBuildSource := ReloadOnSourceChanges
