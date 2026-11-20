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
      TestRunBuilder.build(TestRunPart.Events(literal)).items match {
        case Vector(BuiltRunItem.Event(built)) => RunEvent.of(built.event)
        case items => fail(s"expected one event, but the notation built $items")
      }

    // An event is a line at the event log's least indentation, with the lines indented below it.
    def interleavingsWith(lines: Vector[TestRunPart]): Vector[Vector[TestRunPart]] =
      ConsolidatedRuns.interleavings(ConsolidatedRuns.eventNotationsOf(literal).map(TestRunPart.Events(_)), lines)
  }

  extension (consolidation: Consolidation) {

    /** Adds a run of module `core.test`, given no worker, fed the parts. */
    def addRunOf(parts: TestRunPart*): EndedRun = consolidation.addRun(moduleLabel)(feed => feed.addAll(parts*))

    /** Adds a run of module `core.test`, given no worker, fed the events. */
    def addRunOf(events: Vector[RunEvent]): EndedRun = consolidation.addRun(moduleLabel)(feed => events.foreach(event => feed.add(event)))

    /** Adds a run of module `core.test` in the worker, fed the parts. */
    def addRunIn(worker: Worker, parts: TestRunPart*): EndedRun = consolidation.addRun(moduleLabel, Some(worker))(feed => feed.addAll(parts*))
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

    def linesWithoutSummaryLine: Vector[String] = lines.dropRight(1)

    def colourMarkedTree: String = colourMarkedOutput.tree

    def colourMarkedOutput: ColourMarkedOutput = ColourMarkedOutput(lines.map(ConsolidatedRuns.withColoursMarked))
  }

  final case class ColourMarkedOutput(lines: Vector[String]) {

    def tree: String = lines.dropRight(1).mkString("\n")

    def summaryLine: String = lines.lastOption.getOrElse(fail("the human-readable output has no line"))

    def plainSummaryLine: String = summaryLine.replaceAll("\\{(reset|red|green|yellow|cyan)\\}", "")
  }

  val runStarts: TestRunPart = TestRunPart.Events("run started\nrun: RunStarting")

  val runEndsCompleted: TestRunPart = TestRunPart.Events("run: RunCompleted\nrun ended completed")

  /** The event log of a run that starts, sends the events, then ends completed. */
  def completedRunOf(events: String*): String = ("run started" +: events :+ "run ended completed").mkString("\n")

  /**
   * Suite `samples.<suite>`'s structure, then its SuiteStarting, the events and its SuiteCompleted.
   *
   * @param nodes the suite's scopes and tests, one to a line, each nested under the one above by 2 more spaces
   */
  def suiteRunning(suite: String, nodes: String, events: String*): String =
    (structureOf(suite, nodes) +: s"""suite "$suite": SuiteStarting""" +: events.flatMap(ConsolidatedRuns.eventLinesOf) :+ s"""suite "$suite": SuiteCompleted""")
      .mkString("\n")

  /** Suite `samples.<suite>`'s structure, holding the nodes under the suite, written as for `suiteRunning`. */
  def structureOf(suite: String, nodes: String): String =
    (Vector("structure", s"""  suite "$suite", class "samples.$suite"""") ++ ConsolidatedRuns.nested(nodes, depth = 2)).mkString("\n")

  /**
   * Suite `samples.<suite>` with one test, which succeeds.
   *
   * @param occurrenceAmongTestsOfItsName where the test falls among the run's tests of the same name, counted from 1
   */
  def suiteRuns(suite: String, test: String = "t", occurrenceAmongTestsOfItsName: Int = 1): TestRunPart =
    TestRunPart.Events(
      suiteRunning(
        suite,
        s"""test "$test", label "- $test"""",
        s"""test "$test" #$occurrenceAmongTestsOfItsName: TestStarting""",
        s"""test "$test" #$occurrenceAmongTestsOfItsName: TestSucceeded"""
      )
    )

  /** A completed run of the suites, in order, each with one test `t`, which succeeds. */
  def runOfSuites(suites: String*): Vector[TestRunPart] =
    runStarts +: suites.zipWithIndex.map((suite, index) => suiteRuns(suite, occurrenceAmongTestsOfItsName = index + 1)).toVector :+ runEndsCompleted

  def workerAt(forkGroup: Int, workerIndex: Int): Worker =
    Worker(ForkGroup.at(forkGroup), WorkerIndex(workerIndex))

  def startedWithColour(output: OutputSink = new OutputSink): Consolidation = Consolidation.start(output, coloured = true)

  /** The human-readable output, with colour on, of a consolidation given the runs, then ended. */
  def colourMarkedOutputAfter(addRuns: Consolidation => Unit): ColourMarkedOutput = {
    val output = new OutputSink
    val consolidation = startedWithColour(output)
    addRuns(consolidation)
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
