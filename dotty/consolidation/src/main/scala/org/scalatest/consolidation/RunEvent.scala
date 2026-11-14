package org.scalatest.consolidation

import java.time.Instant
import java.time.format.DateTimeParseException
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent

/** One event of a run's test event log, as **Decode** yields it. Only the run's process can be read from it. */
opaque type RunEvent = TestEventLogEvent

/** The process a run ran in, which the run's `runStarted` event names. */
final case class RunProcess(pid: Long, startTime: Instant)

object RunEvent {

  /**
   * Decodes one whole line of a run's test event log, given without its line end.
   *
   * @return the event the line holds, or why it cannot be decoded
   */
  def decode(line: String): Either[String, RunEvent] =
    TestEventLogDecoder.decode(line).flatMap { event =>
      event match {
        case TestEventLogEvent.RunStarted(_, startTime) =>
          parsedStartTime(startTime).map(_ => event)
        case _ =>
          Right(event)
      }
    }

  private[scalatest] def apply(event: TestEventLogEvent): RunEvent = event

  private def parsedStartTime(startTime: String): Either[String, Instant] =
    try {
      Right(Instant.parse(startTime))
    } catch {
      case _: DateTimeParseException => Left(s"the run's start time is not an instant: $startTime")
    }

  extension (event: RunEvent) {

    /** The run's process, where this is the run's `runStarted` event. */
    def runStarted: Option[RunProcess] =
      event match {
        case TestEventLogEvent.RunStarted(pid, startTime) => Some(RunProcess(pid, Instant.parse(startTime)))
        case _ => None
      }

    private[consolidation] def decoded: TestEventLogEvent = event
  }
}
