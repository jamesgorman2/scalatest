package org.scalatest.consolidation

opaque type TestClassName = String

object TestClassName {

  def apply(value: String): TestClassName = value

  def of(testClass: Class[?]): TestClassName = testClass.getName

  extension (name: TestClassName) {
    def value: String = name
  }
}
