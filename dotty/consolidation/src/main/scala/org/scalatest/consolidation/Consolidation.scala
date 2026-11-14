package org.scalatest.consolidation

import scala.collection.mutable

/**
 * One consolidation: the runs of one test command, consolidated into human-readable output.
 *
 * Every operation may be called from any thread. [[state]] takes no lock.
 */
final class Consolidation private (humanReadableOutput: String => Unit, coloured: Boolean) {

  @volatile private var currentState: ConsolidationState = ConsolidationState.Open
  private val runs = mutable.ArrayBuffer.empty[RunAssembly]

  /** The consolidation's state now. A failure an operation adds is seen by every read made after it returns. */
  def state: ConsolidationState = currentState

  /**
   * Adds a run, whose event stream and captured lines are then fed to the returned feed.
   *
   * @param label the test run label to show the run's suites under
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def addRun(label: TestRunLabel): RunFeed =
    synchronized {
      requireOpen()
      val run = new RunAssembly(label)
      runs += run
      new RunFeed(this, run)
    }

  /**
   * Adds a process that ended before any run existed in it, which fails the test command.
   *
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def addProcessEndedBeforeAnyRun(): Unit =
    synchronized {
      requireOpen()
      fail()
    }

  /**
   * Ends the consolidation, writing the human-readable output. Nothing is written before.
   *
   * @throws IllegalArgumentException if the consolidation has ended, or a run's event stream has not
   */
  def end(): Unit =
    synchronized {
      requireOpen()
      require(runs.forall(!_.isEventStreamOpen), "every run's event stream must have ended")
      HumanReadableWriter.lines(runs.toVector).foreach(line => humanReadableOutput(line.rendered(coloured)))
      currentState = currentState.withEnd
    }

  private[consolidation] def feed(add: => Unit): Unit =
    synchronized {
      requireOpen()
      add
    }

  private[consolidation] def fail(): Unit = currentState = currentState.withFailure

  private def requireOpen(): Unit = require(!currentState.ended, "the consolidation has ended")
}

object Consolidation {

  /**
   * Starts a consolidation.
   *
   * @param humanReadableOutput the sink each line of human-readable output is written to, without its line end
   * @param coloured whether that output is coloured with ANSI escape sequences
   */
  def start(humanReadableOutput: String => Unit, coloured: Boolean): Consolidation = new Consolidation(humanReadableOutput, coloured)
}

/** Where one run's event stream and captured lines are fed, in the order they were read. */
final class RunFeed private[consolidation] (consolidation: Consolidation, run: RunAssembly) {

  /**
   * Adds the next event of the run's event stream.
   *
   * @throws IllegalArgumentException if the run's event stream or the consolidation has ended
   */
  def add(event: RunEvent): Unit =
    consolidation.feed {
      run.add(event.decoded)
      if (RunAssembly.reportsFailure(event.decoded)) {
        consolidation.fail()
      }
    }

  /**
   * Adds the next line captured from the run's processes.
   *
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def add(line: CapturedLine): Unit = consolidation.feed(run.add(line))

  /** Ends the run's event stream: no event of the run follows. */
  def endEventStream(): Unit = consolidation.feed(run.endEventStream())
}
