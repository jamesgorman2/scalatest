package org.scalatest.tools

import java.util.Collections
import java.util.IdentityHashMap
import org.scalatest.EventOrigin
import org.scalatest.Filter
import org.scalatest.InvocationContext
import org.scalatest.Reporter
import org.scalatest.ScopePlace
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.SuiteStart
import org.scalatest.events.Event
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode
import org.scalatest.events.SuiteId
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestClassName
import org.scalatest.events.TestName

private[scalatest] enum TreeNode {
  case OfSuite
  case Scope(places: List[ScopePlace])
  case Test(scalaTestName: TestName)
}

private[scalatest] final case class PlaceOfNewNode(tableAbove: SuiteNodeTable, parent: NodeId)

private[scalatest] final class ScopeExecution(emitter: TestEventLogEmitter, val places: List[ScopePlace], val above: Option[ScopeExecution]) {

  val execution: Execution = new Execution(emitter)

  private val executionOfItsRun = new GuardedByEmitter(emitter, Option.empty[Execution])

  def executionOfRun(latestRun: => Execution): Execution = {
    // Kept from the first call, since a later run of the instance changes `latestRun`.
    val ofRun = executionOfItsRun.get.getOrElse(above.fold(latestRun)(_.executionOfRun(latestRun)))
    executionOfItsRun.set(Some(ofRun))
    ofRun
  }

  def executionsByPath: Map[List[ScopePlace], Execution] =
    above.fold(Map.empty[List[ScopePlace], Execution])(_.executionsByPath).updated(places, execution)
}

private[scalatest] abstract class OriginInRegisteredSuite(val registration: RegisteredSuite, val node: TreeNode) extends EventOrigin {

  def executionsOf(event: Event): NamedExecutions

  def handOff(event: Event): Unit = registration.emitter.handOff(this, event, messagesHandedOff = false)
}

private[scalatest] sealed abstract class ContextOfRegisteredSuite extends InvocationContext {

  def registration: RegisteredSuite

  def register(suite: Suite): SuiteRegistration = new RegisteredSuite(registration.emitter, suite, Some(this))
}

private[scalatest] final class InRunOfSuite(val registration: RegisteredSuite, val scope: Option[ScopeExecution]) extends ContextOfRegisteredSuite {

  def registrationOf(suite: Suite): Option[SuiteRegistration] = Option.when(registration.isOf(suite))(registration)
}

private[scalatest] final class InExecutionOfTest(
  val registration: RegisteredSuite,
  val scalaTestName: TestName,
  val execution: Execution,
  val executionsOfTest: () => NamedExecutions
) extends ContextOfRegisteredSuite {

  def registrationOf(suite: Suite): Option[SuiteRegistration] = None
}

private[scalatest] final class RegisteredSuite(
  val emitter: TestEventLogEmitter,
  instance: Suite,
  startedIn: Option[ContextOfRegisteredSuite]
) extends SuiteRegistration {

  val topLevel: RegisteredSuite = startedIn.fold(this)(_.registration.topLevel)

  private val runContext = new InRunOfSuite(this, None)

  private val scopeItStartedIn = startedIn.collect { case inRun: InRunOfSuite => inRun.scope }.flatten

  // Both read without the lock, to tell whether to take it, and set only under it.
  @volatile private var nodeIsFixed = false
  @volatile private var tableIsSettled = false
  private val nodePath = new GuardedByEmitter(emitter, Option.empty[NodeId])
  private val tableOfNode = new GuardedByEmitter(emitter, Option.empty[SuiteNodeTable])
  private val newNodeAwaitingName = new GuardedByEmitter(emitter, Option.empty[PlaceOfNewNode])
  private val suiteIdOfNode = new GuardedByEmitter(emitter, Option.empty[SuiteId])
  private val emitterHasReadSuiteId = new GuardedByEmitter(emitter, false)
  private val firstSuiteIdItsRunRead = new GuardedByEmitter(emitter, Option.empty[SuiteId])
  private val firstSuiteNameItsRunRead = new GuardedByEmitter(emitter, Option.empty[String])
  private val emitterHasReadSuiteName = new GuardedByEmitter(emitter, false)
  private val suiteItIs = new GuardedByEmitter(emitter, Option.empty[RegisteredSuite])
  private val executionOfLatestRun = new GuardedByEmitter(emitter, Option.empty[Execution])
  private val latestRunAwaitsItsStart = new GuardedByEmitter(emitter, false)
  private val executionsAbove = new GuardedByEmitter(emitter, Option.empty[NamedExecutions])
  private val startsARunOnceNodeIsFixed = new GuardedByEmitter(emitter, false)
  private val structureAwaitingSettledTable = new GuardedByEmitter(emitter, Option.empty[SuiteNodeTable => List[ScopeOrTest]])

  private val isRemoved = new GuardedByEmitter(emitter, false)
  // Only the top-level suite's sets are used. They sit on its registration rather than in the emitter, so that they are
  // garbage-collected with its wrapper.
  private val handedOffBeforeRemoval = new GuardedByEmitter(emitter, identitySet())
  private val closingEventsWithMessagesHandedOff = new GuardedByEmitter(emitter, identitySet())

  private def identitySet(): java.util.Set[Event] = Collections.newSetFromMap(new IdentityHashMap[Event, java.lang.Boolean])

  def isOf(suite: Suite): Boolean = instance eq suite

  def isNodeFixed: Boolean = nodeIsFixed

  def isTableSettled: Boolean = tableIsSettled

  def table: Option[SuiteNodeTable] = tableOfNode.get

  def markRemoved(): Unit = topLevel.isRemoved.set(true)

  def hasBeenRemoved: Boolean = topLevel.isRemoved.get

  def keepHandedOffBeforeRemoval(event: Event): Unit = topLevel.handedOffBeforeRemoval.get.add(event)

  def tryTakeHandedOffBeforeRemoval(event: Event): Boolean = topLevel.handedOffBeforeRemoval.get.remove(event)

  def keepClosingEventWithMessagesHandedOff(event: Event): Unit = topLevel.closingEventsWithMessagesHandedOff.get.add(event)

  def tryTakeClosingEventWithMessagesHandedOff(event: Event): Boolean = topLevel.closingEventsWithMessagesHandedOff.get.remove(event)

  def fixAsTopLevelSuite(table: SuiteNodeTable): Unit = {
    nodePath.set(Some(table.node))
    tableOfNode.set(Some(table))
    suiteIdOfNode.set(Some(table.suiteId))
    nodeIsFixed = true
    tableIsSettled = true
  }

  def unsettledFromOutermost: List[RegisteredSuite] = {
    emitter.assertLockHeld()
    val outer = startedIn.map(_.registration.unsettledFromOutermost).getOrElse(Nil)
    if (tableIsSettled) outer else outer :+ this
  }

  def canHaveTable: Boolean = {
    emitter.assertLockHeld()
    if (nodeIsFixed) {
      tableOfNode.get.isDefined || newNodeAwaitingName.get.isDefined
    } else {
      startedIn.exists(_.registration.canHaveTable)
    }
  }

  def keepSuiteIdItsRunRead(suiteId: SuiteId): Unit = {
    emitter.assertLockHeld()
    if (!nodeIsFixed && firstSuiteIdItsRunRead.get.isEmpty) {
      firstSuiteIdItsRunRead.set(Some(suiteId))
    }
  }

  def suiteIdReadByItsRun: Option[SuiteId] = {
    emitter.assertLockHeld()
    Option.when(!nodeIsFixed)(firstSuiteIdItsRunRead.get).flatten
  }

  def keepSuiteNameItsRunRead(suiteName: String): Unit = {
    if (firstSuiteNameItsRunRead.get.isEmpty) {
      firstSuiteNameItsRunRead.set(Some(suiteName))
    }
  }

  def suiteNameReadByItsRun: Option[String] = firstSuiteNameItsRunRead.get

  def tryClaimSuiteNameRead(): Boolean = {
    val takes = newNodeAwaitingName.get.isDefined && firstSuiteNameItsRunRead.get.isEmpty && !emitterHasReadSuiteName.get
    if (takes) {
      emitterHasReadSuiteName.set(true)
    }
    takes
  }

  def tryClaimSuiteIdRead(): Boolean = {
    emitter.assertLockHeld()
    val takes = !nodeIsFixed && firstSuiteIdItsRunRead.get.isEmpty && !emitterHasReadSuiteId.get && canHaveTable
    if (takes) {
      emitterHasReadSuiteId.set(true)
    }
    takes
  }

  def fixNode(suiteId: Option[SuiteId]): Option[(SuiteNodeTable, List[ScopeOrTest])] = {
    emitter.assertLockHeld()
    if (nodeIsFixed) {
      None
    } else {
      suiteIdOfNode.set(suiteId)
      suiteId.foreach(placeBy)
      nodeIsFixed = true
      firstSuiteIdItsRunRead.set(None)
      if (startsARunOnceNodeIsFixed.get) {
        startExecutionOfRun()
      }
      if (newNodeAwaitingName.get.isDefined) {
        None
      } else {
        tableIsSettled = true
        val awaiting = structureAwaitingSettledTable.get
        structureAwaitingSettledTable.set(None)
        for (scopesAndTestsFor <- awaiting; table <- tableOfNode.get) yield (table, scopesAndTestsFor(table))
      }
    }
  }

  def addNewNode(suiteName: Option[String]): Option[Vector[StructureNode]] = {
    val awaiting = newNodeAwaitingName.get
    newNodeAwaitingName.set(None)
    tableIsSettled = true
    for (place <- awaiting; name <- suiteName; path <- nodePath.get; suiteId <- suiteIdOfNode.get) yield {
      val table = place.tableAbove.addSuite(path, place.parent, name, suiteId, TestClassName(Suite.getSuiteClassName(instance)))
      tableOfNode.set(Some(table))
      val scopesAndTests = structureAwaitingSettledTable.get.fold(List.empty[ScopeOrTest])(_(table))
      structureAwaitingSettledTable.set(None)
      table.replaceLatestStructure(scopesAndTests)
    }
  }

  private def placeBy(suiteId: SuiteId): Unit = {
    emitter.assertLockHeld()
    startedIn match {
      case Some(inRun: InRunOfSuite) if inRun.registration.suiteIdOfNode.get.contains(suiteId) =>
        val outer = inRun.registration
        suiteItIs.set(Some(outer.suiteItIs.get.getOrElse(outer)))
        nodePath.set(outer.nodePath.get)
        tableOfNode.set(outer.tableOfNode.get)
        executionsAbove.set(outer.executionsAbove.get)
      case Some(inRun: InRunOfSuite) =>
        val outer = inRun.registration
        val executionOfOuter = outer.executionOfRun()
        val index = executionOfOuter.indexOfStartingSuite(suiteId)
        placeUnder(outer, outer.nodePath.get, NamedExecutions(outer.executionsAbove.get, executionOfOuter, Map.empty, None), suiteId, index)
      case Some(inTest: InExecutionOfTest) =>
        val outer = inTest.registration
        val index = inTest.execution.indexOfStartingSuite(suiteId)
        val testNode = outer.tableOfNode.get.flatMap(_.testsByName.get(inTest.scalaTestName))
        placeUnder(outer, testNode, inTest.executionsOfTest(), suiteId, index)
      case None => ()
    }
  }

  private def placeUnder(outer: RegisteredSuite, parentNode: Option[NodeId], above: NamedExecutions, suiteId: SuiteId, index: Int): Unit = {
    nodePath.set(parentNode.map(NodeId.nestedSuite(_, suiteId, index)))
    executionsAbove.set(Some(above))
    tableOfNode.set(outer.tableOfNode.get.flatMap(_.childSuites.find(child => nodePath.get.contains(child.node))))
    if (tableOfNode.get.isEmpty) {
      newNodeAwaitingName.set(for (table <- outer.tableOfNode.get; parent <- parentNode) yield PlaceOfNewNode(table, parent))
    }
  }

  def startExecutionOfRun(): Execution = {
    suiteItIs.get match {
      case Some(suite) => suite.executionOfRun()
      case None if latestRunAwaitsItsStart.get =>
        latestRunAwaitsItsStart.set(false)
        executionOfRun()
      case None => newRun()
    }
  }

  private def newRun(): Execution = {
    val execution = tableOfNode.get.fold(new Execution(emitter))(_.takeExecutionForStartingRun())
    executionOfLatestRun.set(Some(execution))
    execution
  }

  def executionOfRun(): Execution =
    suiteItIs.get.fold(executionOfLatestRun.get.getOrElse(runAwaitingItsStart()))(_.executionOfRun())

  private def runAwaitingItsStart(): Execution = {
    val execution = newRun()
    latestRunAwaitsItsStart.set(true)
    execution
  }

  private def executionOpenedBySuiteStarting(): Execution =
    suiteItIs.get.fold(runAwaitingItsStart())(_.executionOfRun())

  def startRunOnceNodeIsFixed(): Unit = {
    if (nodeIsFixed) {
      startExecutionOfRun()
    } else {
      startsARunOnceNodeIsFixed.set(true)
    }
  }

  def structureOnceTableIsSettled(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Option[(SuiteNodeTable, List[ScopeOrTest])] = {
    if (tableIsSettled) {
      tableOfNode.get.map(table => (table, scopesAndTestsFor(table)))
    } else {
      structureAwaitingSettledTable.set(Some(scopesAndTestsFor))
      None
    }
  }

  def readSuiteIdOfInstance(): SuiteId = SuiteId(instance.suiteId)

  def readSuiteNameOfInstance(): String = instance.suiteName

  def startRun(): Unit = emitter.startRun(this)

  private def scopeInContext: Option[ScopeExecution] =
    InvocationContext.get.collect { case inRun: InRunOfSuite if inRun.registration eq this => inRun.scope }.flatten

  def originAt(scopes: List[ScopePlace]): EventOrigin = {
    if (scopes.isEmpty) {
      new OriginOfSuite
    } else {
      new OriginOfScope(new ScopeExecution(emitter, scopes, scopeInContext.filter(_.places == scopes.init)))
    }
  }

  def originOfStartAndEnd: EventOrigin = new OriginOfStartAndEnd

  def startTestExecution(testName: String): EventOrigin = {
    emitter.addTestWhereLacking(this, TestName(testName), instance.scopesAndTestsOfRegistrationTree)
    new OriginOfTestExecution(TestName(testName), scopeInContext)
  }

  def reporterOfTest(testName: String, reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.Test(this, TestName(testName)), emitter.writeEventFrom)

  def reporterAheadOf(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.SuiteAheadOfSorter(this), emitter.writeEventFrom)

  def reporterOfSuite(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.RegisteredByItsRun(this), emitter.writeEventFrom)

  def startWith(reporter: Reporter): SuiteStart = {
    val wrapper = new StructureWrapper(reporter, WrapperNode.NestedSuite(this), emitter.writeEventFrom)
    new SuiteStart {
      def reporter: Reporter = wrapper
      def namesRead(suiteName: String, suiteId: String): Unit = {
        suiteNameRead(suiteName)
        suiteIdRead(suiteId)
      }
      override protected def originOfStartAndEnd: EventOrigin = RegisteredSuite.this.originOfStartAndEnd
      def run(run: => Status): Status = runningInItsContext(run)
    }
  }

  def startAsTopLevelSuite(reporter: Reporter): SuiteStart = {
    val wrapper = new StructureWrapper(reporter, WrapperNode.TopLevelSuite(this), emitter.writeEventFrom)
    new SuiteStart {
      def reporter: Reporter = wrapper
      def namesRead(suiteName: String, suiteId: String): Unit = ()
      def run(run: => Status): Status = {
        val status =
          try runningInItsContext(run)
          catch {
            case thrown: Throwable =>
              emitter.forget(RegisteredSuite.this)
              throw thrown
          }
        removedWhenCompleted(status)
        status
      }
    }
  }

  private def removedWhenCompleted(status: Status): Unit = {
    try status.whenCompleted(_ => emitter.forget(this))
    catch {
      case thrown: Throwable =>
        // The suite's own `run` may have built `status`. If its `whenCompleted` throws, the callback may never run, so
        // the suite is forgotten here.
        emitter.forget(this)
        TestEventLogEmitter.keptFromCaller(throw thrown)
    }
  }

  def runningInItsContext[A](body: => A): A = InvocationContext.within(runContext)(body)

  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = {
    instance.scopesAndTestsOfRegistrationTree.foreach { registered =>
      val testNames = SuiteStructure.testNamesIn(registered)
      val tags = Suite.autoTagClassAnnotations(tagsOfTests, instance, testNames.map(_.value))
      emitter.writeStructureOnceTableIsSettled(this) { table =>
        val verdicts = table.filterKeepingEarlier(testNames, testName => FilterVerdict.of(filter(testName.value, tags, table.suiteId.value)))
        SuiteStructure.keptByFilter(registered, verdicts)
      }
    }
  }

  def updateStructureFromTestNames(keptTests: => Seq[(String, Boolean)]): Unit = {
    val tests = keptTests.toList
    emitter.writeStructureOnceTableIsSettled(this)(_ => SuiteStructure.ofTestNames(tests))
  }

  def suiteNameRead(suiteName: String): Unit = {
    if (!tableIsSettled) {
      emitter.fixNode(this, None, Some(suiteName), handingOff = false)
    }
  }

  def suiteIdRead(suiteId: String): Unit = {
    if (!tableIsSettled) {
      emitter.fixNode(this, Some(SuiteId(suiteId)), None, handingOff = false)
    }
  }

  private def executionsInRun(ofRun: Execution, ofScopes: Map[List[ScopePlace], Execution], ofOwnNode: Option[Execution]): NamedExecutions =
    NamedExecutions(executionsAbove.get, ofRun, ofScopes, ofOwnNode)

  private final class OriginOfSuite extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.OfSuite) {

    def executionsOf(event: Event): NamedExecutions = executionsInRun(executionOfRun(), Map.empty, None)

    override def runningInItsExecution[A](body: => A): A = runningInItsContext(body)
  }

  private final class OriginOfStartAndEnd extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.OfSuite) {

    def executionsOf(event: Event): NamedExecutions = {
      val ofRun = if (event.isInstanceOf[SuiteStarting]) executionOpenedBySuiteStarting() else executionOfRun()
      executionsInRun(ofRun, Map.empty, None)
    }
  }

  private final class OriginOfScope(scope: ScopeExecution) extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.Scope(scope.places)) {

    def executionsOf(event: Event): NamedExecutions =
      executionsInRun(scope.executionOfRun(executionOfRun()), scope.executionsByPath, Some(scope.execution))

    override def runningInItsExecution[A](body: => A): A = InvocationContext.within(new InRunOfSuite(RegisteredSuite.this, Some(scope)))(body)
  }

  private def scopeOfSuiteItIs: Option[ScopeExecution] = {
    emitter.assertLockHeld()
    scopeItStartedIn.filter(_ => suiteItIs.get.isDefined)
  }

  private final class OriginOfTestExecution(testName: TestName, scopeOpened: Option[ScopeExecution])
      extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.Test(testName)) {

    private val execution = new Execution(emitter)
    private val executionOfItsRun = new GuardedByEmitter(emitter, Option.empty[Execution])

    def executionsOf(event: Event): NamedExecutions = executions()

    private def executions(): NamedExecutions = {
      val scope = scopeOpened.orElse(scopeOfSuiteItIs)
      // Kept from the first call, since a later run of the instance changes `executionOfRun()`.
      val ofRun = executionOfItsRun.get.getOrElse(scope.fold(executionOfRun())(_.executionOfRun(executionOfRun())))
      executionOfItsRun.set(Some(ofRun))
      executionsInRun(ofRun, scope.fold(Map.empty[List[ScopePlace], Execution])(_.executionsByPath), Some(execution))
    }

    override def preparedHandOffOfMessage(message: => Event): () => Unit = {
      val built = TestEventLogEmitter.builtTestMessage(message)
      () => emitter.writeTestMessage(this, built)
    }

    override def ofClosingEventWithMessagesHandedOff: EventOrigin = {
      val ofTestExecution = this
      new EventOrigin {
        def handOff(event: Event): Unit = emitter.handOff(ofTestExecution, event, messagesHandedOff = true)
      }
    }

    override def runningInItsExecution[A](body: => A): A =
      InvocationContext.within(new InExecutionOfTest(RegisteredSuite.this, testName, execution, () => executions()))(body)
  }
}
