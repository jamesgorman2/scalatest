package org.scalatest.events

/** A test's name within its suite, as `Suite.testNames` lists it. */
private[scalatest] opaque type TestName = String

private[scalatest] object TestName {

  def apply(value: String): TestName = value

  extension (testName: TestName) {
    def value: String = testName
  }
}
