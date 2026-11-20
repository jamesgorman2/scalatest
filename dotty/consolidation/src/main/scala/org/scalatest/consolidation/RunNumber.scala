package org.scalatest.consolidation

/** A run's number among its module's runs, counted from 1. */
private[consolidation] opaque type RunNumber = Int

private[consolidation] object RunNumber {

  def apply(value: Int): RunNumber = value

  extension (number: RunNumber) {
    def value: Int = number
  }
}
