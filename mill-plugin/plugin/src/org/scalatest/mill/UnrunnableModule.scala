package org.scalatest.mill

import org.scalatest.consolidation.ModuleName

/** A reason the test command cannot run a selected test module. */
private[mill] enum UnrunnableModule {
  case NotJvm(module: ModuleName)
  case WithoutThisRelease(module: ModuleName)
  case UnrecognisedArguments(module: ModuleName, arguments: Seq[String])

  def module: ModuleName

  def line: String =
    this match {
      case NotJvm(module) =>
        s"${module.value} is a Scala.js or Native test module"
      case WithoutThisRelease(module) =>
        val forkCore = ResolvedDependency.forkCoreOfThisRelease
        s"${module.value} does not depend on ${forkCore.organization}:${forkCore.name} at ${forkCore.version}, " +
          "the release of this fork that the Mill plugin belongs to"
      case UnrecognisedArguments(module, arguments) =>
        s"${module.value}'s testArgsDefault holds ${EnglishList.of(arguments)}, which ScalaTest does not recognise"
    }
}
