package org.scalatest.consolidation

import org.scalatest.events.DecodedEvent
import org.scalatest.events.Location

/** What a suite's, scope's or test's opening or closing event records of that node's execution. */
private[consolidation] final case class NodeEvent(timeStamp: Long, duration: Option[Long], location: Option[Location], threadName: String)

private[consolidation] object NodeEvent {

  /** From a `SuiteStarting`, `ScopeOpened` or `TestStarting`. */
  def openingOf(event: DecodedEvent): Option[NodeEvent] =
    event match {
      case starting: DecodedEvent.SuiteStarting => Some(NodeEvent(starting.timeStamp, None, starting.location, starting.threadName))
      case opened: DecodedEvent.ScopeOpened => Some(NodeEvent(opened.timeStamp, None, opened.location, opened.threadName))
      case starting: DecodedEvent.TestStarting => Some(NodeEvent(starting.timeStamp, None, starting.location, starting.threadName))
      case _ => None
    }

  /** From an event that ends a suite's, scope's or test's execution. */
  def closingOf(event: DecodedEvent): Option[NodeEvent] =
    event match {
      case completed: DecodedEvent.SuiteCompleted => Some(NodeEvent(completed.timeStamp, completed.duration, completed.location, completed.threadName))
      case aborted: DecodedEvent.SuiteAborted => Some(NodeEvent(aborted.timeStamp, aborted.duration, aborted.location, aborted.threadName))
      case closed: DecodedEvent.ScopeClosed => Some(NodeEvent(closed.timeStamp, None, closed.location, closed.threadName))
      case pending: DecodedEvent.ScopePending => Some(NodeEvent(pending.timeStamp, None, pending.location, pending.threadName))
      case succeeded: DecodedEvent.TestSucceeded => Some(NodeEvent(succeeded.timeStamp, succeeded.duration, succeeded.location, succeeded.threadName))
      case failed: DecodedEvent.TestFailed => Some(NodeEvent(failed.timeStamp, failed.duration, failed.location, failed.threadName))
      case ignored: DecodedEvent.TestIgnored => Some(NodeEvent(ignored.timeStamp, None, ignored.location, ignored.threadName))
      case pending: DecodedEvent.TestPending => Some(NodeEvent(pending.timeStamp, pending.duration, pending.location, pending.threadName))
      case canceled: DecodedEvent.TestCanceled => Some(NodeEvent(canceled.timeStamp, canceled.duration, canceled.location, canceled.threadName))
      case _ => None
    }
}
