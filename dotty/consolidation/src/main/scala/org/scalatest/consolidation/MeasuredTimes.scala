package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant

private[consolidation] final case class MeasuredTimes(startedAt: Option[Instant], endedAt: Option[Instant]) {

  def duration: Option[Duration] = for (start <- startedAt; end <- endedAt) yield Duration.ofMillis(end.toEpochMilli - start.toEpochMilli)
}

private[consolidation] object MeasuredTimes {

  val unmeasured: MeasuredTimes = MeasuredTimes(None, None)
}
