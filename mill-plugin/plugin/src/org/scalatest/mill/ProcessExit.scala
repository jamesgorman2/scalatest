package org.scalatest.mill

import org.scalatest.consolidation.RunProcess
import scala.jdk.OptionConverters.RichOptional

private[mill] object ProcessExit {

  def hasExited(process: RunProcess): Boolean =
    ProcessHandle.of(process.pid.value).toScala.forall { handle =>
      // The OS may have reused the PID for a later process, which has a different start time.
      !handle.isAlive || !handle.info.startInstant.toScala.contains(process.startTime)
    }
}
