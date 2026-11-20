package org.scalatest.mill

// The group's zero-padded index among the fork groups Mill runs; Mill keys the group's workers' log lines by it.
private[mill] opaque type ForkGroupLabel = String

private[mill] object ForkGroupLabel {

  def apply(value: String): ForkGroupLabel = value

  extension (label: ForkGroupLabel) {
    def value: String = label
  }
}
