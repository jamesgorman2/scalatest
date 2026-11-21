package org.scalatest.events

private[scalatest] opaque type TestClassName = String

private[scalatest] object TestClassName {

  def apply(value: String): TestClassName = value

  extension (name: TestClassName) {
    def value: String = name
  }
}
