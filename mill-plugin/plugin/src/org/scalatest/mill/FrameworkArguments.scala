package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] object FrameworkArguments {

  /** Splits the framework arguments by flag, failing on the first that is not a framework argument the test command passes on. */
  def parse(arguments: Seq[String]): Either[TestCommandFailure, Seq[ScalaTestArgument.Flagged]] = {
    val split = ScalaTestArgument.split(arguments)
    val lastIsShortOfValues = lastFlagIsShortOfValues(arguments)
    split.zipWithIndex.collectFirst {
      case (argument, index) if !isPassedOn(argument) || (lastIsShortOfValues && index == split.size - 1) => written(argument)
    } match {
      case Some(argument) => Left(TestCommandFailure.FrameworkArgumentNotPassedOn(argument))
      case None => Right(split.collect { case flagged: ScalaTestArgument.Flagged => flagged })
    }
  }

  /** Whether the argument is one of the flags a framework argument may hold, alone or with its value joined to it. */
  def isFlag(argument: String): Boolean =
    ScalaTestArgument.split(Seq(argument)) match {
      case Seq(ScalaTestArgument.Flagged(flag, _)) => ModuleScalaTestArguments.allowedFlags.contains(flag)
      case _ => false
    }

  private def isPassedOn(argument: ScalaTestArgument): Boolean =
    argument match {
      case ScalaTestArgument.Flagged("-D", Seq(written)) => isConfigMapEntry(written.drop(2))
      case ScalaTestArgument.Flagged("-P", Seq(written)) => written == "-P" || isThreadCount(written.drop(2))
      case ScalaTestArgument.Flagged(flag, _) => ModuleScalaTestArguments.allowedFlags.contains(flag)
      case ScalaTestArgument.Unrecognised(_) => false
    }

  private def isConfigMapEntry(entry: String): Boolean = {
    // ScalaTest's runner rejects a `-D` entry without a key, `=` and a value.
    val equalsAt = entry.indexOf('=')
    equalsAt > 0 && equalsAt < entry.length - 1
  }

  private def isThreadCount(count: String): Boolean = count.matches("[0-9]+") && count.toIntOption.exists(_ > 0)

  private def lastFlagIsShortOfValues(arguments: Seq[String]): Boolean =
    // The splitter gives a flag short of values, which only the last flag can be, the values it has. One more argument
    // therefore joins the last flag exactly where that flag is short of values, and otherwise stands apart.
    ScalaTestArgument.split(arguments :+ "").size == ScalaTestArgument.split(arguments).size

  private def written(argument: ScalaTestArgument): String =
    argument match {
      case ScalaTestArgument.Flagged(_, flagArguments) => flagArguments.head
      case ScalaTestArgument.Unrecognised(unrecognised) => unrecognised
    }
}
