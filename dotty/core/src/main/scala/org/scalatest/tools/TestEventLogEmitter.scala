package org.scalatest.tools

import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.events.Event
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.RecordableEvent
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStopped
import org.scalatest.events.ScopeClosed
import org.scalatest.events.ScopeOpened
import org.scalatest.events.ScopePending
import org.scalatest.events.SuiteAborted
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestCanceled
import org.scalatest.events.TestEventLogEncoder
import org.scalatest.events.TestFailed
import org.scalatest.events.TestIgnored
import org.scalatest.events.TestPending
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded

/**
 * Writes one run's test event log to a file, which already holds the run's `runStarted` event.
 *
 * Each event is appended as one unbuffered write, under this emitter's lock, so that events handed off from
 * different threads never interleave and a reader sees each event as soon as it is handed off.
 */
private[scalatest] final class TestEventLogEmitter private (eventLog: OutputStream) {

  private var isOpen = true
  private val mintedNodes = new AtomicLong(0L)
  // Both are read and written only under this emitter's lock, and keyed by the event's identity: an event's
  // `equals` compares its fields, which hold the run's own objects. An event an inner wrapper handed off is kept
  // with that wrapper's registration. The registry's lock is taken under this one, to count the event log closed,
  // and never the other way round.
  private val originOfEvent = new IdentityHashMap[Event, RecordedOrigin]
  private val handedOffByAnInnerWrapper = new IdentityHashMap[Event, RegisteredSuite]

  /**
   * Writes an event assigned to the run. After the event that closes the run it writes how the run ended, and
   * nothing after that.
   *
   * An event that cannot be encoded or written ends the event log there, which leaves the run without its
   * `runEnded` event. So that a hand-off cannot change what the run's reporters receive, what encoding or writing
   * throws does not reach the caller. Two kinds of throwable are treated differently:
   *
   *  - an `InterruptedException` does not reach the caller either, but the calling thread is left interrupted;
   *  - a `VirtualMachineError` other than a `StackOverflowError`, or a `ThreadDeath`, is thrown once the event log
   *    is closed. A `StackOverflowError` is not thrown: a throwable or config map value that recurses without end
   *    is one more event that cannot be encoded.
   */
  def runEvent(event: Event): Unit = {
    // Encoded outside the lock: encoding calls the methods of the run's own throwables and config map values, which
    // may block, and every thread of the run takes the lock.
    val encoded = TestEventLogEmitter.caught(TestEventLogEncoder.event(event, None))
    append(encoded, TestEventLogEmitter.outcomeOfClosing(event).map(TestEventLogEncoder.runEnded))
  }

  /**
   * Returns the structure wrapper of a top-level suite, over the reporter the suite would report to. Before it
   * returns, it mints the suite's node table and writes the suite's structure to the event log: the suite, its nested
   * suites, and the scopes and tests the filter keeps. It registers the suite instance against that table, and the
   * caller removes the registration when the suite's run throws or the `Status` it returns completes.
   *
   * A structure that cannot be read ends the event log before it, as an event that cannot be encoded does, and the
   * suite then reports to the reporter itself. What reading or writing the structure throws is kept from the caller
   * as `runEvent` keeps it.
   */
  def wrapperOfTopLevelSuite(suite: Suite, filter: Filter, reporter: Reporter): Reporter = {
    // Read outside the lock: reading calls the suite's own methods.
    TestEventLogEmitter.caught(SuiteStructure.read(suite, filter)) match {
      case Right(structure) =>
        val registration = new RegisteredSuite(this, topLevelSuite(structure), suite, None)
        RegisteredSuites.add(suite, registration)
        new StructureWrapper(reporter, WrapperNode.TopLevelSuite(registration), writeSuiteEvent)
      case Left(thrown) =>
        endWithoutRunEnded()
        TestEventLogEmitter.rethrowIfUncontained(thrown)
        reporter
    }
  }

  /**
   * Mints a node for a top-level suite and for each node of its structure, writes the structure, and returns the
   * suite's node table. No node ID is minted twice in the run.
   */
  def topLevelSuite(structure: SuiteStructure): SuiteNodeTable = {
    val (table, nodes) = SuiteNodeTable.minted(structure, () => NodeId(mintedNodes.incrementAndGet()))
    append(TestEventLogEmitter.caught(TestEventLogEncoder.structure(nodes)), None)
    table
  }

  /** Starts an execution for a run of a registered suite instance. */
  def startRun(registration: RegisteredSuite): Unit = synchronized {
    registration.startRunUnderLock()
  }

  /**
   * Keeps an event's origin until a structure wrapper hands the event off. An event log that has ended keeps none,
   * and none is kept for a registration that has been removed, so its event is assigned as one with no origin.
   */
  def recordOrigin(event: Event, origin: RecordingOrigin): Unit = synchronized {
    if (isOpen && !origin.registration.hasBeenRemoved) {
      originOfEvent.put(event, origin.record())
    }
  }

  /**
   * Marks a registration removed, and drops what this emitter keeps for it: the origins it recorded for events no
   * wrapper has handed off and, for a top-level suite's registration, the events the wrappers of its run handed off
   * that have not reached its own wrapper. An event whose reporter does not pass it on reaches no further wrapper,
   * and would otherwise be kept, with its suite instance, until the event log closes.
   *
   * Those handed-off events move to the top-level suite's registration, which its wrapper holds. An event among them
   * may still reach that wrapper, as an alert a thread the run started sends or one a reporter between the wrappers
   * held back, and the wrapper then writes nothing: an inner wrapper has written it.
   *
   * A nested suite's handed-off events are kept until its top-level suite's registration is removed: a reporter that
   * holds events back, such as a `TestSortingReporter`, may pass one on to the outer wrappers after the nested suite's
   * run has completed. After that, an event sent late is kept by none of them: no origin is recorded for it, and only
   * the top-level suite's wrapper writes it.
   */
  def forget(registration: RegisteredSuite): Unit = synchronized {
    registration.markRemoved()
    originOfEvent.values.removeIf(_.registration eq registration)
    if (registration.topLevel eq registration) {
      val handedOff = handedOffByAnInnerWrapper.entrySet.iterator
      while (handedOff.hasNext) {
        val entry = handedOff.next()
        if (entry.getValue.topLevel eq registration) {
          registration.keepHandedOffBeforeRemoval(entry.getKey)
          handedOff.remove()
        }
      }
    }
  }

  /** How many events this emitter keeps an origin for, or keeps as handed off by an inner wrapper. */
  def keptEventCount: Int = synchronized(originOfEvent.size + handedOffByAnInnerWrapper.size)

  /**
   * Takes an event a structure wrapper received, with the wrapper's node, and writes it unless another wrapper has
   * handed it off: an event reaches the wrapper of its test, of its suite and of each suite around that one, and
   * only the first of them writes it.
   *
   * The event is assigned to the node its origin names, in the executions its origin names. An event with no
   * origin, which is one that no engine site of a registered suite instance sent, is assigned to the wrapper's node,
   * in the execution of that node that opened last and has not closed. The messages recorded in a test's closing
   * event are written before it, assigned as it is.
   *
   * What encoding or writing throws is kept from the caller as `runEvent` keeps it.
   */
  def writeSuiteEvent(wrapper: WrapperNode, event: Event): Unit = {
    originWhereFirstHandedOff(wrapper, event).foreach { origin =>
      // Encoded outside the lock, as `runEvent` encodes.
      val encoded = TestEventLogEmitter.caught {
        TestEventLogEmitter.recordedIn(event).map(TestEventLogEncoder.encoded) :+ TestEventLogEncoder.encoded(event)
      }
      val thrown = synchronized {
        if (isOpen) {
          val written = encoded.flatMap { events =>
            TestEventLogEmitter.caught {
              val execution = Some(assign(wrapper, origin, event))
              events.foreach(one => write(TestEventLogEncoder.line(one, execution)))
            }
          }
          if (written.isLeft) {
            close()
          }
          written.left.toOption
        } else {
          encoded.left.toOption
        }
      }
      thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
    }
  }

  // Returns the event's origin, if it has one, where no wrapper has handed the event off, and nothing where one has.
  // The wrapper of a top-level suite is the last wrapper an event reaches, so it forgets the event. Once the
  // top-level suite's registration is removed, an inner wrapper hands nothing off: keeping the event would retain it
  // where it reaches no further wrapper, and without it the top-level suite's wrapper could not tell it was written.
  // An event an inner wrapper handed off before the removal is kept by the top-level suite's registration instead.
  private def originWhereFirstHandedOff(wrapper: WrapperNode, event: Event): Option[Option[RecordedOrigin]] = synchronized {
    val isLastWrapper = wrapper.isInstanceOf[WrapperNode.TopLevelSuite]
    if (!isOpen) {
      None
    } else if (isLastWrapper && wrapper.registration.takeHandedOffBeforeRemoval(event)) {
      None
    } else if (!isLastWrapper && wrapper.registration.topLevel.hasBeenRemoved) {
      None
    } else if (handedOffByAnInnerWrapper.containsKey(event)) {
      if (isLastWrapper) {
        handedOffByAnInnerWrapper.remove(event)
      }
      None
    } else {
      if (!isLastWrapper) {
        handedOffByAnInnerWrapper.put(event, wrapper.registration)
      }
      Some(Option(originOfEvent.remove(event)))
    }
  }

  // Called under the lock. Numbers the executions the event names, where the event is the first to name them.
  private def assign(wrapper: WrapperNode, origin: Option[RecordedOrigin], event: Event): NodeExecution = {
    val table = origin.fold(wrapper.registration.table)(_.registration.table)
    // A scope or test the suite's structure does not hold has no node, so its event is assigned to the suite.
    table.scopeOrTest(origin.fold(wrapper.treeNode)(_.node)) match {
      case Some(scopeOrTest) => assignToScopeOrTest(table, scopeOrTest, origin, event)
      case None => assignToSuite(table, origin, event)
    }
  }

  private def assignToScopeOrTest(table: SuiteNodeTable, node: NodeId, origin: Option[RecordedOrigin], event: Event): NodeExecution = {
    val executions = table.executionsOf(node)
    val executionsOfParent = table.executionsOf(table.parents(node))
    val (execution, executionOfParent) = origin match {
      case Some(recorded) => (recorded.execution, recorded.executionOfRun)
      case None =>
        (
          TestEventLogEmitter.executionWithNoOrigin(executions, event),
          executionsOfParent.openedLast.orElse(executionsOfParent.latest).getOrElse(new Execution)
        )
    }
    val assigned = NodeExecution(node, executions.number(execution), Some(executionsOfParent.number(executionOfParent)))
    if (TestEventLogEmitter.closesItsScopeOrTest(event)) {
      executions.close(execution)
    }
    assigned
  }

  private def assignToSuite(table: SuiteNodeTable, origin: Option[RecordedOrigin], event: Event): NodeExecution = {
    val executions = table.executionsOf(table.node)
    val execution = origin match {
      case Some(recorded) => recorded.executionOfRun
      case None =>
        val opensAnExecution = executions.openedLast.isEmpty && event.isInstanceOf[SuiteStarting]
        val withNoOrigin = TestEventLogEmitter.executionWithNoOrigin(executions, event)
        if (opensAnExecution) {
          table.awaitRun(withNoOrigin)
        }
        withNoOrigin
    }
    val assigned = NodeExecution(table.node, executions.number(execution), None)
    if (event.isInstanceOf[SuiteCompleted] || event.isInstanceOf[SuiteAborted]) {
      executions.close(execution)
      table.forgetUnrunExecution(execution)
    }
    assigned
  }

  /** Closes the event log of a run that ends without an event that closes it, leaving it without its `runEnded` event. */
  def endWithoutRunEnded(): Unit = {
    synchronized {
      if (isOpen) {
        close()
      }
    }
  }

  // Writes a line, and after it that the run ended where the line closes the run. A line that could not be encoded
  // or written closes the event log.
  private def append(encoded: Either[Throwable, String], runEnded: Option[String]): Unit = {
    val thrown = synchronized {
      if (isOpen) {
        val written = encoded.flatMap(line => TestEventLogEmitter.caught((line +: runEnded.toList).foreach(write)))
        if (written.isLeft || runEnded.isDefined) {
          close()
        }
        written.left.toOption
      } else {
        encoded.left.toOption
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
  }

  private def write(line: String): Unit = eventLog.write(line.getBytes(StandardCharsets.UTF_8))

  private def close(): Unit = {
    isOpen = false
    originOfEvent.clear()
    handedOffByAnInnerWrapper.clear()
    RegisteredSuites.eventLogClosed()
    try eventLog.close() catch { case contained: Throwable if TestEventLogEmitter.isContained(contained) => () }
  }
}

private[scalatest] object TestEventLogEmitter {

  // The messages a test's closing event carries, which no reporter receives as events of their own.
  private def recordedIn(event: Event): Vector[RecordableEvent] = {
    event match {
      case closing: TestSucceeded => closing.recordedEvents.toVector
      case closing: TestFailed => closing.recordedEvents.toVector
      case closing: TestPending => closing.recordedEvents.toVector
      case closing: TestCanceled => closing.recordedEvents.toVector
      case _ => Vector.empty
    }
  }

  // The execution of an event that has no origin: the node's execution that opened last and has not closed. Where
  // none is open, an event that opens an execution takes a new one, and any other the node's latest, or a first.
  private def executionWithNoOrigin(executions: NodeExecutions, event: Event): Execution = {
    val opensAnExecution = event match {
      case _: SuiteStarting | _: TestStarting | _: ScopeOpened | _: TestIgnored => true
      case _ => false
    }
    executions.openedLast.getOrElse(if (opensAnExecution) new Execution else executions.latest.getOrElse(new Execution))
  }

  private def closesItsScopeOrTest(event: Event): Boolean = {
    event match {
      case _: TestSucceeded | _: TestFailed | _: TestPending | _: TestCanceled | _: TestIgnored => true
      case _: ScopeClosed | _: ScopePending => true
      case _ => false
    }
  }

  /** Starts an emitter where a run is given a test event log file. */
  def startedIfGiven(eventLogFile: Option[String]): Option[TestEventLogEmitter] =
    eventLogFile.map(file => started(Paths.get(file), ProcessIdentity.current))

  /** Returns the hand-off for the events a run's runner sends outside every structure wrapper. */
  def handOffTo(emitter: Option[TestEventLogEmitter]): Event => Unit = {
    emitter match {
      case Some(started) => started.runEvent(_)
      case None => TestEventLogHandOff.none
    }
  }

  /**
   * Returns what gives each top-level suite of a run its reporter: its structure wrapper where the run writes a
   * test event log, and otherwise the reporter the suite would report to.
   */
  def reporterOfTopLevelSuiteFor(emitter: Option[TestEventLogEmitter]): (Suite, Filter, Reporter) => Reporter = {
    emitter match {
      case Some(started) => started.wrapperOfTopLevelSuite(_, _, _)
      case None => TestEventLogHandOff.noStructureWrapper
    }
  }

  /** Runs a hand-off, keeping what it throws from the caller as `runEvent` keeps what encoding or writing throws. */
  def keptFromCaller(handOff: => Unit): Unit = caught(handOff).left.foreach(rethrowIfUncontained)

  /**
   * Returns an emitter whose file holds the run's `runStarted` event.
   *
   * The event is written to a file of another name and moved into place, so the event log file never exists
   * without its whole `runStarted` event.
   *
   * @throws IllegalStateException where the process has no identity to write
   */
  def started(eventLogFile: Path, processIdentity: Either[String, ProcessIdentity]): TestEventLogEmitter = {
    processIdentity match {
      case Right(identity) =>
        val runStarted = TestEventLogEncoder.runStarted(identity.pid, identity.startTime.toString)
        val unmoved = eventLogFile.resolveSibling(eventLogFile.getFileName.toString + ".tmp")
        Files.write(unmoved, runStarted.getBytes(StandardCharsets.UTF_8))
        Files.move(unmoved, eventLogFile, StandardCopyOption.ATOMIC_MOVE)
        // Not a FileChannel: interrupting a thread that is writing to one closes it for every thread.
        appendingTo(new FileOutputStream(eventLogFile.toFile, true))
      case Left(reason) =>
        throw new IllegalStateException(s"Cannot write the test event log $eventLogFile: $reason")
    }
  }

  /** Returns an emitter that appends to an event log which already holds the run's `runStarted` event. */
  def appendingTo(eventLog: OutputStream): TestEventLogEmitter = {
    RegisteredSuites.eventLogOpened()
    new TestEventLogEmitter(eventLog)
  }

  private def caught[A](body: => A): Either[Throwable, A] =
    try Right(body) catch { case thrown: Throwable => Left(thrown) }

  private def rethrowIfUncontained(thrown: Throwable): Unit = {
    thrown match {
      case _: InterruptedException => Thread.currentThread().interrupt()
      case uncontained if !isContained(uncontained) => throw uncontained
      case _ => ()
    }
  }

  // Whether a hand-off keeps a throwable from its caller. One that says the JVM or the thread cannot go on reaches it.
  private def isContained(thrown: Throwable): Boolean = {
    thrown match {
      case _: StackOverflowError => true
      case _: VirtualMachineError | _: ThreadDeath => false
      case _ => true
    }
  }

  private def outcomeOfClosing(event: Event): Option[RunOutcome] = {
    event match {
      case _: RunCompleted => Some(RunOutcome.Completed)
      case _: RunStopped => Some(RunOutcome.Stopped)
      case _: RunAborted => Some(RunOutcome.Aborted)
      case _ => None
    }
  }
}
