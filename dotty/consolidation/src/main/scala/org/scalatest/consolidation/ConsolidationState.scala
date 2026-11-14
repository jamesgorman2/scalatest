package org.scalatest.consolidation

/** Whether a consolidation has ended, and whether anything added to it fails the test command. */
enum ConsolidationState(val ended: Boolean, val failed: Boolean) {
  case Open extends ConsolidationState(false, false)
  case OpenFailed extends ConsolidationState(false, true)
  case Ended extends ConsolidationState(true, false)
  case EndedFailed extends ConsolidationState(true, true)

  private[consolidation] def withFailure: ConsolidationState = if (ended) EndedFailed else OpenFailed

  private[consolidation] def withEnd: ConsolidationState = if (failed) EndedFailed else Ended
}
