package org.scalatest.consolidation

import java.time.Instant
import java.time.format.DateTimeParseException
import org.scalatest.events.MalformedTestEventLogException
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEncoder
import org.scalatest.events.TestEventLogEvent

/** One event of a run's test event log, as [[RunEvent.decode]] returns it. Only the run's process can be read from it. */
opaque type RunEvent = DecodedRunEvent

private[scalatest] final case class DecodedRunEvent(event: TestEventLogEvent, runProcess: Option[RunProcess])

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
   * @throws MalformedTestEventLogException if the line cannot be decoded; its message names the line and why
   */
  def decode(line: String): RunEvent = {
    val event = TestEventLogDecoder.decode(line)
    try {
      of(event)
    } catch {
      case malformed: MalformedTestEventLogException => throw new MalformedTestEventLogException(line, malformed.reason)
    }
  }

  private[scalatest] def of(event: TestEventLogEvent): RunEvent =
    event match {
      case TestEventLogEvent.RunStarted(pid, startTime) =>
        DecodedRunEvent(event, Some(RunProcess(Pid(pid), startInstant(pid, startTime))))
      case _ =>
        DecodedRunEvent(event, None)
    }

  private def startInstant(pid: Long, startTime: String): Instant =
    try {
      Instant.parse(startTime)
    } catch {
      case _: DateTimeParseException => {
        val line = TestEventLogEncoder.runStarted(pid, startTime).stripSuffix("\n")
        throw new MalformedTestEventLogException(line, s"the run's start time is not an instant: $startTime")
      }
    }

  extension (event: RunEvent) {

    /** The run's process, if this is its `runStarted` event. */
    def runProcess: Option[RunProcess] = event.runProcess

    private[consolidation] def decoded: TestEventLogEvent = event.event
  }
}
