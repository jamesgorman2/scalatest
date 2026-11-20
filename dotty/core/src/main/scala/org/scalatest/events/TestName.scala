package org.scalatest.events

private[scalatest] opaque type TestName = String

private[scalatest] object TestName {

  def apply(value: String): TestName = value

  extension (testName: TestName) {
    def value: String = testName
  }
}
