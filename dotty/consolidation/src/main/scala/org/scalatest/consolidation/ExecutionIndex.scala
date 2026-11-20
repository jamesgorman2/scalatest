package org.scalatest.consolidation

/** An execution's index among its node's executions: the last element of its execution path. */
private[consolidation] opaque type ExecutionIndex = Int

private[consolidation] object ExecutionIndex {

  def apply(value: Int): ExecutionIndex = value

  given ordering: Ordering[ExecutionIndex] = Ordering.Int
}
