import sbt.*
import Keys.*

/** Global Scala 3 build settings applied to all projects.
  *
  * Disables pipelined compilation because Scala 3 inline macros used in tests may fail to load when
  * pipelining is enabled.
  */
object Scala3BuildSettings extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  override def buildSettings: Seq[Def.Setting[?]] = Seq(
    ThisBuild / usePipelining := false
  )
}
