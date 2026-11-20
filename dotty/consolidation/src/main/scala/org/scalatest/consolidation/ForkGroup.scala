package org.scalatest.consolidation

/** The 0-based position of a run's fork group among the groups its module's fork grouping gives, counting every group. */
final case class ForkGroup private (position: Int)

object ForkGroup {

  /** The fork group at the position. Throws `IllegalArgumentException` where the position is negative. */
  def at(position: Int): ForkGroup = new ForkGroup(CountedFromZero.checked(position, "a fork group's position"))
}
