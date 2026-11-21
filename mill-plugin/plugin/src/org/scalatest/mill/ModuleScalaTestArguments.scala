package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object ModuleScalaTestArguments {

  val allowedFlags: Seq[AllowedFlag] = Seq(
    AllowedFlag("-D", separateValueCount = 0),
    AllowedFlag("-n", separateValueCount = 1),
    AllowedFlag("-l", separateValueCount = 1),
    AllowedFlag("-P", separateValueCount = 0),
    AllowedFlag("-m", separateValueCount = 1),
    AllowedFlag("-w", separateValueCount = 1),
    AllowedFlag("-F", separateValueCount = 1),
    AllowedFlag("-T", separateValueCount = 1),
    AllowedFlag("-t", separateValueCount = 1),
    AllowedFlag("-z", separateValueCount = 1),
    AllowedFlag("-S", separateValueCount = 1),
    AllowedFlag("-W", separateValueCount = 2)
  )

  def allowedFlag(flag: String): Option[AllowedFlag] = allowedFlags.find(_.flag == flag)

  def forRun(moduleArguments: Seq[String]): Either[TestCommandFailure, Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    split.collectFirst { case ScalaTestArgument.Unrecognised(argument) => argument } match {
      case Some(argument) => Left(TestCommandFailure.UnrecognisedModuleArgument(argument))
      case None =>
        Right(split.collect { case flagged @ ScalaTestArgument.Flagged(flag, _) if allowedFlag(flag).isDefined => flagged })
    }
  }
}
