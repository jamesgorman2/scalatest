package org.scalatest.consolidation

/** A run of a module, numbered from 1 among its module's runs. */
private[consolidation] final case class ConsolidatedRun(number: RunNumber, assembled: AssembledRun)

/** A module of the consolidation: its run nodes, in ascending order by number, and its times. */
private[consolidation] final case class ConsolidatedModule(label: TestRunLabel, times: MeasuredTimes, runs: Vector[ConsolidatedRun])

private[consolidation] object ConsolidatedModule {

  /**
   * Numbers the module's runs from 1 in ascending order of fork group, then of worker index. A run given no worker comes
   * before every run given one, and runs that tie keep the order they were added in.
   *
   * @param runs the module's runs, in the order they were added
   */
  def of(label: TestRunLabel, times: MeasuredTimes, runs: Vector[AssembledRun]): ConsolidatedModule = {
    val numbered = runs
      .sortBy(_.worker)(AssembledRun.numberingOrder)
      .zipWithIndex
      .map((run, index) => ConsolidatedRun(RunNumber(index + 1), run))
    ConsolidatedModule(label, times, numbered)
  }
}

/** The node tree of an ended consolidation, from which every output is written. Its modules are in ascending order by name. */
private[consolidation] final case class ConsolidatedRoot(
  buildTool: Option[BuildTool],
  moduleSelectors: Seq[ModuleSelector],
  failures: Vector[String],
  times: MeasuredTimes,
  modules: Vector[ConsolidatedModule],
  output: Vector[CapturedLine]
) {

  def runs: Vector[AssembledRun] = modules.flatMap(_.runs.map(_.assembled))
}
