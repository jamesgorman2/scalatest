package org.scalatest.consolidation

private[consolidation] final case class ConsolidatedRun(number: RunNumber, assembled: AssembledRun)

private[consolidation] final case class ConsolidatedModule(
  name: ModuleName,
  times: MeasuredTimes,
  runs: Vector[ConsolidatedRun],
  failures: Vector[String],
  output: Vector[CapturedLine]
)

private[consolidation] object ConsolidatedModule {

  def of(name: ModuleName, times: MeasuredTimes, runs: Vector[AssembledRun], attached: Attachments): ConsolidatedModule = {
    val numbered = runs
      .sortBy(_.worker)(AssembledRun.numberingOrder)
      .zipWithIndex
      .map((run, index) => ConsolidatedRun(RunNumber(index + 1), run))
    ConsolidatedModule(name, times, numbered, attached.failures, attached.output)
  }
}

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
