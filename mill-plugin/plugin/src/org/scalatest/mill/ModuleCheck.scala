package org.scalatest.mill

import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ScalaTestArgument

private[mill] final case class ModuleCheck(testArgsDefault: Seq[String], runtimeDependencies: Set[ResolvedDependency]) {

  def runArguments(module: ModuleName, frameworkArguments: Seq[ScalaTestArgument.Flagged]): Either[Seq[CannotRunReason], RunArguments] = {
    val withoutThisRelease =
      Option.when(!runtimeDependencies.contains(ResolvedDependency.forkCoreOfThisRelease))(CannotRunReason.WithoutThisRelease(module))
    ModuleScalaTestArguments.kept(testArgsDefault) match {
      case Left(unrecognised) =>
        Left(withoutThisRelease.toSeq :+ CannotRunReason.UnrecognisedArguments(module, unrecognised))
      case Right(kept) =>
        withoutThisRelease.map(Seq(_)).toLeft(RunArguments.from(kept, frameworkArguments))
    }
  }
}
