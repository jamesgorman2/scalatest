package org.scalatest.consolidation

import org.scalatest.Resources
import org.scalatest.events.IndentedText
import org.scalatest.events.StructureNode
import org.scalatest.tools.AnsiColor
import org.scalatest.tools.AnsiCyan
import org.scalatest.tools.AnsiGreen
import org.scalatest.tools.AnsiRed
import org.scalatest.tools.AnsiYellow
import org.scalatest.tools.StringReporter

private[consolidation] final case class Segment(text: String, colour: AnsiColor) {

  def rendered(coloured: Boolean): String = if (coloured) colour.code + text + StringReporter.ansiReset else text
}

/** One line of human-readable output: its depth in the tree, then its text in one or more colours. */
private[consolidation] final case class OutputLine(depth: Int, segments: Vector[Segment]) {

  def rendered(coloured: Boolean): String = ("  " * depth) + segments.map(_.rendered(coloured)).mkString
}

/** Lays out the human-readable output: each test run label, then its runs' suites, scopes and tests under it. */
private[consolidation] object HumanReadableWriter {

  private val notRunNote = "*** NOT RUN ***"

  def lines(runs: Seq[RunAssembly]): Vector[OutputLine] =
    runs.groupBy(_.label).toVector.sortBy(_._1).flatMap { (label, runsUnderLabel) =>
      val suites = runsUnderLabel.toVector.flatMap(_.topLevelSuites).sortBy(_.name)
      OutputLine(0, Vector(Segment("[" + label + "]", AnsiCyan))) +: suites.flatMap(suiteLines(_, 1))
    }

  private def suiteLines(suite: AssembledNode, depth: Int): Vector[OutputLine] =
    OutputLine(depth, Vector(Segment(suiteNameLine(suite.name), AnsiGreen))) +: childLines(suite, depth)

  private def childLines(node: AssembledNode, depth: Int): Vector[OutputLine] =
    node.children.flatMap { child =>
      child.node match {
        case _: StructureNode.Suite => suiteLines(child, depth + 1)
        case scope: StructureNode.Scope => OutputLine(depth + 1, scopeLine(scope, child.outcome)) +: childLines(child, depth + 1)
        case test: StructureNode.Test => Vector(OutputLine(depth + 1, testLine(test, child.outcome)))
      }
    }

  private def suiteNameLine(name: String): String =
    StringReporter.stringToPrintWhenNoError(Resources.suiteStarting _, formatterOf(name + ":"), name, None, None, false, false).mkString

  private def scopeLine(scope: StructureNode.Scope, outcome: Option[Outcome]): Vector[Segment] =
    outcome match {
      case Some(Outcome.Pending) =>
        Vector(Segment(Resources.specTextAndNote(scope.label, Resources.pendingNote), AnsiYellow))
      case Some(Outcome.Aborted) =>
        Vector(Segment(scopeText(scope), AnsiGreen), Segment(" " + Resources.abortedNote, AnsiRed))
      case Some(Outcome.NotRun) =>
        Vector(Segment(scopeText(scope), AnsiGreen), Segment(" " + notRunNote, AnsiRed))
      case _ =>
        Vector(Segment(scopeText(scope), AnsiGreen))
    }

  private def scopeText(scope: StructureNode.Scope): String =
    StringReporter.stringToPrintWhenNoError(Resources.scopeOpened, formatterOf(scope.label), "", None, Some(scope.name), false, false).mkString

  private def testLine(test: StructureNode.Test, outcome: Option[Outcome]): Vector[Segment] = {
    val (text, colour) =
      outcome match {
        case Some(Outcome.Failed) =>
          (firstLineOnError(test, Resources.failedNote, Resources.testFailed _), AnsiRed)
        case Some(Outcome.Canceled) =>
          (firstLineOnError(test, Resources.canceledNote, Resources.testCanceled _), AnsiYellow)
        case Some(Outcome.Ignored) =>
          (Resources.specTextAndNote(test.label, Resources.ignoredNote), AnsiYellow)
        case Some(Outcome.Pending) =>
          (Resources.specTextAndNote(test.label, Resources.pendingNote), AnsiYellow)
        case Some(Outcome.Aborted) =>
          (Resources.specTextAndNote(test.label, Resources.abortedNote), AnsiRed)
        case Some(Outcome.NotRun) =>
          (Resources.specTextAndNote(test.label, notRunNote), AnsiRed)
        case Some(Outcome.Succeeded) | None =>
          (StringReporter.stringToPrintWhenNoError(Resources.testSucceeded, formatterOf(test.label), "", Some(test.name), None, false, false).mkString, AnsiGreen)
      }
    Vector(Segment(text, colour))
  }

  // The test's line only: the lines under it come from the closing event's message and throwable.
  private def firstLineOnError(test: StructureNode.Test, note: String, errorMessage: Any => String): String =
    StringReporter
      .stringsToPrintOnError(note, errorMessage, "", None, Vector.empty, formatterOf(test.label), None, Some(test.name), None, false, false, false, false, false)
      .head

  private def formatterOf(text: String): Option[IndentedText] = Some(IndentedText(text, text, 0))
}
