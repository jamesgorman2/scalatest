package org.scalatest.consolidation

/** A run's fork group, by its 0-based position among every group its module's fork grouping gives. */
final case class ForkGroup private (position: Int)

object ForkGroup {

  def at(position: Int): ForkGroup = new ForkGroup(CountedFromZero.checked(position, "a fork group's position"))
}
