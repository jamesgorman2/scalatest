package org.scalatest.mill

private[mill] opaque type ArtifactName = String

private[mill] object ArtifactName {

  def apply(value: String): ArtifactName = value

  extension (name: ArtifactName) {
    def value: String = name
  }
}
