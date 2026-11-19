package org.scalatest.consolidation

/** The 0-based position of a run's fork group among the groups its module's fork grouping gives, counting every group. */
opaque type ForkGroup = Int

object ForkGroup {

  def apply(position: Int): ForkGroup = position

  extension (forkGroup: ForkGroup) {
    def position: Int = forkGroup
  }
}
