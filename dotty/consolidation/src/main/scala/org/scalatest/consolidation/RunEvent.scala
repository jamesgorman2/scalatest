package org.scalatest.consolidation

import java.time.Instant
import java.time.format.DateTimeParseException
import org.scalatest.events.MalformedTestEventLogException
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent

/** One event of a run's test event log, as [[RunEvent.decode]] returns it. Only the run's process can be read from it. */
opaque type RunEvent = DecodedRunEvent

/** A decoded event, with the process its `runStarted` event names, whose start time is parsed once, on decoding. */
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
   * @return the event the line holds, or why it cannot be decoded
   */
  def decode(line: String): Either[String, RunEvent] =
    try {
      of(TestEventLogDecoder.decode(line))
    } catch {
      case malformed: MalformedTestEventLogException => Left(malformed.reason)
    }

  /** The event, or why it cannot be one: a `runStarted` event whose start time is not an instant. */
  private[scalatest] def of(event: TestEventLogEvent): Either[String, RunEvent] =
    event match {
      case TestEventLogEvent.RunStarted(pid, startTime) =>
        parsedStartTime(startTime).map(instant => DecodedRunEvent(event, Some(RunProcess(Pid(pid), instant))))
      case _ =>
        Right(DecodedRunEvent(event, None))
    }

  private def parsedStartTime(startTime: String): Either[String, Instant] =
    try {
      Right(Instant.parse(startTime))
    } catch {
      case _: DateTimeParseException => Left(s"the run's start time is not an instant: $startTime")
    }

  extension (event: RunEvent) {

    /** The run's process, if this is its `runStarted` event. */
    def runProcess: Option[RunProcess] = event.runProcess

    private[consolidation] def decoded: TestEventLogEvent = event.event
  }
}
