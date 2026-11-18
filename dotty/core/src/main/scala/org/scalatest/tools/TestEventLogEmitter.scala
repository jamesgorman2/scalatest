package org.scalatest.tools

import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
import org.scalatest.events.RunOutcome
import org.scalatest.events.ScopeClosed
import org.scalatest.events.ScopeOpened
import org.scalatest.events.ScopePending
import org.scalatest.events.SuiteAborted
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.StructureNode
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
  // Read and written only under this emitter's lock.
  private val topLevelSuiteCountBySuiteId = scala.collection.mutable.HashMap.empty[String, Int]
  // The events handed off at a send site or by an inner wrapper that have not reached their top-level suite's
  // wrapper, each mapped to that suite's registration. Keyed by the event's identity, since an event's `equals`
  // compares its fields, which hold the run's own objects. Read and written only under this emitter's lock.
  private val handedOff = new IdentityHashMap[Event, RegisteredSuite]

  def assertLockHeld(): Unit = assert(Thread.holdsLock(this), "The test event log emitter's lock is not held")

  private def underLockKeepingThrown(body: => Option[Throwable]): Unit =
    synchronized(body).foreach(TestEventLogEmitter.rethrowIfUncontained)

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
  def writeRunEvent(event: Event): Unit = {
    // Encoded outside the lock: encoding calls the methods of the run's own throwables and config map values, which
    // may block, and every thread of the run takes the lock.
    val encoded = TestEventLogEmitter.caught(TestEventLogEncoder.event(event, None))
    append(encoded, RunOutcome.reportedBy(event).map(TestEventLogEncoder.runEnded))
  }

  /**
   * Before it returns, it builds the suite's node table and writes the suite's structure to the event log: the suite,
   * its nested suites, and the scopes and tests the filter keeps.
   *
   * A structure that cannot be read ends the event log before it, as an event that cannot be encoded does, and the
   * suite then starts as one of a run that writes no test event log. What reading or writing the structure throws is
   * kept from the caller as `writeRunEvent` keeps it.
   */
  def startOfTopLevelSuite(suite: Suite, filter: Filter, reporter: Reporter): SuiteStart = {
    // Read outside the lock: reading calls the suite's own methods.
    TestEventLogEmitter.caught(SuiteStructure.read(suite, filter)) match {
      case Right(structure) =>
        val registration = new RegisteredSuite(this, suite, None)
        registration.fixAsTopLevelSuite(addTopLevelSuite(structure))
        registration.startAsTopLevelSuite(reporter)
      case Left(thrown) =>
        endWithoutRunEnded()
        TestEventLogEmitter.rethrowIfUncontained(thrown)
        SuiteStart.withoutEventLog(reporter)
    }
  }

  /**
   * Writes the structure. A top-level suite that shares its suite ID with an earlier one takes the next index, so no
   * two nodes of the run share a node path.
   */
  def addTopLevelSuite(structure: SuiteStructure): SuiteNodeTable = {
    val index = synchronized {
      val earlier = topLevelSuiteCountBySuiteId.getOrElse(structure.suiteId, 0)
      topLevelSuiteCountBySuiteId(structure.suiteId) = earlier + 1
      earlier
    }
    val (table, nodes) = SuiteNodeTable.ofTopLevelSuite(this, structure, index)
    append(TestEventLogEmitter.caught(TestEventLogEncoder.structure(nodes)), None)
    table
  }

  /**
   * Where the latest structure of the suite node of a registered instance's run lacks a test that the instance's
   * registration tree, `registered`, holds, adds the test and writes a further `structure` event. The tree is read
   * only where the structure lacks the test, as when a `runTests` override runs a test the filter drops. An instance
   * whose node table is not settled, or that has none, adds nothing.
   */
  def addTestWhereLacking(registration: RegisteredSuite, testName: String, registered: => Option[List[RegisteredNode]]): Unit = {
    val lacking = synchronized(Option.when(isOpen && registration.isTableSettled)(registration.table).flatten.filterNot(_.latestHoldsTest(testName)))
    lacking.foreach { table =>
      registered.foreach { tree =>
        // The latest structure is read again under the lock, since another instance of the suite may have changed it.
        underLockKeepingThrown {
          SuiteStructure.withTestAdded(tree, table.latestScopesAndTests, testName).flatMap(writeStructureWhereChanged(table, _))
        }
      }
    }
  }

  /**
   * Where the node table of a registered instance's run is settled, writes a further `structure` event of the scopes
   * and tests `scopesAndTestsFor` gives for its node table, where they differ from the suite's latest structure, and
   * makes them the latest. A scope or test an earlier structure of the suite held keeps its node. Otherwise the
   * registration keeps them, and they are written once its node table is settled.
   *
   * It writes under the lock, so that two instances of one suite that run at once write their structures one after
   * the other. What writing throws is kept from the caller as `writeRunEvent` keeps it.
   */
  def writeStructureOnceTableIsSettled(registration: RegisteredSuite)(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Unit = {
    underLockKeepingThrown {
      if (isOpen) {
        registration.structureOnceTableIsSettled(scopesAndTestsFor).flatMap(writeStructureWhereChanged(_, _))
      } else {
        None
      }
    }
  }

  /**
   * Fixes the node of a registered instance's run and settles its node table, after doing so for each instance whose
   * run it started in. Each uses the suite ID and suite name its run read; `suiteIdRead` and `suiteNameRead` are this
   * instance's, where its run has read none before. A node that no structure holds needs both: its table is added,
   * and its first structure written, once its name is known.
   *
   * Where a run has not read one of them and `handingOff` says an event is about to be handed off, the emitter reads
   * it from the instance, outside the lock, once at most, and only while the event log is open and the instance may
   * have a node table. An instance whose read throws, or that can have no node table, is left without one. Each node
   * fixed writes the structure its registration keeps for it, where that differs from its node table.
   *
   * An inner instance's node is placed below its outer one's, so it stays unsettled while an outer one is, as where
   * another thread is reading that one's suite ID. The names its run read are kept, and it is settled by a later
   * call once the outer node is.
   *
   * What reading a name or writing a structure throws is kept from the caller as `writeRunEvent` keeps it.
   */
  def fixNode(registration: RegisteredSuite, suiteIdRead: Option[String], suiteNameRead: Option[String], handingOff: Boolean): Unit = {
    val unsettled = synchronized {
      if (isOpen) {
        suiteIdRead.foreach(registration.keepSuiteIdItsRunRead)
        suiteNameRead.foreach(registration.keepSuiteNameItsRunRead)
        registration.unsettledFromOutermost
      } else {
        Nil
      }
    }
    // Stops at the first instance left unsettled: each instance after it is placed below it.
    val remaining = unsettled.iterator
    var isSettled = true
    while (isSettled && remaining.hasNext) {
      val one = remaining.next()
      isSettled = fixNodeOf(one, handingOff) && settleTableOf(one, handingOff)
    }
  }

  // Returns whether the node is fixed.
  private def fixNodeOf(registration: RegisteredSuite, handingOff: Boolean): Boolean = {
    val (suiteIdRead, readsSuiteId) = synchronized((registration.suiteIdReadByItsRun, isOpen && handingOff && registration.takeReadOfSuiteId()))
    val suiteId = if (readsSuiteId) TestEventLogEmitter.caught(Some(registration.readSuiteIdOfInstance())) else Right(suiteIdRead)
    val (thrown, isFixed) = synchronized {
      if (!isOpen) {
        (None, false)
      } else {
        val thrown = suiteId match {
          case Left(thrownByRead) =>
            registration.fixNode(None)
            Some(thrownByRead)
          case Right(Some(id)) =>
            TestEventLogEmitter.caught(registration.fixNode(Some(id))).fold(Some(_), _.flatMap(writeStructureWhereChanged(_, _)))
          case Right(None) =>
            // Read by another thread, which fixes the node, unless the instance can have no node table.
            if (!registration.canHaveTable) {
              registration.fixNode(None)
            }
            None
        }
        (thrown, registration.isNodeFixed)
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
    isFixed
  }

  // Returns whether the table is settled.
  private def settleTableOf(registration: RegisteredSuite, handingOff: Boolean): Boolean = {
    val (suiteNameRead, readsSuiteName) = synchronized((registration.suiteNameReadByItsRun, isOpen && handingOff && registration.takeReadOfSuiteName()))
    val suiteName = if (readsSuiteName) TestEventLogEmitter.caught(Some(registration.readSuiteNameOfInstance())) else Right(suiteNameRead)
    val (thrown, isSettled) = synchronized {
      if (!isOpen) {
        (None, false)
      } else if (registration.isTableSettled) {
        (None, true)
      } else {
        val thrown = suiteName match {
          case Left(thrownByRead) =>
            registration.addNewNode(None)
            Some(thrownByRead)
          case Right(Some(name)) =>
            TestEventLogEmitter.caught(registration.addNewNode(Some(name))).fold(Some(_), _.flatMap(writeStructure))
          case Right(None) =>
            None
        }
        (thrown, registration.isTableSettled)
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
    isSettled
  }

  // Encoding a structure calls no user code, so it is encoded under the lock that makes it the latest.
  private def writeStructureWhereChanged(table: SuiteNodeTable, scopesAndTests: List[ScopeOrTest]): Option[Throwable] = {
    assertLockHeld()
    if (isOpen && table.latestScopesAndTests != scopesAndTests) {
      writeStructure(table.replaceLatestStructure(scopesAndTests))
    } else {
      None
    }
  }

  private def writeStructure(nodes: Vector[StructureNode]): Option[Throwable] = {
    assertLockHeld()
    val written = TestEventLogEmitter.caught(write(TestEventLogEncoder.structure(nodes)))
    if (written.isLeft) {
      close()
    }
    written.left.toOption
  }

  def startRun(registration: RegisteredSuite): Unit = synchronized {
    registration.startRunOnceNodeIsFixed()
  }

  /**
   * Writes an event a send site hands off with its origin, assigned to the node and executions the origin names. It
   * keeps the event so that no structure wrapper it reaches hands it off again. The messages recorded in a test's
   * closing event are written before it, assigned as it is, unless `messagesHandedOff` says the test handed them off
   * one by one.
   *
   * First, where they are not yet, the node of the origin's suite instance is fixed and its node table settled.
   * Nothing is handed off where the event log has ended, the instance has no node table, or its top-level suite's
   * registration has been removed: a wrapper the event reaches then hands it off as an event with no origin. A test's
   * closing event sent after the removal, whose messages the test handed off, is marked so that the top-level suite's
   * wrapper writes it without them.
   *
   * The event is encoded outside the lock, as `writeRunEvent` encodes. What encoding or writing throws is kept from the
   * caller as `writeRunEvent` keeps it.
   */
  def handOff(origin: OriginInRegisteredSuite, event: Event, messagesHandedOff: Boolean): Unit = {
    val registration = origin.registration
    if (!registration.isTableSettled) {
      val names = TestEventLogEmitter.namesIn(event)
      fixNode(registration, names.map(_.suiteId), names.map(_.suiteName), handingOff = true)
    }
    if (synchronized(handsOffAtSendSite(registration, event, messagesHandedOff))) {
      val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff)
      underLockKeepingThrown {
        if (handsOffAtSendSite(registration, event, messagesHandedOff)) {
          handedOff.put(event, registration.topLevel)
          writeEvent(encoded, assignWithOrigin(origin, event))
        } else {
          None
        }
      }
    }
  }

  private def handsOffAtSendSite(registration: RegisteredSuite, event: Event, messagesHandedOff: Boolean): Boolean = {
    assertLockHeld()
    val hasTable = isOpen && registration.table.isDefined
    if (hasTable && registration.hasBeenRemoved && messagesHandedOff) {
      registration.topLevel.keepClosingEventWithMessagesHandedOff(event)
    }
    hasTable && !registration.hasBeenRemoved
  }

  /**
   * Marks a top-level suite's registration removed, once the suite's run has completed, and stops keeping the events
   * handed off for its run, at their send sites or by its inner wrappers, that have not reached its own wrapper. An
   * event whose reporter does not pass it on reaches no further wrapper, and would otherwise be kept until the event
   * log closes.
   *
   * Those events move to the top-level suite's registration, which its wrapper holds. One may still reach that
   * wrapper, as an alert from a thread the run started or an event a reporter between the wrappers held back. The
   * wrapper then writes nothing, since the event has been written.
   *
   * A nested suite's handed-off events stay here until then, since a reporter that holds events back, such as a
   * `TestSortingReporter`, may pass one on to the outer wrappers after the nested suite's run has completed. After the
   * removal, nothing is handed off for an event sent late until it reaches the top-level suite's wrapper, which writes
   * it.
   */
  def forget(registration: RegisteredSuite): Unit = synchronized {
    registration.markRemoved()
    val kept = handedOff.entrySet.iterator
    while (kept.hasNext) {
      val entry = kept.next()
      if (entry.getValue eq registration) {
        registration.keepHandedOffBeforeRemoval(entry.getKey)
        kept.remove()
      }
    }
  }

  def handedOffEventCount: Int = synchronized(handedOff.size)

  /**
   * Writes the event unless it has already been handed off, at its send site or by another wrapper. An event reaches
   * the wrapper of its test, of its suite and of each suite around that one; only the first wrapper that finds it not
   * handed off writes it.
   *
   * The event is assigned as one with no origin, from its own data: to the suite its suite ID names (the wrapper's own
   * suite, or one at or below the wrapper's node), and to the test its test name names in that suite. A suite that no
   * table at or below the wrapper's node holds is first given a node directly under the wrapper's node. A test that its
   * suite's table lacks is first given a node directly under its suite. Either node is written in a further structure.
   * A message that names no suite is assigned to the wrapper's node. The messages recorded in a test's closing event
   * are written before it, assigned as it is, unless the test has handed them off one by one.
   *
   * First, where they are not yet and the instance may have a node table, the node of the wrapper's suite instance is
   * fixed and its node table settled. A wrapper of an instance that has no node table hands nothing off, so the event
   * is handed off by a wrapper around it.
   *
   * What encoding or writing throws is kept from the caller as `writeRunEvent` keeps it.
   */
  def writeEventFrom(wrapper: WrapperNode, event: Event): Unit = {
    if (!wrapper.registration.isTableSettled && synchronized(isOpen && wrapper.registration.canHaveTable)) {
      fixNode(wrapper.registration, None, None, handingOff = true)
    }
    messagesHandedOffWhereFirstHandedOff(wrapper, event).foreach { messagesHandedOff =>
      // Encoded outside the lock, as `writeRunEvent` encodes.
      val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff)
      underLockKeepingThrown(if (isOpen) writeEvent(encoded, assignFromItsOwnData(wrapper, event)) else encoded.left.toOption)
    }
  }

  // The events are assigned only where they were encoded. Closes the event log where they cannot be assigned or
  // written.
  private def writeEvent(encoded: Either[Throwable, Vector[EncodedEvent]], assigned: => TestEventLogEmitter.Assignment): Option[Throwable] = {
    assertLockHeld()
    val written = encoded.flatMap { events =>
      TestEventLogEmitter.caught {
        val assignment = assigned
        assignment.furtherStructure.foreach(nodes => write(TestEventLogEncoder.structure(nodes)))
        events.foreach(one => write(TestEventLogEncoder.line(one, Some(assignment.execution))))
      }
    }
    if (written.isLeft) {
      close()
    }
    written.left.toOption
  }

  /**
   * Writes a message a test's informer or documenter handed off as the test called it, and that no reporter receives
   * as an event of its own, assigned to the test in the execution `origin` names. A registration that has been
   * removed, or whose instance has no node table, writes no message.
   *
   * `TestEventLogEmitter.builtTestMessage` builds and encodes the message outside the lock, as `writeRunEvent` encodes.
   * What building, encoding or writing it throws is kept from the caller as `writeRunEvent` keeps it.
   */
  def writeTestMessage(origin: OriginInRegisteredSuite, built: Either[Throwable, (Event, EncodedEvent)]): Unit = {
    val registration = origin.registration
    underLockKeepingThrown {
      if (isOpen && registration.table.isDefined && !registration.hasBeenRemoved) {
        built match {
          case Right((message, encoded)) => writeEvent(Right(Vector(encoded)), assignWithOrigin(origin, message))
          case Left(thrownByBuild) =>
            close()
            Some(thrownByBuild)
        }
      } else {
        built.left.toOption
      }
    }
  }

  // Returns `None` where this wrapper is not to hand the event off. Otherwise returns `Some` of whether the event is a
  // test's closing event whose messages the test handed off one by one. A wrapper of an instance with no node table
  // hands nothing off.
  // The top-level suite's wrapper is the last an event reaches, so it forgets the event. Once the top-level suite's
  // registration is removed, inner wrappers hand nothing off and that wrapper writes the event: an inner wrapper that
  // wrote it would have to keep it for that wrapper to skip, for good where it never reaches that wrapper. An event
  // handed off before the removal is kept by the top-level suite's registration instead.
  private def messagesHandedOffWhereFirstHandedOff(wrapper: WrapperNode, event: Event): Option[Boolean] = synchronized {
    val isLastWrapper = wrapper.isInstanceOf[WrapperNode.TopLevelSuite]
    if (!isOpen || wrapper.registration.table.isEmpty) {
      None
    } else if (isLastWrapper && wrapper.registration.takeHandedOffBeforeRemoval(event)) {
      None
    } else if (!isLastWrapper && wrapper.registration.topLevel.hasBeenRemoved) {
      None
    } else if (handedOff.containsKey(event)) {
      if (isLastWrapper) {
        handedOff.remove(event)
      }
      None
    } else {
      if (!isLastWrapper) {
        handedOff.put(event, wrapper.registration.topLevel)
      }
      Some(wrapper.registration.topLevel.takeClosingEventWithMessagesHandedOff(event))
    }
  }

  // Called where the origin's instance has a node table. Indexes the executions the origin names, where the event is
  // the first to name them. A scope or test the suite's structure does not hold has no node, so its event is assigned
  // to the suite.
  private def assignWithOrigin(origin: OriginInRegisteredSuite, event: Event): TestEventLogEmitter.Assignment = {
    assertLockHeld()
    val table = origin.registration.table.get
    val named = Some(origin.executionsOf(event))
    val execution = table.scopeOrTest(origin.node) match {
      case Some(scopeOrTest) => assignToScopeOrTest(table, scopeOrTest, named, event)
      case None => assignToSuite(table, named, event)
    }
    TestEventLogEmitter.Assignment(execution, None)
  }

  // The suite an event's data names is the wrapper's own suite, else one at or below the wrapper's node, and is
  // otherwise given a node directly under the wrapper's node. A test its suite's table lacks is given a node directly
  // under the suite, not marked ignored, since nothing filtered it. Either node is added to the suite's latest
  // structure, which the assignment returns for the caller to write before the event.
  private def assignFromItsOwnData(wrapper: WrapperNode, event: Event): TestEventLogEmitter.Assignment = {
    assertLockHeld()
    val ofWrapper = wrapper.registration.table.get
    val wrapperNode = ofWrapper.scopeOrTest(wrapper.treeNode)
    TestEventLogEmitter.namesIn(event).filterNot(_ => TestEventLogEmitter.isAbortOfDistributedTest(event)) match {
      case None =>
        val execution = wrapperNode match {
          case Some(scopeOrTest) => assignToScopeOrTest(ofWrapper, scopeOrTest, None, event)
          case None => assignToSuite(ofWrapper, None, event)
        }
        TestEventLogEmitter.Assignment(execution, None)
      case Some(names) =>
        val found = TestEventLogEmitter.tableNamed(ofWrapper, wrapperNode, names.suiteId)
        val table = found.getOrElse {
          ofWrapper.addSuiteUnder(wrapperNode.getOrElse(ofWrapper.node), names.suiteName, names.suiteId, names.suiteClassName.getOrElse(names.suiteId))
        }
        val lackedTest = names.testName.filterNot(table.testsByName.contains)
        val furtherStructure = Option.when(found.isEmpty || lackedTest.isDefined) {
          table.replaceLatestStructure(table.latestScopesAndTests ++ SuiteStructure.ofTestNames(lackedTest.toList.map(_ -> false)))
        }
        val execution = names.testName.flatMap(table.testsByName.get) match {
          case Some(test) => assignToScopeOrTest(table, test, None, event)
          case None => assignToSuite(table, None, event)
        }
        TestEventLogEmitter.Assignment(execution, furtherStructure)
    }
  }

  // Where the event has an origin, its node's execution and those above it are those the origin names.
  private def assignToScopeOrTest(table: SuiteNodeTable, node: NodeId, named: Option[NamedExecutions], event: Event): NodeExecution = {
    assertLockHeld()
    val executions = table.executionsOf(node)
    val execution = named.flatMap(_.ofOwnNode).getOrElse(TestEventLogEmitter.executionWithNoOrigin(executions, event))
    val assigned = NodeExecution(node, table.executionPathAbove(node, named) :+ executions.indexOf(execution))
    if (TestEventLogEmitter.closesItsScopeOrTest(event)) {
      executions.close(execution)
    }
    assigned
  }

  private def assignToSuite(table: SuiteNodeTable, named: Option[NamedExecutions], event: Event): NodeExecution = {
    assertLockHeld()
    val executions = table.executionsOf(table.node)
    val execution = named match {
      case Some(fromOrigin) => fromOrigin.ofSuite
      case None =>
        val opensAnExecution = executions.latestOpen.isEmpty && event.isInstanceOf[SuiteStarting]
        val withNoOrigin = TestEventLogEmitter.executionWithNoOrigin(executions, event)
        if (opensAnExecution) {
          table.keepForNextRun(withNoOrigin)
        }
        withNoOrigin
    }
    val assigned = NodeExecution(table.node, table.executionPathAboveSuite(named) :+ executions.indexOf(execution))
    if (event.isInstanceOf[SuiteCompleted] || event.isInstanceOf[SuiteAborted]) {
      executions.close(execution)
      table.forgetUnrunExecution(execution)
      table.markEnded()
    }
    assigned
  }

  def endWithoutRunEnded(): Unit = {
    synchronized {
      if (isOpen) {
        close()
      }
    }
  }

  // A line that could not be encoded or written closes the event log.
  private def append(encoded: Either[Throwable, String], runEnded: Option[String]): Unit = {
    underLockKeepingThrown {
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
  }

  private def write(line: String): Unit = {
    assertLockHeld()
    eventLog.write(line.getBytes(StandardCharsets.UTF_8))
  }

  private def close(): Unit = {
    assertLockHeld()
    isOpen = false
    handedOff.clear()
    try eventLog.close() catch { case contained: Throwable if TestEventLogEmitter.isContained(contained) => () }
  }
}

private[scalatest] object TestEventLogEmitter {

  // `furtherStructure`, present where assigning the event gave a suite or test a node, must be written before the event.
  private final case class Assignment(execution: NodeExecution, furtherStructure: Option[Vector[StructureNode]])

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
    executions.latestOpen.getOrElse(if (opensAnExecution) executions.newExecution() else executions.lastIndexed.getOrElse(executions.newExecution()))
  }

  def closesItsScopeOrTest(event: Event): Boolean = {
    event match {
      case _: TestSucceeded | _: TestFailed | _: TestPending | _: TestCanceled | _: TestIgnored => true
      case _: ScopeClosed | _: ScopePending => true
      case _ => false
    }
  }

  def startedIfGiven(eventLogFile: Option[String]): Option[TestEventLogEmitter] =
    eventLogFile.map(file => started(Paths.get(file), ProcessIdentity.current))

  /** The hand-off for the events a run's runner sends outside every structure wrapper. */
  def handOffTo(emitter: Option[TestEventLogEmitter]): Event => Unit = {
    emitter match {
      case Some(started) => started.writeRunEvent(_)
      case None => TestEventLogHandOff.none
    }
  }

  def startOfTopLevelSuiteFor(emitter: Option[TestEventLogEmitter]): (Suite, Filter, Reporter) => SuiteStart = {
    emitter match {
      case Some(started) => started.startOfTopLevelSuite(_, _, _)
      case None => TestEventLogHandOff.startWithoutEventLog
    }
  }

  def builtTestMessage(message: => Event): Either[Throwable, (Event, EncodedEvent)] = {
    caught {
      val built = message
      (built, TestEventLogEncoder.encoded(built))
    }
  }

  def keptFromCaller(handOff: => Unit): Unit = caught(handOff).left.foreach(rethrowIfUncontained)

  /**
   * The `runStarted` event is written to a file of another name and moved into place, so the event log file never exists
   * without its whole `runStarted` event.
   *
   * @throws IllegalStateException where the process has no identity to write
   */
  def started(eventLogFile: Path, processIdentity: Either[String, ProcessIdentity]): TestEventLogEmitter = {
    processIdentity match {
      case Right(identity) =>
        val runStarted = TestEventLogEncoder.runStarted(identity.pid, identity.startTime.toString)
        val unmoved = eventLogFile.resolveSibling(eventLogFile.getFileName.toString + ".tmp")
        // Created anew, so a link planted under its name in a shared folder is never followed. A file an earlier run
        // left there is replaced.
        if (Files.isRegularFile(unmoved, LinkOption.NOFOLLOW_LINKS)) {
          Files.delete(unmoved)
        }
        Files.write(unmoved, runStarted.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.move(unmoved, eventLogFile, StandardCopyOption.ATOMIC_MOVE)
        // Not a FileChannel: interrupting a thread that is writing to one closes it for every thread.
        appendingTo(new FileOutputStream(eventLogFile.toFile, true))
      case Left(reason) =>
        throw new IllegalStateException(s"Cannot write the test event log $eventLogFile: $reason")
    }
  }

  /** `eventLog` must already hold the run's `runStarted` event. */
  def appendingTo(eventLog: OutputStream): TestEventLogEmitter = new TestEventLogEmitter(eventLog)

  def encodedWithRecorded(event: Event, messagesHandedOff: Boolean): Either[Throwable, Vector[EncodedEvent]] = {
    val recorded = if (messagesHandedOff) Vector.empty else recordedIn(event)
    caught(recorded.map(TestEventLogEncoder.encoded) :+ TestEventLogEncoder.encoded(event))
  }

  final case class NamesInEvent(suiteName: String, suiteId: String, suiteClassName: Option[String], testName: Option[String])

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

  // A `DistributedTestRunnerSuite` runs one test, so its `SuiteAborted` is assigned to that test's node.
  private def isAbortOfDistributedTest(event: Event): Boolean = {
    event match {
      case aborted: SuiteAborted => aborted.suiteClassName.contains(classOf[DistributedTestRunnerSuite].getName)
      case _ => false
    }
  }

  // The table of the suite with this suite ID, named by an event a wrapper hands off. It is the wrapper's own suite,
  // `table`, where that has the suite ID. Otherwise it is the first not yet ended of the suites with that ID at or below
  // the wrapper's node, else the first of them. The wrapper's node is `test` for a test's wrapper, else its suite's.
  // Recurses once for each suite nested in another.
  private def tableNamed(table: SuiteNodeTable, test: Option[NodeId], suiteId: String): Option[SuiteNodeTable] = {
    def nestedWithin(suite: SuiteNodeTable): Vector[SuiteNodeTable] = suite.childSuites.flatMap(nested => nested +: nestedWithin(nested))
    if (table.suiteId == suiteId) {
      Some(table)
    } else {
      val underWrapper = test.fold(table.childSuites)(node => table.childSuites.filter(_.parent.contains(node)))
      val sharingItsId = underWrapper.flatMap(nested => nested +: nestedWithin(nested)).filter(_.suiteId == suiteId)
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
}
