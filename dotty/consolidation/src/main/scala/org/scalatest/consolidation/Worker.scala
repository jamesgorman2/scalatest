package org.scalatest.consolidation

/** The worker a run ran in: its fork group, and its index within that fork group. */
final case class Worker(forkGroup: ForkGroup, index: WorkerIndex)
