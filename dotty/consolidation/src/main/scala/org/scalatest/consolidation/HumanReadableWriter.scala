package org.scalatest.consolidation

import org.scalatest.Resources
import org.scalatest.events.DecodedEvent
import org.scalatest.events.IndentedText
import org.scalatest.events.StructureNode
import org.scalatest.tools.AnsiColor
import org.scalatest.tools.AnsiCyan
import org.scalatest.tools.AnsiGreen
import org.scalatest.tools.AnsiRed
import org.scalatest.tools.AnsiYellow
import org.scalatest.tools.StringReporter
import org.scalatest.tools.ThrowableView

private[consolidation] final case class Segment(text: String, colour: AnsiColor) {

  def rendered(coloured: Boolean): String = if (coloured) colour.code + text + StringReporter.ansiReset else text
}

private[consolidation] final case class OutputLine(depth: Int, segments: Vector[Segment]) {

  def rendered(coloured: Boolean): String = ("  " * depth) + segments.map(_.rendered(coloured)).mkString
}

private[consolidation] object HumanReadableWriter {

  private val notRunNote = "*** NOT RUN ***"

  def lines(runs: Seq[RunAssembly]): Vector[OutputLine] =
    runs.groupBy(_.label).toVector.sortBy(_._1.value).flatMap { (label, runsUnderLabel) =>
      val suites = runsUnderLabel.toVector.flatMap(_.topLevelSuites).sortBy(_.name)
      linesOf("[" + label.value + "]", 0, AnsiCyan) ++ suites.flatMap(nodeLines(_, 1))
    }

  private def nodeLines(node: AssembledNode, depth: Int): Vector[OutputLine] =
    node.node match {
      case suite: StructureNode.Suite =>
        linesOf(suiteNameLine(suite.name), depth, AnsiGreen) ++
          contentLines(node, depth + 1) ++ suiteEndingLines(suite, node, depth + 1) ++ countLines(node, depth + 1)
      case scope: StructureNode.Scope =>
        linesOf(scopeLine(scope, node.outcome), depth) ++ contentLines(node, depth + 1) ++ countLines(node, depth + 1)
      case test: StructureNode.Test =>
        testLines(test, node, depth) ++ node.messages.flatMap(message => messageLines(message.event, depth + 1))
    }

  // A collapsed node shows its messages alone. Any other shows each of its messages after the children that had
  // started when it arrived.
  private def contentLines(node: AssembledNode, depth: Int): Vector[OutputLine] = {
    def messagesAfter(childrenBefore: Int): Vector[OutputLine] =
      node.messages.filter(_.childrenBefore == childrenBefore).flatMap(message => messageLines(message.event, depth))
    if (node.isCollapsed)
      node.messages.flatMap(message => messageLines(message.event, depth))
    else
      node.children.indices.toVector.flatMap(index => messagesAfter(index) ++ nodeLines(node.children(index), depth)) ++
        messagesAfter(node.children.size)
  }

  private def countLines(node: AssembledNode, depth: Int): Vector[OutputLine] =
    if (node.isCollapsed && node.children.nonEmpty) linesOf(countLine(node.descendants), depth, AnsiRed)
    else Vector.empty

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
    StringReporter.stringToPrintWhenNoError(Resources.suiteStarting _, formatterOf(name + ":"), name, None, None, false, false).mkString

  private def suiteEndingLines(suite: StructureNode.Suite, node: AssembledNode, depth: Int): Vector[OutputLine] =
    (node.outcome, node.ending) match {
      case (Some(Outcome.NotRun), _) =>
        linesOf(Resources.specTextAndNote(suite.className, notRunNote), depth, AnsiRed)
      case (Some(Outcome.Aborted), Some(aborted: DecodedEvent.SuiteAborted)) =>
        val lines = linesOnError(Resources.abortedNote, Resources.suiteAborted _, aborted.message, aborted.throwable, Vector.empty, suite.className, Some(suite.name), None)
        linesOf(lines.head, depth, AnsiRed) ++ linesUnder(lines.tail, aborted.throwable, depth + 1, AnsiRed)
      case _ =>
        Vector.empty
    }

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

  private def testLines(test: StructureNode.Test, node: AssembledNode, depth: Int): Vector[OutputLine] =
    (node.outcome, node.ending) match {
      case (Some(Outcome.Failed), Some(failed: DecodedEvent.TestFailed)) =>
        val lines = linesOnError(Resources.failedNote, Resources.testFailed _, failed.message, failed.throwable, failed.analysis, test.label, None, Some(test.name))
        linesOf(lines.head, depth, AnsiRed) ++ linesUnder(lines.tail, failed.throwable, depth + 1, AnsiRed)
      case (Some(Outcome.Canceled), Some(canceled: DecodedEvent.TestCanceled)) =>
        val lines = linesOnError(Resources.canceledNote, Resources.testCanceled _, canceled.message, canceled.throwable, Vector.empty, test.label, None, Some(test.name))
        linesOf(lines.head, depth, AnsiYellow) ++ linesUnder(lines.tail, canceled.throwable, depth + 1, AnsiYellow)
      case (outcome, _) =>
        linesOf(Vector(testLine(test, outcome)), depth)
    }

  private def testLine(test: StructureNode.Test, outcome: Option[Outcome]): Segment =
    outcome match {
      case Some(Outcome.Ignored) =>
        Segment(Resources.specTextAndNote(test.label, Resources.ignoredNote), AnsiYellow)
      case Some(Outcome.Pending) =>
        Segment(Resources.specTextAndNote(test.label, Resources.pendingNote), AnsiYellow)
      case Some(Outcome.Aborted) =>
        Segment(Resources.specTextAndNote(test.label, Resources.abortedNote), AnsiRed)
      case Some(Outcome.NotRun) =>
        Segment(Resources.specTextAndNote(test.label, notRunNote), AnsiRed)
      case _ =>
        Segment(StringReporter.stringToPrintWhenNoError(Resources.testSucceeded, formatterOf(test.label), "", Some(test.name), None, false, false).mkString, AnsiGreen)
    }

  // A message's own line is marked with its kind in place of 3.2.20's icon. The lines under it are 3.2.20's.
  private def messageLines(message: DecodedEvent, depth: Int): Vector[OutputLine] =
    message match {
      case info: DecodedEvent.InfoProvided =>
        markedMessageLines("[info] ", Resources.infoProvidedNote, Resources.infoProvided _, info.message, info.throwable, depth, AnsiGreen)
      case note: DecodedEvent.NoteProvided =>
        markedMessageLines("[note] ", Resources.noteProvidedNote, Resources.noteProvided _, note.message, note.throwable, depth, AnsiGreen)
      case alert: DecodedEvent.AlertProvided =>
        markedMessageLines("[alert] ", Resources.alertProvidedNote, Resources.alertProvided _, alert.message, alert.throwable, depth, AnsiYellow)
      case markup: DecodedEvent.MarkupProvided =>
        StringReporter.stringToPrintWhenMarkup(formatterOf("[markup] " + markup.text), None, None, markup.text, false).flatMap(linesOf(_, depth, AnsiGreen))
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

  // An event's first line, then its message, analysis and stack trace lines, with `S` and `F` off.
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
      note, errorMessage, message, throwable, analysis.toVector, formatterOf(text), suiteName, testName, None, false, false, false, false, false
    )

  // The lines under an event's first line. They end with its stack trace lines, in which each frame line sits under
  // its throwable's first line or `Cause:` line, and each of those sits where the message lines do. A stack trace line
  // is taken for a frame line before it is split, so a message's continuation starting `at ` stays with its message.
  private def linesUnder(lines: Vector[String], throwable: Option[ThrowableView], depth: Int, colour: AnsiColor): Vector[OutputLine] = {
    val stackTraceLineCount = StringReporter.stackTraceLines(throwable, false, false).size
    val (messageLines, stackTraceLines) = lines.splitAt(lines.size - stackTraceLineCount)
    messageLines.flatMap(linesOf(_, depth, colour)) ++
      stackTraceLines.flatMap(line => linesOf(line, if (isFrameLine(line)) depth + 1 else depth, colour))
  }

  private def linesOf(text: String, depth: Int, colour: AnsiColor): Vector[OutputLine] =
    linesOf(Vector(Segment(text, colour)), depth)

  // Segments holding line breaks, such as a multi-line label or message, give one output line per line of text, all at
  // their depth. Each piece of a segment keeps the segment's colour, so no colour runs across a break, and a segment
  // after the last break, such as an outcome's note, stays on the last line. Line breaks ending the text add no line.
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
