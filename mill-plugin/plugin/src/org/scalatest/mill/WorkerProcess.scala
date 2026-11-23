package org.scalatest.mill

private[mill] enum WorkerProcess {
  case Started
  // Mill's test queue starts no process for a worker that finds its queue empty.
  case NotStarted
  // Mill gives a failure for a process that exited without writing its `out.json`, as where its JVM died.
  case EndedWithoutResult

  def started: Boolean = this != NotStarted
}
