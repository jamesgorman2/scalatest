package org.scalatest.tools

/**
 * What a test expects of a run.
 *
 * @param reported what the run's reporters are told, as `ReportedEvents` describes it
 * @param eventLogInNotation the event log the run writes, in the notation `TestEventLogRuns.built` reads
 * @param eventLogInFormat the lines the run writes, holding only the keys the test checks
 * @param eventNamesOfEachNode the names of the events the event log assigns to each node, in order
 * @param linesTheDecoderReads the same event log with the keys the decoder needs and the test does not check
 */
private[tools] final case class StatedRun(
  reported: Vector[String],
  eventLogInNotation: String,
  eventLogInFormat: List[String],
  eventNamesOfEachNode: Map[NodePath, Vector[String]] = Map.empty,
  linesTheDecoderReads: Option[List[String]] = None
)
