package org.scalatest.consolidation

/** An execution's index among its node's executions, as the last index of the execution's path gives it. */
private[consolidation] opaque type ExecutionIndex = Int

private[consolidation] object ExecutionIndex {

  def apply(value: Int): ExecutionIndex = value

  given ordering: Ordering[ExecutionIndex] = Ordering.Int
}
