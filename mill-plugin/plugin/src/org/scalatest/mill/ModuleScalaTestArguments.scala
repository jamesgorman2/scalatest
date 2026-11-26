package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object ModuleScalaTestArguments {

  def kept(moduleArguments: Seq[String]): Either[TestCommandFailure, Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    split.collectFirst { case ScalaTestArgument.Unrecognised(argument) => argument } match {
      case Some(argument) => Left(TestCommandFailure.UnrecognisedModuleArgument(argument))
      case None =>
        Right(split.collect { case flagged: ScalaTestArgument.Flagged if AllowedFlag.from(flagged).isDefined => flagged })
    }
  }
}
