package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object RunArguments {

  /**
   * The ScalaTest arguments each run of a module receives before those the test command adds, and that the test
   * command's discovery receives: the module's own arguments, then the framework arguments in the order given. A
   * framework argument whose flag ScalaTest's runner takes only once replaces every module argument with that flag, so
   * that the framework argument takes effect.
   */
  def from(
      moduleArguments: Seq[ScalaTestArgument.Flagged],
      frameworkArguments: Seq[ScalaTestArgument.Flagged]
  ): Seq[ScalaTestArgument.Flagged] = {
    val replacedFlags = frameworkArguments.map(_.flag).filter(flag => AllowedFlag.from(flag).exists(_.isTakenOnce)).toSet
    moduleArguments.filterNot(argument => replacedFlags.contains(argument.flag)) ++ frameworkArguments
  }
}
