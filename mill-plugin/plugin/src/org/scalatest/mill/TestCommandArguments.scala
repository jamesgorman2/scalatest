package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] final case class TestCommandArguments(
    moduleSelectors: Seq[ModuleSelectorArgument],
    stdoutContent: StdoutContent,
    jsonOutputPath: Option[String],
    classGlobs: Seq[ClassGlob],
    frameworkArguments: Seq[ScalaTestArgument.Flagged]
)

private[mill] object TestCommandArguments {

  private val stdoutArguments: Map[String, StdoutContent] =
    Map("-oH" -> StdoutContent.HumanReadableOutput, "-o" -> StdoutContent.NoOutput)

  val jsonOutputArgument: String = "-fJ"

  val separator: String = "--"

  private val noArguments: TestCommandArguments =
    TestCommandArguments(Vector.empty, StdoutContent.HumanReadableOutput, jsonOutputPath = None, classGlobs = Nil, frameworkArguments = Nil)

  def parse(arguments: Seq[String]): Either[TestCommandFailure, TestCommandArguments] = {
    val (ownArguments, fromFirstSeparator) = arguments.span(_ != separator)
    val (classGlobs, fromSecondSeparator) = fromFirstSeparator.drop(1).span(_ != separator)
    for {
      parsed <- parseFrom(ownArguments.toList, noArguments, stdoutGiven = false)
      frameworkArguments <- FrameworkArguments.parse(fromSecondSeparator.drop(1))
    } yield parsed.copy(classGlobs = classGlobs.map(ClassGlob(_)), frameworkArguments = frameworkArguments)
  }

  private def parseFrom(arguments: List[String], parsed: TestCommandArguments, stdoutGiven: Boolean): Either[TestCommandFailure, TestCommandArguments] =
    arguments match {
      case Nil =>
        Right(parsed)
      case `jsonOutputArgument` :: path :: rest if parsed.jsonOutputPath.isEmpty =>
        parseFrom(rest, parsed.copy(jsonOutputPath = Some(path)), stdoutGiven)
      case `jsonOutputArgument` :: Nil if parsed.jsonOutputPath.isEmpty =>
        Left(TestCommandFailure.JsonOutputPathMissing)
      case argument :: rest if stdoutArguments.contains(argument) && !stdoutGiven =>
        parseFrom(rest, parsed.copy(stdoutContent = stdoutArguments(argument)), stdoutGiven = true)
      case argument :: _ if FrameworkArguments.isAllowedFlag(argument) =>
        Left(TestCommandFailure.FrameworkArgumentAmongOwnArguments(argument))
      case argument :: _ if argument.startsWith("-") =>
        Left(TestCommandFailure.UnexpectedArgument(argument))
      case moduleSelector :: rest =>
        parseFrom(rest, parsed.copy(moduleSelectors = parsed.moduleSelectors :+ ModuleSelectorArgument(moduleSelector)), stdoutGiven)
    }
}
