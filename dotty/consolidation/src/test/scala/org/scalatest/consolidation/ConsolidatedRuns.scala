package org.scalatest.consolidation

import scala.collection.mutable
import org.scalatest.Assertions
import org.scalatest.events.BuiltRunItem
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart

/** Feeds runs to the consolidation library and reads its human-readable output, with colours marked. */
private[consolidation] trait ConsolidatedRuns { this: Assertions =>

  val moduleLabel: TestRunLabel = TestRunLabel("core.test")

  extension (literal: String) {

    /** The literal with its margin stripped and its line endings made LF, as the output's are. A CRLF checkout gives it CRLF. */
    def stripExpectedMargin: String = literal.stripMargin.replace("\r\n", "\n")
  }

  /** Collects each line of human-readable output it receives. */
  final class OutputSink extends (String => Unit) {

    private val received = mutable.ArrayBuffer.empty[String]

    def apply(line: String): Unit = synchronized(received += line)

    def lines: Vector[String] = synchronized(received.toVector)

    /** Every line but the last, the summary line. */
    def linesApartFromSummaryLine: Vector[String] = lines.dropRight(1)

    /** The lines apart from the summary line, joined with LF, each ANSI SGR sequence replaced by a marker naming it. */
    def markedTextApartFromSummaryLine: String = markedOutput.apartFromSummaryLine

    /** The lines, each ANSI SGR sequence replaced by a marker naming it. */
    def markedOutput: MarkedOutput = MarkedOutput(lines.map(ConsolidatedRuns.withColoursMarked))
  }

  /** The lines of human-readable output, each ANSI SGR sequence replaced by a marker naming it. */
  final case class MarkedOutput(lines: Vector[String]) {

    /** The lines apart from the summary line, joined with LF. */
    def apartFromSummaryLine: String = lines.dropRight(1).mkString("\n")

    /** The last line. */
    def summaryLine: String = lines.lastOption.getOrElse(fail("the human-readable output has no line"))

    /** The last line, without its colour markers. */
    def summaryLineText: String = summaryLine.replaceAll("\\{(reset|red|green|yellow|cyan)\\}", "")
  }

  /** Adds the run to the consolidation, feeding each event and captured line in the order the parts give them. */
  def addRun(consolidation: Consolidation, parts: TestRunPart*): RunFeed = {
    val feed = consolidation.addRun(moduleLabel)
    TestRunBuilder.build(parts*) match {
      case Right(run) =>
        run.items.foreach {
          case BuiltRunItem.Event(built) => feed.add(RunEvent(built.event))
          case BuiltRunItem.CapturedLine(CapturedStream.Stdout, line) => feed.add(CapturedLine.Stdout(line))
          case BuiltRunItem.CapturedLine(CapturedStream.Stderr, line) => feed.add(CapturedLine.Stderr(line))
        }
      case Left(reason) => fail(reason)
    }
    feed
  }

  /**
   * Each way of feeding the captured lines, in their order, among the event log's events: before the first, between
   * any two, or after the last. An event is a line at the event log's least indentation with the lines indented below it.
   */
  def capturedLinesAtEveryPoint(eventLog: String, lines: Vector[TestRunPart]): Vector[Vector[TestRunPart]] =
    ConsolidatedRuns.interleavings(ConsolidatedRuns.eventsOf(eventLog).map(TestRunPart.Events(_)), lines)

  /** The single event the notation describes, built by the test-only builder. */
  def runEvent(notation: String): RunEvent =
    TestRunBuilder.build(TestRunPart.Events(notation)).map(_.items) match {
      case Right(Vector(BuiltRunItem.Event(built))) => RunEvent(built.event)
      case Right(items) => fail(s"expected one event, but the notation built $items")
      case Left(reason) => fail(reason)
    }

  /** The coloured human-readable output of one run whose event stream has ended, apart from its summary line, with colours marked. */
  def consolidated(eventLog: String): String = consolidated(TestRunPart.Events(eventLog))

  /**
   * The coloured human-readable output, apart from its summary line, with colours marked, of one run built from its
   * parts whose event stream has ended.
   */
  def consolidated(parts: TestRunPart*): String = consolidatedOutput(parts*).apartFromSummaryLine

  /** The coloured human-readable output of one run whose event stream has ended, with colours marked. */
  def consolidatedOutput(eventLog: String): MarkedOutput = consolidatedOutput(TestRunPart.Events(eventLog))

  /** The coloured human-readable output, with colours marked, of one run built from its parts whose event stream has ended. */
  def consolidatedOutput(parts: TestRunPart*): MarkedOutput = {
    val output = new OutputSink
    val consolidation = Consolidation.start(output, coloured = true)
    addRun(consolidation, parts*).endEventStream()
    consolidation.end()
    output.markedOutput
  }
}

private[consolidation] object ConsolidatedRuns {

  private val markers = Vector(
    "\u001b[0m" -> "{reset}",
    "\u001b[31m" -> "{red}",
    "\u001b[32m" -> "{green}",
    "\u001b[33m" -> "{yellow}",
    "\u001b[36m" -> "{cyan}"
  )

  def withColoursMarked(line: String): String =
    markers.foldLeft(line) { case (marked, (sequence, marker)) => marked.replace(sequence, marker) }

  private def eventsOf(eventLog: String): Vector[String] = {
    val lines = eventLog.linesIterator.filter(_.trim.nonEmpty).toVector
    val leastIndentation = lines.map(_.takeWhile(_ == ' ').length).min
    lines.foldLeft(Vector.empty[String]) { (events, line) =>
      if (line.takeWhile(_ == ' ').length == leastIndentation || events.isEmpty) {
        events :+ line
      } else {
        events.init :+ (events.last + "\n" + line)
      }
    }
  }

  private def interleavings(first: Vector[TestRunPart], second: Vector[TestRunPart]): Vector[Vector[TestRunPart]] =
    if (first.isEmpty) {
      Vector(second)
    } else if (second.isEmpty) {
      Vector(first)
    } else {
      interleavings(first.tail, second).map(first.head +: _) ++ interleavings(first, second.tail).map(second.head +: _)
    }
}
