package org.scalatest.mill

import java.nio.file.Path
import scala.util.control.NoStackTrace

private[mill] enum TestCommandFailure extends Exception with NoStackTrace {
  case JsonOutputPathMissing
  case UnexpectedArgument(argument: String)
  case NoModuleSelector
  case SeveralModuleSelectors(selectors: Seq[String])
  case NotOneJvmTestModule(selector: String, resolved: String)
  case UnresolvedModuleSelector(selector: String, error: String)
  case ModuleInputsNotEvaluated(module: String, error: String)
  case UnrecognisedModuleArgument(argument: String)
  case JsonOutputPathIsFolder(path: Path)
  case JsonOutputNotDeleted(path: Path, cause: Throwable)
  case JsonOutputNotWritten(path: Path, cause: Throwable, partialFileNotDeleted: Option[Throwable])

  override def getMessage: String = message

  def message: String = {
    val jsonOutputArgument = TestCommandArguments.jsonOutputArgument
    this match {
      case JsonOutputPathMissing =>
        s"The test command's $jsonOutputArgument takes a path, but none follows it: $jsonOutputArgument"
      case UnexpectedArgument(argument) =>
        s"The test command takes at most one of -oH or a bare -o, and at most one $jsonOutputArgument followed by a path, but was given: $argument"
      case NoModuleSelector =>
        "The test command takes one module selector, but was given none"
      case SeveralModuleSelectors(selectors) =>
        s"The test command takes one module selector, but was given: ${selectors.mkString(" ")}"
      case NotOneJvmTestModule(selector, resolved) =>
        s"Module selector $selector must resolve to one JVM test module, but resolved to: $resolved"
      case UnresolvedModuleSelector(selector, error) =>
        s"Module selector $selector resolves to no module: $error"
      case ModuleInputsNotEvaluated(_, error) =>
        error
      case UnrecognisedModuleArgument(argument) =>
        s"The module's ScalaTest arguments hold $argument, which ScalaTest does not recognise"
      case JsonOutputPathIsFolder(path) =>
        s"The test command cannot delete $path, the file $jsonOutputArgument names: it is a folder"
      case JsonOutputNotDeleted(path, cause) =>
        s"The test command cannot delete $path, the file $jsonOutputArgument names: $cause"
      case JsonOutputNotWritten(path, cause, partialFileNotDeleted) =>
        s"The test command cannot write its JSON output to $path, the file $jsonOutputArgument names: $cause" +
          partialFileNotDeleted.fold("")(deleteFailure => s"; it cannot delete the partly written file either: $deleteFailure")
    }
  }
}
