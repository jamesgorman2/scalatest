package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object ModuleScalaTestArguments {

  val allowedFlags: Seq[String] = Seq("-D", "-n", "-l", "-P", "-m", "-w", "-F", "-T", "-t", "-z", "-S", "-W")

  def forRun(moduleArguments: Seq[String]): Either[TestCommandFailure, Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    split.collectFirst { case ScalaTestArgument.Unrecognised(argument) => argument } match {
      case Some(argument) => Left(TestCommandFailure.UnrecognisedModuleArgument(argument))
      case None =>
        Right(split.collect { case flagged @ ScalaTestArgument.Flagged(flag, _) if allowedFlags.contains(flag) => flagged })
    }
  }
}
