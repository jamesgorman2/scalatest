package org.scalatest.mill

import java.nio.file.Path
import scala.util.control.NoStackTrace

private[mill] enum TestCommandFailure extends Exception with NoStackTrace {
  case JsonOutputPathMissing
  case UnexpectedArgument(argument: String)
  case FrameworkArgumentAmongOwnArguments(argument: String)
  case FrameworkArgumentNotPassedOn(argument: String)
  case NoModuleSelector
  case SeveralModuleSelectors(selectors: Seq[ModuleSelectorArgument])
  case NotOneJvmTestModule(selector: ModuleSelectorArgument, resolved: String)
  case UnresolvedModuleSelector(selector: ModuleSelectorArgument, error: String)
  case ModuleInputsNotEvaluated(error: String)
  case UnrecognisedModuleArgument(argument: String)
  case JsonOutputPathIsFolder(path: Path)
  case JsonOutputNotDeleted(path: Path, cause: Throwable)
  case JsonOutputNotWritten(path: Path, cause: Throwable, partlyWrittenFileDeleteFailure: Option[Throwable])

  override def getMessage: String = message

  def message: String = {
    val jsonOutputArgument = TestCommandArguments.jsonOutputArgument
    val separator = TestCommandArguments.separator
    val frameworkFlags = EnglishList.of(AllowedFlag.values.toSeq.map(_.text))
    this match {
      case JsonOutputPathMissing =>
        s"The test command's $jsonOutputArgument takes a path, but none follows it: $jsonOutputArgument"
      case UnexpectedArgument(argument) =>
        s"The test command takes at most one of -oH or a bare -o, and at most one $jsonOutputArgument followed by a path, but was given: $argument"
      case FrameworkArgumentAmongOwnArguments(argument) =>
        s"The test command takes ScalaTest's $frameworkFlags only as framework arguments, after a second $separator, but was given among its own arguments: $argument"
      case FrameworkArgumentNotPassedOn(argument) =>
        s"The test command takes as framework arguments only ScalaTest's $frameworkFlags, each with the values ScalaTest's runner takes with it, but was given: $argument"
      case NoModuleSelector =>
        "The test command takes one module selector, but was given none"
      case SeveralModuleSelectors(selectors) =>
        s"The test command takes one module selector, but was given: ${selectors.map(_.text).mkString(" ")}"
      case NotOneJvmTestModule(selector, resolved) =>
        s"Module selector ${selector.text} must resolve to one JVM test module, but resolved to: $resolved"
      case UnresolvedModuleSelector(selector, error) =>
        s"Module selector ${selector.text} resolves to no module: $error"
      case ModuleInputsNotEvaluated(error) =>
        error
      case UnrecognisedModuleArgument(argument) =>
        s"The module's ScalaTest arguments hold $argument, which ScalaTest does not recognise"
      case JsonOutputPathIsFolder(path) =>
        s"The test command cannot delete $path, the file $jsonOutputArgument names: it is a folder"
      case JsonOutputNotDeleted(path, cause) =>
        s"The test command cannot delete $path, the file $jsonOutputArgument names: $cause"
      case JsonOutputNotWritten(path, cause, partlyWrittenFileDeleteFailure) =>
        s"The test command cannot write its JSON output to $path, the file $jsonOutputArgument names: $cause" +
          partlyWrittenFileDeleteFailure.fold("")(deleteFailure => s"; it cannot delete the partly written file either: $deleteFailure")
    }
  }
}
