package org.scalatest.consolidation

/** A run's fork group, identified by its 0-based position among all of its module's fork groups. */
final case class ForkGroup private (position: Int)

object ForkGroup {

  /** @throws IllegalArgumentException if the position is negative */
  def at(position: Int): ForkGroup = new ForkGroup(CountedFromZero.checked(position, "a fork group's position"))
}
