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
import org.scalatest.events.SuiteId
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestCanceled
import org.scalatest.events.TestEventLogEncoder
import org.scalatest.events.TestFailed
import org.scalatest.events.TestIgnored
import org.scalatest.events.TestName
import org.scalatest.events.TestPending
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded

private[scalatest] final class TestEventLogEmitter private (eventLog: OutputStream) {

  private val isOpen = new GuardedByEmitter(this, true)
  private val topLevelSuiteCountBySuiteId = new GuardedByEmitter(this, scala.collection.mutable.HashMap.empty[SuiteId, Int])
  // By identity: an event's `equals` compares its fields, which hold the run's own objects. Mapped to the top-level
  // suite's registration, not that of the suite that sent the event: a reporter that holds events back, such as a
  // `TestSortingReporter`, may pass one on to the outer wrappers after a nested suite's run has completed.
  private val handedOffAwaitingTopLevelWrapper = new GuardedByEmitter(this, new IdentityHashMap[Event, RegisteredSuite])

  def assertLockHeld(): Unit = assert(Thread.holdsLock(this), "The test event log emitter's lock is not held")

  private def underLockKeepingThrown(body: => Option[Throwable]): Unit =
    synchronized(body).foreach(TestEventLogEmitter.rethrowUnlessKeptFromCaller)

  def writeRunEvent(event: Event): Unit = {
    val encoded = TestEventLogEmitter.caughtOutsideLock(TestEventLogEncoder.event(event, None))
    append(encoded, RunOutcome.reportedBy(event).map(TestEventLogEncoder.runEnded))
  }

  def startOfTopLevelSuite(suite: Suite, filter: Filter, reporter: Reporter): SuiteStart = {
    TestEventLogEmitter.caughtOutsideLock(SuiteStructure.read(suite, filter)) match {
      case Right(structure) =>
        val registration = new RegisteredSuite(this, suite, None)
        registration.fixAsTopLevelSuite(addTopLevelSuite(structure))
        registration.startAsTopLevelSuite(reporter)
      case Left(thrown) =>
        endWithoutRunEnded()
        TestEventLogEmitter.rethrowUnlessKeptFromCaller(thrown)
        SuiteStart.withoutEventLog(reporter)
    }
  }

  def addTopLevelSuite(structure: SuiteStructure): SuiteNodeTable = {
    // A top-level suite sharing its suite ID with an earlier one takes the next index, so no two nodes share a node path.
    val index = synchronized {
      val countBySuiteId = topLevelSuiteCountBySuiteId.get
      val earlier = countBySuiteId.getOrElse(structure.suiteId, 0)
      countBySuiteId(structure.suiteId) = earlier + 1
      earlier
    }
    val (table, nodes) = SuiteNodeTable.ofTopLevelSuite(this, structure, index)
    append(TestEventLogEmitter.caught(TestEventLogEncoder.structure(nodes)), None)
    table
  }

  def addTestWhereLacking(registration: RegisteredSuite, testName: TestName, registered: => Option[List[RegisteredNode]]): Unit = {
    // The latest structure lacks a test that a `runTests` override runs though the filter drops it.
    val lacking = synchronized(Option.when(isOpen.get && registration.isTableSettled)(registration.table).flatten.filterNot(_.latestHoldsTest(testName)))
    lacking.foreach { table =>
      registered.foreach { tree =>
        // The latest structure is read again under the lock, since another instance of the suite may have changed it.
        underLockKeepingThrown {
          SuiteStructure.withTestAdded(tree, table.latestScopesAndTests, testName).flatMap(writeStructureWhereChanged(table, _))
        }
      }
    }
  }

  def writeStructureOnceTableIsSettled(registration: RegisteredSuite)(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Unit = {
    underLockKeepingThrown {
      if (isOpen.get) {
        registration.structureOnceTableIsSettled(scopesAndTestsFor).flatMap(writeStructureWhereChanged(_, _))
      } else {
        None
      }
    }
  }

  def fixNode(registration: RegisteredSuite, suiteIdRead: Option[SuiteId], suiteNameRead: Option[String], handingOff: Boolean): Unit = {
    val unsettled = synchronized {
      if (isOpen.get) {
        suiteIdRead.foreach(registration.keepSuiteIdItsRunRead)
        suiteNameRead.foreach(registration.keepSuiteNameItsRunRead)
        registration.unsettledFromOutermost
      } else {
        Nil
      }
    }
    // Stops at the first instance left unsettled: each later instance nests within it, so it cannot settle until that
    // one does.
    val remaining = unsettled.iterator
    var isSettled = true
    while (isSettled && remaining.hasNext) {
      val one = remaining.next()
      isSettled = tryFixNode(one, handingOff) && trySettleTable(one, handingOff)
    }
  }

  private def tryFixNode(registration: RegisteredSuite, handingOff: Boolean): Boolean = {
    val (suiteIdRead, readsSuiteId) = synchronized((registration.suiteIdReadByItsRun, isOpen.get && handingOff && registration.takeReadOfSuiteId()))
    val suiteId = if (readsSuiteId) TestEventLogEmitter.caughtOutsideLock(Some(registration.readSuiteIdOfInstance())) else Right(suiteIdRead)
    val (thrown, isFixed) = synchronized {
      if (!isOpen.get) {
        (None, false)
      } else {
        val thrown = suiteId match {
          case Left(thrownByRead) =>
            registration.fixNode(None)
            Some(thrownByRead)
          case Right(Some(id)) =>
            TestEventLogEmitter.caught(registration.fixNode(Some(id))).fold(Some(_), _.flatMap(writeStructureWhereChanged(_, _)))
          case Right(None) =>
            // Whoever reads the suite ID, the instance's run or another thread handing off, fixes the node. An
            // instance that can have no node table is fixed here, without one.
            if (!registration.canHaveTable) {
              registration.fixNode(None)
            }
            None
        }
        (thrown, registration.isNodeFixed)
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowUnlessKeptFromCaller)
    isFixed
  }

  private def trySettleTable(registration: RegisteredSuite, handingOff: Boolean): Boolean = {
    val (suiteNameRead, readsSuiteName) = synchronized((registration.suiteNameReadByItsRun, isOpen.get && handingOff && registration.takeReadOfSuiteName()))
    val suiteName = if (readsSuiteName) TestEventLogEmitter.caughtOutsideLock(Some(registration.readSuiteNameOfInstance())) else Right(suiteNameRead)
    val (thrown, isSettled) = synchronized {
      if (!isOpen.get) {
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
    thrown.foreach(TestEventLogEmitter.rethrowUnlessKeptFromCaller)
    isSettled
  }

  private def writeStructureWhereChanged(table: SuiteNodeTable, scopesAndTests: List[ScopeOrTest]): Option[Throwable] = {
    assertLockHeld()
    if (isOpen.get && table.latestScopesAndTests != scopesAndTests) {
      writeStructure(table.replaceLatestStructure(scopesAndTests))
    } else {
      None
    }
  }

  private def writeStructure(nodes: Vector[StructureNode]): Option[Throwable] = {
    assertLockHeld()
    // Encoded under the lock, so structures are written in the order they become the latest. This is safe because
    // encoding a structure calls none of the run's own code.
    val written = TestEventLogEmitter.caught(write(TestEventLogEncoder.structure(nodes)))
    if (written.isLeft) {
      close()
    }
    written.left.toOption
  }

  def startRun(registration: RegisteredSuite): Unit = synchronized {
    registration.startRunOnceNodeIsFixed()
  }

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
          handedOffAwaitingTopLevelWrapper.get.put(event, registration.topLevel)
          writeEvent(encoded, assignWithOrigin(origin, event))
        } else {
          None
        }
      }
    }
  }

  private def handsOffAtSendSite(registration: RegisteredSuite, event: Event, messagesHandedOff: Boolean): Boolean = {
    assertLockHeld()
    val hasTable = isOpen.get && registration.table.isDefined
    if (hasTable && registration.hasBeenRemoved && messagesHandedOff) {
      registration.topLevel.keepClosingEventWithMessagesHandedOff(event)
    }
    hasTable && !registration.hasBeenRemoved
  }

  def forget(topLevel: RegisteredSuite): Unit = synchronized {
    topLevel.markRemoved()
    // An event whose reporter does not pass it on never reaches the top-level suite's wrapper, so would stay in
    // `handedOffAwaitingTopLevelWrapper` until the event log closes. The registration keeps it instead, for that wrapper
    // to skip should it still arrive late as:
    // - an alert from a thread the run started;
    // - an event a reporter between the wrappers held back.
    val kept = handedOffAwaitingTopLevelWrapper.get.entrySet.iterator
    while (kept.hasNext) {
      val entry = kept.next()
      if (entry.getValue eq topLevel) {
        topLevel.keepHandedOffBeforeRemoval(entry.getKey)
        kept.remove()
      }
    }
  }

  def handedOffEventCount: Int = synchronized(handedOffAwaitingTopLevelWrapper.get.size)

  def writeEventFrom(wrapper: WrapperNode, event: Event): Unit = {
    if (!wrapper.registration.isTableSettled && synchronized(isOpen.get && wrapper.registration.canHaveTable)) {
      fixNode(wrapper.registration, None, None, handingOff = true)
    }
    messagesHandedOffWhereFirstHandedOff(wrapper, event).foreach { messagesHandedOff =>
      val encoded = TestEventLogEmitter.encodedWithRecorded(event, messagesHandedOff)
      underLockKeepingThrown(if (isOpen.get) writeEvent(encoded, assignFromItsOwnData(wrapper, event)) else encoded.left.toOption)
    }
  }

  private def writeEvent(encoded: Either[Throwable, Vector[EncodedEvent]], assigned: => TestEventLogEmitter.Assignment): Option[Throwable] = {
    assertLockHeld()
    val written = encoded.flatMap { events =>
      TestEventLogEmitter.caught {
        val assignment = assigned
        // Written before the events, since they may name the nodes it adds.
        assignment.furtherStructure.foreach(nodes => write(TestEventLogEncoder.structure(nodes)))
        events.foreach(one => write(TestEventLogEncoder.line(one, Some(assignment.execution))))
      }
    }
    if (written.isLeft) {
      close()
    }
    written.left.toOption
  }

  def writeTestMessage(origin: OriginInRegisteredSuite, built: Either[Throwable, (Event, EncodedEvent)]): Unit = {
    val registration = origin.registration
    underLockKeepingThrown {
      if (isOpen.get && registration.table.isDefined && !registration.hasBeenRemoved) {
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

  private def messagesHandedOffWhereFirstHandedOff(wrapper: WrapperNode, event: Event): Option[Boolean] = synchronized {
    val isLastWrapper = wrapper.isInstanceOf[WrapperNode.TopLevelSuite]
    val awaitingTopLevelWrapper = handedOffAwaitingTopLevelWrapper.get
    if (!isOpen.get || wrapper.registration.table.isEmpty) {
      None
    } else if (isLastWrapper && wrapper.registration.takeHandedOffBeforeRemoval(event)) {
      None
    } else if (!isLastWrapper && wrapper.registration.topLevel.hasBeenRemoved) {
      // Left for the top-level suite's wrapper to write. If an inner wrapper wrote it, the event would have to be kept
      // for that wrapper to skip, until the event log closes if it never arrives there.
      None
    } else if (awaitingTopLevelWrapper.containsKey(event)) {
      if (isLastWrapper) {
        awaitingTopLevelWrapper.remove(event)
      }
      None
    } else {
      if (!isLastWrapper) {
        awaitingTopLevelWrapper.put(event, wrapper.registration.topLevel)
      }
      Some(wrapper.registration.topLevel.takeClosingEventWithMessagesHandedOff(event))
    }
  }

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

  private def assignFromItsOwnData(wrapper: WrapperNode, event: Event): TestEventLogEmitter.Assignment = {
    assertLockHeld()
    val wrapperTable = wrapper.registration.table.get
    val wrapperNode = wrapperTable.scopeOrTest(wrapper.treeNode)
    // A `DistributedTestRunnerSuite` runs one test, so its abort is assigned to the wrapper's node, which is that test's node.
    TestEventLogEmitter.namesIn(event).filterNot(_ => TestEventLogEmitter.isAbortOfDistributedTest(event)) match {
      case None =>
        val execution = wrapperNode match {
          case Some(scopeOrTest) => assignToScopeOrTest(wrapperTable, scopeOrTest, None, event)
          case None => assignToSuite(wrapperTable, None, event)
        }
        TestEventLogEmitter.Assignment(execution, None)
      case Some(names) =>
        val found = TestEventLogEmitter.tableNamed(wrapperTable, wrapperNode, names.suiteId)
        val table = found.getOrElse {
          wrapperTable.addSuiteUnder(wrapperNode.getOrElse(wrapperTable.node), names.suiteName, names.suiteId, names.suiteClassName.getOrElse(names.suiteId.value))
        }
        val lackedTest = names.testName.filterNot(table.testsByName.contains)
        val isIgnoredByFilter = false
        val furtherStructure = Option.when(found.isEmpty || lackedTest.isDefined) {
          table.replaceLatestStructure(table.latestScopesAndTests ++ SuiteStructure.ofTestNames(lackedTest.toList.map(_.value -> isIgnoredByFilter)))
        }
        val execution = names.testName.flatMap(table.testsByName.get) match {
          case Some(test) => assignToScopeOrTest(table, test, None, event)
          case None => assignToSuite(table, None, event)
        }
        TestEventLogEmitter.Assignment(execution, furtherStructure)
    }
  }

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
      if (isOpen.get) {
        close()
      }
    }
  }

  private def append(encoded: Either[Throwable, String], runEnded: Option[String]): Unit = {
    underLockKeepingThrown {
      if (isOpen.get) {
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
    isOpen.set(false)
    handedOffAwaitingTopLevelWrapper.get.clear()
    try eventLog.close() catch { case kept: Throwable if TestEventLogEmitter.isKeptFromCaller(kept) => () }
  }
}

private[scalatest] object TestEventLogEmitter {

  private final case class Assignment(execution: NodeExecution, furtherStructure: Option[Vector[StructureNode]])

  private def messagesRecordedIn(event: Event): Vector[RecordableEvent] = {
    event match {
      case closing: TestSucceeded => closing.recordedEvents.toVector
      case closing: TestFailed => closing.recordedEvents.toVector
      case closing: TestPending => closing.recordedEvents.toVector
      case closing: TestCanceled => closing.recordedEvents.toVector
      case _ => Vector.empty
    }
  }

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

  def startedIfGiven(eventLogFile: Option[String]): Option[TestEventLogEmitter] = startedIfGiven(eventLogFile, ProcessIdentity.current)

  def startedIfGiven(eventLogFile: Option[String], processIdentity: => ProcessIdentity): Option[TestEventLogEmitter] = {
    eventLogFile.map { file =>
      val path = Paths.get(file)
      started(path, identityToWriteIn(path, processIdentity))
    }
  }

  private def identityToWriteIn(eventLogFile: Path, processIdentity: => ProcessIdentity): ProcessIdentity = {
    try {
      processIdentity
    } catch {
      case unreadable: IllegalStateException =>
        throw new IllegalStateException(s"Cannot write the test event log $eventLogFile: ${unreadable.getMessage}", unreadable)
    }
  }

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
    caughtOutsideLock {
      val built = message
      (built, TestEventLogEncoder.encoded(built))
    }
  }

  def keptFromCaller(handOff: => Unit): Unit = caught(handOff).left.foreach(rethrowUnlessKeptFromCaller)

  def started(eventLogFile: Path, processIdentity: ProcessIdentity): TestEventLogEmitter = {
    val runStarted = TestEventLogEncoder.runStarted(processIdentity.pid, processIdentity.startTime.toString)
    val unmoved = eventLogFile.resolveSibling(eventLogFile.getFileName.toString + ".tmp")
    if (Files.isRegularFile(unmoved, LinkOption.NOFOLLOW_LINKS)) {
      Files.delete(unmoved)
    }
    // Created anew, so a link planted under its name in a shared folder is never followed.
    Files.write(unmoved, runStarted.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    // Moved into place whole, so the event log file never exists without its whole `runStarted` event.
    Files.move(unmoved, eventLogFile, StandardCopyOption.ATOMIC_MOVE)
    // Unbuffered, so a reader sees each event as soon as it is handed off. Not a FileChannel: interrupting a thread
    // that is writing to one closes it for every thread.
    appendingTo(new FileOutputStream(eventLogFile.toFile, true))
  }

  def appendingTo(eventLogHoldingRunStarted: OutputStream): TestEventLogEmitter = new TestEventLogEmitter(eventLogHoldingRunStarted)

  def encodedWithRecorded(event: Event, messagesHandedOff: Boolean): Either[Throwable, Vector[EncodedEvent]] = {
    // A test's recorded messages reach no reporter as events of their own, so are written with its closing event.
    val recorded = if (messagesHandedOff) Vector.empty else messagesRecordedIn(event)
    caughtOutsideLock(recorded.map(TestEventLogEncoder.encoded) :+ TestEventLogEncoder.encoded(event))
  }

  final case class NamesInEvent(suiteName: String, suiteId: SuiteId, suiteClassName: Option[String], testName: Option[TestName])

  object NamesInEvent {

    def from(suiteName: String, suiteId: String, suiteClassName: Option[String], testName: Option[String]): NamesInEvent =
      NamesInEvent(suiteName, SuiteId(suiteId), suiteClassName, testName.map(TestName(_)))
  }

  def namesIn(event: Event): Option[NamesInEvent] = {
    def of(nameInfo: NameInfo): NamesInEvent = NamesInEvent.from(nameInfo.suiteName, nameInfo.suiteId, nameInfo.suiteClassName, nameInfo.testName)
    event match {
      case testEvent: TestStarting => Some(NamesInEvent.from(testEvent.suiteName, testEvent.suiteId, testEvent.suiteClassName, Some(testEvent.testName)))
      case testEvent: TestSucceeded => Some(NamesInEvent.from(testEvent.suiteName, testEvent.suiteId, testEvent.suiteClassName, Some(testEvent.testName)))
      case testEvent: TestFailed => Some(NamesInEvent.from(testEvent.suiteName, testEvent.suiteId, testEvent.suiteClassName, Some(testEvent.testName)))
      case testEvent: TestIgnored => Some(NamesInEvent.from(testEvent.suiteName, testEvent.suiteId, testEvent.suiteClassName, Some(testEvent.testName)))
      case testEvent: TestPending => Some(NamesInEvent.from(testEvent.suiteName, testEvent.suiteId, testEvent.suiteClassName, Some(testEvent.testName)))
      case testEvent: TestCanceled => Some(NamesInEvent.from(testEvent.suiteName, testEvent.suiteId, testEvent.suiteClassName, Some(testEvent.testName)))
      case suiteEvent: SuiteStarting => Some(NamesInEvent.from(suiteEvent.suiteName, suiteEvent.suiteId, suiteEvent.suiteClassName, None))
      case suiteEvent: SuiteCompleted => Some(NamesInEvent.from(suiteEvent.suiteName, suiteEvent.suiteId, suiteEvent.suiteClassName, None))
      case suiteEvent: SuiteAborted => Some(NamesInEvent.from(suiteEvent.suiteName, suiteEvent.suiteId, suiteEvent.suiteClassName, None))
      case scopeEvent: ScopeOpened => Some(of(scopeEvent.nameInfo).copy(testName = None))
      case scopeEvent: ScopeClosed => Some(of(scopeEvent.nameInfo).copy(testName = None))
      case scopeEvent: ScopePending => Some(of(scopeEvent.nameInfo).copy(testName = None))
      case provided: InfoProvided => provided.nameInfo.map(of)
      case provided: NoteProvided => provided.nameInfo.map(of)
      case provided: AlertProvided => provided.nameInfo.map(of)
      case provided: MarkupProvided => provided.nameInfo.map(of)
      case _ => None
    }
  }

  private def isAbortOfDistributedTest(event: Event): Boolean = {
    event match {
      case aborted: SuiteAborted => aborted.suiteClassName.contains(classOf[DistributedTestRunnerSuite].getName)
      case _ => false
    }
  }

  private def tableNamed(wrapperTable: SuiteNodeTable, wrapperNode: Option[NodeId], suiteId: SuiteId): Option[SuiteNodeTable] = {
    def nestedWithin(suite: SuiteNodeTable): Vector[SuiteNodeTable] = suite.childSuites.flatMap(nested => nested +: nestedWithin(nested))
    if (wrapperTable.suiteId == suiteId) {
      Some(wrapperTable)
    } else {
      val underWrapper = wrapperNode.fold(wrapperTable.childSuites)(node => wrapperTable.childSuites.filter(_.parent.contains(node)))
      val sharingItsId = underWrapper.flatMap(nested => nested +: nestedWithin(nested)).filter(_.suiteId == suiteId)
      sharingItsId.find(!_.hasEnded).orElse(sharingItsId.headOption)
    }
  }

  def caught[A](body: => A): Either[Throwable, A] =
    try Right(body) catch { case thrown: Throwable => Left(thrown) }

  private def caughtOutsideLock[A](body: => A): Either[Throwable, A] = {
    // Outside the lock: the run's own suites, throwables and config map values may block, and every thread of the run
    // takes the lock.
    caught(body)
  }

  def rethrowUnlessKeptFromCaller(thrown: Throwable): Unit = {
    thrown match {
      case _: InterruptedException => Thread.currentThread().interrupt()
      case notKept if !isKeptFromCaller(notKept) => throw notKept
      case _ => ()
    }
  }

  private def isKeptFromCaller(thrown: Throwable): Boolean = {
    // Kept from the caller unless the JVM or the thread cannot go on, so that a hand-off cannot change what the run's
    // reporters receive.
    thrown match {
      // Encoding a throwable or config map value that recurses without end overflows the stack: an encoding failure
      // like any other.
      case _: StackOverflowError => true
      case _: VirtualMachineError | _: ThreadDeath => false
      case _ => true
    }
  }
}

private[scalatest] final class GuardedByEmitter[A](emitter: TestEventLogEmitter, initial: A) {

  private var current = initial

  def get: A = {
    emitter.assertLockHeld()
    current
  }

  def set(value: A): Unit = {
    emitter.assertLockHeld()
    current = value
  }
}
