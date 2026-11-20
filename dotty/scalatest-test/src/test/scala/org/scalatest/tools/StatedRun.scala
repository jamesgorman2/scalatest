package org.scalatest.tools

private[tools] final case class StatedRun(
  reported: Vector[String],
  eventLogInNotation: String,
  eventLogInFormat: List[String],
  eventNamesOfEachNode: Map[NodePath, Vector[String]] = Map.empty,
  linesTheDecoderReads: Option[List[String]] = None
)
