package org.scalatest.consolidation

import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.TestEventLogEvent

private[consolidation] object LinkedThrowables {

  /** A copy of `throwable` with this cause and these suppressed throwables, which may refer back to the copy. */
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

  /** The events, with each `TestFailed` throwable replaced by what `relink` makes of it. */
  def withFailuresRelinked(events: Vector[TestEventLogEvent])(relink: DecodedThrowable => DecodedThrowable): Vector[TestEventLogEvent] =
    events.map {
      case TestEventLogEvent.ScalaTestEvent(execution, failed: DecodedEvent.TestFailed) =>
        TestEventLogEvent.ScalaTestEvent(execution, failed.copy(throwable = failed.throwable.map(relink)))
      case other => other
    }
}
