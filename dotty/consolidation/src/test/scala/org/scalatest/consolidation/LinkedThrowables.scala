package org.scalatest.consolidation

import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.TestEventLogEvent

private[consolidation] object LinkedThrowables {

  def relinked(
    throwable: DecodedThrowable,
    cause: => Option[DecodedThrowable],
    suppressed: => IndexedSeq[DecodedThrowable] = Vector.empty
  ): DecodedThrowable =
    new DecodedThrowable(
      throwable.className,
      throwable.message,
      throwable.stackDepth,
      throwable.isPropertyCheckFailure,
      throwable.stackTrace,
      cause,
      suppressed
    )

  def withFailuresRelinked(events: Vector[TestEventLogEvent])(relink: DecodedThrowable => DecodedThrowable): Vector[TestEventLogEvent] =
    events.map {
      case TestEventLogEvent.ScalaTestEvent(execution, failed: DecodedEvent.TestFailed) =>
        TestEventLogEvent.ScalaTestEvent(execution, failed.copy(throwable = failed.throwable.map(relink)))
      case other => other
    }
}
