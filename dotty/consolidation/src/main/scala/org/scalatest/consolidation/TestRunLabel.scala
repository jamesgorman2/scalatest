package org.scalatest.consolidation

/** The label naming a run's module. */
opaque type TestRunLabel = String

object TestRunLabel {

  def apply(value: String): TestRunLabel = value

  extension (label: TestRunLabel) {
    def value: String = label
  }
}
