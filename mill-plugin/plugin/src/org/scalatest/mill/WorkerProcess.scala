package org.scalatest.mill

/**
 * Whether a worker started a process. Mill's test queue starts none for a worker that finds its queue empty.
 */
private[mill] enum WorkerProcess {
  case Started
  case NotStarted
}
