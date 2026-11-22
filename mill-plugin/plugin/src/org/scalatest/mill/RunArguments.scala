package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

/**
 * The ScalaTest arguments, as command-line strings, that each run of a module and the test command's discovery receive:
 * the module's kept arguments, then the framework arguments. Where both set the same setting, ScalaTest takes the later
 * value or adds the two, except that a framework argument whose flag `AllowedFlag.frameworkArgumentReplacesModuleArguments`
 * replaces the module's arguments that have the same flag.
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
