package org.scalatest.mill

private[mill] enum WorkerProcess {
  case EndedWithResult
  // Mill gives a failure for a process that exited without writing its `out.json`, as where its JVM died.
  case EndedWithoutResult
  // Mill's test queue starts no process for a worker that finds its queue empty.
  case NotStarted

  def started: Boolean = this != NotStarted
}
