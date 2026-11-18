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
import org.scalatest.events.NodePlace
import org.scalatest.events.StructureNode
import org.scalatest.events.SuiteStarting

private[scalatest] enum TreeNode {
  case OfSuite
  case Scope(places: List[ScopePlace])
  case Test(testName: String)
}

/**
 * `runTestsInBranch` starts a scope execution each time it opens the scope, within the execution of the scope above
 * it, where there is one.
 *
 * @param places the scope's path, from its suite down
 */
private[scalatest] final class ScopeExecution(emitter: TestEventLogEmitter, val places: List[ScopePlace], val above: Option[ScopeExecution]) {

  val execution = new Execution(emitter)

  // Read and written only under the emitter's lock.
  private var executionOfItsRun = Option.empty[Execution]

  /** Fixed when an event naming the scope is first handed off. */
  def executionOfRun(latestRun: => Execution): Execution = {
    emitter.assertLockHeld()
    val ofRun = executionOfItsRun.getOrElse(above.fold(latestRun)(_.executionOfRun(latestRun)))
    executionOfItsRun = Some(ofRun)
    ofRun
  }

  def executionsByPath: Map[List[ScopePlace], Execution] =
    above.fold(Map.empty[List[ScopePlace], Execution])(_.executionsByPath).updated(places, execution)
}

/** A send site hands each event off with its origin, and the emitter writes the event at once. */
private[scalatest] abstract class OriginInRegisteredSuite(val registration: RegisteredSuite, val node: TreeNode) extends EventOrigin {

  /** Called under the emitter's lock, once the instance's node is fixed. */
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

/** @param executionsOfTest called under the emitter's lock */
private[scalatest] final class InExecutionOfTest(
  val registration: RegisteredSuite,
  val testName: String,
  val execution: Execution,
  val executionsOfTest: () => NamedExecutions
) extends ContextOfRegisteredSuite {

  def registrationOf(suite: Suite): Option[SuiteRegistration] = None
}

/**
 * The registration of a suite instance's run in a run that writes a test event log. A top-level suite's is made, with
 * its node, by the site that installs its wrapper. Any other's is made by the first site of its run that finds an
 * invocation context set, and fixes its node once its suite ID is known:
 *  - where its run started in the run of a suite, outside that suite's tests, and its suite ID is that suite's, it is
 *    that suite: it takes that suite's node, and its run is that suite's;
 *  - otherwise its node path is the path of the suite or test it started in, followed by its suite ID and its index
 *    among the suites of that suite ID started in the execution it started in. It takes the node table a structure
 *    gives that path, where one does. Where none does, the suite is new to the event log: once its suite name is
 *    known too, it adds a node table under that suite or test, and the emitter writes the table's first structure.
 *
 * An instance with no node table, as where the instance it started in has none, hands no event off at a send site,
 * and its wrappers hand nothing off: its events are assigned by the wrappers around it, from their own data.
 *
 * Its state is read and written only under the emitter's lock.
 *
 * @param startedIn none for a top-level suite
 */
private[scalatest] final class RegisteredSuite(
  val emitter: TestEventLogEmitter,
  instance: Suite,
  startedIn: Option[ContextOfRegisteredSuite]
) extends SuiteRegistration {

  val topLevel: RegisteredSuite = startedIn.fold(this)(_.registration.topLevel)

  private val runContext = new InRunOfSuite(this, None)

  // Its tests name it only where the instance turns out to be the suite whose run it started in, as a per-test
  // instance is.
  private val scopeItStartedIn = startedIn.collect { case inRun: InRunOfSuite => inRun.scope }.flatten

  // Read without the lock, to tell whether to take it. Set only under it.
  @volatile private var nodeIsFixed = false
  // Whether the node is fixed and its node table found, added or known to be none. Read and set as `nodeIsFixed` is.
  @volatile private var tableIsSettled = false
  private var nodePath = Option.empty[NodeId]
  private var tableOfNode = Option.empty[SuiteNodeTable]
  // Where no structure holds the fixed node, the table of the suite it sits under and the node it sits under there,
  // until its suite name is known.
  private var newNodeAwaitingName = Option.empty[(SuiteNodeTable, NodeId)]
  // None where the node was fixed with no node.
  private var suiteIdOfNode = Option.empty[String]
  private var emitterHasReadSuiteId = false
  private var firstSuiteIdItsRunRead = Option.empty[String]
  private var firstSuiteNameItsRunRead = Option.empty[String]
  private var emitterHasReadSuiteName = false
  private var suiteItIs = Option.empty[RegisteredSuite]
  private var executionOfLatestRun = Option.empty[Execution]
  // Whether the latest run's execution was started before the run's first engine site started it: by the instance's
  // SuiteStarting, or by an event or a suite placed under the instance before then. The run takes it.
  private var latestRunAwaitsItsStart = false
  // Those of the suite or test in which its run started.
  private var executionsAbove = Option.empty[NamedExecutions]
  private var startsARunOnceNodeIsFixed = false
  private var structureAwaitingSettledTable = Option.empty[SuiteNodeTable => List[ScopeOrTest]]

  // Kept on the top-level suite's registration alone. Each holds events by identity:
  //  - the first holds the events handed off for this top-level suite's run, at their send sites or by inner
  //    wrappers, that had not reached its wrapper when this registration was removed;
  //  - the second holds each closing event of a test of this run, sent after the removal, whose messages the test
  //    handed off one by one, until the top-level suite's wrapper hands the event off.
  // Both live here, not in the emitter, so that they are dropped with the top-level suite's wrapper.
  private var isRemoved = false
  private val handedOffBeforeRemoval = identitySet()
  private val closingEventsWithMessagesHandedOff = identitySet()

  private def identitySet(): java.util.Set[Event] = Collections.newSetFromMap(new IdentityHashMap[Event, java.lang.Boolean])

  def isOf(suite: Suite): Boolean = instance eq suite

  def isNodeFixed: Boolean = nodeIsFixed

  def isTableSettled: Boolean = tableIsSettled

  def table: Option[SuiteNodeTable] = {
    emitter.assertLockHeld()
    tableOfNode
  }

  def markRemoved(): Unit = {
    emitter.assertLockHeld()
    topLevel.isRemoved = true
  }

  def hasBeenRemoved: Boolean = {
    emitter.assertLockHeld()
    topLevel.isRemoved
  }

  def keepHandedOffBeforeRemoval(event: Event): Unit = {
    emitter.assertLockHeld()
    handedOffBeforeRemoval.add(event)
  }

  /** Returns whether it was kept. */
  def takeHandedOffBeforeRemoval(event: Event): Boolean = {
    emitter.assertLockHeld()
    handedOffBeforeRemoval.remove(event)
  }

  def keepClosingEventWithMessagesHandedOff(event: Event): Unit = {
    emitter.assertLockHeld()
    closingEventsWithMessagesHandedOff.add(event)
  }

  /** Returns whether it was kept. */
  def takeClosingEventWithMessagesHandedOff(event: Event): Boolean = {
    emitter.assertLockHeld()
    closingEventsWithMessagesHandedOff.remove(event)
  }

  /** Called before the registration is shared. */
  def fixAsTopLevelSuite(table: SuiteNodeTable): Unit = {
    nodePath = Some(table.node)
    tableOfNode = Some(table)
    suiteIdOfNode = Some(table.suiteId)
    nodeIsFixed = true
    tableIsSettled = true
  }

  /** This instance and each instance whose run it started in. */
  def unsettledFromOutermost: List[RegisteredSuite] = {
    emitter.assertLockHeld()
    val outer = startedIn.map(_.registration.unsettledFromOutermost).getOrElse(Nil)
    if (tableIsSettled) outer else outer :+ this
  }

  /** Whether the instance's node has, or may yet have once fixed and named, a node table. */
  def canHaveTable: Boolean = {
    emitter.assertLockHeld()
    if (nodeIsFixed) {
      tableOfNode.isDefined || newNodeAwaitingName.isDefined
    } else {
      startedIn.exists(_.registration.canHaveTable)
    }
  }

  def keepSuiteIdItsRunRead(suiteId: String): Unit = {
    emitter.assertLockHeld()
    if (!nodeIsFixed && firstSuiteIdItsRunRead.isEmpty) {
      firstSuiteIdItsRunRead = Some(suiteId)
    }
  }

  def suiteIdReadByItsRun: Option[String] = {
    emitter.assertLockHeld()
    Option.when(!nodeIsFixed)(firstSuiteIdItsRunRead).flatten
  }

  def keepSuiteNameItsRunRead(suiteName: String): Unit = {
    emitter.assertLockHeld()
    if (firstSuiteNameItsRunRead.isEmpty) {
      firstSuiteNameItsRunRead = Some(suiteName)
    }
  }

  def suiteNameReadByItsRun: Option[String] = {
    emitter.assertLockHeld()
    firstSuiteNameItsRunRead
  }

  /** Whether the emitter is to read the instance's suite name itself, which it does once at most. */
  def takeReadOfSuiteName(): Boolean = {
    emitter.assertLockHeld()
    val takes = newNodeAwaitingName.isDefined && firstSuiteNameItsRunRead.isEmpty && !emitterHasReadSuiteName
    emitterHasReadSuiteName ||= takes
    takes
  }

  /** Whether the emitter is to read the instance's suite ID itself, which it does once at most. */
  def takeReadOfSuiteId(): Boolean = {
    emitter.assertLockHeld()
    val takes = !nodeIsFixed && firstSuiteIdItsRunRead.isEmpty && !emitterHasReadSuiteId && canHaveTable
    emitterHasReadSuiteId ||= takes
    takes
  }

  /**
   * Where a structure holds the node, or it has no table, its table is settled and this returns the structure it keeps
   * to write, if any. Called outermost first.
   */
  def fixNode(suiteId: Option[String]): Option[(SuiteNodeTable, List[ScopeOrTest])] = {
    emitter.assertLockHeld()
    if (nodeIsFixed) {
      None
    } else {
      suiteIdOfNode = suiteId
      suiteId.foreach(placeBy)
      nodeIsFixed = true
      firstSuiteIdItsRunRead = None
      if (startsARunOnceNodeIsFixed) {
        startExecutionOfRun()
      }
      if (newNodeAwaitingName.isDefined) {
        None
      } else {
        tableIsSettled = true
        val awaiting = structureAwaitingSettledTable
        structureAwaitingSettledTable = None
        for (scopesAndTestsFor <- awaiting; table <- tableOfNode) yield (table, scopesAndTestsFor(table))
      }
    }
  }

  /**
   * Settles the table of a fixed node that no structure holds, and returns the nodes of the added table's first
   * `structure` event. Where `suiteName` is none, as where reading it threw, the node has no table. Called once the
   * node is fixed.
   */
  def addNewNode(suiteName: Option[String]): Option[Vector[StructureNode]] = {
    emitter.assertLockHeld()
    val awaiting = newNodeAwaitingName
    newNodeAwaitingName = None
    tableIsSettled = true
    for ((above, parentNode) <- awaiting; name <- suiteName; path <- nodePath; suiteId <- suiteIdOfNode) yield {
      val table = above.addSuite(path, parentNode, name, suiteId, Suite.getSuiteClassName(instance))
      tableOfNode = Some(table)
      val scopesAndTests = structureAwaitingSettledTable.fold(List.empty[ScopeOrTest])(_(table))
      structureAwaitingSettledTable = None
      table.replaceLatestStructure(scopesAndTests)
    }
  }

  private def placeBy(suiteId: String): Unit = {
    emitter.assertLockHeld()
    startedIn match {
      case Some(inRun: InRunOfSuite) if inRun.registration.suiteIdOfNode.contains(suiteId) =>
        val outer = inRun.registration
        suiteItIs = Some(outer.suiteItIs.getOrElse(outer))
        nodePath = outer.nodePath
        tableOfNode = outer.tableOfNode
        executionsAbove = outer.executionsAbove
      case Some(inRun: InRunOfSuite) =>
        val outer = inRun.registration
        val executionOfOuter = outer.executionOfRun()
        val index = executionOfOuter.indexOfStartingSuite(suiteId)
        placeUnder(outer, outer.nodePath, NamedExecutions(outer.executionsAbove, executionOfOuter, Map.empty, None), suiteId, index)
      case Some(inTest: InExecutionOfTest) =>
        val outer = inTest.registration
        val index = inTest.execution.indexOfStartingSuite(suiteId)
        val testNode = outer.tableOfNode.flatMap(_.testsByName.get(inTest.testName))
        placeUnder(outer, testNode, inTest.executionsOfTest(), suiteId, index)
      case None => ()
    }
  }

  private def placeUnder(outer: RegisteredSuite, parentNode: Option[NodeId], above: NamedExecutions, suiteId: String, index: Int): Unit = {
    emitter.assertLockHeld()
    nodePath = parentNode.map(path => NodeId(path.places :+ NodePlace.Suite(suiteId, index)))
    executionsAbove = Some(above)
    tableOfNode = outer.tableOfNode.flatMap(_.childSuites.find(child => nodePath.contains(child.node)))
    if (tableOfNode.isEmpty) {
      newNodeAwaitingName = for (table <- outer.tableOfNode; parent <- parentNode) yield (table, parent)
    }
  }

  /**
   * Starts an execution for a run of the instance, whose node is fixed, or takes the one started before the run's
   * first engine site where no run has taken it yet.
   */
  def startExecutionOfRun(): Execution = {
    emitter.assertLockHeld()
    suiteItIs match {
      case Some(suite) => suite.executionOfRun()
      case None if latestRunAwaitsItsStart =>
        latestRunAwaitsItsStart = false
        executionOfRun()
      case None => newRun()
    }
  }

  private def newRun(): Execution = {
    emitter.assertLockHeld()
    val execution = tableOfNode.fold(new Execution(emitter))(_.takeExecutionForStartingRun())
    executionOfLatestRun = Some(execution)
    execution
  }

  /**
   * The execution of the instance's latest run, starting one, for the run's first engine site to take, where no site
   * has.
   */
  def executionOfRun(): Execution = {
    emitter.assertLockHeld()
    suiteItIs.fold(executionOfLatestRun.getOrElse(runAwaitingItsStart()))(_.executionOfRun())
  }

  private def runAwaitingItsStart(): Execution = {
    emitter.assertLockHeld()
    val execution = newRun()
    latestRunAwaitsItsStart = true
    execution
  }

  private def executionOpenedBySuiteStarting(): Execution = {
    emitter.assertLockHeld()
    suiteItIs.fold(runAwaitingItsStart())(_.executionOfRun())
  }

  def startRunOnceNodeIsFixed(): Unit = {
    emitter.assertLockHeld()
    if (nodeIsFixed) {
      startExecutionOfRun()
    } else {
      startsARunOnceNodeIsFixed = true
    }
  }

  def structureOnceTableIsSettled(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Option[(SuiteNodeTable, List[ScopeOrTest])] = {
    emitter.assertLockHeld()
    if (tableIsSettled) {
      tableOfNode.map(table => (table, scopesAndTestsFor(table)))
    } else {
      structureAwaitingSettledTable = Some(scopesAndTestsFor)
      None
    }
  }

  /** Called outside the emitter's lock: it is user code. */
  def readSuiteIdOfInstance(): String = instance.suiteId

  /** Called outside the emitter's lock: it is user code. */
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
    emitter.addTestWhereLacking(this, testName, instance.scopesAndTestsOfRegistrationTree)
    new OriginOfTestExecution(testName, scopeInContext)
  }

  def reporterOfTest(testName: String, reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.Test(this, testName), emitter.writeEventFrom)

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

  // The suite's own `run` may have built the Status, so `whenCompleted` may be user code. Where it throws, nothing
  // will call back, so the registration is removed at once.
  private def removedWhenCompleted(status: Status): Unit = {
    try status.whenCompleted(_ => emitter.forget(this))
    catch {
      case thrown: Throwable =>
        emitter.forget(this)
        TestEventLogEmitter.keptFromCaller(throw thrown)
    }
  }

  def runningInItsContext[A](body: => A): A = InvocationContext.within(runContext)(body)

  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = {
    instance.scopesAndTestsOfRegistrationTree.foreach { registered =>
      val tags = Suite.autoTagClassAnnotations(tagsOfTests, instance, SuiteStructure.testNamesIn(registered))
      emitter.writeStructureOnceTableIsSettled(this) { table =>
        SuiteStructure.keptByFilter(registered, table.filterKeepingEarlier(SuiteStructure.testNamesIn(registered), testName => filter(testName, tags, table.suiteId)))
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
      emitter.fixNode(this, Some(suiteId), None, handingOff = false)
    }
  }

  private def executionsInRun(ofRun: Execution, ofScopes: Map[List[ScopePlace], Execution], ofOwnNode: Option[Execution]): NamedExecutions = {
    emitter.assertLockHeld()
    NamedExecutions(executionsAbove, ofRun, ofScopes, ofOwnNode)
  }

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

  // Also the origin of the messages registered in the scope.
  private final class OriginOfScope(scope: ScopeExecution) extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.Scope(scope.places)) {

    def executionsOf(event: Event): NamedExecutions =
      executionsInRun(scope.executionOfRun(executionOfRun()), scope.executionsByPath, Some(scope.execution))

    override def runningInItsExecution[A](body: => A): A = InvocationContext.within(new InRunOfSuite(RegisteredSuite.this, Some(scope)))(body)
  }

  // Called once the node is fixed.
  private def scopeOfSuiteItIs: Option[ScopeExecution] = {
    emitter.assertLockHeld()
    scopeItStartedIn.filter(_ => suiteItIs.isDefined)
  }

  // The test ran in the run in flight when its first event was handed off. Where it ran in a scope execution, that is
  // `scopeOpened` where the instance's run opened it, else `scopeOfSuiteItIs`.
  private final class OriginOfTestExecution(testName: String, scopeOpened: Option[ScopeExecution])
      extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.Test(testName)) {

    private val execution = new Execution(emitter)
    // Read and written only under the emitter's lock.
    private var executionOfItsRun = Option.empty[Execution]

    def executionsOf(event: Event): NamedExecutions = executions()

    private def executions(): NamedExecutions = {
      emitter.assertLockHeld()
      val scope = scopeOpened.orElse(scopeOfSuiteItIs)
      val ofRun = executionOfItsRun.getOrElse(scope.fold(executionOfRun())(_.executionOfRun(executionOfRun())))
      executionOfItsRun = Some(ofRun)
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
