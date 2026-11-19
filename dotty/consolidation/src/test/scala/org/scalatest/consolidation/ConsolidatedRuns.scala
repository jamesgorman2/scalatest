package org.scalatest.consolidation

import scala.collection.mutable
import org.scalatest.Assertions
import org.scalatest.events.BuiltRunItem
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart

private[consolidation] trait ConsolidatedRuns { this: Assertions =>

  val moduleLabel: TestRunLabel = TestRunLabel("core.test")

  extension (literal: String) {

    // A CRLF checkout gives the literal CRLF line endings; the output's are LF.
    def stripExpectedMargin: String = literal.stripMargin.replace("\r\n", "\n")

    def toRunEvent: RunEvent =
      TestRunBuilder.build(TestRunPart.Events(literal)).map(_.items) match {
        case Right(Vector(BuiltRunItem.Event(built))) => RunEvent.of(built.event).fold(fail(_), identity)
        case Right(items) => fail(s"expected one event, but the notation built $items")
        case Left(reason) => fail(reason)
      }
  }

  final class OutputSink extends (String => Unit) {

    private val received = mutable.ArrayBuffer.empty[String]

    def apply(line: String): Unit = synchronized(received += line)

    def lines: Vector[String] = synchronized(received.toVector)

    def linesWithoutSummaryLine: Vector[String] = lines.dropRight(1)

    def colourMarkedTree: String = colourMarkedOutput.tree

    def colourMarkedOutput: ColourMarkedOutput = ColourMarkedOutput(lines.map(ConsolidatedRuns.withColoursMarked))
  }

  final case class ColourMarkedOutput(lines: Vector[String]) {

    def tree: String = lines.dropRight(1).mkString("\n")

    def summaryLine: String = lines.lastOption.getOrElse(fail("the human-readable output has no line"))

    def plainSummaryLine: String = summaryLine.replaceAll("\\{(reset|red|green|yellow|cyan)\\}", "")
  }

  def addRun(consolidation: Consolidation, parts: TestRunPart*): RunFeed = {
    val feed = consolidation.addRun(moduleLabel)
    TestRunBuilder.build(parts*) match {
      case Right(run) =>
        run.items.foreach {
          case BuiltRunItem.Event(built) => feed.add(RunEvent.of(built.event).fold(fail(_), identity))
          case BuiltRunItem.CapturedLine(CapturedStream.Stdout, line) => feed.add(CapturedLine.Stdout(line))
          case BuiltRunItem.CapturedLine(CapturedStream.Stderr, line) => feed.add(CapturedLine.Stderr(line))
        }
      case Left(reason) => fail(reason)
    }
    feed
  }

  /** Feeds the events to one run of module `core.test`, then ends the consolidation. */
  def consolidateRun(consolidation: Consolidation, events: Vector[RunEvent]): Unit = {
    val feed = consolidation.addRun(moduleLabel)
    events.foreach(event => feed.add(event))
    feed.endEventStream()
    consolidation.end()
  }

  // An event is a line at the event log's least indentation, with the lines indented below it.
  def interleavingsOf(eventLog: String, lines: Vector[TestRunPart]): Vector[Vector[TestRunPart]] =
    ConsolidatedRuns.interleavings(ConsolidatedRuns.eventNotationsOf(eventLog).map(TestRunPart.Events(_)), lines)

  def colourMarkedTreeFrom(eventLog: String): String = colourMarkedTreeFrom(TestRunPart.Events(eventLog))

  def colourMarkedTreeFrom(parts: TestRunPart*): String = colourMarkedOutputFrom(parts*).tree

  def colourMarkedOutputFrom(eventLog: String): ColourMarkedOutput = colourMarkedOutputFrom(TestRunPart.Events(eventLog))

  def colourMarkedOutputFrom(parts: TestRunPart*): ColourMarkedOutput = {
    val output = new OutputSink
    val consolidation = Consolidation.start(output, coloured = true)
    addRun(consolidation, parts*).endEventStream()
    consolidation.end()
    output.colourMarkedOutput
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

  private def eventNotationsOf(eventLog: String): Vector[String] = {
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
