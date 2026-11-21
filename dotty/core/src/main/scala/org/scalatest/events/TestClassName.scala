package org.scalatest.events

/** A test class's binary name, as ScalaTest reports it, such as `samples.Outer$Inner`. */
private[scalatest] opaque type TestClassName = String

private[scalatest] object TestClassName {

  def apply(value: String): TestClassName = value

  extension (name: TestClassName) {
    def value: String = name
  }
}
