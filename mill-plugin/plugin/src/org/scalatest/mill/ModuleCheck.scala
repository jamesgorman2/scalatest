package org.scalatest.mill

import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ScalaTestArgument

private[mill] final case class ModuleCheck(testArgsDefault: Seq[String], runtimeDependencies: Set[ResolvedDependency]) {

  def runArguments(module: ModuleName, frameworkArguments: Seq[ScalaTestArgument.Flagged]): Either[Seq[UnrunnableModule], RunArguments] = {
    val withoutThisRelease =
      Option.when(!runtimeDependencies.contains(ResolvedDependency.forkCoreOfThisRelease))(UnrunnableModule.WithoutThisRelease(module))
    ModuleScalaTestArguments.kept(testArgsDefault) match {
      case Right(kept) if withoutThisRelease.isEmpty =>
        Right(RunArguments.from(kept, frameworkArguments))
      case kept =>
        Left(withoutThisRelease.toSeq ++ kept.left.toOption.map(UnrunnableModule.UnrecognisedArguments(module, _)))
    }
  }
}
