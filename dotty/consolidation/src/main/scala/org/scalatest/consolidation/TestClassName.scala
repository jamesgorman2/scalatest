package org.scalatest.consolidation

/** The fully qualified name of a test class, such as `samples.AlphaSuite`. */
opaque type TestClassName = String

object TestClassName {

  def apply(value: String): TestClassName = value

  extension (name: TestClassName) {
    def value: String = name
  }
}
