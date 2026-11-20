package org.scalatest.mill

/** A fork group's index among the groups Mill runs, as Mill pads it, which keys the group's workers in Mill's logging. */
private[mill] opaque type ForkGroupLabel = String

private[mill] object ForkGroupLabel {

  def apply(value: String): ForkGroupLabel = value

  extension (label: ForkGroupLabel) {
    def value: String = label
  }
}
