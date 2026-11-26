package org.scalatest.mill

import coursier.core.Configuration
import coursier.core.Resolution
import coursier.core.VariantSelector
import mill.api.Task
import mill.javalib.BoundDep
import org.scalatest.consolidation.ScalaTestArgument

private[mill] final case class ModuleCheck(testArgsDefault: Seq[String], runtimeResolution: Resolution) {

  def runArguments(frameworkArguments: Seq[ScalaTestArgument.Flagged]): Either[TestCommandFailure, RunArguments] =
    ModuleScalaTestArguments.kept(testArgsDefault).map(kept => RunArguments.from(kept, frameworkArguments))
}

private[mill] object ModuleCheck {

  // The check evaluates no compile, so that no module's tests wait on another module's compile. `resolution`, unlike
  // the `classpath` that `resolvedRunMvnDeps` calls, fetches no artifact.
  def task(module: SelectedTestModule): Task[ModuleCheck] = {
    val testModule = module.module
    Task.Anon {
      val runtimeDependency =
        BoundDep(testModule.coursierDependencyTask().copy(variantSelector = VariantSelector.ConfigurationBased(Configuration.runtime)), force = false)
      ModuleCheck(testModule.testArgsDefault(), testModule.millResolver().resolution(Seq(runtimeDependency)))
    }
  }
}
