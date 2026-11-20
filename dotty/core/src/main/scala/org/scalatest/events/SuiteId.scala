package org.scalatest.events

/** A suite's suite ID, as `Suite.suiteId` returns it. */
private[scalatest] opaque type SuiteId = String

private[scalatest] object SuiteId {

  def apply(value: String): SuiteId = value

  extension (suiteId: SuiteId) {
    def value: String = suiteId
  }
}
