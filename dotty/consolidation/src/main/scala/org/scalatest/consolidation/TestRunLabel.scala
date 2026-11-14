package org.scalatest.consolidation

/** The test run label a run's suites are shown under, as the human-readable output writes it between brackets. */
opaque type TestRunLabel = String

object TestRunLabel {

  def apply(value: String): TestRunLabel = value

  extension (label: TestRunLabel) {
    def value: String = label
  }
}
