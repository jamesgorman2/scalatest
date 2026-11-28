package org.scalatest.consolidation

import java.time.Instant
import scala.collection.mutable
import org.scalatest.ScalaTestVersions
import org.scalatest.events.TestClassName

/**
 * Consolidates the runs of one test command into human-readable output and, when `jsonOutput` is given, JSON output.
 *
 * Every method is thread-safe.
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
  private var rootAttachments = Attachments.none
  private val moduleAttachments = mutable.Map.empty[ModuleName, Attachments]
  private val modulesWithTestResult = mutable.Set.empty[ModuleName]
  private val modulesEndedAbnormally = mutable.Set.empty[ModuleName]
  private val modulesFailingTestCommand = mutable.Set.empty[ModuleName]

  def state: ConsolidationState = currentState

  /**
   * True when the event stream of one of the module's runs has reported a test as succeeded, failed, ignored, pending
   * or canceled, or a suite as aborted.
   *
   * A suite or test that the consolidation marks as aborted because its end event never arrived is not a test result.
   */
  def hasTestResult(module: ModuleName): Boolean =
    synchronized {
      modulesWithTestResult.contains(module)
    }

  /**
   * True when any of the following holds:
   *   - a failure is attached to the module;
   *   - a process of the module ended before any run existed in it;
   *   - one of the module's runs has an outcome other than completed; or
   *   - the event stream of one of the module's runs ended without recording the run's outcome.
   *
   * A failed or canceled test, an aborted suite, and a node aborted or not run inside a completed run do not end the
   * module abnormally, although each fails the test command (see [[modulesFailingTheTestCommand]]).
   */
  def hasEndedAbnormally(module: ModuleName): Boolean =
    synchronized {
      modulesEndedAbnormally.contains(module)
    }

  /**
   * The modules that fail the test command, ordered by name. A module fails the test command when:
   *   - it has ended abnormally (see [[hasEndedAbnormally]]);
   *   - one of its runs has reported a test as failed or canceled, or a suite as aborted; or
   *   - one of its runs holds a node that is aborted or not run when the run's event stream ends.
   */
  def modulesFailingTheTestCommand: Vector[ModuleName] =
    synchronized {
      modulesFailingTestCommand.toVector.sortBy(_.value)
    }

  /**
   * Adds a run and passes its feed to `feedEventStream`. The run's event stream ends when `feedEventStream` returns or
   * throws.
   *
   * A module's runs are numbered from 1 by fork group, then by worker index. Runs without a worker come first, and
   * runs that tie keep the order in which they were added. The numbers are assigned when the consolidation ends. Both
   * the run's line in the human-readable output and its run node in the JSON output show its number.
   *
   * @param module the run's module. The run appears under this module's line in the human-readable output.
   * @param worker the worker the run ran in. The run node in the JSON output records the worker's fork group but not its index.
   * @return the run, whose event stream has ended
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
   * Records that a process of the module ended before any run existed in it. This fails the test command and ends the
   * module abnormally.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addProcessEndedBeforeAnyRun(module: ModuleName): Unit =
    synchronized {
      requireOpen()
      endModuleAbnormally(module)
    }

  /**
   * Attaches to the module a line captured from a runner that is not a run, such as a build tool's discovery runner.
   * A module with output but no run still appears in the human-readable and JSON outputs. The module's captured lines
   * follow the lines for its runs, in the order in which they were attached.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addModuleOutput(module: ModuleName, line: CapturedLine): Unit =
    synchronized {
      requireOpen()
      attachToModule(module, _.withOutput(line))
    }

  /**
   * Attaches to the module the message of a failure that belongs to none of its runs, such as its tests not starting.
   * This fails the test command and ends the module abnormally. A module with a failure but no run still appears in
   * the human-readable and JSON outputs. The module's failures follow its captured lines, in the order in which they
   * were attached.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addModuleFailure(module: ModuleName, message: String): Unit =
    synchronized {
      requireOpen()
      attachToModule(module, _.withFailure(message))
      endModuleAbnormally(module)
    }

  /**
   * Attaches to the root a line captured from a runner that is not a run, such as a build tool's discovery runner.
   * The root's captured lines follow the lines for every module, in the order in which they were attached.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addRootOutput(line: CapturedLine): Unit =
    synchronized {
      requireOpen()
      rootAttachments = rootAttachments.withOutput(line)
    }

  /**
   * Attaches to the root the message of a failure of a runner that is not a run. This fails the test command.
   * The root's failures follow its captured lines, in the order in which they were attached.
   *
   * @throws java.lang.IllegalStateException if the consolidation has ended
   */
  def addRootFailure(message: String): Unit =
    synchronized {
      requireOpen()
      rootAttachments = rootAttachments.withFailure(message)
      fail()
    }

  /**
   * Sets a module's start and end times, measured by the caller's clock. These replace any times set by an earlier
   * call. A module with times but no run still appears in the human-readable and JSON outputs.
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
   * Ends the consolidation and writes the human-readable output, then the JSON output if a sink was given for it. The
   * last line of the human-readable output is the summary line. No output is written before this call.
   *
   * @param endedAt the time at which every run had ended, measured by the caller's clock
   * @return the consolidation's final state
   * @throws java.lang.IllegalStateException if the consolidation has ended, or if a run's event stream has not ended, as
   *                               happens when this is called from within [[addRun]]'s `feedEventStream`
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
    val modules = (runsByModule.keySet ++ moduleTimes.keySet ++ moduleAttachments.keySet).toVector.sortBy(_.value).map { module =>
      ConsolidatedModule.of(
        module,
        moduleTimes.getOrElse(module, MeasuredTimes.unmeasured),
        runsByModule.getOrElse(module, Vector.empty),
        moduleAttachments.getOrElse(module, Attachments.none)
      )
    }
    ConsolidatedRoot(buildTool, moduleSelectors, MeasuredTimes(startedAt, endedAt), modules, rootAttachments)
  }

  private def attachToModule(module: ModuleName, attach: Attachments => Attachments): Unit =
    moduleAttachments(module) = attach(moduleAttachments.getOrElse(module, Attachments.none))

  private[consolidation] def feed(add: => Unit): Unit =
    synchronized {
      requireOpen()
      add
    }

  private[consolidation] def fail(): Unit = currentState = currentState.withFailure

  private[consolidation] def recordTestResult(module: ModuleName): Unit = {
    assertLockHeld()
    modulesWithTestResult += module
  }

  private[consolidation] def endModuleAbnormally(module: ModuleName): Unit = {
    assertLockHeld()
    modulesEndedAbnormally += module
    failTestCommandFrom(module)
  }

  private[consolidation] def failTestCommandFrom(module: ModuleName): Unit = {
    assertLockHeld()
    modulesFailingTestCommand += module
    fail()
  }

  private[consolidation] def assertLockHeld(): Unit = assert(Thread.holdsLock(this), "the consolidation's lock is not held")

  private def requireOpen(): Unit =
    if (currentState.ended) {
      throw new IllegalStateException("the consolidation has ended")
    }
}

object Consolidation {

  /** The version of `com.edc:scalatest-core_3` that this library was built with. The JSON output writes it as `scalaTestVersion`. */
  val scalaTestVersion: String = ScalaTestVersions.ScalaTestVersion

  /**
   * Starts a consolidation.
   *
   * Both sinks are called only from [[Consolidation#end]]. Until they return, every other method except
   * [[Consolidation#state]] waits.
   *
   * @param humanReadableOutput the sink that receives each line of human-readable output, without its line ending
   * @param coloured true if the human-readable output is to be coloured with ANSI escape sequences
   * @param jsonOutput the sink that receives the JSON output. If `None`, no JSON output is produced.
   * @param moduleSelectors the module selectors, in the order given, each with the test modules it resolved to
   * @param buildTool the build tool that started the runs
   * @param startedAt the time at which the consolidation started, measured by the caller's clock
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

/** Receives one run's event stream and captured lines, in the order in which they were read. */
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
      val module = run.get.module
      if (endedEventStream.get.nonEmpty) {
        throw new IllegalStateException(s"the event stream of a run under ${module.value} has ended")
      }
      val decoded = event.decoded
      run.set(run.get.added(decoded))
      // A run that ends abnormally may have lost some of its module's test results.
      if (RunAssembly.endsRunAbnormally(decoded)) {
        consolidation.endModuleAbnormally(module)
      } else if (RunAssembly.reportsFailure(decoded)) {
        consolidation.failTestCommandFrom(module)
      }
      if (RunAssembly.reportsTestResult(decoded)) {
        consolidation.recordTestResult(module)
      }
    }

  /**
   * Adds the next line captured from the run's processes. Lines can still be added after the run's event stream has
   * ended.
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
        val module = run.get.module
        val eventStream = run.get.assembledWholeEventStream
        endedEventStream.set(Some(eventStream))
        // An event stream that ends without recording a completed run may have lost some of its module's test results.
        if (eventStream.endsItsModuleAbnormally) {
          consolidation.endModuleAbnormally(module)
        } else if (eventStream.failsTestCommand) {
          consolidation.failTestCommandFrom(module)
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
   * Records the test classes that were assigned to the run. A run that was assigned test classes but ran none of them
   * still has a line in the human-readable output.
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
