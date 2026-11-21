package org.scalatest.events

private[scalatest] opaque type SuiteId = String

private[scalatest] object SuiteId {

  def apply(value: String): SuiteId = value

  extension (suiteId: SuiteId) {
    def value: String = suiteId
  }
}
