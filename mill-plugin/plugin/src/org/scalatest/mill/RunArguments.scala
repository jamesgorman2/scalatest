package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

/**
 * The ScalaTest arguments, as command-line strings, that each run of a module and the test command's discovery receive:
 * the module's kept arguments, then the framework arguments. Where both set the same setting, ScalaTest takes the later
 * value or uses both. For a flag for which `AllowedFlag.frameworkArgumentReplacesModuleArguments` holds, the framework
 * argument instead replaces each module argument that has that flag.
 */
private[mill] final class RunArguments private (val commandLine: Seq[String])

private[mill] object RunArguments {

  def from(moduleArguments: Seq[ScalaTestArgument.Flagged], frameworkArguments: Seq[ScalaTestArgument.Flagged]): RunArguments = {
    val replacedFlags: Set[AllowedFlag] =
      frameworkArguments.flatMap(AllowedFlag.from).filter(_.frameworkArgumentReplacesModuleArguments).toSet
    val unreplacedModuleArguments =
      moduleArguments.filterNot(argument => AllowedFlag.from(argument).exists(replacedFlags.contains))
    new RunArguments((unreplacedModuleArguments ++ frameworkArguments).flatMap(_.arguments))
  }
}
