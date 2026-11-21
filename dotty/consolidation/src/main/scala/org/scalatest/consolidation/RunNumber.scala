package org.scalatest.consolidation

private[consolidation] opaque type RunNumber = Int

private[consolidation] object RunNumber {

  def apply(value: Int): RunNumber = value

  extension (number: RunNumber) {
    def value: Int = number
  }
}
