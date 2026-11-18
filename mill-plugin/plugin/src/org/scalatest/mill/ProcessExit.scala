package org.scalatest.mill

import org.scalatest.consolidation.RunProcess
import scala.jdk.OptionConverters.RichOptional

private[mill] object ProcessExit {

  // The PID may since name a later process, which has another start time.
  def hasExited(process: RunProcess): Boolean =
    ProcessHandle.of(process.pid.value).toScala.forall { handle =>
      !handle.isAlive || !handle.info.startInstant.toScala.contains(process.startTime)
    }
}
