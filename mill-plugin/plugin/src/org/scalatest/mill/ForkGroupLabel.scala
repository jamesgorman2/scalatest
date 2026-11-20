package org.scalatest.mill

private[mill] opaque type ForkGroupLabel = String

private[mill] object ForkGroupLabel {

  def of(indexAmongGroupsRun: Int, groupsRun: Int): ForkGroupLabel = PaddedIndex.of(indexAmongGroupsRun, groupsRun)

  extension (label: ForkGroupLabel) {
    def value: String = label
  }
}
