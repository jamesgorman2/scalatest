package org.scalatest.consolidation

import org.scalatest.events.StructureNode
import org.scalatest.tools.AnsiCyan
import org.scalatest.tools.AnsiGreen
import org.scalatest.tools.AnsiRed
import org.scalatest.tools.AnsiYellow

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

  def of(runs: Seq[AssembledRun]): SummaryCounts =
    runs.flatMap(_.eventStream.topLevelSuites).flatMap(_.withDescendants).map(countsOf).foldLeft(none)(_.plus(_))

  private def countsOf(node: AssembledNode): SummaryCounts =
    (node.node, node.execution.outcome) match {
      case (_: StructureNode.Suite, None) => none.copy(suitesExpected = 1, completed = 1)
      case (_: StructureNode.Suite, Some(NodeOutcome.Aborted)) => none.copy(suitesExpected = 1, aborted = 1)
      case (_: StructureNode.Suite, _) => none.copy(suitesExpected = 1)
      case (_: StructureNode.Test, Some(NodeOutcome.Ignored)) => none.copy(ignored = 1)
      case (_: StructureNode.Test, Some(NodeOutcome.Succeeded)) => testRun.copy(succeeded = 1)
      case (_: StructureNode.Test, Some(NodeOutcome.Failed)) => testRun.copy(failed = 1)
      case (_: StructureNode.Test, Some(NodeOutcome.Canceled)) => testRun.copy(canceled = 1)
      case (_: StructureNode.Test, Some(NodeOutcome.Pending)) => testRun.copy(pending = 1)
      case (_: StructureNode.Test, _) => none.copy(testsExpected = 1)
      case (_: StructureNode.Scope, _) => none
    }

  private val testRun: SummaryCounts = none.copy(testsExpected = 1, testsRun = 1)
}

private[consolidation] object SummaryLine {

  def of(runs: Seq[AssembledRun]): Vector[Segment] = segmentsOf(SummaryCounts.of(runs))

  private def segmentsOf(counts: SummaryCounts): Vector[Segment] = {
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
