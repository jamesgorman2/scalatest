package org.scalatest.mill

private[mill] opaque type ForkGroupLabel = String

private[mill] object ForkGroupLabel {

  def of(index: Int, count: Int): ForkGroupLabel =
    // Copies Mill 1.3.1's `testForked`, which keys the group's workers' log lines by the label.
    PaddedIndex.of(index, count)

  extension (label: ForkGroupLabel) {
    def value: String = label
  }
}
