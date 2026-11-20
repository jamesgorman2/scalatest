package org.scalatest.consolidation

import java.time.Instant
import scala.collection.mutable

/**
 * One consolidation: the runs of one test command, consolidated into human-readable output and, where it is given a
 * sink for it, JSON output.
 *
 * Every operation may be called from any thread. [[state]] takes no lock.
 */
final class Consolidation private[consolidation] (
  humanReadableOutput: String => Unit,
  coloured: Boolean,
  jsonOutput: Option[JsonOutput => Unit],
  moduleSelectors: Seq[ModuleSelector],
  buildTool: Option[BuildTool],
  startedAt: Option[Instant],
  newRun: (TestRunLabel, Option[Worker]) => RunAssembly
) {

  @volatile private var currentState: ConsolidationState = ConsolidationState.Open
  private val runs = mutable.ArrayBuffer.empty[RunFeed]
  private val moduleTimes = mutable.Map.empty[TestRunLabel, MeasuredTimes]
  private val rootOutput = mutable.ArrayBuffer.empty[CapturedLine]
  private val rootFailures = mutable.ArrayBuffer.empty[String]

  /** A read made after an operation returns sees any failure that operation added. */
  def state: ConsolidationState = currentState

  /**
   * Adds a run and gives `feedEventStream` the feed of the run's event stream and captured lines. The run's event stream
   * ends when `feedEventStream` returns or throws, and what it throws is rethrown.
   *
   * A module's runs are numbered from 1 in ascending order of fork group, then of worker index; runs given no worker come
   * first, in the order they are added. Numbers are fixed when the consolidation ends, so a run added later can renumber
   * earlier ones. A run's line in the human-readable output, and its run node in the JSON output, show its number.
   *
   * @param label the run's module, which heads the module's line in the human-readable output
   * @param worker the worker the run ran in. The run node in the JSON output records the worker's fork group but not its index.
   * @return the run, its event stream ended
   * @throws IllegalStateException if the consolidation has ended
   */
  def addRun(label: TestRunLabel, worker: Option[Worker] = None)(feedEventStream: RunFeed => Unit): EndedRun = {
    val feed = synchronized {
      requireOpen()
      val added = new RunFeed(this, newRun(label, worker))
      runs += added
      added
    }
    try {
      feedEventStream(feed)
    } finally {
      feed.endEventStream()
    }
    new EndedRun(feed)
  }

  /**
   * Adds a process that ended before any run existed in it. This fails the test command.
   *
   * @throws IllegalStateException if the consolidation has ended
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
   * @throws IllegalStateException if the consolidation has ended
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
   * @throws IllegalStateException if the consolidation has ended
   */
  def addRootFailure(message: String): Unit =
    synchronized {
      requireOpen()
      rootFailures += message
      fail()
    }

  /**
   * Gives a module's start and end time, each by the caller's clock and each where the caller measured it. A later call
   * for the same module replaces the times an earlier one gave. A module timed but given no run is still a module of
   * every output, with nothing under it.
   *
   * @param startedAt when the caller started the module's tests
   * @param endedAt when the last of the module's test processes ended
   * @throws IllegalStateException if the consolidation has ended
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
   * @return the consolidation's state, now ended
   * @throws IllegalStateException if the consolidation has ended, or a run's event stream is still being fed, as where
   *                               this is called from within [[addRun]]
   */
  def end(endedAt: Option[Instant] = None): ConsolidationState =
    synchronized {
      requireOpen()
      val consolidated = root(endedAt, runs.toVector.map(_.assembled))
      HumanReadableWriter.lines(consolidated).foreach(line => humanReadableOutput(line.rendered(coloured)))
      jsonOutput.foreach(sink => sink(new JsonOutput(consolidated)))
      currentState = currentState.withEnd
      currentState
    }

  private def root(endedAt: Option[Instant], assembledRuns: Vector[AssembledRun]): ConsolidatedRoot = {
    val runsByModule = assembledRuns.groupBy(_.label)
    val modules = (runsByModule.keySet ++ moduleTimes.keySet).toVector.sortBy(_.value).map { module =>
      ConsolidatedModule.of(module, moduleTimes.getOrElse(module, MeasuredTimes.unmeasured), runsByModule.getOrElse(module, Vector.empty))
    }
    ConsolidatedRoot(buildTool, moduleSelectors, rootFailures.toVector, MeasuredTimes(startedAt, endedAt), modules, rootOutput.toVector)
  }

  private[consolidation] def feed(add: => Unit): Unit =
    synchronized {
      requireOpen()
      add
    }

  private[consolidation] def fail(): Unit = currentState = currentState.withFailure

  private def requireOpen(): Unit =
    if (currentState.ended) {
      throw new IllegalStateException("the consolidation has ended")
    }
}

object Consolidation {

  /**
   * @param humanReadableOutput the sink each line of human-readable output is written to, without its line end
   * @param coloured whether that output is coloured with ANSI escape sequences
   * @param jsonOutput the sink given the JSON output once, when the consolidation ends, to write where it chooses; every
   *                   operation but [[state]] waits until the sink returns. With none, no JSON output is made
   * @param moduleSelectors each module selector, in the order given, with the test modules it resolved to
   * @param buildTool the build tool that started the runs, where the caller gives one
   * @param startedAt the root's start time by the caller's clock: when the consolidation started
   */
  def start(
    humanReadableOutput: String => Unit,
    coloured: Boolean,
    jsonOutput: Option[JsonOutput => Unit] = None,
    moduleSelectors: Seq[ModuleSelector] = Vector.empty,
    buildTool: Option[BuildTool] = None,
    startedAt: Option[Instant] = None
  ): Consolidation =
    new Consolidation(
      humanReadableOutput,
      coloured,
      jsonOutput,
      moduleSelectors,
      buildTool,
      startedAt,
      RunAssembly.apply
    )
}

/** Receives one run's event stream and captured lines, in the order they were read. */
final class RunFeed private[consolidation] (consolidation: Consolidation, run: RunAssembly) {

  // Read and written only under the consolidation's lock.
  private var endedEventStream: Option[AssembledEventStream] = None

  /**
   * Adds the next event of the run's event stream.
   *
   * @throws IllegalStateException if the run's event stream or the consolidation has ended
   */
  def add(event: RunEvent): Unit =
    consolidation.feed {
      if (endedEventStream.nonEmpty) {
        throw new IllegalStateException(s"the event stream of a run under ${run.label.value} has ended")
      }
      run.add(event.decoded)
      if (RunAssembly.reportsFailure(event.decoded) || RunAssembly.endsOtherThanCompleted(event.decoded)) {
        consolidation.fail()
      }
    }

  /**
   * Adds the next line captured from the run's processes, before or after the run's event stream ends.
   *
   * @throws IllegalStateException if the consolidation has ended
   */
  def add(line: CapturedLine): Unit = consolidation.feed(run.add(line))

  // A run whose stream ends without its `runEnded` event does not finish, which fails the test command. So does a tree
  // holding a suite, scope or test that aborted or did not run: a node that did not run is known only once the stream
  // has ended.
  private[consolidation] def endEventStream(): Unit =
    consolidation.feed {
      if (endedEventStream.isEmpty) {
        val eventStream = run.endEventStream()
        endedEventStream = Some(eventStream)
        if (eventStream.failsTestCommand) {
          consolidation.fail()
        }
      }
    }

  private[consolidation] def addAssigned(classes: Iterable[TestClassName]): Unit = consolidation.feed(run.addAssigned(classes))

  private[consolidation] def assembled: AssembledRun =
    run.assembled(
      endedEventStream.getOrElse(throw new IllegalStateException(s"the event stream of a run under ${run.label.value} has not ended"))
    )
}

/** A run whose event stream has ended. */
final class EndedRun private[consolidation] (feed: RunFeed) {

  /**
   * Adds the run's assigned test classes. A run that was assigned test classes and ran none of them shows its line in
   * the human-readable output.
   *
   * @throws IllegalStateException if the consolidation has ended
   */
  def addAssignedTestClasses(classes: Iterable[TestClassName]): Unit = feed.addAssigned(classes)
}
