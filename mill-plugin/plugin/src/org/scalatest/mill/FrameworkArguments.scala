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

  def isAllowedFlag(argument: String): Boolean =
    ScalaTestArgument.split(Seq(argument)) match {
      case Seq(ScalaTestArgument.Flagged(flag, _)) => AllowedFlag.from(flag).isDefined
      case _ => false
    }

  private def isPassedOn(argument: ScalaTestArgument): Boolean =
    argument match {
      case flagged: ScalaTestArgument.Flagged =>
        !flagged.isShortOfValues && AllowedFlag.from(flagged.flag).exists(allowedFlag => acceptsValues(allowedFlag, flagged))
      case ScalaTestArgument.Unrecognised(_) => false
    }

  private def acceptsValues(allowedFlag: AllowedFlag, flagged: ScalaTestArgument.Flagged): Boolean =
    allowedFlag.acceptsJoinedValue(joinedValueOf(flagged)) && separateValuesOf(flagged).forall(allowedFlag.acceptsSeparateValue)

  private def joinedValueOf(flagged: ScalaTestArgument.Flagged): String = textOf(flagged).drop(flagged.flag.length)

  private def separateValuesOf(flagged: ScalaTestArgument.Flagged): Seq[String] = flagged.arguments.drop(1)

  private def textOf(argument: ScalaTestArgument): String =
    argument match {
      case ScalaTestArgument.Flagged(_, flagArguments) => flagArguments.head
      case ScalaTestArgument.Unrecognised(unrecognised) => unrecognised
    }
}
