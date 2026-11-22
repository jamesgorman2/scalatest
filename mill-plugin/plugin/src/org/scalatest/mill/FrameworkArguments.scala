package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object FrameworkArguments {

  def parse(arguments: Seq[String]): Either[TestCommandFailure, Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(arguments)
    split.find(argument => !isPassedOn(argument)) match {
      case Some(argument) => Left(TestCommandFailure.FrameworkArgumentNotPassedOn(textOf(argument)))
      case None => Right(split.collect { case flagged: ScalaTestArgument.Flagged => flagged })
    }
  }

  private def isPassedOn(argument: ScalaTestArgument): Boolean =
    argument match {
      case flagged: ScalaTestArgument.Flagged =>
        !flagged.isShortOfValues && AllowedFlag.of(flagged).exists(_.acceptsValuesOf(flagged))
      case ScalaTestArgument.Unrecognised(_) => false
    }

  private def textOf(argument: ScalaTestArgument): String =
    argument match {
      case ScalaTestArgument.Flagged(_, flagArguments) => flagArguments.head
      case ScalaTestArgument.Unrecognised(unrecognised) => unrecognised
    }
}
