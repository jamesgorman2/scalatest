package org.scalatest.consolidation

import scala.collection.mutable
import org.scalatest.Assertions
import org.scalatest.events.BuiltRunItem
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart

/** Feeds the consolidation library runs a criterion states, and reads its human-readable output with colours marked. */
private[consolidation] trait ConsolidatedRuns { this: Assertions =>

  val moduleLabel: String = "core.test"

  /** Each line of the human-readable output, as the sink received it. */
  final class OutputSink extends (String => Unit) {

    private val received = mutable.ArrayBuffer.empty[String]

    def apply(line: String): Unit = synchronized(received += line)

    def lines: Vector[String] = synchronized(received.toVector)

    /** The lines, one to a line, with each ANSI SGR sequence replaced by a marker naming it. */
    def markedText: String = lines.map(ConsolidatedRuns.withColoursMarked).mkString("\n")
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

  /** The coloured human-readable output of a consolidation of one run, its event stream ended, with colours marked. */
  def consolidated(eventLog: String): String = {
    val output = new OutputSink
    val consolidation = Consolidation.start(output, coloured = true)
    addRun(consolidation, TestRunPart.Events(eventLog)).endEventStream()
    consolidation.end()
    output.markedText
  }

  /** The marked lines of [[consolidated]]. */
  def consolidatedLines(eventLog: String): Vector[String] = consolidated(eventLog).split("\n").toVector
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
}
