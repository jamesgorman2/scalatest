package org.scalatest.consolidation

import java.time.Instant
import scala.collection.mutable
import org.scalatest.events.TestClassName

/**
 * Consolidates the runs of one test command into human-readable output and, where `jsonOutput` is given, JSON output.
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
  newRun: (ModuleName, Option[Worker]) => RunAssembly
) {

  @volatile private var currentState: ConsolidationState = ConsolidationState.Open
  private val runs = mutable.ArrayBuffer.empty[RunFeed]
  private val moduleTimes = mutable.Map.empty[ModuleName, MeasuredTimes]
  private val rootOutput = mutable.ArrayBuffer.empty[CapturedLine]
  private val rootFailures = mutable.ArrayBuffer.empty[String]

  /** A read made after an operation returns sees any failure that operation added. */
  def state: ConsolidationState = currentState

  /**
   * Adds a run and passes its feed to `feedEventStream`. The run's event stream ends when `feedEventStream` returns or
   * throws; anything it throws is rethrown.
   *
   * A module's runs are numbered from 1 by fork group, then by worker index. Runs given no worker come first, and runs
   * that tie keep the order they were added in. Numbers are fixed when the consolidation ends, so a run added later can
   * renumber earlier ones. A run's line in the human-readable output, and its run node in the JSON output, show its
   * number.
   *
   * @param module the run's module, under whose line the run appears in the human-readable output
   * @param worker the worker the run ran in. The run node in the JSON output records the worker's fork group but not its index.
   * @return the run, its event stream ended
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addRun(module: ModuleName, worker: Option[Worker] = None)(feedEventStream: RunFeed => Unit): EndedRun = {
    val feed = synchronized {
      requireOpen()
      val added = new RunFeed(this, newRun(module, worker))
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
   * @throws java.lang.IllegalStateException if the consolidation has ended
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
   * @throws java.lang.IllegalStateException if the consolidation has ended
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
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addRootFailure(message: String): Unit =
    synchronized {
      requireOpen()
      rootFailures += message
      fail()
    }

  /**
   * Sets a module's start and end times by the caller's clock, replacing any an earlier call set. A module timed but
   * given no run is still a module of every output, with nothing under it.
   *
   * @param startedAt when the caller started the module's tests
   * @param endedAt when the last of the module's test processes ended
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def timeModule(module: ModuleName, startedAt: Option[Instant], endedAt: Option[Instant]): Unit =
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
   * @throws java.lang.IllegalStateException if the consolidation has ended, or a run's event stream is still being fed, such as
   *                               from within [[addRun]]'s `feedEventStream`
   */
  def end(endedAt: Option[Instant] = None): ConsolidationState =
    synchronized {
      requireOpen()
      val consolidated = root(endedAt, runs.toVector.map(_.assembled))
      HumanReadableOutput.lines(consolidated).foreach(line => humanReadableOutput(line.rendered(coloured)))
      jsonOutput.foreach(sink => sink(new JsonOutput(consolidated)))
      currentState = currentState.withEnd
      currentState
    }

  private def root(endedAt: Option[Instant], assembledRuns: Vector[AssembledRun]): ConsolidatedRoot = {
    val runsByModule = assembledRuns.groupBy(_.module)
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

  private[consolidation] def assertLockHeld(): Unit = assert(Thread.holdsLock(this), "the consolidation's lock is not held")

  private def requireOpen(): Unit =
    if (currentState.ended) {
      throw new IllegalStateException("the consolidation has ended")
    }
}

object Consolidation {

  /**
   * @param humanReadableOutput the sink each line of human-readable output is written to, without its line end
   * @param coloured whether that output is coloured with ANSI escape sequences
   * @param jsonOutput the sink given the JSON output once, when the consolidation ends. Every operation but [[Consolidation#state]]
   *                   waits until the sink returns. With none, no JSON output is made
   * @param moduleSelectors each module selector, in the order given, with the test modules it resolved to
   * @param buildTool the build tool that started the runs
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
final class RunFeed private[consolidation] (consolidation: Consolidation, newRun: RunAssembly) {

  private val run = new GuardedByConsolidation(consolidation, newRun)
  private val endedEventStream = new GuardedByConsolidation(consolidation, Option.empty[AssembledEventStream])

  /**
   * Adds the next event of the run's event stream.
   *
   * @throws java.lang.IllegalStateException if the run's event stream or the consolidation has ended
   */
  def add(event: RunEvent): Unit =
    consolidation.feed {
      if (endedEventStream.get.nonEmpty) {
        throw new IllegalStateException(s"the event stream of a run under ${run.get.module.value} has ended")
      }
      run.set(run.get.added(event.decoded))
      if (RunAssembly.reportsFailure(event.decoded) || RunAssembly.endsRunAbnormally(event.decoded)) {
        consolidation.fail()
      }
    }

  /**
   * Adds the next line captured from the run's processes, before or after the run's event stream ends.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def add(line: CapturedLine): Unit =
    consolidation.feed {
      run.set(run.get.added(line))
    }

  private[consolidation] def endEventStream(): Unit =
    consolidation.feed {
      if (endedEventStream.get.isEmpty) {
        val eventStream = run.get.assembledWholeEventStream
        endedEventStream.set(Some(eventStream))
        if (eventStream.failsTestCommand) {
          consolidation.fail()
        }
      }
    }

  private[consolidation] def addAssigned(classes: Iterable[TestClassName]): Unit =
    consolidation.feed {
      run.set(run.get.withAssigned(classes))
    }

  private[consolidation] def assembled: AssembledRun =
    run.get.assembled(
      endedEventStream.get.getOrElse(throw new IllegalStateException(s"the event stream of a run under ${run.get.module.value} has not ended"))
    )
}

/** A run whose event stream has ended. */
final class EndedRun private[consolidation] (feed: RunFeed) {

  /**
   * Adds the run's assigned test classes. A run that was assigned test classes and ran none of them shows its line in
   * the human-readable output.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  private[scalatest] def addAssignedTestClasses(classes: Iterable[TestClassName]): Unit = feed.addAssigned(classes)
}

private[consolidation] final class GuardedByConsolidation[A](consolidation: Consolidation, initial: A) {

  private var current = initial

  def get: A = {
    consolidation.assertLockHeld()
    current
  }

  def set(value: A): Unit = {
    consolidation.assertLockHeld()
    current = value
  }
}
