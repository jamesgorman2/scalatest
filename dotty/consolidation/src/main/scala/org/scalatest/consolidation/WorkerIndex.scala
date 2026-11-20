package org.scalatest.consolidation

/** The 0-based index under which a run's worker was started within its fork group. A fork group's only worker has index 0. */
final case class WorkerIndex private (value: Int)

object WorkerIndex {

  /** @throws IllegalArgumentException if the value is negative */
  def apply(value: Int): WorkerIndex = new WorkerIndex(CountedFromZero.checked(value, "a worker index"))
}
