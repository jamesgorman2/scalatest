package org.scalatest.mill

/** @param jsonOutputPath the path `-fJ` names, as given */
private[mill] final case class TestCommandArguments(
    moduleSelectors: Seq[String],
    humanReadableOnStdout: Boolean,
    jsonOutputPath: Option[String]
)

private[mill] object TestCommandArguments {

  private val stdoutArguments: Map[String, Boolean] = Map("-oH" -> true, "-o" -> false)

  private val jsonOutputArgument: String = "-fJ"

  def parse(arguments: Seq[String]): Either[String, TestCommandArguments] =
    parseFrom(arguments.toList, TestCommandArguments(Vector.empty, humanReadableOnStdout = true, jsonOutputPath = None), stdoutGiven = false)

  private def parseFrom(arguments: List[String], parsed: TestCommandArguments, stdoutGiven: Boolean): Either[String, TestCommandArguments] =
    arguments match {
      case Nil =>
        Right(parsed)
      case `jsonOutputArgument` :: path :: rest if parsed.jsonOutputPath.isEmpty =>
        parseFrom(rest, parsed.copy(jsonOutputPath = Some(path)), stdoutGiven)
      case `jsonOutputArgument` :: Nil if parsed.jsonOutputPath.isEmpty =>
        Left(s"The test command's $jsonOutputArgument takes a path, but none follows it: $jsonOutputArgument")
      case argument :: rest if stdoutArguments.contains(argument) && !stdoutGiven =>
        parseFrom(rest, parsed.copy(humanReadableOnStdout = stdoutArguments(argument)), stdoutGiven = true)
      case argument :: _ if argument.startsWith("-") =>
        Left(s"The test command takes at most one of -oH or a bare -o, and at most one $jsonOutputArgument followed by a path, but was given: $argument")
      case moduleSelector :: rest =>
        parseFrom(rest, parsed.copy(moduleSelectors = parsed.moduleSelectors :+ moduleSelector), stdoutGiven)
    }
}
