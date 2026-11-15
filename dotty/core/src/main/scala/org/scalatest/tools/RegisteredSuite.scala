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
import org.scalatest.events.SuiteStarting

/** The suite, scope or test of a suite's registration tree that an event is for. */
private[scalatest] enum TreeNode {
  case OfSuite
  case Scope(places: List[ScopePlace])
  case Test(testName: String)
}

/**
 * One execution of a scope of a registered instance's run, which `runTestsInBranch` starts each time it opens the
 * scope, within the execution of the scope above it, where it has one.
 *
 * @param places the scope's path, from its suite down
 */
private[scalatest] final class ScopeExecution(val places: List[ScopePlace], val above: Option[ScopeExecution]) {

  val execution = new Execution

  // Read and written only under the emitter's lock.
  private var executionOfItsRun = Option.empty[Execution]

  /**
   * The execution of the run the scope opened in: that of the scope above it, else `latestRun`, taken as an event
   * naming the scope is first handed off. Called under the emitter's lock.
   */
  def executionOfRun(latestRun: => Execution): Execution = {
    val ofRun = executionOfItsRun.getOrElse(above.fold(latestRun)(_.executionOfRun(latestRun)))
    executionOfItsRun = Some(ofRun)
    ofRun
  }

  /** This execution and that of each scope above it, by the scope's path. */
  def byPath: Map[List[ScopePlace], Execution] = above.fold(Map.empty[List[ScopePlace], Execution])(_.byPath).updated(places, execution)
}

/**
 * The origin of the events one engine site sends for a registered suite instance. A send site hands each event off
 * with it, and the emitter writes the event then.
 *
 * @param registration the registration of the instance that sends the events
 * @param node the suite, scope or test of its registration tree the events are for
 */
private[scalatest] abstract class OriginInRegisteredSuite(val registration: RegisteredSuite, val node: TreeNode) extends EventOrigin {

  /** The executions the origin names for `event`. Called under the emitter's lock, once the instance's node is fixed. */
  def executionsOf(event: Event): NamedExecutions

  def handOff(event: Event): Unit = registration.emitter.handOff(this, event, messagesHandedOff = false)
}

/** The invocation context of a registered suite instance's run, or of one execution of one of its tests. */
private[scalatest] sealed abstract class ContextOfRegisteredSuite extends InvocationContext {

  /** The registration whose run, or whose test's execution, this context is. */
  def registration: RegisteredSuite

  def register(suite: Suite): SuiteRegistration = new RegisteredSuite(registration.emitter, suite, Some(this))
}

/**
 * The invocation context of a registered suite instance's run, outside its tests.
 *
 * @param scope the execution of the scope the code runs in, where it runs in one
 */
private[scalatest] final class InRunOfSuite(val registration: RegisteredSuite, val scope: Option[ScopeExecution] = None) extends ContextOfRegisteredSuite {

  def registrationOf(suite: Suite): Option[SuiteRegistration] = Option.when(registration.isOf(suite))(registration)
}

/** The invocation context of one execution of a test of a registered suite instance. */
private[scalatest] final class InExecutionOfTest(val registration: RegisteredSuite, val testName: String, val execution: Execution)
    extends ContextOfRegisteredSuite {

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
 *    gives that path, where one does.
 *
 * An instance with no node table, as where no structure declared its place, hands no event off at a send site, and
 * its wrappers hand nothing off: its events are assigned by the wrappers around it, from their own data.
 *
 * Its state is read and written only under the emitter's lock.
 *
 * @param startedIn the context its run started in, none for a top-level suite
 */
private[scalatest] final class RegisteredSuite(
  val emitter: TestEventLogEmitter,
  instance: Suite,
  startedIn: Option[ContextOfRegisteredSuite]
) extends SuiteRegistration {

  /** The registration of the top-level suite this instance runs within: its own where it is that suite. */
  val topLevel: RegisteredSuite = startedIn.fold(this)(_.registration.topLevel)

  // An instance whose run started in a scope of a suite's run runs in that scope: where it is that suite, as a
  // per-test instance is, its tests name that scope's execution.
  private val runContext = new InRunOfSuite(this, startedIn.collect { case inRun: InRunOfSuite => inRun.scope }.flatten)

  // Read without the lock, to tell whether to take it. Set only under it.
  @volatile private var nodeIsFixed = false
  private var nodePath = Option.empty[NodeId]
  private var tableOfNode = Option.empty[SuiteNodeTable]
  // The suite ID the node was fixed from, none where it was fixed with no node.
  private var suiteIdOfRun = Option.empty[String]
  // Set once the emitter takes the read of the instance's suite ID, so that it reads it once at most.
  private var emitterReadsSuiteId = false
  // The first suite ID the instance's run read, kept until the node is fixed from it.
  private var suiteIdItsRunRead = Option.empty[String]
  // Where this instance's run is that of the suite it started in, that suite's registration.
  private var runOfSuite = Option.empty[RegisteredSuite]
  private var executionOfLatestRun = Option.empty[Execution]
  // Whether the latest run's execution was opened by the instance's SuiteStarting, for its run to take.
  private var latestRunOpenedBySuiteStarting = false
  // The execution of each suite above the instance's node, from the top-level suite down, in which its run started.
  private var executionsOfSuitesAbove = Vector.empty[Execution]
  private var startsARunOnceNodeIsFixed = false
  // The scopes and tests of the instance's run, as the filter keeps them, written once the node is fixed.
  private var structureAwaitingNode = Option.empty[SuiteNodeTable => List[ScopeOrTest]]

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

  /** Whether this registration's own instance is `suite`. */
  def isOf(suite: Suite): Boolean = instance eq suite

  /** Whether the node of the instance's run is fixed. */
  def isNodeFixed: Boolean = nodeIsFixed

  /** The node table of the instance's node, where it has one. Called under the emitter's lock. */
  def table: Option[SuiteNodeTable] = tableOfNode

  /** Marks the top-level suite's registration removed. Called under the emitter's lock. */
  def markRemoved(): Unit = topLevel.isRemoved = true

  /** Whether the top-level suite's registration has been removed. Called under the emitter's lock. */
  def hasBeenRemoved: Boolean = topLevel.isRemoved

  /**
   * Keeps an event handed off for this top-level suite's run, at its send site or by an inner wrapper, which had not
   * reached this suite's wrapper when this registration was removed. Called under the emitter's lock.
   */
  def keepHandedOffBeforeRemoval(event: Event): Unit = handedOffBeforeRemoval.add(event)

  /**
   * Stops keeping an event handed off before this registration was removed, and returns whether it was kept. Called
   * under the emitter's lock.
   */
  def takeHandedOffBeforeRemoval(event: Event): Boolean = handedOffBeforeRemoval.remove(event)

  /**
   * Keeps a closing event of a test of this top-level suite's run, sent after this registration was removed, whose
   * messages the test handed off one by one. Called under the emitter's lock.
   */
  def keepClosingEventWithMessagesHandedOff(event: Event): Unit = closingEventsWithMessagesHandedOff.add(event)

  /**
   * Stops keeping a closing event whose messages its test handed off one by one, and returns whether it was kept.
   * Called under the emitter's lock.
   */
  def takeClosingEventWithMessagesHandedOff(event: Event): Boolean = closingEventsWithMessagesHandedOff.remove(event)

  /**
   * Makes this the registration of a top-level suite, whose node table the emitter built from the suite's structure.
   * Called before the registration is shared.
   */
  def fixAsTopLevelSuite(table: SuiteNodeTable): Unit = {
    nodePath = Some(table.node)
    tableOfNode = Some(table)
    suiteIdOfRun = Some(table.suiteId)
    nodeIsFixed = true
  }

  /**
   * The registrations, outermost first, of this instance and of each instance whose run it started in, whose nodes
   * are not fixed. Called under the emitter's lock.
   */
  def unfixedFromOutermost: List[RegisteredSuite] = {
    val outer = startedIn.map(_.registration.unfixedFromOutermost).getOrElse(Nil)
    if (nodeIsFixed) outer else outer :+ this
  }

  /**
   * Whether the instance's node has, or may yet have once fixed, a node table. One started in a test's execution, or
   * in the run of an instance with none, has none. Called under the emitter's lock.
   */
  def canHaveTable: Boolean = {
    if (nodeIsFixed) {
      tableOfNode.isDefined
    } else {
      startedIn match {
        case Some(inRun: InRunOfSuite) => inRun.registration.canHaveTable
        case _ => false
      }
    }
  }

  /**
   * Keeps the suite ID the instance's run read, where its node is not fixed and the run has read none before, until
   * the node is fixed from it. Called under the emitter's lock.
   */
  def keepSuiteIdItsRunRead(suiteId: String): Unit = {
    if (!nodeIsFixed && suiteIdItsRunRead.isEmpty) {
      suiteIdItsRunRead = Some(suiteId)
    }
  }

  /** The first suite ID the instance's run read, where its node is not yet fixed. Called under the emitter's lock. */
  def suiteIdReadByItsRun: Option[String] = Option.when(!nodeIsFixed)(suiteIdItsRunRead).flatten

  /**
   * Whether the emitter is to read the instance's suite ID itself: where its node is not fixed, its run has read no
   * suite ID, it may have a node table, and the emitter has not read it before. Called under the emitter's lock.
   */
  def takeReadOfSuiteId(): Boolean = {
    val takes = !nodeIsFixed && suiteIdItsRunRead.isEmpty && !emitterReadsSuiteId && canHaveTable
    emitterReadsSuiteId ||= takes
    takes
  }

  /**
   * Fixes the node of the instance's run from its suite ID, where it is not yet fixed: with no node, and so no node
   * table, where the suite ID is none. Called under the emitter's lock, outermost first.
   */
  def fixNode(suiteId: Option[String]): Option[(SuiteNodeTable, List[ScopeOrTest])] = {
    if (nodeIsFixed) {
      None
    } else {
      suiteIdOfRun = suiteId
      suiteId.foreach(placeBy)
      nodeIsFixed = true
      suiteIdItsRunRead = None
      if (startsARunOnceNodeIsFixed) {
        startRunUnderLock()
      }
      val awaiting = structureAwaitingNode
      structureAwaitingNode = None
      for (scopesAndTestsFor <- awaiting; table <- tableOfNode) yield (table, scopesAndTestsFor(table))
    }
  }

  // Gives the instance its node, and the node's table where a structure declared one.
  private def placeBy(ofRun: String): Unit = {
    startedIn match {
      case Some(inRun: InRunOfSuite) if inRun.registration.suiteIdOfRun.contains(ofRun) =>
        val outer = inRun.registration
        runOfSuite = Some(outer.runOfSuite.getOrElse(outer))
        nodePath = outer.nodePath
        tableOfNode = outer.tableOfNode
        executionsOfSuitesAbove = outer.executionsOfSuitesAbove
      case Some(inRun: InRunOfSuite) =>
        val outer = inRun.registration
        val executionOfOuter = outer.executionOfRun()
        val index = executionOfOuter.indexOfStartingSuite(ofRun)
        nodePath = outer.nodePath.map(path => NodeId(path.places :+ NodePlace.Suite(ofRun, index)))
        tableOfNode = outer.tableOfNode.flatMap(_.childSuites.find(child => nodePath.contains(child.node)))
        executionsOfSuitesAbove = outer.executionsOfSuitesAbove :+ executionOfOuter
      case Some(inTest: InExecutionOfTest) =>
        val index = inTest.execution.indexOfStartingSuite(ofRun)
        nodePath = inTest.registration.nodePath.map(path => NodeId(path.places :+ NodePlace.Test(inTest.testName) :+ NodePlace.Suite(ofRun, index)))
        tableOfNode = None
      case None => ()
    }
  }

  /**
   * Starts an execution for a run of the instance, whose node is fixed: the one the instance's `SuiteStarting` opened,
   * where no run has taken it. Called under the emitter's lock.
   */
  def startRunUnderLock(): Execution = {
    runOfSuite match {
      case Some(suite) => suite.executionOfRun()
      case None if latestRunOpenedBySuiteStarting =>
        latestRunOpenedBySuiteStarting = false
        executionOfRun()
      case None => newRun()
    }
  }

  private def newRun(): Execution = {
    val execution = tableOfNode.fold(new Execution)(_.takeExecutionForStartingRun())
    executionOfLatestRun = Some(execution)
    execution
  }

  /** The execution of the instance's latest run, starting one where no site has. Called under the emitter's lock. */
  def executionOfRun(): Execution = runOfSuite.fold(executionOfLatestRun.getOrElse(newRun()))(_.executionOfRun())

  // The execution the instance's SuiteStarting opens, which its run then takes. Called under the emitter's lock.
  private def executionOpenedBySuiteStarting(): Execution = {
    runOfSuite match {
      case Some(suite) => suite.executionOfRun()
      case None =>
        val execution = newRun()
        latestRunOpenedBySuiteStarting = true
        execution
    }
  }

  /** Starts a run where the node is fixed, and otherwise once it is. Called under the emitter's lock. */
  def startRunOnceNodeIsFixed(): Unit = {
    if (nodeIsFixed) {
      startRunUnderLock()
    } else {
      startsARunOnceNodeIsFixed = true
    }
  }

  /** Writes the run's structure where the node is fixed, and otherwise once it is. Called under the emitter's lock. */
  def structureOnceNodeIsFixed(scopesAndTestsFor: SuiteNodeTable => List[ScopeOrTest]): Option[(SuiteNodeTable, List[ScopeOrTest])] = {
    if (nodeIsFixed) {
      tableOfNode.map(table => (table, scopesAndTestsFor(table)))
    } else {
      structureAwaitingNode = Some(scopesAndTestsFor)
      None
    }
  }

  /** Calls the instance's own `suiteId`. Called outside the emitter's lock: it is user code. */
  def readSuiteIdOfInstance(): String = instance.suiteId

  def startRun(): Unit = emitter.startRun(this)

  // The execution of the scope this instance's run runs in on this thread, where it runs in one.
  private def scopeInContext: Option[ScopeExecution] =
    InvocationContext.get.collect { case inRun: InRunOfSuite if inRun.registration eq this => inRun.scope }.flatten

  def originAt(scopes: List[ScopePlace]): EventOrigin = {
    if (scopes.isEmpty) {
      new OriginOfSuite
    } else {
      new OriginOfScope(new ScopeExecution(scopes, scopeInContext.filter(_.places == scopes.init)))
    }
  }

  def originOfStartAndEnd: EventOrigin = new OriginOfStartAndEnd

  def startTestExecution(testName: String): EventOrigin = {
    emitter.addTestWhereLacking(this, testName, instance.scopesAndTestsOfRegistrationTree)
    new OriginOfTestExecution(testName, scopeInContext)
  }

  def reporterOfTest(testName: String, reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.Test(this, testName), emitter.writeSuiteEvent)

  def reporterAheadOf(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.SuiteAheadOfSorter(this), emitter.writeSuiteEvent)

  def reporterOfSuite(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.RegisteredByItsRun(this), emitter.writeSuiteEvent)

  def startWith(reporter: Reporter): SuiteStart = {
    val wrapper = new StructureWrapper(reporter, WrapperNode.NestedSuite(this), emitter.writeSuiteEvent)
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

  /**
   * How the site that installs a top-level suite's wrapper starts the suite: with that wrapper over `reporter`, in
   * the context of this registration. The registration is removed where the suite's run throws, and otherwise once
   * the `Status` it returns completes.
   */
  def startAsTopLevelSuite(reporter: Reporter): SuiteStart = {
    val wrapper = new StructureWrapper(reporter, WrapperNode.TopLevelSuite(this), emitter.writeSuiteEvent)
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
      emitter.writeStructureOnceNodeIsFixed(this) { table =>
        SuiteStructure.kept(registered, table.filterKeepingEarlier(SuiteStructure.testNamesIn(registered), testName => filter(testName, tags, table.suiteId)))
      }
    }
  }

  def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit = {
    val testNames = keptTestNames.toList
    emitter.writeStructureOnceNodeIsFixed(this)(_ => SuiteStructure.ofTestNames(testNames))
  }

  // A node table takes its suite's name from the suite's structure, so nothing yet needs the name a run reads.
  def suiteNameRead(suiteName: String): Unit = ()

  def suiteIdRead(suiteId: String): Unit = {
    if (!nodeIsFixed) {
      emitter.fixNode(this, Some(suiteId))
    }
  }

  private def executionsInRun(ofRun: Execution, ofScopes: Map[List[ScopePlace], Execution], ofOwnNode: Option[Execution]): NamedExecutions =
    NamedExecutions(executionsOfSuitesAbove, ofRun, ofScopes, ofOwnNode)

  // The origin of the suite-level events of the instance's run, which take the run's execution.
  private final class OriginOfSuite extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.OfSuite) {

    def executionsOf(event: Event): NamedExecutions = executionsInRun(executionOfRun(), Map.empty, None)

    override def runningInItsExecution[A](body: => A): A = runningInItsContext(body)
  }

  // The origin of the instance's SuiteStarting, SuiteCompleted and SuiteAborted. Its SuiteStarting opens the execution
  // its run takes.
  private final class OriginOfStartAndEnd extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.OfSuite) {

    def executionsOf(event: Event): NamedExecutions = {
      val ofRun = if (event.isInstanceOf[SuiteStarting]) executionOpenedBySuiteStarting() else executionOfRun()
      executionsInRun(ofRun, Map.empty, None)
    }
  }

  // The origin of the events of one execution of a scope, and of the messages registered in it.
  private final class OriginOfScope(scope: ScopeExecution) extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.Scope(scope.places)) {

    def executionsOf(event: Event): NamedExecutions =
      executionsInRun(scope.executionOfRun(executionOfRun()), scope.byPath, Some(scope.execution))

    override def runningInItsExecution[A](body: => A): A = InvocationContext.within(new InRunOfSuite(RegisteredSuite.this, Some(scope)))(body)
  }

  // The origin of the events of one execution of a test, which ran in the scope execution `scope`, where it ran in a
  // scope, and in the run in flight when its first event was handed off.
  private final class OriginOfTestExecution(testName: String, scope: Option[ScopeExecution])
      extends OriginInRegisteredSuite(RegisteredSuite.this, TreeNode.Test(testName)) {

    private val execution = new Execution
    // Read and written only under the emitter's lock.
    private var executionOfItsRun = Option.empty[Execution]

    def executionsOf(event: Event): NamedExecutions = {
      val ofRun = executionOfItsRun.getOrElse(scope.fold(executionOfRun())(_.executionOfRun(executionOfRun())))
      executionOfItsRun = Some(ofRun)
      executionsInRun(ofRun, scope.fold(Map.empty[List[ScopePlace], Execution])(_.byPath), Some(execution))
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
      InvocationContext.within(new InExecutionOfTest(RegisteredSuite.this, testName, execution))(body)
  }
}
