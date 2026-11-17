package org.scalatest.consolidation

import org.scalatest.Resources
import org.scalatest.events.DecodedEvent
import org.scalatest.events.IndentedText
import org.scalatest.events.RunOutcome
import org.scalatest.events.StructureNode
import org.scalatest.tools.AnsiColor
import org.scalatest.tools.AnsiCyan
import org.scalatest.tools.AnsiGreen
import org.scalatest.tools.AnsiRed
import org.scalatest.tools.AnsiYellow
import org.scalatest.tools.StringReporter
import org.scalatest.tools.ThrowableView

/** A piece of a line in one colour, or uncoloured where `colour` is absent. */
private[consolidation] final case class Segment(text: String, colour: Option[AnsiColor]) {

  def rendered(coloured: Boolean): String =
    colour.filter(_ => coloured).fold(text)(colour => colour.code + text + StringReporter.ansiReset)
}

private[consolidation] object Segment {

  def apply(text: String, colour: AnsiColor): Segment = Segment(text, Some(colour))
}

private[consolidation] final case class OutputLine(depth: Int, segments: Vector[Segment]) {

  def rendered(coloured: Boolean): String = ("  " * depth) + segments.map(_.rendered(coloured)).mkString
}

private[consolidation] object HumanReadableWriter {

  private val notRunNote = "*** NOT RUN ***"

  private val runStoppedLine = "*** RUN STOPPED ***"

  private val rootLine = "Consolidated tests:"

  private val rootAbortedLine = "*** CONSOLIDATED TESTS ABORTED ***"

  /** Every line of the human-readable output: the tree's lines, then the unindented summary line. */
  def lines(runs: Seq[RunAssembly], rootOutput: Seq[CapturedLine], rootFailures: Seq[String]): Vector[OutputLine] =
    treeLines(runs, rootOutput, rootFailures) :+ OutputLine(0, SummaryLine.of(runs))

  // The root shows a line only where output or a failure is attached to it; otherwise each module's line is unindented.
  // Under the root's line come the lines for each module, then the root's captured lines, then its failures.
  private def treeLines(runs: Seq[RunAssembly], rootOutput: Seq[CapturedLine], rootFailures: Seq[String]): Vector[OutputLine] =
    if (rootOutput.isEmpty && rootFailures.isEmpty) moduleLines(runs, 0)
    else
      linesOf(rootLine, 0, AnsiCyan) ++ moduleLines(runs, 1) ++ rootOutput.flatMap(capturedLineLines(_, 1)) ++
        rootFailureLines(rootFailures, 1)

  // A run that completed with no output attached shows no line; its suites take the depth its line would have had.
  private def moduleLines(runs: Seq[RunAssembly], depth: Int): Vector[OutputLine] =
    runs.groupBy(_.label).toVector.sortBy(_._1.value).flatMap { (label, runsOfModule) =>
      linesOf("[" + label.value + "]", depth, AnsiCyan) ++ runsOfModule.toVector.sortBy(_.number).flatMap(runLines(_, depth + 1))
    }

  private def rootFailureLines(failures: Seq[String], depth: Int): Vector[OutputLine] =
    if (failures.isEmpty) Vector.empty
    else linesOf(rootAbortedLine, depth, AnsiRed) ++ failures.flatMap(linesOf(_, depth + 1, AnsiRed))

  // Under a run's line come the lines for its suites, then its messages and captured lines in the order they were fed,
  // then, if the run aborted or stopped, how it ended.
  private def runLines(run: RunAssembly, depth: Int): Vector[OutputLine] = {
    val outputAndEnd = run.output.flatMap(outputLines(_, depth + 1)) ++ runEndLines(run, depth + 1)
    if (outputAndEnd.isEmpty) run.topLevelSuites.flatMap(nodeLines(_, depth))
    else linesOf(s"Run ${run.number}:", depth, AnsiCyan) ++ run.topLevelSuites.flatMap(nodeLines(_, depth + 1)) ++ outputAndEnd
  }

  private def outputLines(output: RunOutput, depth: Int): Vector[OutputLine] =
    output match {
      case RunOutput.Message(event) => messageLines(event, depth)
      case RunOutput.Captured(line) => capturedLineLines(line, depth)
    }

  private def capturedLineLines(line: CapturedLine, depth: Int): Vector[OutputLine] =
    line match {
      case CapturedLine.Stdout(text) => linesOf(Vector(Segment("[stdout] " + text, None)), depth)
      case CapturedLine.Stderr(text) => linesOf("[stderr] " + text, depth, AnsiYellow)
    }

  private def runEndLines(run: RunAssembly, depth: Int): Vector[OutputLine] =
    run.outcome match {
      case Some(RunOutcome.Aborted) =>
        linesOf(Resources.runAborted, depth, AnsiRed) ++ run.runAborted.fold(Vector.empty[OutputLine])(runAbortLines(_, depth + 1))
      case Some(RunOutcome.Stopped) =>
        linesOf(runStoppedLine, depth, AnsiRed)
      case _ =>
        Vector.empty
    }

  // ScalaTest 3.2.20 prints a run abort's message on the line after `*** RUN ABORTED ***`, then the lines it prints
  // under any error event's first line.
  private def runAbortLines(aborted: DecodedEvent.RunAborted, depth: Int): Vector[OutputLine] = {
    val lines = StringReporter.stringsToPrintOnError(
      Resources.abortedNote,
      _ => Resources.runAborted,
      aborted.message,
      aborted.throwable,
      Vector.empty,
      None,
      None,
      None,
      None,
      presentUnformatted = false,
      presentAllDurations = false,
      presentShortStackTraces = false,
      presentFullStackTraces = false,
      presentFilePathname = false
    )
    linesUnder(Vector(aborted.message).filter(_.nonEmpty) ++ lines.tail, aborted.throwable, depth, AnsiRed)
  }

  // Under a test's line come its failure or cancel lines, then its messages and the suites it ran.
  private def nodeLines(node: AssembledNode, depth: Int): Vector[OutputLine] = {
    val execution = node.execution
    node.node match {
      case suite: StructureNode.Suite =>
        linesOf(suiteNameLine(suite.name), depth, AnsiGreen) ++ contentLines(execution, depth + 1) ++
          suiteEndingLines(suite, execution, depth + 1) ++ countLines(execution, depth + 1)
      case scope: StructureNode.Scope =>
        linesOf(scopeLine(scope, execution.outcome), depth) ++ contentLines(execution, depth + 1) ++ countLines(execution, depth + 1)
      case test: StructureNode.Test =>
        testLines(test, execution, depth) ++ contentLines(execution, depth + 1) ++ countLines(execution, depth + 1)
    }
  }

  // A collapsed execution shows its messages alone. Any other shows each of its messages after the children that had
  // started in it when the message arrived.
  private def contentLines(execution: AssembledExecution, depth: Int): Vector[OutputLine] = {
    def messagesAfter(childrenBefore: Int): Vector[OutputLine] =
      execution.messages.filter(_.childrenBefore == childrenBefore).flatMap(message => messageLines(message.event, depth))
    if (execution.isCollapsed)
      execution.messages.flatMap(message => messageLines(message.event, depth))
    else
      execution.children.indices.toVector.flatMap(index => messagesAfter(index) ++ nodeLines(execution.children(index), depth)) ++
        messagesAfter(execution.children.size)
  }

  private def countLines(execution: AssembledExecution, depth: Int): Vector[OutputLine] = {
    val notRun = if (execution.isCollapsed) execution.descendants.filter(_.execution.outcome.contains(NodeOutcome.NotRun)) else Vector.empty
    if (notRun.isEmpty) Vector.empty else linesOf(countLine(notRun), depth, AnsiRed)
  }

  private def countLine(hidden: Vector[AssembledNode]): String = {
    def count(kind: String, n: Int): Option[String] = if (n == 0) None else Some(s"$n $kind" + (if (n == 1) "" else "s"))
    val counts = Vector(
      count("suite", hidden.count(_.node.isInstanceOf[StructureNode.Suite])),
      count("scope", hidden.count(_.node.isInstanceOf[StructureNode.Scope])),
      count("test", hidden.count(_.node.isInstanceOf[StructureNode.Test]))
    ).flatten
    "(" + counts.mkString(", ") + " not run)"
  }

  private def suiteNameLine(name: String): String =
    StringReporter.stringToPrintWhenNoError(Resources.suiteStarting _, formatterOf(name + ":"), name, None, None, presentAllDurations = false, presentUnformatted = false).mkString

  private def suiteEndingLines(suite: StructureNode.Suite, execution: AssembledExecution, depth: Int): Vector[OutputLine] =
    (execution.outcome, execution.ending) match {
      case (Some(NodeOutcome.NotRun), _) =>
        linesOf(Resources.specTextAndNote(suite.className, notRunNote), depth, AnsiRed)
      case (Some(NodeOutcome.Aborted), Some(aborted: DecodedEvent.SuiteAborted)) =>
        val lines = linesOnError(Resources.abortedNote, Resources.suiteAborted _, aborted.message, aborted.throwable, Vector.empty, suite.className, Some(suite.name), None)
        linesOf(lines.head, depth, AnsiRed) ++ linesUnder(lines.tail, aborted.throwable, depth + 1, AnsiRed)
      case (Some(NodeOutcome.Aborted), _) =>
        linesOf(Resources.specTextAndNote(suite.className, Resources.abortedNote), depth, AnsiRed)
      case _ =>
        Vector.empty
    }

  private def scopeLine(scope: StructureNode.Scope, outcome: Option[NodeOutcome]): Vector[Segment] =
    outcome match {
      case Some(NodeOutcome.Pending) =>
        Vector(Segment(Resources.specTextAndNote(scope.label, Resources.pendingNote), AnsiYellow))
      case Some(NodeOutcome.Aborted) =>
        Vector(Segment(scopeText(scope), AnsiGreen), Segment(" " + Resources.abortedNote, AnsiRed))
      case Some(NodeOutcome.NotRun) =>
        Vector(Segment(scopeText(scope), AnsiGreen), Segment(" " + notRunNote, AnsiRed))
      case _ =>
        Vector(Segment(scopeText(scope), AnsiGreen))
    }

  private def scopeText(scope: StructureNode.Scope): String =
    StringReporter.stringToPrintWhenNoError(Resources.scopeOpened, formatterOf(scope.label), "", None, Some(scope.name), presentAllDurations = false, presentUnformatted = false).mkString

  private def testLines(test: StructureNode.Test, execution: AssembledExecution, depth: Int): Vector[OutputLine] =
    (execution.outcome, execution.ending) match {
      case (Some(NodeOutcome.Failed), Some(failed: DecodedEvent.TestFailed)) =>
        val lines = linesOnError(Resources.failedNote, Resources.testFailed _, failed.message, failed.throwable, failed.analysis, test.label, None, Some(test.name))
        linesOf(lines.head, depth, AnsiRed) ++ linesUnder(lines.tail, failed.throwable, depth + 1, AnsiRed)
      case (Some(NodeOutcome.Canceled), Some(canceled: DecodedEvent.TestCanceled)) =>
        val lines = linesOnError(Resources.canceledNote, Resources.testCanceled _, canceled.message, canceled.throwable, Vector.empty, test.label, None, Some(test.name))
        linesOf(lines.head, depth, AnsiYellow) ++ linesUnder(lines.tail, canceled.throwable, depth + 1, AnsiYellow)
      case (outcome, _) =>
        linesOf(Vector(testLine(test, outcome)), depth)
    }

  private def testLine(test: StructureNode.Test, outcome: Option[NodeOutcome]): Segment =
    outcome match {
      case Some(NodeOutcome.Ignored) =>
        Segment(Resources.specTextAndNote(test.label, Resources.ignoredNote), AnsiYellow)
      case Some(NodeOutcome.Pending) =>
        Segment(Resources.specTextAndNote(test.label, Resources.pendingNote), AnsiYellow)
      case Some(NodeOutcome.Aborted) =>
        Segment(Resources.specTextAndNote(test.label, Resources.abortedNote), AnsiRed)
      case Some(NodeOutcome.NotRun) =>
        Segment(Resources.specTextAndNote(test.label, notRunNote), AnsiRed)
      case _ =>
        Segment(StringReporter.stringToPrintWhenNoError(Resources.testSucceeded, formatterOf(test.label), "", Some(test.name), None, presentAllDurations = false, presentUnformatted = false).mkString, AnsiGreen)
    }

  // A message's own line is marked with its kind in place of ScalaTest 3.2.20's icon. The lines under it are as 3.2.20 prints them.
  private def messageLines(message: DecodedEvent, depth: Int): Vector[OutputLine] =
    message match {
      case info: DecodedEvent.InfoProvided =>
        markedMessageLines("[info] ", Resources.infoProvidedNote, Resources.infoProvided _, info.message, info.throwable, depth, AnsiGreen)
      case note: DecodedEvent.NoteProvided =>
        markedMessageLines("[note] ", Resources.noteProvidedNote, Resources.noteProvided _, note.message, note.throwable, depth, AnsiGreen)
      case alert: DecodedEvent.AlertProvided =>
        markedMessageLines("[alert] ", Resources.alertProvidedNote, Resources.alertProvided _, alert.message, alert.throwable, depth, AnsiYellow)
      case markup: DecodedEvent.MarkupProvided =>
        StringReporter.stringToPrintWhenMarkup(formatterOf("[markup] " + markup.text), None, None, markup.text, presentUnformatted = false).flatMap(linesOf(_, depth, AnsiGreen))
      case _ =>
        Vector.empty
    }

  private def markedMessageLines(
    mark: String,
    note: String,
    errorMessage: Any => String,
    message: String,
    throwable: Option[ThrowableView],
    depth: Int,
    colour: AnsiColor
  ): Vector[OutputLine] = {
    val lines = linesOnError(note, errorMessage, message, throwable, Vector.empty, mark + message, None, None)
    linesOf(mark + message, depth, colour) ++ linesUnder(lines.tail, throwable, depth + 1, colour)
  }

  // An event's first line, then its message, analysis and stack trace lines, with the `S` and `F` reporter options off.
  private def linesOnError(
    note: String,
    errorMessage: Any => String,
    message: String,
    throwable: Option[ThrowableView],
    analysis: IndexedSeq[String],
    text: String,
    suiteName: Option[String],
    testName: Option[String]
  ): Vector[String] =
    StringReporter.stringsToPrintOnError(
      note,
      errorMessage,
      message,
      throwable,
      analysis.toVector,
      formatterOf(text),
      suiteName,
      testName,
      None,
      presentUnformatted = false,
      presentAllDurations = false,
      presentShortStackTraces = false,
      presentFullStackTraces = false,
      presentFilePathname = false
    )

  // The lines under an event's first line: its message lines, then its stack trace lines. A throwable's first line and
  // each `Cause:` line sit at the message lines' depth, and each frame line one deeper. A line is classed as a frame
  // line before it is split, so a throwable message's continuation starting `at ` stays at its first line's depth.
  private def linesUnder(lines: Vector[String], throwable: Option[ThrowableView], depth: Int, colour: AnsiColor): Vector[OutputLine] = {
    val stackTraceLineCount = StringReporter.stackTraceLines(throwable, presentShortStackTraces = false, presentFullStackTraces = false).size
    val (messageLines, stackTraceLines) = lines.splitAt(lines.size - stackTraceLineCount)
    messageLines.flatMap(linesOf(_, depth, colour)) ++
      stackTraceLines.flatMap(line => linesOf(line, if (isFrameLine(line)) depth + 1 else depth, colour))
  }

  private def linesOf(text: String, depth: Int, colour: AnsiColor): Vector[OutputLine] =
    linesOf(Vector(Segment(text, colour)), depth)

  // One output line per line of text, all at `depth`, splitting segments at line breaks, such as in a multi-line label:
  // - each piece keeps its segment's colour, so no colour runs across a break;
  // - a segment after the last break, such as an outcome's note, stays on the last line;
  // - trailing line breaks add no line.
  private def linesOf(segments: Vector[Segment], depth: Int): Vector[OutputLine] = {
    val segmentsByLine = segments.foldLeft(Vector(Vector.empty[Segment])) { (lines, segment) =>
      val pieces = segment.text.split("\n", -1).toVector.map(piece => Segment(piece.stripSuffix("\r"), segment.colour))
      (lines.init :+ (lines.last :+ pieces.head)) ++ pieces.tail.map(Vector(_))
    }
    val withoutTrailingBlankLines = segmentsByLine.reverse.dropWhile(_.forall(_.text.isEmpty)).reverse
    (if (withoutTrailingBlankLines.isEmpty) segmentsByLine.take(1) else withoutTrailingBlankLines).map(OutputLine(depth, _))
  }

  // A class name holds no space, so neither a throwable's first line nor its `Cause:` line starts with `at ` or is `...`.
  private def isFrameLine(line: String): Boolean = line.startsWith("at ") || line == "..."

  private def formatterOf(text: String): Option[IndentedText] = Some(IndentedText(text, text, 0))
}
