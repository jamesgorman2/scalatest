package org.scalatest.consolidation

import scala.collection.mutable
import org.scalatest.Assertions
import org.scalatest.events.BuiltRunItem
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart

private[consolidation] trait ConsolidatedRuns { this: Assertions =>

  val module: ModuleName = ModuleName("core.test")

  extension (literal: String) {

    def stripExpectedMargin: String =
      literal.stripMargin
        // A CRLF checkout gives the literal CRLF line endings; the output's are LF.
        .replace("\r\n", "\n")

    def toRunEvent: RunEvent =
      TestRunBuilder.build(TestRunPart.Events(literal)).items match {
        case Vector(BuiltRunItem.Event(built)) => RunEvent.of(built.event)
        case items => fail(s"expected one event, but the notation built $items")
      }

    def interleavingsWith(lines: Vector[TestRunPart]): Vector[Vector[TestRunPart]] =
      ConsolidatedRuns.interleavings(ConsolidatedRuns.eventNotationsOf(literal).map(TestRunPart.Events(_)), lines)
  }

  extension (consolidation: Consolidation) {

    def addRunOf(parts: TestRunPart*): EndedRun = consolidation.addRun(module)(feed => feed.addAll(parts*))

    def addRunOf(events: Vector[RunEvent]): EndedRun = consolidation.addRun(module)(feed => events.foreach(event => feed.add(event)))

    def addRunIn(worker: Worker, parts: TestRunPart*): EndedRun = consolidation.addRun(module, Some(worker))(feed => feed.addAll(parts*))
  }

  extension (feed: RunFeed) {

    def addAll(parts: TestRunPart*): Unit =
      TestRunBuilder.build(parts*).items.foreach {
        case BuiltRunItem.Event(built) => feed.add(RunEvent.of(built.event))
        case BuiltRunItem.CapturedLine(CapturedStream.Stdout, line) => feed.add(CapturedLine.Stdout(line))
        case BuiltRunItem.CapturedLine(CapturedStream.Stderr, line) => feed.add(CapturedLine.Stderr(line))
      }
  }

  final class OutputSink extends (String => Unit) {

    private val received = mutable.ArrayBuffer.empty[String]

    def apply(line: String): Unit = synchronized(received += line)

    def lines: Vector[String] = synchronized(received.toVector)

    def linesWithoutSummaryLine: Vector[String] = colourMarkedOutput.treeLines

    def colourMarkedTree: String = colourMarkedOutput.tree

    def colourMarkedOutput: ColourMarkedOutput = ColourMarkedOutput(lines.map(ConsolidatedRuns.withColoursMarked))
  }

  final case class ColourMarkedOutput(lines: Vector[String]) {

    def treeLines: Vector[String] = lines.dropRight(1)

    def tree: String = treeLines.mkString("\n")

    def summaryLine: String = lines.lastOption.getOrElse(fail("the human-readable output has no line"))

    def plainSummaryLine: String = summaryLine.replaceAll("\\{(reset|red|green|yellow|cyan)\\}", "")
  }

  val runStarts: TestRunPart = TestRunPart.Events("run started\nrun: RunStarting")

  val runEndsCompleted: TestRunPart = TestRunPart.Events("run: RunCompleted\nrun ended completed")

  def completedRunOf(events: String*): String = ("run started" +: events :+ "run ended completed").mkString("\n")

  def eventLogOfSuite(suite: String, nodes: String, events: String*): String =
    (structureOf(suite, nodes) +: s"""suite "$suite": SuiteStarting""" +: events.flatMap(ConsolidatedRuns.eventLinesOf) :+ s"""suite "$suite": SuiteCompleted""")
      .mkString("\n")

  def structureOf(suite: String, nodes: String): String =
    (Vector("structure", s"""  suite "$suite", class "samples.$suite"""") ++ ConsolidatedRuns.nested(nodes, depth = 2)).mkString("\n")

  def suiteRuns(suite: String, test: String = "t", occurrenceAmongTestsOfItsName: Int = 1): TestRunPart =
    TestRunPart.Events(
      eventLogOfSuite(
        suite,
        s"""test "$test", label "- $test"""",
        s"""test "$test" #$occurrenceAmongTestsOfItsName: TestStarting""",
        s"""test "$test" #$occurrenceAmongTestsOfItsName: TestSucceeded"""
      )
    )

  def runOfSuites(suites: String*): Vector[TestRunPart] =
    runStarts +: suites.zipWithIndex.map((suite, index) => suiteRuns(suite, occurrenceAmongTestsOfItsName = index + 1)).toVector :+ runEndsCompleted

  def workerAt(forkGroup: Int, workerIndex: Int): Worker =
    Worker(ForkGroup.at(forkGroup), WorkerIndex(workerIndex))

  def startedWithColour(output: OutputSink = new OutputSink): Consolidation = Consolidation.start(output, coloured = true)

  def colourMarkedOutputAfter(act: Consolidation => Unit): ColourMarkedOutput = {
    val output = new OutputSink
    val consolidation = startedWithColour(output)
    act(consolidation)
    consolidation.end()
    output.colourMarkedOutput
  }

  def colourMarkedTreeFrom(eventLog: String): String = colourMarkedTreeFrom(TestRunPart.Events(eventLog))

  def colourMarkedTreeFrom(parts: TestRunPart*): String = colourMarkedOutputFrom(parts*).tree

  def colourMarkedOutputFrom(eventLog: String): ColourMarkedOutput = colourMarkedOutputFrom(TestRunPart.Events(eventLog))

  def colourMarkedOutputFrom(parts: TestRunPart*): ColourMarkedOutput = colourMarkedOutputAfter(_.addRunOf(parts*))
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

  private def indentationOf(line: String): Int = line.takeWhile(_ == ' ').length

  private def nonBlankLinesOf(notation: String): Vector[String] = notation.linesIterator.filter(_.trim.nonEmpty).toVector

  private def eventLinesOf(notation: String): Vector[String] = nonBlankLinesOf(notation).map(_.trim)

  private def nested(notation: String, depth: Int): Vector[String] = {
    val lines = nonBlankLinesOf(notation)
    val leastIndentation = lines.map(indentationOf).minOption.getOrElse(0)
    lines.map(line => "  " * depth + line.drop(leastIndentation))
  }

  private def eventNotationsOf(eventLog: String): Vector[String] = {
    val lines = nonBlankLinesOf(eventLog)
    val leastIndentation = lines.map(indentationOf).min
    lines.foldLeft(Vector.empty[String]) { (events, line) =>
      if (indentationOf(line) == leastIndentation || events.isEmpty) {
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
