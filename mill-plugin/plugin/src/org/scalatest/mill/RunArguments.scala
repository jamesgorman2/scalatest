package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

/**
 * The ScalaTest arguments that each run of a module and the test command's discovery receive, built so that a
 * framework argument takes effect over the module's own argument for the same setting.
 */
private[mill] final case class RunArguments(arguments: Seq[ScalaTestArgument.Flagged]) {

  val written: Seq[String] = arguments.flatMap(_.arguments)
}

private[mill] object RunArguments {

  def from(moduleArguments: Seq[ScalaTestArgument.Flagged], frameworkArguments: Seq[ScalaTestArgument.Flagged]): RunArguments = {
    val replacedFlags: Set[AllowedFlag] = frameworkArguments.flatMap(argument => AllowedFlag.from(argument.flag)).filter(_.isTakenOnce).toSet
    RunArguments(moduleArguments.filterNot(argument => AllowedFlag.from(argument.flag).exists(replacedFlags.contains)) ++ frameworkArguments)
  }
}
