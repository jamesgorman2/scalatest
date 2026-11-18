package org.scalatest.consolidation

import java.time.Instant
import scala.collection.mutable

/**
 * One consolidation: the runs of one test command, consolidated into human-readable output and, where it is given a
 * sink for it, JSON output.
 *
 * Every operation may be called from any thread. [[state]] takes no lock.
 */
final class Consolidation private (
  humanReadableOutput: String => Unit,
  coloured: Boolean,
  jsonOutput: Option[String => Unit],
  moduleSelectors: Seq[ModuleSelector],
  buildTool: Option[BuildTool],
  startedAt: Option[Instant]
) {

  @volatile private var currentState: ConsolidationState = ConsolidationState.Open
  private val runs = mutable.ArrayBuffer.empty[RunAssembly]
  private val moduleTimes = mutable.Map.empty[TestRunLabel, MeasuredTimes]
  private val rootOutput = mutable.ArrayBuffer.empty[CapturedLine]
  private val rootFailures = mutable.ArrayBuffer.empty[String]

  /** A read made after an operation returns sees any failure that operation added. */
  def state: ConsolidationState = currentState

  /**
   * The run's event stream and captured lines go to the returned feed. A module's runs are numbered from 1 in
   * the order they are added, and a run's line in the human-readable output shows its number.
   *
   * @param label the run's module, which heads the module's line in the human-readable output
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def addRun(label: TestRunLabel): RunFeed =
    synchronized {
      requireOpen()
      val run = new RunAssembly(label, runs.count(_.label == label) + 1)
      runs += run
      new RunFeed(this, run)
    }

  /**
   * Adds a process that ended before any run existed in it. This fails the test command.
   *
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def addProcessEndedBeforeAnyRun(): Unit =
    synchronized {
      requireOpen()
      fail()
    }

  /**
   * Attaches a line captured from a runner that is not a run, such as a build tool's discovery runner, to the root.
   * The root's captured lines follow the lines for every module, in the order they were attached.
   *
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def addRootOutput(line: CapturedLine): Unit =
    synchronized {
      requireOpen()
      rootOutput += line
    }

  /**
   * Attaches the message of a failure of a runner that is not a run to the root. This fails the test command.
   * The root's failures follow its captured lines, in the order they were attached.
   *
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def addRootFailure(message: String): Unit =
    synchronized {
      requireOpen()
      rootFailures += message
      fail()
    }

  /**
   * Gives a module's start and end time, each by the caller's clock and each where the caller measured it. A later call
   * for the same module replaces the times an earlier one gave.
   *
   * @param startedAt when the caller started the module's tests
   * @param endedAt when the last of the module's test processes ended
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def timeModule(module: TestRunLabel, startedAt: Option[Instant], endedAt: Option[Instant]): Unit =
    synchronized {
      requireOpen()
      moduleTimes(module) = MeasuredTimes(startedAt, endedAt)
    }

  /**
   * Ends the consolidation and writes the human-readable output, whose last line is the summary line, then the JSON
   * output where a sink was given for it. Nothing is written before this.
   *
   * @param endedAt the root's end time by the caller's clock: when every run had ended
   * @throws IllegalArgumentException if the consolidation has ended, or a run's event stream has not
   */
  def end(endedAt: Option[Instant] = None): Unit =
    synchronized {
      requireOpen()
      require(runs.forall(!_.isEventStreamOpen), "every run's event stream must have ended")
      HumanReadableWriter.lines(runs.toVector, rootOutput.toVector, rootFailures.toVector).foreach(line => humanReadableOutput(line.rendered(coloured)))
      jsonOutput.foreach(sink => sink(JsonOutputWriter.text(root(endedAt))))
      currentState = currentState.withEnd
    }

  private def root(endedAt: Option[Instant]): ConsolidatedRoot = {
    val runsByModule = runs.toVector.groupBy(_.label)
    val modules = (runsByModule.keySet ++ moduleTimes.keySet).toVector.sortBy(_.value).map { module =>
      ConsolidatedModule(module, moduleTimes.getOrElse(module, MeasuredTimes.unmeasured), runsByModule.getOrElse(module, Vector.empty))
    }
    ConsolidatedRoot(buildTool, moduleSelectors, rootFailures.toVector, MeasuredTimes(startedAt, endedAt), modules, rootOutput.toVector)
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
   * @param humanReadableOutput the sink each line of human-readable output is written to, without its line end
   * @param coloured whether that output is coloured with ANSI escape sequences
   * @param jsonOutput the sink the JSON output is written to, as one text, once the consolidation ends; with none, no
   *                   JSON output is made
   * @param moduleSelectors each module selector, in the order given, with the test modules it resolved to
   * @param buildTool the build tool that started the runs, where the caller gives one
   * @param startedAt the root's start time by the caller's clock: when the consolidation started
   */
  def start(
    humanReadableOutput: String => Unit,
    coloured: Boolean,
    jsonOutput: Option[String => Unit] = None,
    moduleSelectors: Seq[ModuleSelector] = Vector.empty,
    buildTool: Option[BuildTool] = None,
    startedAt: Option[Instant] = None
  ): Consolidation =
    new Consolidation(humanReadableOutput, coloured, jsonOutput, moduleSelectors, buildTool, startedAt)
}

/** Receives one run's event stream and captured lines, in the order they were read. */
final class RunFeed private[consolidation] (consolidation: Consolidation, run: RunAssembly) {

  /**
   * Adds the next event of the run's event stream.
   *
   * @throws IllegalArgumentException if the run's event stream or the consolidation has ended
   */
  def add(event: RunEvent): Unit =
    consolidation.feed {
      run.add(event.decoded)
      if (RunAssembly.reportsFailure(event.decoded) || RunAssembly.endsOtherThanCompleted(event.decoded)) {
        consolidation.fail()
      }
    }

  /**
   * Adds the next line captured from the run's processes.
   *
   * @throws IllegalArgumentException if the consolidation has ended
   */
  def add(line: CapturedLine): Unit = consolidation.feed(run.add(line))

  /**
   * Ends the run's event stream. No further event of the run may be added. A run whose stream ends without its
   * `runEnded` event does not finish, which fails the test command. Ending the stream also fails the state where the
   * run's tree holds a suite, scope or test that aborted or did not run: a node that did not run is known only once the
   * stream has ended.
   */
  def endEventStream(): Unit =
    consolidation.feed {
      run.endEventStream()
      if (run.outcome.isEmpty || run.holdsAbortedOrNotRunNode) {
        consolidation.fail()
      }
    }
}
