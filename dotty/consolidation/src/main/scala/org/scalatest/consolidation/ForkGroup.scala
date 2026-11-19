package org.scalatest.consolidation

/** The 0-based position of a run's fork group among the groups its module's fork grouping gives, counting every group. */
opaque type ForkGroup = Int

object ForkGroup {

  /** @throws IllegalArgumentException if the position is negative */
  def apply(position: Int): ForkGroup = {
    require(position >= 0, s"a fork group's position must not be negative, but was $position")
    position
  }

  extension (forkGroup: ForkGroup) {
    def position: Int = forkGroup
  }
}
