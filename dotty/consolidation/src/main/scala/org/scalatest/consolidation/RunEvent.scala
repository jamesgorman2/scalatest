package org.scalatest.consolidation

import java.time.Instant
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent

/** One event of a run's test event log, as [[RunEvent.decode]] returns it. Only the run's process can be read from it. */
opaque type RunEvent = TestEventLogEvent

/** An operating-system process ID. */
opaque type Pid = Long

object Pid {

  def apply(value: Long): Pid = value

  extension (pid: Pid) {
    def value: Long = pid
  }
}

/** The process a run ran in, as its `runStarted` event names it. */
final case class RunProcess(pid: Pid, startTime: Instant)

object RunEvent {

  /**
   * Decodes one whole line of a run's test event log, given without its line end.
   *
   * @return the event the line holds, or why it cannot be decoded
   */
  def decode(line: String): Either[String, RunEvent] = TestEventLogDecoder.decode(line)

  private[scalatest] def apply(event: TestEventLogEvent): RunEvent = event

  extension (event: RunEvent) {

    /** The run's process, if this is its `runStarted` event. */
    def runProcess: Option[RunProcess] =
      event match {
        case TestEventLogEvent.RunStarted(pid, startTime) => Some(RunProcess(Pid(pid), startTime))
        case _ => None
      }

    private[consolidation] def decoded: TestEventLogEvent = event
  }
}
