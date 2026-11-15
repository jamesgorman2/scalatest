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
import org.scalatest.events.EncodedEvent
import org.scalatest.events.Event
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace

/** The suite, scope or test of a suite's registration tree that an event is for. */
private[scalatest] enum TreeNode {
  case OfSuite
  case Scope(places: List[ScopePlace])
  case Test(testName: String)
}

/**
 * An event's origin as the emitter keeps it until the event is handed off.
 *
 * @param execution the execution of the event's own node
 * @param executionOfRun the execution of the run that sent the event, which is its parent's execution
 * @param messagesOfTest for an event of a test's execution, the messages the test hands off during it
 */
private[scalatest] final class RecordedOrigin(
  val registration: RegisteredSuite,
  val node: TreeNode,
  val execution: Execution,
  val executionOfRun: Execution,
  val messagesOfTest: Option[TestExecutionMessages] = None
)

/**
 * The messages a test's informer and documenter hand off during one execution of the test, kept in call order among
 * the execution's other events. Each message is assigned as the execution's first written event is. A message is
 * held until that event, and every event of the execution whose origin was recorded before the message, is written.
 * A reporter ahead of every wrapper may hold such an event back.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class TestExecutionMessages {

  private var assigned = Option.empty[NodeExecution]
  // Each entry is an unwritten event of the execution, `Left`, or a held message, `Right`, in call order.
  private var awaited = Vector.empty[Either[Event, EncodedEvent]]

  /** The node and executions of the execution's first written event, once it has been written. */
  def assignment: Option[NodeExecution] = assigned

  /** Whether a message handed off now is written at once: nothing of the execution called before it is unwritten. */
  def writesAtOnce: Boolean = assigned.isDefined && awaited.isEmpty

  /** Holds the messages handed off from now on behind an event of the execution, until that event is written. */
  def awaitWriting(event: Event): Unit = awaited :+= Left(event)

  def hold(message: EncodedEvent): Unit = awaited :+= Right(message)

  /**
   * Takes an event of the execution that is being written, and its assignment where it is the first. Returns the held
   * messages to write before the event, and those to write after it.
   *
   * A closing event releases every held message, before it. Any other event releases, after it, the held messages up
   * to the next unwritten event of the execution.
   */
  def written(event: Event, execution: NodeExecution, closesTheTest: Boolean): (Vector[EncodedEvent], Vector[EncodedEvent]) = {
    if (assigned.isEmpty) {
      assigned = Some(execution)
    }
    val others = awaited.filterNot {
      case Left(unwritten) => unwritten eq event
      case Right(_) => false
    }
    if (closesTheTest) {
      awaited = Vector.empty
      (others.collect { case Right(message) => message }, Vector.empty)
    } else {
      val (released, rest) = others.span(_.isRight)
      awaited = rest
      (Vector.empty, released.collect { case Right(message) => message })
    }
  }
}

/**
 * The origin of the events one engine site sends. The emitter asks it for what to keep beside each of them.
 *
 * @param registration the registration of the instance that sends the events
 */
private[scalatest] abstract class RecordingOrigin(val registration: RegisteredSuite) extends EventOrigin {

  /** Called under the emitter's lock. */
  def record(): RecordedOrigin
}

/** The invocation context of a registered suite instance's run, or of one execution of one of its tests. */
private[scalatest] sealed abstract class ContextOfRegisteredSuite extends InvocationContext {

  /** The registration whose run, or whose test's execution, this context is. */
  def registration: RegisteredSuite

  def register(suite: Suite): SuiteRegistration = new RegisteredSuite(registration.emitter, suite, Some(this))
}

/** The invocation context of a registered suite instance's run, outside its tests. */
private[scalatest] final class InRunOfSuite(val registration: RegisteredSuite) extends ContextOfRegisteredSuite {

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
 * An instance with no node table, as where no structure declared its place, records no origin, and its wrappers hand
 * nothing off: its events are assigned by the wrappers around it, from their own data.
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

  private val runContext = new InRunOfSuite(this)

  // Read without the lock, to tell whether to take it. Set only under it.
  @volatile private var nodeIsFixed = false
  private var nodePath = Option.empty[NodeId]
  private var tableOfNode = Option.empty[SuiteNodeTable]
  // The suite ID the node was fixed from, none where it was fixed with no node.
  private var suiteIdOfRun = Option.empty[String]
  // Set once the emitter takes the read of the instance's suite ID, so that it reads it once at most.
  private var emitterReadsSuiteId = false
  // The registration of the suite this instance is, whose run this instance's run is.
  private var runOfSuite = Option.empty[RegisteredSuite]
  private var executionOfLatestRun = Option.empty[Execution]
  private var startsARunOnceNodeIsFixed = false
  // The scopes and tests of the instance's run, as the filter keeps them, written once the node is fixed.
  private var structureAwaitingNode = Option.empty[SuiteNodeTable => List[ScopeOrTest]]

  // Kept on the top-level suite's registration alone. Each holds events by identity:
  //  - the set holds the events inner wrappers of this top-level suite's run handed off that had not reached its
  //    wrapper when this registration was removed;
  //  - the map holds each closing event of a test of this run whose messages the test handed off one by one, with
  //    those messages, until a wrapper hands the event off.
  // Both live here, not in the emitter, so that they are dropped with the top-level suite's wrapper. Removing the
  // registration drops neither, so a closing event sent or passed on after the removal is still written without its
  // recorded messages.
  private var isRemoved = false
  private val handedOffBeforeRemoval = Collections.newSetFromMap(new IdentityHashMap[Event, java.lang.Boolean])
  private val closingEventsWithMessagesHandedOff = new IdentityHashMap[Event, TestExecutionMessages]

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
   * Keeps an event an inner wrapper of this top-level suite's run handed off, which had not reached this suite's
   * wrapper when this registration was removed. Called under the emitter's lock.
   */
  def keepHandedOffBeforeRemoval(event: Event): Unit = handedOffBeforeRemoval.add(event)

  /**
   * Stops keeping an event an inner wrapper handed off before this registration was removed, and returns whether it
   * was kept. Called under the emitter's lock.
   */
  def takeHandedOffBeforeRemoval(event: Event): Boolean = handedOffBeforeRemoval.remove(event)

  /**
   * Keeps a closing event of a test of this top-level suite's run, whose messages the test handed off one by one,
   * with those messages. Called under the emitter's lock.
   */
  def keepClosingEventWithMessagesHandedOff(event: Event, messages: TestExecutionMessages): Unit =
    closingEventsWithMessagesHandedOff.put(event, messages)

  /**
   * Stops keeping a closing event whose messages its test handed off one by one, and returns those messages where it
   * was kept. Called under the emitter's lock.
   */
  def takeClosingEventWithMessagesHandedOff(event: Event): Option[TestExecutionMessages] =
    Option(closingEventsWithMessagesHandedOff.remove(event))

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
   * Whether the emitter is to read the instance's suite ID itself: where its node is not fixed, may have a node table,
   * and the emitter has not read it before. Called under the emitter's lock.
   */
  def takeReadOfSuiteId(): Boolean = {
    val takes = !nodeIsFixed && !emitterReadsSuiteId && canHaveTable
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
      case Some(inRun: InRunOfSuite) =>
        val outer = inRun.registration
        val index = outer.executionOfRun().indexOfStartingSuite(ofRun)
        nodePath = outer.nodePath.map(path => NodeId(path.places :+ NodePlace.Suite(ofRun, index)))
        tableOfNode = outer.tableOfNode.flatMap(_.childSuites.find(child => nodePath.contains(child.node)))
      case Some(inTest: InExecutionOfTest) =>
        val index = inTest.execution.indexOfStartingSuite(ofRun)
        nodePath = inTest.registration.nodePath.map(path => NodeId(path.places :+ NodePlace.Test(inTest.testName) :+ NodePlace.Suite(ofRun, index)))
        tableOfNode = None
      case None => ()
    }
  }

  /** Starts an execution for a run of the instance, whose node is fixed. Called under the emitter's lock. */
  def startRunUnderLock(): Execution = {
    runOfSuite match {
      case Some(suite) => suite.executionOfRun()
      case None =>
        val execution = tableOfNode.fold(new Execution)(_.takeExecutionForStartingRun())
        executionOfLatestRun = Some(execution)
        execution
    }
  }

  /** The execution of the instance's latest run, starting one where no site has. Called under the emitter's lock. */
  def executionOfRun(): Execution = runOfSuite.fold(executionOfLatestRun.getOrElse(startRunUnderLock()))(_.executionOfRun())

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

  def originAt(scopes: List[ScopePlace]): EventOrigin =
    new OriginInRun(if (scopes.isEmpty) TreeNode.OfSuite else TreeNode.Scope(scopes))

  def startTestExecution(testName: String): EventOrigin = {
    emitter.addTestWhereLacking(this, testName, instance.scopesAndTestsOfRegistrationTree)
    new OriginOfTestExecution(testName)
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

  // An event of the instance's own carries the names its send helper read.
  private def recordOrigin(event: Event, origin: RecordingOrigin, messages: Option[TestExecutionMessages]): Unit = {
    if (!nodeIsFixed) {
      emitter.fixNode(this, TestEventLogEmitter.namesIn(event).map(_.suiteId))
    }
    messages match {
      case Some(handedOff) => emitter.recordOriginOfClosingEventWithMessagesHandedOff(event, origin, handedOff)
      case None => emitter.recordOrigin(event, origin)
    }
  }

  // The origin of the events of the suite itself or of one of its scopes, each of which takes the run's execution.
  private final class OriginInRun(node: TreeNode) extends RecordingOrigin(RegisteredSuite.this) {

    def recordFor(event: Event): Unit = recordOrigin(event, this, None)

    def record(): RecordedOrigin = {
      val execution = executionOfRun()
      new RecordedOrigin(RegisteredSuite.this, node, execution, execution)
    }
  }

  // The origin of the events of one execution of a test, which ran in the run in flight when its first event was sent.
  private final class OriginOfTestExecution(testName: String) extends RecordingOrigin(RegisteredSuite.this) {

    private val execution = new Execution
    private val messages = new TestExecutionMessages
    // Read and written only under the emitter's lock.
    private var executionOfItsRun = Option.empty[Execution]

    def recordFor(event: Event): Unit = recordOrigin(event, this, None)

    def record(): RecordedOrigin = {
      val ofRun = executionOfItsRun.getOrElse(executionOfRun())
      executionOfItsRun = Some(ofRun)
      new RecordedOrigin(RegisteredSuite.this, TreeNode.Test(testName), execution, ofRun, Some(messages))
    }

    override def preparedHandOffOfMessage(message: => Event): () => Unit = {
      val encoded = TestEventLogEmitter.encodedTestMessage(message)
      () => emitter.writeTestMessage(RegisteredSuite.this, messages, encoded)
    }

    override def ofClosingEventWithMessagesHandedOff: EventOrigin = {
      val ofTestExecution = this
      new RecordingOrigin(RegisteredSuite.this) {
        def recordFor(event: Event): Unit = recordOrigin(event, ofTestExecution, Some(messages))
        def record(): RecordedOrigin = ofTestExecution.record()
      }
    }

    override def runningTheTest[A](body: => A): A =
      InvocationContext.within(new InExecutionOfTest(RegisteredSuite.this, testName, execution))(body)
  }
}
