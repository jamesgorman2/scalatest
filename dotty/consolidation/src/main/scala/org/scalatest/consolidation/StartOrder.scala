package org.scalatest.consolidation

/** Where an execution's start falls among the starts of every execution of its run. */
private[consolidation] opaque type StartOrder = Int

private[consolidation] object StartOrder {

  val first: StartOrder = 1

  given ordering: Ordering[StartOrder] = Ordering.Int

  extension (order: StartOrder) {
    def next: StartOrder = order + 1
  }
}
