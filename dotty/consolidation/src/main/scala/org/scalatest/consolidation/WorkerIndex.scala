package org.scalatest.consolidation

/** The 0-based index under which a run's worker was started within its fork group. A fork group's only worker has index 0. */
final case class WorkerIndex private (value: Int)

object WorkerIndex {

  /** The worker index of the value, or a reason where the value is negative. */
  def from(value: Int): Either[String, WorkerIndex] =
    if (value >= 0) {
      Right(WorkerIndex(value))
    } else {
      Left(s"a worker index must not be negative, but was $value")
    }
}
