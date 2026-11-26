package org.scalatest.mill

import org.scalatest.consolidation.Consolidation

/** A library, and the version of it, that a test module's dependency resolution kept. */
private[mill] final case class ResolvedDependency(organization: String, name: String, version: String)

private[mill] object ResolvedDependency {

  /** Core of the release of this fork that the Mill plugin belongs to, whose test event logs the plugin reads. */
  val forkCoreOfThisRelease: ResolvedDependency = ResolvedDependency("com.edc", "scalatest-core_3", Consolidation.scalaTestVersion)
}
