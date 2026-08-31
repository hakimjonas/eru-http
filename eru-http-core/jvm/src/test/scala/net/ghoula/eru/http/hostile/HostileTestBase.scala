package net.ghoula.eru.http.hostile

import munit.FunSuite

/** Base class for hostile/adversarial tests — shared across core and downstream modules.
  *
  * Tests subject components to attack traffic (Slowloris, OOM, flood, exhaustion) and assert
  * survival under adversarial conditions. They are expensive and opt-in via `-Dhostile=true`;
  * default `sbt test` skips them (shown as "Skipped" in MUnit output so the signal stays honest).
  *
  * The server module depends on the core test tree (`coreJVM % "test->test"`) so server-level
  * hostile specs reuse this same base and the `ResourceSnapshot` helper.
  *
  * Run with:
  * {{{
  * sbt -Dhostile=true 'coreJVM/testOnly *hostile*'
  * sbt -Dhostile=true 'server/testOnly *hostile*'
  * sbt -Dhostile=true test   // all hostile specs across all modules
  * }}}
  *
  * See `HOSTILE_TESTING.md` at the repo root for full operator guide.
  */
abstract class HostileTestBase extends FunSuite {

  protected def requireHostileMode(): Unit = {
    assume(
      HostileTestBase.hostileEnabled,
      "Hostile tests are opt-in. Set -Dhostile=true to run."
    )
  }
}

object HostileTestBase {
  lazy val hostileEnabled: Boolean =
    sys.env.get("HOSTILE").contains("true") || System.getProperty("hostile") == "true"
}
