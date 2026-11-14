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
import org.scalatest.RegisteredNode
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.events.EncodedEvent
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
  // Both are read and written only under this emitter's lock. Both are keyed by the event's identity, since an
  // event's `equals` compares its fields, which hold the run's own objects. The second maps each event an inner
  // wrapper handed off to that wrapper's registration. `RegisteredSuites`' lock is taken under this one, only to
  // count the event log closed, and never the other way round.
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
        val registration = new RegisteredSuite(this, topLevelSuite(structure), suite, None, None)
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
    val (table, nodes) = SuiteNodeTable.minted(structure, mintNode)
    append(TestEventLogEmitter.caught(TestEventLogEncoder.structure(nodes)), None)
    table
  }

  /**
   * Writes a further `structure` event for a suite, where `scopesAndTests` differ from those of its latest structure,
   * and makes them its latest. A scope or test an earlier structure of the suite held keeps its node.
   *
   * It writes under the lock, so that two instances of one suite that run at once mint no node twice. What writing
   * throws is kept from the caller as `runEvent` keeps it.
   */
  def writeStructureWhereChanged(table: SuiteNodeTable, scopesAndTests: List[ScopeOrTest]): Unit = {
    val thrown = synchronized(writeStructureWhereChangedUnderLock(table, scopesAndTests))
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
  }

  /**
   * Writes a further `structure` event for a suite, as `writeStructureWhereChanged` does, from the scopes and tests of
   * a registration tree that the filter keeps. A test a structure of the suite has already filtered keeps what that
   * filtering said, and any other is filtered by `filtered`, as `Filter.apply` filters one test.
   */
  def writeStructureOfTreeWhereChanged(table: SuiteNodeTable, registered: List[RegisteredNode], filtered: String => (Boolean, Boolean)): Unit = {
    val thrown = synchronized {
      val filteredTests = table.filterKeepingEarlier(SuiteStructure.testNamesIn(registered), filtered)
      writeStructureWhereChangedUnderLock(table, SuiteStructure.kept(registered, filteredTests))
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
  }

  /**
   * Adds a test to its suite's latest structure, writing a further `structure` event, where that structure lacks the
   * test and the instance's registration tree, `registered`, holds it. The tree is read only where the structure
   * lacks the test, as where a `runTests` override runs a test the filter drops.
   */
  def addTestWhereLacking(table: SuiteNodeTable, testName: String, registered: => Option[List[RegisteredNode]]): Unit = {
    if (synchronized(isOpen && !table.latestHoldsTest(testName))) {
      registered.foreach { tree =>
        // The latest structure is read again under the lock, since another instance of the suite may have changed it.
        val thrown = synchronized {
          SuiteStructure.withTestAdded(tree, table.scopesAndTests, testName).flatMap(writeStructureWhereChangedUnderLock(table, _))
        }
        thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
      }
    }
  }

  // Encoding a structure calls no code of the run's, so it is encoded under the lock, where its nodes are minted.
  private def writeStructureWhereChangedUnderLock(table: SuiteNodeTable, scopesAndTests: List[ScopeOrTest]): Option[Throwable] = {
    if (isOpen && table.scopesAndTests != scopesAndTests) {
      val written = TestEventLogEmitter.caught(write(TestEventLogEncoder.structure(table.replaceLatestStructure(scopesAndTests, mintNode))))
      if (written.isLeft) {
        close()
      }
      written.left.toOption
    } else {
      None
    }
  }

  private def mintNode(): NodeId = NodeId(mintedNodes.incrementAndGet())

  /** Starts an execution for a run of a registered suite instance. */
  def startRun(registration: RegisteredSuite): Unit = synchronized {
    registration.startRunUnderLock()
  }

  /** Returns the execution of a registered suite instance's latest run, starting one where no site has. */
  def startRunUnlessStarted(registration: RegisteredSuite): Execution = synchronized {
    registration.startRunUnlessStarted()
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
   * Marks a registration removed, and drops what this emitter keeps for it:
   *  - the origins it recorded for events no wrapper has handed off;
   *  - for a top-level suite's registration, the events the wrappers of its run handed off that have not reached its
   *    own wrapper.
   * An event whose reporter does not pass it on reaches no further wrapper, and would otherwise be kept, with its
   * suite instance, until the event log closes.
   *
   * The dropped handed-off events move to the top-level suite's registration, which its wrapper holds. One may still
   * reach that wrapper, as an alert from a thread the run started or an event a reporter between the wrappers held
   * back. The wrapper then writes nothing, since an inner wrapper has written it.
   *
   * A nested suite's handed-off events stay here until its top-level suite's registration is removed, since a
   * reporter that holds events back, such as a `TestSortingReporter`, may pass one on to the outer wrappers after the
   * nested suite's run has completed. After that removal, nothing is kept for an event sent late: no origin is
   * recorded for it, and only the top-level suite's wrapper writes it.
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
   * The event is assigned to the node and executions its origin names. An event with no origin, one that no engine
   * site of a registered suite instance sent, is assigned to the wrapper's node,
   * in the execution of that node that opened last and has not closed. The messages recorded in a test's closing
   * event are written before it, assigned as it is, unless the test has handed them off one by one.
   *
   * The first written event of a test's execution also writes the messages the test handed off before it, assigned
   * as it is: after it, or before it where it closes the test.
   *
   * What encoding or writing throws is kept from the caller as `runEvent` keeps it.
   */
  def writeSuiteEvent(wrapper: WrapperNode, event: Event): Unit = {
    originWhereFirstHandedOff(wrapper, event).foreach { origin =>
      val recorded = if (origin.exists(_.recordedEventsHandedOff)) Vector.empty else TestEventLogEmitter.recordedIn(event)
      // Encoded outside the lock, as `runEvent` encodes.
      val encoded = TestEventLogEmitter.caught(recorded.map(TestEventLogEncoder.encoded) :+ TestEventLogEncoder.encoded(event))
      val thrown = synchronized {
        if (isOpen) {
          val written = encoded.flatMap { events =>
            TestEventLogEmitter.caught {
              val execution = assign(wrapper, origin, event)
              val heldMessages = origin.flatMap(_.messagesOfTest).filter(_.assignment.isEmpty).fold(Vector.empty[EncodedEvent])(_.assign(execution))
              val inOrder = if (TestEventLogEmitter.closesItsScopeOrTest(event)) heldMessages ++ events else events ++ heldMessages
              inOrder.foreach(one => write(TestEventLogEncoder.line(one, Some(execution))))
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

  /**
   * Writes a message a test's informer or documenter handed off as the test called it, and that no reporter receives
   * as an event of its own. It is assigned as the first written event of the test's execution is, and held until
   * that event is written. A registration that has been removed writes no message.
   *
   * What building, encoding or writing the message throws is kept from the caller as `runEvent` keeps it.
   */
  def writeTestMessage(registration: RegisteredSuite, messages: TestExecutionMessages, message: => Event): Unit = {
    // Built and encoded outside the lock, as `runEvent` encodes.
    val encoded = TestEventLogEmitter.caught(TestEventLogEncoder.encoded(message))
    val thrown = synchronized {
      if (isOpen && !registration.hasBeenRemoved) {
        val written = encoded.flatMap { one =>
          messages.assignment match {
            case Some(execution) => TestEventLogEmitter.caught(write(TestEventLogEncoder.line(one, Some(execution))))
            case None => Right(messages.hold(one))
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

  // Returns `Some` of the event's origin, itself `None` where the event has no origin, where this wrapper is to hand
  // the event off, and `None` where it is not.
  // The top-level suite's wrapper is the last an event reaches, so it forgets the event. Once the top-level suite's
  // registration is removed, inner wrappers hand nothing off and that wrapper writes the event: an inner wrapper that
  // wrote it would have to keep it for that wrapper to skip, for good where it never reaches that wrapper. An event
  // an inner wrapper handed off before the removal is kept by the top-level suite's registration instead.
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
