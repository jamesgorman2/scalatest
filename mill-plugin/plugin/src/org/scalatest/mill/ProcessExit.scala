package org.scalatest.mill

import org.scalatest.consolidation.RunProcess
import scala.jdk.OptionConverters.RichOptional

private[mill] object ProcessExit {

  /** Whether a run's process has exited: its PID is gone, has no start time, or names a later process. */
  def hasExited(process: RunProcess): Boolean =
    ProcessHandle.of(process.pid.value).toScala.forall { handle =>
      !handle.isAlive || !handle.info.startInstant.toScala.contains(process.startTime)
    }
}
