package org.scalatest.consolidation

import org.scalatest.events.StructureNode
import org.scalatest.tools.AnsiCyan
import org.scalatest.tools.AnsiGreen
import org.scalatest.tools.AnsiRed
import org.scalatest.tools.AnsiYellow

/**
 * The counts the summary line gives. Each suite and test counts once for each of its executions, and once where it
 * has none.
 *
 * @param suitesExpected every suite
 * @param testsExpected  every test that is not ignored
 * @param testsRun       each execution of a test that succeeded, failed, was cancelled or is pending
 */
private[consolidation] final case class SummaryCounts(
  suitesExpected: Int,
  completed: Int,
  aborted: Int,
  testsExpected: Int,
  testsRun: Int,
  succeeded: Int,
  failed: Int,
  canceled: Int,
  ignored: Int,
  pending: Int
) {

  def plus(other: SummaryCounts): SummaryCounts =
    SummaryCounts(
      suitesExpected + other.suitesExpected,
      completed + other.completed,
      aborted + other.aborted,
      testsExpected + other.testsExpected,
      testsRun + other.testsRun,
      succeeded + other.succeeded,
      failed + other.failed,
      canceled + other.canceled,
      ignored + other.ignored,
      pending + other.pending
    )
}

private[consolidation] object SummaryCounts {

  val none: SummaryCounts = SummaryCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

  /**
   * The counts over the assembled tree of the runs, the tree the human-readable output prints. A collapsed subtree is
   * still counted; an attributed node is not in the tree, so it is not. Requires each run's event stream to have ended.
   */
  def of(runs: Seq[RunAssembly]): SummaryCounts =
    runs.flatMap(_.topLevelSuites).flatMap(suite => suite +: suite.descendants).map(countsOf).foldLeft(none)(_.plus(_))

  private def countsOf(node: AssembledNode): SummaryCounts =
    (node.node, node.execution.outcome) match {
      case (_: StructureNode.Suite, None) => none.copy(suitesExpected = 1, completed = 1)
      case (_: StructureNode.Suite, Some(Outcome.Aborted)) => none.copy(suitesExpected = 1, aborted = 1)
      case (_: StructureNode.Suite, _) => none.copy(suitesExpected = 1)
      case (_: StructureNode.Test, Some(Outcome.Ignored)) => none.copy(ignored = 1)
      case (_: StructureNode.Test, Some(Outcome.Succeeded)) => testRun.copy(succeeded = 1)
      case (_: StructureNode.Test, Some(Outcome.Failed)) => testRun.copy(failed = 1)
      case (_: StructureNode.Test, Some(Outcome.Canceled)) => testRun.copy(canceled = 1)
      case (_: StructureNode.Test, Some(Outcome.Pending)) => testRun.copy(pending = 1)
      case (_: StructureNode.Test, _) => none.copy(testsExpected = 1)
      case (_: StructureNode.Scope, _) => none
    }

  private val testRun: SummaryCounts = none.copy(testsExpected = 1, testsRun = 1)
}

/** The last line of the human-readable output. */
private[consolidation] object SummaryLine {

  /**
   * The summary line of the runs, as fragments. Each part, a word and its count, is in the colour of a line of its kind,
   * whatever its count, and every other character is cyan. Requires each run's event stream to have ended.
   */
  def of(runs: Seq[RunAssembly]): Vector[Segment] = fragmentsOf(SummaryCounts.of(runs))

  def fragmentsOf(counts: SummaryCounts): Vector[Segment] = {
    import counts.*
    Vector(
      cyan(s"Suites: $completed of $suitesExpected completed, "),
      Segment(s"$aborted aborted", AnsiRed),
      cyan(s". Tests: $testsRun of $testsExpected run: "),
      Segment(s"succeeded $succeeded", AnsiGreen),
      cyan(", "),
      Segment(s"failed $failed", AnsiRed),
      cyan(", "),
      Segment(s"canceled $canceled", AnsiYellow),
      cyan(", "),
      Segment(s"ignored $ignored", AnsiYellow),
      cyan(", "),
      Segment(s"pending $pending", AnsiYellow)
    )
  }

  private def cyan(text: String): Segment = Segment(text, AnsiCyan)
}
