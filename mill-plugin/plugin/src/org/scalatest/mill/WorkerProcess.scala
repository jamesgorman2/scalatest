package org.scalatest.mill

private[mill] enum WorkerProcess {
  case Started
  // Mill's test queue starts no process for a worker that finds its queue empty.
  case NotStarted
}
