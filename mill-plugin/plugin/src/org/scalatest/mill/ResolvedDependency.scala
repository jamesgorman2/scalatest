package org.scalatest.mill

import org.scalatest.consolidation.Consolidation

private[mill] final case class ResolvedDependency(organization: Organization, name: ArtifactName, version: LibraryVersion) {

  def text: String = s"${organization.value}:${name.value} at ${version.value}"
}

private[mill] object ResolvedDependency {

  /** Core of the release of this fork that the Mill plugin belongs to, whose test event logs the plugin reads. */
  val forkCoreOfThisRelease: ResolvedDependency =
    ResolvedDependency(Organization("com.edc"), ArtifactName("scalatest-core_3"), LibraryVersion(Consolidation.scalaTestVersion))
}
