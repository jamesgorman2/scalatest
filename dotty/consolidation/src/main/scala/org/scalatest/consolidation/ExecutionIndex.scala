package org.scalatest.consolidation

private[consolidation] opaque type ExecutionIndex = Int

private[consolidation] object ExecutionIndex {

  def apply(value: Int): ExecutionIndex = value

  given ordering: Ordering[ExecutionIndex] = Ordering.Int
}
