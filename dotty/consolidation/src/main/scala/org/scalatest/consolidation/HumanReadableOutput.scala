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

private[consolidation] final case class Segment(text: String, colour: Option[AnsiColor]) {

  def rendered(coloured: Boolean): String =
    colour.filter(_ => coloured).fold(text)(colour => colour.code + text + StringReporter.ansiReset)

  def splitAtLineBreaks: Vector[Segment] = text.split("\n", -1).toVector.map(piece => Segment(piece.stripSuffix("\r"), colour))
}

private[consolidation] object Segment {

  def apply(text: String, colour: AnsiColor): Segment = Segment(text, Some(colour))
}

private[consolidation] final case class OutputLine(depth: Int, segments: Vector[Segment]) {

  def rendered(coloured: Boolean): String = ("  " * depth) + segments.map(_.rendered(coloured)).mkString
}

private[consolidation] object HumanReadableOutput {

  private val notRunNote = "*** NOT RUN ***"

  private val runStoppedLine = "*** RUN STOPPED ***"

  private val rootLine = "Consolidated tests:"

  private val rootAbortedLine = "*** CONSOLIDATED TESTS ABORTED ***"

  def lines(root: ConsolidatedRoot): Vector[OutputLine] =
    treeLines(root) :+ OutputLine(0, SummaryLine.of(root.runs))

  private def treeLines(root: ConsolidatedRoot): Vector[OutputLine] =
    if (root.output.isEmpty && root.failures.isEmpty) root.modules.flatMap(moduleLines(_, 0))
    else
      linesOf(rootLine, 0, AnsiCyan) ++ root.modules.flatMap(moduleLines(_, 1)) ++ root.output.flatMap(linesOf(_, 1)) ++
        rootFailureLines(root.failures, 1)

  private def moduleLines(module: ConsolidatedModule, depth: Int): Vector[OutputLine] = {
    val (shownRuns, hiddenRuns) = module.runs.partition(showsItsLine)
    // A stable sort keeps suites that share a name in order of their runs' numbers, then in the order each run lists them.
    val hiddenRunsSuites = hiddenRuns.flatMap(_.assembled.eventStream.topLevelSuites).sortBy(_.name)
    linesOf("[" + module.label.value + "]", depth, AnsiCyan) ++ hiddenRunsSuites.flatMap(nodeLines(_, depth + 1)) ++
      shownRuns.flatMap(runLines(_, depth + 1))
  }

  private def showsItsLine(run: ConsolidatedRun): Boolean = {
    val assembled = run.assembled
    assembled.output.nonEmpty || assembled.eventStream.endedAbnormally || assembled.ranNoneOfItsAssignedTestClasses
  }

  private def rootFailureLines(failures: Seq[String], depth: Int): Vector[OutputLine] =
    if (failures.isEmpty) Vector.empty
    else linesOf(rootAbortedLine, depth, AnsiRed) ++ failures.flatMap(linesOf(_, depth + 1, AnsiRed))

  private def runLines(run: ConsolidatedRun, depth: Int): Vector[OutputLine] = {
    val assembled = run.assembled
    linesOf(s"Run ${run.number.value}:", depth, AnsiCyan) ++ assembled.eventStream.topLevelSuites.flatMap(nodeLines(_, depth + 1)) ++
      assembled.output.flatMap(outputLines(_, depth + 1)) ++ runEndLines(assembled.eventStream, depth + 1)
  }

  private def outputLines(output: RunOutput, depth: Int): Vector[OutputLine] =
    output match {
      case RunOutput.Message(message) => messageLines(message, depth)
      case RunOutput.Captured(line) => linesOf(line, depth)
    }

  private def linesOf(line: CapturedLine, depth: Int): Vector[OutputLine] =
    line match {
      case CapturedLine.Stdout(text) => linesOf(Vector(Segment("[stdout] " + text, None)), depth)
      case CapturedLine.Stderr(text) => linesOf("[stderr] " + text, depth, AnsiYellow)
    }

  private def runEndLines(eventStream: AssembledEventStream, depth: Int): Vector[OutputLine] =
    eventStream.outcome match {
      case Some(RunOutcome.Aborted) =>
        linesOf(Resources.runAborted, depth, AnsiRed) ++ eventStream.abort.fold(Vector.empty[OutputLine])(runAbortLines(_, depth + 1))
      case Some(RunOutcome.Stopped) =>
        linesOf(runStoppedLine, depth, AnsiRed)
      case _ =>
        Vector.empty
    }

  private def runAbortLines(aborted: DecodedEvent.RunAborted, depth: Int): Vector[OutputLine] = {
    val linesAfterFirst = errorLines(Resources.abortedNote, _ => Resources.runAborted, aborted.message, aborted.throwable, Vector.empty, None, None, None).tail
    // ScalaTest 3.2.20 prints a run abort's message on the line after `*** RUN ABORTED ***`, then the lines it prints
    // under any error event's first line.
    linesUnder(Vector(aborted.message).filter(_.nonEmpty) ++ linesAfterFirst, aborted.throwable, depth, AnsiRed)
  }

  private def nodeLines(node: AssembledNode, depth: Int): Vector[OutputLine] = {
    val execution = node.execution
    node.node match {
      case suite: StructureNode.Suite =>
        linesOf(suiteNameLine(suite.name), depth, AnsiGreen) ++ contentLines(execution, depth + 1) ++
          suiteEndingLines(suite, execution, depth + 1) ++ notRunCountLines(execution, depth + 1)
      case scope: StructureNode.Scope =>
        linesOf(scopeLine(scope, execution.outcome), depth) ++ contentLines(execution, depth + 1) ++ notRunCountLines(execution, depth + 1)
      case test: StructureNode.Test =>
        testLines(test, execution, depth) ++ contentLines(execution, depth + 1) ++ notRunCountLines(execution, depth + 1)
    }
  }

  private def contentLines(execution: AssembledExecution, depth: Int): Vector[OutputLine] = {
    def messagesAfter(childrenBefore: Int): Vector[OutputLine] =
      execution.messages.filter(_.childrenBefore == childrenBefore).flatMap(placed => messageLines(placed.message, depth))
    if (execution.isCollapsed)
      execution.messages.flatMap(placed => messageLines(placed.message, depth))
    else
      execution.children.zipWithIndex.flatMap((child, childrenBefore) => messagesAfter(childrenBefore) ++ nodeLines(child, depth)) ++
        messagesAfter(execution.children.size)
  }

  private def notRunCountLines(execution: AssembledExecution, depth: Int): Vector[OutputLine] = {
    val notRun = if (execution.isCollapsed) execution.descendants.filter(_.execution.outcome.contains(NodeOutcome.NotRun)) else Vector.empty
    if (notRun.isEmpty) Vector.empty else linesOf(countLine(notRun), depth, AnsiRed)
  }

  private def countLine(notRun: Vector[AssembledNode]): String = {
    def counted(kind: String, count: Int): Option[String] = if (count == 0) None else Some(s"$count $kind" + (if (count == 1) "" else "s"))
    val counts = NodeKind.values.toVector.flatMap(kind => counted(kind.word, notRun.count(_.kind == kind)))
    "(" + counts.mkString(", ") + " not run)"
  }

  private def suiteNameLine(name: String): String =
    StringReporter.stringToPrintWhenNoError(Resources.suiteStarting _, formatterOf(name + ":"), name, None, None, presentAllDurations = false, presentUnformatted = false).mkString

  private def suiteEndingLines(suite: StructureNode.Suite, execution: AssembledExecution, depth: Int): Vector[OutputLine] =
    (execution.outcome, execution.ending) match {
      case (Some(NodeOutcome.NotRun), _) =>
        linesOf(Resources.specTextAndNote(suite.className, notRunNote), depth, AnsiRed)
      case (Some(NodeOutcome.Aborted), Some(aborted: FailureEnding.SuiteAborted)) =>
        firstLineAndLinesUnder(
          errorLines(Resources.abortedNote, Resources.suiteAborted _, aborted.message, aborted.throwable, Vector.empty, formatterOf(suite.className), Some(suite.name), None),
          aborted.throwable,
          depth,
          AnsiRed
        )
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
      case (Some(NodeOutcome.Failed), Some(failed: FailureEnding.TestFailed)) =>
        firstLineAndLinesUnder(
          errorLines(Resources.failedNote, Resources.testFailed _, failed.message, failed.throwable, failed.analysis, formatterOf(test.label), None, Some(test.name)),
          failed.throwable,
          depth,
          AnsiRed
        )
      case (Some(NodeOutcome.Canceled), Some(canceled: FailureEnding.TestCanceled)) =>
        firstLineAndLinesUnder(
          errorLines(Resources.canceledNote, Resources.testCanceled _, canceled.message, canceled.throwable, Vector.empty, formatterOf(test.label), None, Some(test.name)),
          canceled.throwable,
          depth,
          AnsiYellow
        )
      case (Some(NodeOutcome.Aborted), Some(aborted: FailureEnding.SuiteAborted)) =>
        firstLineAndLinesUnder(
          errorLines(Resources.abortedNote, Resources.suiteAborted _, aborted.message, aborted.throwable, Vector.empty, formatterOf(test.label), None, Some(test.name)),
          aborted.throwable,
          depth,
          AnsiRed
        )
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

  private def messageLines(message: ProvidedMessage, depth: Int): Vector[OutputLine] =
    message.kind match {
      case MessageKind.Info =>
        markedMessageLines(message, Resources.infoProvidedNote, Resources.infoProvided _, depth, AnsiGreen)
      case MessageKind.Note =>
        markedMessageLines(message, Resources.noteProvidedNote, Resources.noteProvided _, depth, AnsiGreen)
      case MessageKind.Alert =>
        markedMessageLines(message, Resources.alertProvidedNote, Resources.alertProvided _, depth, AnsiYellow)
      case MessageKind.Markup =>
        StringReporter.stringToPrintWhenMarkup(formatterOf(markOf(message) + message.text), None, None, message.text, presentUnformatted = false).flatMap(linesOf(_, depth, AnsiGreen))
    }

  private def markedMessageLines(
    message: ProvidedMessage,
    note: String,
    errorMessage: Any => String,
    depth: Int,
    colour: AnsiColor
  ): Vector[OutputLine] = {
    val markedText = markOf(message) + message.text
    val linesAfterFirst = errorLines(note, errorMessage, message.text, message.throwable, Vector.empty, formatterOf(markedText), None, None).tail
    linesOf(markedText, depth, colour) ++ linesUnder(linesAfterFirst, message.throwable, depth + 1, colour)
  }

  private def markOf(message: ProvidedMessage): String =
    // In place of ScalaTest 3.2.20's icon.
    "[" + message.kind.word + "] "

  private def errorLines(
    note: String,
    errorMessage: Any => String,
    message: String,
    throwable: Option[ThrowableView],
    analysis: IndexedSeq[String],
    formatter: Option[IndentedText],
    suiteName: Option[String],
    testName: Option[String]
  ): Vector[String] = {
    def linesWith(shown: Option[ThrowableView]): Vector[String] =
      StringReporter.stringsToPrintOnError(
        note,
        errorMessage,
        message,
        shown,
        analysis.toVector,
        formatter,
        suiteName,
        testName,
        None,
        presentUnformatted = false,
        presentAllDurations = false,
        presentShortStackTraces = false,
        presentFullStackTraces = false,
        presentFilePathname = false
      )
    throwable match {
      case Some(view) if stringReporterThrowsOn(message, view) => {
        // Core's `StringReporter` keeps ScalaTest 3.2.20's NullPointerException here, so these are the lines
        // `StringReporter` prints for a throwable whose message is empty.
        val withoutThrowable = linesWith(None)
        withoutThrowable.head +: (lineNumberLines(view) ++ withoutThrowable.tail ++ stackTraceLinesOf(throwable))
      }
      case _ =>
        linesWith(throwable)
    }
  }

  private def stringReporterThrowsOn(message: String, throwable: ThrowableView): Boolean =
    message.trim.isEmpty && throwable.message.isEmpty

  private def lineNumberLines(throwable: ThrowableView): Vector[String] =
    if (throwable.isPropertyCheckFailure || throwable.stackDepth.isEmpty) Vector.empty
    else Vector(StringReporter.withPossibleLineNumber("", Some(throwable), presentFilePathname = false)).filter(_.nonEmpty)

  private def stackTraceLinesOf(throwable: Option[ThrowableView]): Vector[String] =
    StringReporter.stackTraceLines(throwable, presentShortStackTraces = false, presentFullStackTraces = false).toVector

  private def linesUnder(lines: Vector[String], throwable: Option[ThrowableView], depth: Int, colour: AnsiColor): Vector[OutputLine] = {
    val stackTraceLineCount = stackTraceLinesOf(throwable).size
    val (messageLines, stackTraceLines) = lines.splitAt(lines.size - stackTraceLineCount)
    messageLines.flatMap(linesOf(_, depth, colour)) ++
      // A line is classed as a frame line before it is split, so a throwable message's continuation starting `at `
      // stays at the depth of the message's first line.
      stackTraceLines.flatMap(line => linesOf(line, if (isFrameLine(line)) depth + 1 else depth, colour))
  }

  private def firstLineAndLinesUnder(lines: Vector[String], throwable: Option[ThrowableView], depth: Int, colour: AnsiColor): Vector[OutputLine] =
    linesOf(lines.head, depth, colour) ++ linesUnder(lines.tail, throwable, depth + 1, colour)

  private def linesOf(text: String, depth: Int, colour: AnsiColor): Vector[OutputLine] =
    linesOf(Vector(Segment(text, colour)), depth)

  private def linesOf(segments: Vector[Segment], depth: Int): Vector[OutputLine] = {
    val segmentsByLine = segments.foldLeft(Vector(Vector.empty[Segment]))(withSegmentAdded)
    val withoutTrailingBlankLines = segmentsByLine.reverse.dropWhile(isBlank).reverse
    (if (withoutTrailingBlankLines.isEmpty) segmentsByLine.take(1) else withoutTrailingBlankLines).map(OutputLine(depth, _))
  }

  private def isBlank(line: Vector[Segment]): Boolean = line.forall(_.text.isEmpty)

  private def withSegmentAdded(segmentsByLine: Vector[Vector[Segment]], segment: Segment): Vector[Vector[Segment]] = {
    val pieces = segment.splitAtLineBreaks
    (segmentsByLine.init :+ (segmentsByLine.last :+ pieces.head)) ++ pieces.tail.map(Vector(_))
  }

  private def isFrameLine(line: String): Boolean =
    // A class name holds no space, so neither a throwable's first line nor its `Cause:` line starts with `at ` or is `...`.
    line.startsWith("at ") || line == "..."

  private def formatterOf(text: String): Option[IndentedText] = Some(IndentedText(text, text, 0))
}
