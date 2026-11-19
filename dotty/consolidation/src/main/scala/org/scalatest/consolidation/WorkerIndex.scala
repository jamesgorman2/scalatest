package org.scalatest.consolidation

/** The 0-based index under which a run's worker was started within its fork group. A fork group's only worker has index 0. */
opaque type WorkerIndex = Int

object WorkerIndex {

  def apply(value: Int): WorkerIndex = value

  extension (index: WorkerIndex) {
    def value: Int = index
  }
}
