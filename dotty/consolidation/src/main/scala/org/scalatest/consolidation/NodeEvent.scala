package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import org.scalatest.events.DecodedEvent
import org.scalatest.events.Location

/** What a suite's, scope's or test's opening or closing event records of that node's execution. */
private[consolidation] final case class NodeEvent(at: Instant, duration: Option[Duration], location: Option[Location], threadName: String)

private[consolidation] object NodeEvent {

  def of(event: DecodedEvent.NodeOpening | DecodedEvent.NodeClosing): NodeEvent =
    NodeEvent(Instant.ofEpochMilli(event.timeStamp), event.duration.map(Duration.ofMillis), event.location, event.threadName)
}
