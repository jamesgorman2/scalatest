package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant

/** The start and end time of the root or a module, each where the consolidation's caller measured it. */
private[consolidation] final case class MeasuredTimes(startedAt: Option[Instant], endedAt: Option[Instant]) {

  def duration: Option[Duration] = for (start <- startedAt; end <- endedAt) yield Duration.ofMillis(end.toEpochMilli - start.toEpochMilli)
}

private[consolidation] object MeasuredTimes {

  val unmeasured: MeasuredTimes = MeasuredTimes(None, None)
}
