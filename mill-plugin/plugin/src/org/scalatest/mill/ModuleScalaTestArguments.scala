package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object ModuleScalaTestArguments {

  /** The arguments a run receives, or, where ScalaTest does not recognise any argument, every such argument. */
  def kept(moduleArguments: Seq[String]): Either[Seq[String], Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(moduleArguments)
    val unrecognised = split.collect { case ScalaTestArgument.Unrecognised(argument) => argument }
    if (unrecognised.nonEmpty)
      Left(unrecognised)
    else
      Right(split.collect { case flagged: ScalaTestArgument.Flagged if AllowedFlag.from(flagged).isDefined => flagged })
  }
}
