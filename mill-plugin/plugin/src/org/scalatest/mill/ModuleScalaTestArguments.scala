package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object ModuleScalaTestArguments {

  def kept(moduleArguments: Seq[String]): Either[Seq[String], Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    val unrecognised = split.collect { case ScalaTestArgument.Unrecognised(argument) => argument }
    if (unrecognised.nonEmpty)
      Left(unrecognised)
    else
      Right(split.collect { case flagged: ScalaTestArgument.Flagged if AllowedFlag.from(flagged).isDefined => flagged })
  }
}
