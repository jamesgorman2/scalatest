package org.scalatest.consolidation

/** The 0-based index under which a run's worker was started within its fork group. A fork group's only worker has index 0. */
opaque type WorkerIndex = Int

object WorkerIndex {

  /** @throws IllegalArgumentException if the index is negative */
  def apply(index: Int): WorkerIndex = {
    require(index >= 0, s"a worker's index must not be negative, but was $index")
    index
  }

  extension (workerIndex: WorkerIndex) {
    def index: Int = workerIndex
  }
}
