package org.scalatest.tools

import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import org.scalatest.Filter
import org.scalatest.RegisteredNode
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteStart
import org.scalatest.events.AlertProvided
import org.scalatest.events.EncodedEvent
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.NameInfo
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.NoteProvided
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
  // How many top-level suites of each suite ID the run has written. Read and written only under this emitter's lock.
  private val topLevelSuitesOfSuiteId = scala.collection.mutable.HashMap.empty[String, Int]
  // Both are read and written only under this emitter's lock. Both are keyed by the event's identity, since an
  // event's `equals` compares its fields, which hold the run's own objects. The second maps each event an inner
  // wrapper handed off to that wrapper's registration.
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
   * Returns how a top-level suite starts: with its structure wrapper over the reporter the suite would report to, in
   * the invocation context of the suite's registration. Before it returns, it builds the suite's node table and writes
   * the suite's structure to the event log: the suite, its nested suites, and the scopes and tests the filter keeps. It
   * registers the suite instance's run against that table.
   *
   * A structure that cannot be read ends the event log before it, as an event that cannot be encoded does, and the
   * suite then starts as one of a run that writes no test event log. What reading or writing the structure throws is
   * kept from the caller as `runEvent` keeps it.
   */
  def startOfTopLevelSuite(suite: Suite, filter: Filter, reporter: Reporter): SuiteStart = {
    // Read outside the lock: reading calls the suite's own methods.
    TestEventLogEmitter.caught(SuiteStructure.read(suite, filter)) match {
      case Right(structure) =>
        val registration = new RegisteredSuite(this, suite, None)
        registration.fixAsTopLevelSuite(topLevelSuite(structure))
        registration.startAsTopLevelSuite(reporter)
      case Left(thrown) =>
        endWithoutRunEnded()
        TestEventLogEmitter.rethrowIfUncontained(thrown)
        SuiteStart.withoutEventLog(reporter)
    }
  }

  /**
   * Gives a top-level suite and each node of its structure its node path, writes the structure, and returns the
   * suite's node table. A top-level suite that shares its suite ID with an earlier one takes the next index, so no two
   * nodes of the run share a node path.
   */
  def topLevelSuite(structure: SuiteStructure): SuiteNodeTable = {
    val index = synchronized {
      val earlier = topLevelSuitesOfSuiteId.getOrElse(structure.suiteId, 0)
      topLevelSuitesOfSuiteId(structure.suiteId) = earlier + 1
      earlier
    }
    val (table, nodes) = SuiteNodeTable.ofTopLevelSuite(structure, index)
    append(TestEventLogEmitter.caught(TestEventLogEncoder.structure(nodes)), None)
    table
  }

  /**
   * Where the latest structure of the suite node of a registered instance's run lacks a test that the instance's
   * registration tree, `registered`, holds, adds the test and writes a further `structure` event. The tree is read
   * only where the structure lacks the test, as when a `runTests` override runs a test the filter drops. An instance
   * whose node is not fixed, or that has no node table, adds nothing.
   */
  def addTestWhereLacking(registration: RegisteredSuite, testName: String, registered: => Option[List[RegisteredNode]]): Unit = {
    val lacking = synchronized(Option.when(isOpen && registration.isNodeFixed)(registration.table).flatten.filterNot(_.latestHoldsTest(testName)))
    lacking.foreach { table =>
      registered.foreach { tree =>
        // The latest structure is read again under the lock, since another instance of the suite may have changed it.
        val thrown = synchronized {
          SuiteStructure.withTestAdded(tree, table.scopesAndTests, testName).flatMap(writeStructureWhereChangedUnderLock(table, _))
        }
        thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
      }
    }
  }

  /**
   * Where the node of a registered instance's run is fixed, writes a further `structure` event of the scopes and tests
   * `scopesAndTestsFor` gives for its node table, where they differ from the suite's latest structure, and makes them
   * the latest. A scope or test an earlier structure of the suite held keeps its node. Otherwise the registration
   * keeps them, and they are written once its node is fixed.
   *
   * It writes under the lock, so that two instances of one suite that run at once write their structures one after
   * the other. What writing throws is kept from the caller as `runEvent` keeps it.
   */
  def writeStructureOnceNodeIsFixed(registration: RegisteredSuite)(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Unit = {
    val thrown = synchronized {
      if (isOpen) {
        registration.structureOnceNodeIsFixed(scopesAndTestsFor).flatMap(writeStructureWhereChangedUnderLock(_, _))
      } else {
        None
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
  }

  /**
   * Fixes the node of a registered instance's run, and first that of each instance whose run it started in, from
   * the suite ID each run read: `suiteIdRead` for this instance's, where its run has read none before. Where a run
   * has read none, the emitter reads it from the instance, outside the lock. Each node fixed writes the structure its
   * registration keeps for it, where that differs from its node table.
   *
   * What reading the suite ID or writing a structure throws is kept from the caller as `runEvent` keeps it.
   */
  def fixNode(registration: RegisteredSuite, suiteIdRead: Option[String]): Unit = {
    val unfixed = synchronized(registration.unfixedFromOutermost)
    if (unfixed.nonEmpty) {
      val suiteIds = TestEventLogEmitter.caught {
        unfixed.map { (one, readByItsRun) =>
          one -> readByItsRun.orElse(Option.when(one eq registration)(suiteIdRead).flatten).getOrElse(one.readSuiteIdOfInstance())
        }
      }
      val thrown = suiteIds.flatMap { withSuiteIds =>
        synchronized {
          if (isOpen) {
            TestEventLogEmitter.caught(withSuiteIds.flatMap((one, suiteId) => one.fixNode(suiteId))).flatMap { structures =>
              structures.flatMap((table, scopesAndTests) => writeStructureWhereChangedUnderLock(table, scopesAndTests)).headOption.toLeft(())
            }
          } else {
            Right(())
          }
        }
      }
      thrown.left.foreach(TestEventLogEmitter.rethrowIfUncontained)
    }
  }

  /** Keeps the suite name a registered instance's run read first. */
  def keepSuiteName(registration: RegisteredSuite, suiteName: String): Unit = synchronized(registration.keepSuiteName(suiteName))

  // Encoding a structure calls no user code, so it is encoded under the lock that makes it the latest.
  private def writeStructureWhereChangedUnderLock(table: SuiteNodeTable, scopesAndTests: List[ScopeOrTest]): Option[Throwable] = {
    if (isOpen && table.scopesAndTests != scopesAndTests) {
      val written = TestEventLogEmitter.caught(write(TestEventLogEncoder.structure(table.replaceLatestStructure(scopesAndTests))))
      if (written.isLeft) {
        close()
      }
      written.left.toOption
    } else {
      None
    }
  }

  /** Starts an execution for a run of a registered suite instance, once the node of the run is fixed. */
  def startRun(registration: RegisteredSuite): Unit = synchronized {
    registration.startRunOnceNodeIsFixed()
  }

  /**
   * Keeps an event's origin until a structure wrapper hands the event off. An event log that has ended keeps none,
   * and none is kept for a registration that has been removed, or whose instance has no node table, so its event is
   * assigned as one with no origin.
   *
   * The messages a test hands off after an event of its execution is recorded are held until that event is written.
   */
  def recordOrigin(event: Event, origin: RecordingOrigin): Unit = synchronized {
    if (isOpen && !origin.registration.hasBeenRemoved && origin.registration.isNodeFixed && origin.registration.table.isDefined) {
      val recorded = origin.record()
      originOfEvent.put(event, recorded)
      recorded.messagesOfTest.foreach(_.awaitWriting(event))
    }
  }

  /**
   * Keeps the origin of a test's closing event, as `recordOrigin` does, and marks the event as one whose recorded
   * messages the test has handed off one by one. The top-level suite's registration keeps the mark, which outlives
   * the origin. An event whose origin was never kept, or was dropped when its registration was removed, is still
   * written without its recorded messages, and with the test's held messages before it.
   */
  def recordOriginOfClosingEventWithMessagesHandedOff(event: Event, origin: RecordingOrigin, messages: TestExecutionMessages): Unit = synchronized {
    if (isOpen) {
      origin.registration.topLevel.keepClosingEventWithMessagesHandedOff(event, messages)
      recordOrigin(event, origin)
    }
  }

  /**
   * Marks a top-level suite's registration removed, once the suite's run has completed, and drops what this emitter
   * keeps for its run:
   *  - the origins the instances of its run recorded for events no wrapper has handed off;
   *  - the events the wrappers of its run handed off that have not reached its own wrapper.
   * An event whose reporter does not pass it on reaches no further wrapper, and would otherwise be kept, with its
   * suite instance, until the event log closes.
   *
   * The dropped handed-off events move to the top-level suite's registration, which its wrapper holds. One may still
   * reach that wrapper, as an alert from a thread the run started or an event a reporter between the wrappers held
   * back. The wrapper then writes nothing, since an inner wrapper has written it.
   *
   * A nested suite's handed-off events stay here until then, since a reporter that holds events back, such as a
   * `TestSortingReporter`, may pass one on to the outer wrappers after the nested suite's run has completed. After the
   * removal, nothing is kept for an event sent late: no origin is recorded for it, and only the top-level suite's
   * wrapper writes it.
   */
  def forget(registration: RegisteredSuite): Unit = synchronized {
    registration.markRemoved()
    originOfEvent.values.removeIf(_.registration.topLevel eq registration)
    val handedOff = handedOffByAnInnerWrapper.entrySet.iterator
    while (handedOff.hasNext) {
      val entry = handedOff.next()
      if (entry.getValue.topLevel eq registration) {
        registration.keepHandedOffBeforeRemoval(entry.getKey)
        handedOff.remove()
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
   * site of a registered suite instance sent, is assigned from its own data: to the suite its suite ID names, at or
   * below the wrapper's suite, and to the test its test name names in that suite. A suite or test no structure holds
   * is first given a node in a further structure. A message that names no suite is assigned to the wrapper's node.
   * The messages recorded in a test's closing event are written before it, assigned as it is, unless the test has
   * handed them off one by one.
   *
   * An event of a test's execution also writes the messages held for it, assigned as the execution's first written
   * event is: after it, or before it where it closes the test.
   *
   * The node of the wrapper's suite instance is fixed first, where it is not yet. A wrapper of an instance that has
   * no node table hands nothing off, so the event is handed off by a wrapper around it.
   *
   * What encoding or writing throws is kept from the caller as `runEvent` keeps it.
   */
  def writeSuiteEvent(wrapper: WrapperNode, event: Event): Unit = {
    if (!wrapper.registration.isNodeFixed) {
      fixNode(wrapper.registration, None)
    }
    originWhereFirstHandedOff(wrapper, event).foreach { (origin, messagesHandedOff) =>
      // Encoded outside the lock, as `runEvent` encodes.
      val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff.isDefined)
      val thrown = synchronized(if (isOpen) writeUnderLock(wrapper, origin, messagesHandedOff, event, encoded) else encoded.left.toOption)
      thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
    }
  }

  // Writes an event a wrapper handed off, closing the event log where it cannot be written.
  private def writeUnderLock(
    wrapper: WrapperNode,
    origin: Option[RecordedOrigin],
    messagesHandedOff: Option[TestExecutionMessages],
    event: Event,
    encoded: Either[Throwable, Vector[EncodedEvent]]
  ): Option[Throwable] = {
    val written = encoded.flatMap { events =>
      TestEventLogEmitter.caught {
        val execution = assign(wrapper, origin, event)
        val messagesOfTest = origin.flatMap(_.messagesOfTest).orElse(messagesHandedOff)
        val (before, after) = messagesOfTest.fold((Vector.empty[EncodedEvent], Vector.empty[EncodedEvent])) {
          _.written(event, execution, TestEventLogEmitter.closesItsScopeOrTest(event))
        }
        val messageExecution = messagesOfTest.flatMap(_.assignment).getOrElse(execution)
        before.foreach(one => write(TestEventLogEncoder.line(one, Some(messageExecution))))
        events.foreach(one => write(TestEventLogEncoder.line(one, Some(execution))))
        after.foreach(one => write(TestEventLogEncoder.line(one, Some(messageExecution))))
      }
    }
    if (written.isLeft) {
      close()
    }
    written.left.toOption
  }

  /**
   * Writes a message a test's informer or documenter handed off as the test called it, and that no reporter receives
   * as an event of its own. It is assigned as the first written event of the test's execution is, and held until
   * that event, and every event of the execution whose origin was recorded before the message, is written. A
   * registration that has been removed, or whose instance has no node table, writes no message.
   *
   * `TestEventLogEmitter.encodedTestMessage` builds and encodes the message outside the lock, as `runEvent` encodes.
   * What building, encoding or writing it throws is kept from the caller as `runEvent` keeps it.
   */
  def writeTestMessage(registration: RegisteredSuite, messages: TestExecutionMessages, encoded: Either[Throwable, EncodedEvent]): Unit = {
    val thrown = synchronized {
      if (isOpen && !registration.hasBeenRemoved && registration.table.isDefined) {
        val written = encoded.flatMap { one =>
          messages.assignment.filter(_ => messages.writesAtOnce) match {
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
  // the event off, and `None` where it is not. For a test's closing event whose messages the test handed off one by
  // one, it also returns those messages. A wrapper of an instance with no node table hands nothing off.
  // The top-level suite's wrapper is the last an event reaches, so it forgets the event. Once the top-level suite's
  // registration is removed, inner wrappers hand nothing off and that wrapper writes the event: an inner wrapper that
  // wrote it would have to keep it for that wrapper to skip, for good where it never reaches that wrapper. An event
  // an inner wrapper handed off before the removal is kept by the top-level suite's registration instead.
  private def originWhereFirstHandedOff(wrapper: WrapperNode, event: Event): Option[(Option[RecordedOrigin], Option[TestExecutionMessages])] = synchronized {
    val isLastWrapper = wrapper.isInstanceOf[WrapperNode.TopLevelSuite]
    if (!isOpen || wrapper.registration.table.isEmpty) {
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
      Some((Option(originOfEvent.remove(event)), wrapper.registration.topLevel.takeClosingEventWithMessagesHandedOff(event)))
    }
  }

  // Called under the lock. Numbers the executions the event names, where the event is the first to name them.
  private def assign(wrapper: WrapperNode, origin: Option[RecordedOrigin], event: Event): NodeExecution = {
    origin.flatMap(recorded => recorded.registration.table.map((recorded, _))) match {
      case Some((recorded, table)) =>
        // A scope or test the suite's structure does not hold has no node, so its event is assigned to the suite.
        table.scopeOrTest(recorded.node) match {
          case Some(scopeOrTest) => assignToScopeOrTest(table, scopeOrTest, Some(recorded), event)
          case None => assignToSuite(table, Some(recorded), event)
        }
      case None => assignFromItsOwnData(wrapper, event)
    }
  }

  // Called under the lock. The suite an event's data names is found at or below the wrapper's suite, else anywhere in
  // the wrapper's top-level suite, and is otherwise given a node nested directly in the wrapper's suite. A test its
  // suite's table lacks is given a node directly under the suite. Either node is written in a further structure.
  private def assignFromItsOwnData(wrapper: WrapperNode, event: Event): NodeExecution = {
    val ofWrapper = wrapper.registration.table.get
    TestEventLogEmitter.namesIn(event).filterNot(_ => TestEventLogEmitter.isAbortOfDistributedTest(event)) match {
      case None =>
        ofWrapper.scopeOrTest(wrapper.treeNode) match {
          case Some(scopeOrTest) => assignToScopeOrTest(ofWrapper, scopeOrTest, None, event)
          case None => assignToSuite(ofWrapper, None, event)
        }
      case Some(names) =>
        val found = TestEventLogEmitter.tableAtOrBelow(ofWrapper, names.suiteId)
          .orElse(wrapper.registration.topLevel.table.flatMap(TestEventLogEmitter.tableAtOrBelow(_, names.suiteId)))
        val table = found.getOrElse(ofWrapper.addNestedSuite(names.suiteName, names.suiteId, names.suiteClassName.getOrElse(names.suiteId)))
        val lackedTest = names.testName.filterNot(table.tests.contains)
        if (found.isEmpty || lackedTest.isDefined) {
          val scopesAndTests = table.scopesAndTests ++ SuiteStructure.ofTestNames(lackedTest.toList)
          write(TestEventLogEncoder.structure(table.replaceLatestStructure(scopesAndTests)))
        }
        names.testName.flatMap(table.tests.get) match {
          case Some(test) => assignToScopeOrTest(table, test, None, event)
          case None => assignToSuite(table, None, event)
        }
    }
  }

  // Where the event has an origin, its suite's execution is that of the run that sent it.
  private def assignToScopeOrTest(table: SuiteNodeTable, node: NodeId, origin: Option[RecordedOrigin], event: Event): NodeExecution = {
    val executions = table.executionsOf(node)
    val (execution, executionOfSuite) = origin match {
      case Some(recorded) => (recorded.execution, Some(recorded.executionOfRun))
      case None => (TestEventLogEmitter.executionWithNoOrigin(executions, event), None)
    }
    val assigned = NodeExecution(node, table.executionPathAbove(node, executionOfSuite) :+ executions.number(execution))
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
    val assigned = NodeExecution(table.node, table.executionPathAboveSuite :+ executions.number(execution))
    if (event.isInstanceOf[SuiteCompleted] || event.isInstanceOf[SuiteAborted]) {
      executions.close(execution)
      table.forgetUnrunExecution(execution)
      table.markEnded()
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

  def closesItsScopeOrTest(event: Event): Boolean = {
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
   * Returns how each top-level suite of a run starts: with its structure wrapper, in the context of its registration,
   * where the run writes a test event log, and otherwise with the reporter the suite would report to, in no context.
   */
  def startOfTopLevelSuiteFor(emitter: Option[TestEventLogEmitter]): (Suite, Filter, Reporter) => SuiteStart = {
    emitter match {
      case Some(started) => started.startOfTopLevelSuite(_, _, _)
      case None => TestEventLogHandOff.startWithoutEventLog
    }
  }

  /** Builds and encodes a test's message for `writeTestMessage`, keeping what either throws. */
  def encodedTestMessage(message: => Event): Either[Throwable, EncodedEvent] = caught(TestEventLogEncoder.encoded(message))

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
  def appendingTo(eventLog: OutputStream): TestEventLogEmitter = new TestEventLogEmitter(eventLog)

  /** An event as a structure wrapper hands it off, with the messages its closing event records unless the test handed them off one by one. */
  def encodedWithRecorded(event: Event, messagesHandedOff: Boolean): Either[Throwable, Vector[EncodedEvent]] = {
    val recorded = if (messagesHandedOff) Vector.empty else recordedIn(event)
    caught(recorded.map(TestEventLogEncoder.encoded) :+ TestEventLogEncoder.encoded(event))
  }

  /** The names an event carries of its suite, and of its test where it is a test's. */
  final case class NamesInEvent(suiteName: String, suiteId: String, suiteClassName: Option[String], testName: Option[String])

  /** The names an event carries: none for a run's event, or a message whose `nameInfo` is `None`. */
  def namesIn(event: Event): Option[NamesInEvent] = {
    def of(nameInfo: NameInfo): NamesInEvent = NamesInEvent(nameInfo.suiteName, nameInfo.suiteId, nameInfo.suiteClassName, nameInfo.testName)
    event match {
      case e: TestStarting => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, Some(e.testName)))
      case e: TestSucceeded => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, Some(e.testName)))
      case e: TestFailed => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, Some(e.testName)))
      case e: TestIgnored => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, Some(e.testName)))
      case e: TestPending => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, Some(e.testName)))
      case e: TestCanceled => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, Some(e.testName)))
      case e: SuiteStarting => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, None))
      case e: SuiteCompleted => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, None))
      case e: SuiteAborted => Some(NamesInEvent(e.suiteName, e.suiteId, e.suiteClassName, None))
      case e: ScopeOpened => Some(of(e.nameInfo).copy(testName = None))
      case e: ScopeClosed => Some(of(e.nameInfo).copy(testName = None))
      case e: ScopePending => Some(of(e.nameInfo).copy(testName = None))
      case e: InfoProvided => e.nameInfo.map(of)
      case e: NoteProvided => e.nameInfo.map(of)
      case e: AlertProvided => e.nameInfo.map(of)
      case e: MarkupProvided => e.nameInfo.map(of)
      case _ => None
    }
  }

  /** Whether the event is the `SuiteAborted` of a `DistributedTestRunnerSuite`, which runs one test: it is assigned to that test's node. */
  private def isAbortOfDistributedTest(event: Event): Boolean = {
    event match {
      case aborted: SuiteAborted => aborted.suiteClassName.contains(classOf[DistributedTestRunnerSuite].getName)
      case _ => false
    }
  }

  // The table of the suite of this suite ID at or below `table`: `table` itself, else the first that has not ended of
  // those nested within it, else the first of them. Recurses once for each suite nested in another.
  private def tableAtOrBelow(table: SuiteNodeTable, suiteId: String): Option[SuiteNodeTable] = {
    def nestedWithin(suite: SuiteNodeTable): Vector[SuiteNodeTable] = suite.childSuites.flatMap(nested => nested +: nestedWithin(nested))
    if (table.suiteId == suiteId) {
      Some(table)
    } else {
      val sharingItsId = nestedWithin(table).filter(_.suiteId == suiteId)
      sharingItsId.find(!_.hasEnded).orElse(sharingItsId.headOption)
    }
  }

  def caught[A](body: => A): Either[Throwable, A] =
    try Right(body) catch { case thrown: Throwable => Left(thrown) }

  def rethrowIfUncontained(thrown: Throwable): Unit = {
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
