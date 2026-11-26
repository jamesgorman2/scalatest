package org.scalatest.mill

import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ScalaTestArgument

private[mill] final case class ModuleCheck(testArgsDefault: Seq[String], runtimeDependencies: Set[ResolvedDependency]) {

  def runArguments(module: ModuleName, frameworkArguments: Seq[ScalaTestArgument.Flagged]): Either[Seq[CannotRunReason], RunArguments] = {
    val keptArguments = ModuleScalaTestArguments.kept(testArgsDefault)
    val withoutThisRelease =
      Option.when(!runtimeDependencies.contains(ResolvedDependency.forkCoreOfThisRelease))(CannotRunReason.WithoutThisRelease(module))
    val unrecognisedArguments = keptArguments.left.toOption.map(CannotRunReason.UnrecognisedArguments(module, _))
    val reasons = withoutThisRelease.toSeq ++ unrecognisedArguments
    keptArguments match {
      case Right(kept) if reasons.isEmpty => Right(RunArguments.from(kept, frameworkArguments))
      case _ => Left(reasons)
    }
  }
}
