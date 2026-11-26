package org.scalatest.mill

import coursier.core.Configuration
import coursier.core.Resolution
import coursier.core.VariantSelector
import mill.api.Task
import mill.javalib.BoundDep

private[mill] object ModuleCheckTask {

  // The check evaluates no compile, so that no module's tests wait on another module's compile. `resolution` fetches
  // no artifact, unlike the `classpath` that `resolvedRunMvnDeps` calls. It also takes none of the runtime resolution
  // parameters, so the root dependency names the runtime configuration itself.
  def of(module: SelectedTestModule): Task[ModuleCheck] = {
    val testModule = module.module
    Task.Anon {
      val runtimeDependency =
        BoundDep(testModule.coursierDependencyTask().copy(variantSelector = VariantSelector.ConfigurationBased(Configuration.runtime)), force = false)
      ModuleCheck(testModule.testArgsDefault(), resolvedDependencies(testModule.millResolver().resolution(Seq(runtimeDependency))))
    }
  }

  private def resolvedDependencies(resolution: Resolution): Set[ResolvedDependency] =
    resolution.retainedVersions.map { case (library, version) =>
      ResolvedDependency(Organization(library.organization.value), ArtifactName(library.name.value), LibraryVersion(version.repr))
    }.toSet
}
