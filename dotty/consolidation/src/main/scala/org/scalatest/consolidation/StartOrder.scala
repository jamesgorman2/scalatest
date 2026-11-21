package org.scalatest.consolidation

private[consolidation] opaque type StartOrder = Int

private[consolidation] object StartOrder {

  val first: StartOrder = 1

  given ordering: Ordering[StartOrder] = Ordering.Int

  extension (order: StartOrder) {
    def next: StartOrder = order + 1
  }
}
