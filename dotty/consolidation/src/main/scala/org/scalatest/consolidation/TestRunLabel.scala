package org.scalatest.consolidation

/** A module's name, such as `core.test`. */
opaque type TestRunLabel = String

object TestRunLabel {

  def apply(value: String): TestRunLabel = value

  extension (label: TestRunLabel) {
    def value: String = label
  }
}
