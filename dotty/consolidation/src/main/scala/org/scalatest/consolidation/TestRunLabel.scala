package org.scalatest.consolidation

/** The test run label: a run's module, which heads the module's line. The human-readable output writes it in brackets. */
opaque type TestRunLabel = String

object TestRunLabel {

  def apply(value: String): TestRunLabel = value

  extension (label: TestRunLabel) {
    def value: String = label
  }
}
