package org.scalatest.mill

import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ScalaTestArgument

private[mill] enum CannotRunReason {
  case NotJvm(module: ModuleName)
  case WithoutThisRelease(module: ModuleName)
  case UnrecognisedArguments(module: ModuleName, arguments: Seq[ScalaTestArgument.Unrecognised])

  def module: ModuleName

  def line: String =
    this match {
      case NotJvm(module) =>
        s"${module.value} is a Scala.js or Native test module"
      case WithoutThisRelease(module) =>
        s"${module.value} does not depend on ${ResolvedDependency.forkCoreOfThisRelease.text}, " +
          "the release of this fork that the Mill plugin belongs to"
      case UnrecognisedArguments(module, arguments) =>
        s"${module.value}'s testArgsDefault holds ${EnglishList.of(arguments.map(_.argument))}, which ScalaTest does not recognise"
    }
}
