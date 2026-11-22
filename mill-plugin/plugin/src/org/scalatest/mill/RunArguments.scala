package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

/**
 * The ScalaTest arguments that each run of a module and the test command's discovery receive, as written, built so
 * that a framework argument takes effect over the module's own argument for the same setting.
 */
private[mill] final class RunArguments private (val written: Seq[String])

private[mill] object RunArguments {

  def from(moduleArguments: Seq[ScalaTestArgument.Flagged], frameworkArguments: Seq[ScalaTestArgument.Flagged]): RunArguments = {
    val replacedFlags: Set[String] =
      frameworkArguments.map(_.flag).filter(flag => AllowedFlag.from(flag).exists(_.frameworkArgumentReplacesModuleArguments)).toSet
    val keptModuleArguments = moduleArguments.filterNot(argument => replacedFlags.contains(argument.flag))
    new RunArguments((keptModuleArguments ++ frameworkArguments).flatMap(_.arguments))
  }
}
