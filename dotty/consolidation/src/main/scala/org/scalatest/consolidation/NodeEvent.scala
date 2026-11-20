package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import org.scalatest.events.DecodedEvent
import org.scalatest.events.Location

/** What a suite's, scope's or test's opening or closing event records of that node's execution. */
private[consolidation] final case class NodeEvent(at: Instant, duration: Option[Duration], location: Option[Location], threadName: String)

private[consolidation] object NodeEvent {

  /** From a `SuiteStarting`, `ScopeOpened` or `TestStarting`. */
  def openingOf(event: DecodedEvent): Option[NodeEvent] =
    event match {
      case _: DecodedEvent.SuiteStarting | _: DecodedEvent.ScopeOpened | _: DecodedEvent.TestStarting => Some(of(event))
      case _ => None
    }

  /** From an event that ends a suite's, scope's or test's execution. */
  def closingOf(event: DecodedEvent): Option[NodeEvent] =
    event match {
      case _: DecodedEvent.SuiteCompleted | _: DecodedEvent.SuiteAborted | _: DecodedEvent.ScopeClosed | _: DecodedEvent.ScopePending |
          _: DecodedEvent.TestSucceeded | _: DecodedEvent.TestFailed | _: DecodedEvent.TestIgnored | _: DecodedEvent.TestPending |
          _: DecodedEvent.TestCanceled =>
        Some(of(event))
      case _ => None
    }

  private def of(event: DecodedEvent): NodeEvent =
    NodeEvent(Instant.ofEpochMilli(event.timeStamp), event.duration.map(Duration.ofMillis), event.location, event.threadName)
}
