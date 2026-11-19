package org.scalatest.consolidation

/** The 0-based position of a run's fork group among the groups its module's fork grouping gives, counting every group. */
final case class ForkGroup private (position: Int)

object ForkGroup {

  /** The fork group at the position, or a reason where the position is negative. */
  def from(position: Int): Either[String, ForkGroup] =
    if (position >= 0) {
      Right(ForkGroup(position))
    } else {
      Left(s"a fork group's position must not be negative, but was $position")
    }
}
