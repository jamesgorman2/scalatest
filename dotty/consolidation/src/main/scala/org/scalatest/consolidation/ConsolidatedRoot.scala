package org.scalatest.consolidation

/** A module of the consolidation: its run nodes, in ascending order by number, and its times. */
private[consolidation] final case class ConsolidatedModule(label: TestRunLabel, times: MeasuredTimes, runs: Vector[RunAssembly])

/** The node tree of an ended consolidation, from which every output is written. Its modules are in ascending order by name. */
private[consolidation] final case class ConsolidatedRoot(
  buildTool: Option[BuildTool],
  moduleSelectors: Seq[ModuleSelector],
  failures: Vector[String],
  times: MeasuredTimes,
  modules: Vector[ConsolidatedModule],
  output: Vector[CapturedLine]
) {

  def runs: Vector[RunAssembly] = modules.flatMap(_.runs)
}
