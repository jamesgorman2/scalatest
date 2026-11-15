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
  // How many top-level suites of each suite ID the run has written. Read and written only under this emitter's lock.
  private val topLevelSuitesOfSuiteId = scala.collection.mutable.HashMap.empty[String, Int]
  // The events handed off at a send site or by an inner wrapper that have not reached their top-level suite's
  // wrapper, each mapped to that suite's registration. Keyed by the event's identity, since an event's `equals`
  // compares its fields, which hold the run's own objects. Read and written only under this emitter's lock.
  private val handedOff = new IdentityHashMap[Event, RegisteredSuite]

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
   * whose node table is not settled, or that has none, adds nothing.
   */
  def addTestWhereLacking(registration: RegisteredSuite, testName: String, registered: => Option[List[RegisteredNode]]): Unit = {
    val lacking = synchronized(Option.when(isOpen && registration.isTableSettled)(registration.table).flatten.filterNot(_.latestHoldsTest(testName)))
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
   * Where the node table of a registered instance's run is settled, writes a further `structure` event of the scopes
   * and tests `scopesAndTestsFor` gives for its node table, where they differ from the suite's latest structure, and
   * makes them the latest. A scope or test an earlier structure of the suite held keeps its node. Otherwise the
   * registration keeps them, and they are written once its node table is settled.
   *
   * It writes under the lock, so that two instances of one suite that run at once write their structures one after
   * the other. What writing throws is kept from the caller as `runEvent` keeps it.
   */
  def writeStructureOnceTableIsSettled(registration: RegisteredSuite)(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Unit = {
    val thrown = synchronized {
      if (isOpen) {
        registration.structureOnceTableIsSettled(scopesAndTestsFor).flatMap(writeStructureWhereChangedUnderLock(_, _))
      } else {
        None
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
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
   * What reading a name or writing a structure throws is kept from the caller as `runEvent` keeps it.
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
    unsettled.forall(one => fixNodeOf(one, handingOff) && settleTableOf(one, handingOff))
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
            TestEventLogEmitter.caught(registration.fixNode(Some(id))).fold(Some(_), _.flatMap(writeStructureWhereChangedUnderLock(_, _)))
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

  // Settles the node table of a fixed node, and returns whether it is settled. Where no structure holds the node and
  // its suite name is known, it adds the table and writes its first structure.
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
            TestEventLogEmitter.caught(registration.addNewNode(Some(name))).fold(Some(_), _.flatMap(writeStructureUnderLock))
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
  private def writeStructureWhereChangedUnderLock(table: SuiteNodeTable, scopesAndTests: List[ScopeOrTest]): Option[Throwable] = {
    if (isOpen && table.scopesAndTests != scopesAndTests) {
      writeStructureUnderLock(table.replaceLatestStructure(scopesAndTests))
    } else {
      None
    }
  }

  private def writeStructureUnderLock(nodes: Vector[StructureNode]): Option[Throwable] = {
    val written = TestEventLogEmitter.caught(write(TestEventLogEncoder.structure(nodes)))
    if (written.isLeft) {
      close()
    }
    written.left.toOption
  }

  /** Starts an execution for a run of a registered suite instance, once the node of the run is fixed. */
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
   * The event is encoded outside the lock, as `runEvent` encodes. What encoding or writing throws is kept from the
   * caller as `runEvent` keeps it.
   */
  def handOff(origin: OriginInRegisteredSuite, event: Event, messagesHandedOff: Boolean): Unit = {
    val registration = origin.registration
    if (!registration.isTableSettled) {
      val names = TestEventLogEmitter.namesIn(event)
      fixNode(registration, names.map(_.suiteId), names.map(_.suiteName), handingOff = true)
    }
    if (synchronized(handsOffAtSendSite(registration, event, messagesHandedOff))) {
      val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff)
      val thrown = synchronized {
        if (handsOffAtSendSite(registration, event, messagesHandedOff)) {
          handedOff.put(event, registration.topLevel)
          writeUnderLock(encoded, assignWithOrigin(origin, event))
        } else {
          None
        }
      }
      thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
    }
  }

  // Called under the lock.
  private def handsOffAtSendSite(registration: RegisteredSuite, event: Event, messagesHandedOff: Boolean): Boolean = {
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

  /** How many events this emitter keeps as handed off, for a top-level suite whose run has not completed. */
  def keptEventCount: Int = synchronized(handedOff.size)

  /**
   * Takes an event a structure wrapper received, with the wrapper's node, and writes it unless it has been handed off,
   * at its send site or by another wrapper: an event reaches the wrapper of its test, of its suite and of each suite
   * around that one, and only the first of them that finds it not handed off writes it.
   *
   * The event is assigned as one with no origin, from its own data: to the suite its suite ID names, the wrapper's own
   * suite or one at or below the wrapper's node, and to the test its test name names in that suite. A suite no table
   * at or below the wrapper's node holds is first given a node directly under the wrapper's node, and a test its
   * suite's table lacks a node directly under its suite, in a further structure. A message that names no suite is
   * assigned to the wrapper's node. The
   * messages recorded in a test's closing event are written before it, assigned as it is, unless the test has handed
   * them off one by one.
   *
   * First, where they are not yet and the instance may have a node table, the node of the wrapper's suite instance is
   * fixed and its node table settled. A wrapper of an instance that has no node table hands nothing off, so the event
   * is handed off by a wrapper around it.
   *
   * What encoding or writing throws is kept from the caller as `runEvent` keeps it.
   */
  def writeSuiteEvent(wrapper: WrapperNode, event: Event): Unit = {
    if (!wrapper.registration.isTableSettled && synchronized(isOpen && wrapper.registration.canHaveTable)) {
      fixNode(wrapper.registration, None, None, handingOff = true)
    }
    messagesHandedOffWhereFirstHandedOff(wrapper, event).foreach { messagesHandedOff =>
      // Encoded outside the lock, as `runEvent` encodes.
      val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff)
      val thrown = synchronized(if (isOpen) writeUnderLock(encoded, assignFromItsOwnData(wrapper, event)) else encoded.left.toOption)
      thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
    }
  }

  // Writes the lines of an event and of the messages its closing event records, each assigned as `assigned` gives,
  // closing the event log where they cannot be written.
  private def writeUnderLock(encoded: Either[Throwable, Vector[EncodedEvent]], assigned: => NodeExecution): Option[Throwable] = {
    val written = encoded.flatMap { events =>
      TestEventLogEmitter.caught {
        val execution = assigned
        events.foreach(one => write(TestEventLogEncoder.line(one, Some(execution))))
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
   * `TestEventLogEmitter.builtTestMessage` builds and encodes the message outside the lock, as `runEvent` encodes.
   * What building, encoding or writing it throws is kept from the caller as `runEvent` keeps it.
   */
  def writeTestMessage(origin: OriginInRegisteredSuite, built: Either[Throwable, (Event, EncodedEvent)]): Unit = {
    val registration = origin.registration
    val thrown = synchronized {
      if (isOpen && registration.table.isDefined && !registration.hasBeenRemoved) {
        built match {
          case Right((message, encoded)) => writeUnderLock(Right(Vector(encoded)), assignWithOrigin(origin, message))
          case Left(thrownByBuild) =>
            close()
            Some(thrownByBuild)
        }
      } else {
        built.left.toOption
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
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

  // Called under the lock, where the origin's instance has a node table. Numbers the executions the origin names,
  // where the event is the first to name them. A scope or test the suite's structure does not hold has no node, so
  // its event is assigned to the suite.
  private def assignWithOrigin(origin: OriginInRegisteredSuite, event: Event): NodeExecution = {
    val table = origin.registration.table.get
    val named = Some(origin.executionsOf(event))
    table.scopeOrTest(origin.node) match {
      case Some(scopeOrTest) => assignToScopeOrTest(table, scopeOrTest, named, event)
      case None => assignToSuite(table, named, event)
    }
  }

  // Called under the lock. The suite an event's data names is the wrapper's own suite, else one at or below the
  // wrapper's node, and is otherwise given a node directly under the wrapper's node. A test its suite's table lacks is
  // given a node directly under the suite. Either node is written in a further structure.
  private def assignFromItsOwnData(wrapper: WrapperNode, event: Event): NodeExecution = {
    val ofWrapper = wrapper.registration.table.get
    val wrapperNode = ofWrapper.scopeOrTest(wrapper.treeNode)
    TestEventLogEmitter.namesIn(event).filterNot(_ => TestEventLogEmitter.isAbortOfDistributedTest(event)) match {
      case None =>
        wrapperNode match {
          case Some(test) => assignToScopeOrTest(ofWrapper, test, None, event)
          case None => assignToSuite(ofWrapper, None, event)
        }
      case Some(names) =>
        val found = TestEventLogEmitter.tableNamed(ofWrapper, wrapperNode, names.suiteId)
        val table = found.getOrElse {
          ofWrapper.addSuiteUnder(wrapperNode.getOrElse(ofWrapper.node), names.suiteName, names.suiteId, names.suiteClassName.getOrElse(names.suiteId))
        }
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

  // Where the event has an origin, its node's execution and those above it are those the origin names.
  private def assignToScopeOrTest(table: SuiteNodeTable, node: NodeId, named: Option[NamedExecutions], event: Event): NodeExecution = {
    val executions = table.executionsOf(node)
    val execution = named.flatMap(_.ofOwnNode).getOrElse(TestEventLogEmitter.executionWithNoOrigin(executions, event))
    val assigned = NodeExecution(node, table.executionPathAbove(node, named) :+ executions.number(execution))
    if (TestEventLogEmitter.closesItsScopeOrTest(event)) {
      executions.close(execution)
    }
    assigned
  }

  private def assignToSuite(table: SuiteNodeTable, named: Option[NamedExecutions], event: Event): NodeExecution = {
    val executions = table.executionsOf(table.node)
    val execution = named match {
      case Some(fromOrigin) => fromOrigin.ofSuite
      case None =>
        val opensAnExecution = executions.openedLast.isEmpty && event.isInstanceOf[SuiteStarting]
        val withNoOrigin = TestEventLogEmitter.executionWithNoOrigin(executions, event)
        if (opensAnExecution) {
          table.awaitRun(withNoOrigin)
        }
        withNoOrigin
    }
    val assigned = NodeExecution(table.node, table.executionPathAboveSuite(named) :+ executions.number(execution))
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
    handedOff.clear()
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
  def builtTestMessage(message: => Event): Either[Throwable, (Event, EncodedEvent)] = {
    caught {
      val built = message
      (built, TestEventLogEncoder.encoded(built))
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

  // The table of the suite of this suite ID that an event a wrapper hands off names: the wrapper's own suite, `table`,
  // where it has that suite ID, else the first that has not ended of the suites at or below the wrapper's node, else
  // the first of them. The wrapper's node is `test` where the wrapper is a test's, and otherwise its suite's. Recurses
  // once for each suite nested in another.
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

  private def outcomeOfClosing(event: Event): Option[RunOutcome] = {
    event match {
      case _: RunCompleted => Some(RunOutcome.Completed)
      case _: RunStopped => Some(RunOutcome.Stopped)
      case _: RunAborted => Some(RunOutcome.Aborted)
      case _ => None
    }
  }
}
