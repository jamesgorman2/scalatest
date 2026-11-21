package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object FrameworkArguments {

  def parse(arguments: Seq[String]): Either[TestCommandFailure, Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(arguments)
    split
      .filterNot(isPassedOn)
      .headOption
      .map(argument => TestCommandFailure.FrameworkArgumentNotPassedOn(textOf(argument)))
      .toLeft(split.collect { case flagged: ScalaTestArgument.Flagged => flagged })
  }

  def isAllowedFlag(argument: String): Boolean =
    ScalaTestArgument.split(Seq(argument)) match {
      case Seq(ScalaTestArgument.Flagged(flag, _)) => ModuleScalaTestArguments.allowedFlag(flag).isDefined
      case _ => false
    }

  private def isPassedOn(argument: ScalaTestArgument): Boolean =
    argument match {
      case ScalaTestArgument.Flagged("-D", Seq(text)) => isConfigMapEntry(text.drop(2))
      case ScalaTestArgument.Flagged("-P", Seq(text)) => text == "-P" || isThreadCount(text.drop(2))
      case flagged: ScalaTestArgument.Flagged => ModuleScalaTestArguments.allowedFlag(flagged.flag).exists(_.hasItsSeparateValues(flagged))
      case ScalaTestArgument.Unrecognised(_) => false
    }

  private def isConfigMapEntry(entry: String): Boolean = {
    // ScalaTest's runner rejects a `-D` entry without a key, `=` and a value.
    val equalsAt = entry.indexOf('=')
    equalsAt > 0 && equalsAt < entry.length - 1
  }

  private def isThreadCount(count: String): Boolean = count.matches("[0-9]+") && count.toIntOption.exists(_ > 0)

  private def textOf(argument: ScalaTestArgument): String =
    argument match {
      case ScalaTestArgument.Flagged(_, flagArguments) => flagArguments.head
      case ScalaTestArgument.Unrecognised(unrecognised) => unrecognised
    }
}
