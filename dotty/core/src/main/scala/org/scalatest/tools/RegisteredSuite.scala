package org.scalatest.tools

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import org.scalatest.EventOrigin
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.ScopePlace
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.SuiteRegistrations
import org.scalatest.events.EncodedEvent
import org.scalatest.events.Event
import org.scalatest.events.NodeExecution

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
 * The messages a test's informer and documenter hand off during one execution of the test, kept in the order of the
 * test's calls among the execution's other events. Each message is assigned as the execution's first written event
 * is. A message is held while that event is unwritten, and while an event of the execution whose origin was recorded
 * before the message was handed off is unwritten, as where a reporter ahead of every wrapper holds the event back.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class TestExecutionMessages {

  // Each entry is an unwritten event of the execution, `Left`, or a held message, `Right`, in the order of the calls.
  private var assigned = Option.empty[NodeExecution]
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

/**
 * The registration of a suite instance against its suite's node table, in a run that writes a test event log.
 *
 * @param ofTopLevelSuite the registration of the top-level suite this instance runs within, or none where it is that suite
 * @param runOfCreator for an instance created by another instance of the suite to run one test, the execution of the
 *                     creator's run. The instance's run uses it instead of starting its own.
 */
private[scalatest] final class RegisteredSuite(
  emitter: TestEventLogEmitter,
  val table: SuiteNodeTable,
  instance: Suite,
  ofTopLevelSuite: Option[RegisteredSuite],
  runOfCreator: Option[Execution]
) extends SuiteRegistration {

  /** The registration of the top-level suite this instance runs within: its own where it is that suite. */
  val topLevel: RegisteredSuite = ofTopLevelSuite.getOrElse(this)

  // All four are read and written only under the emitter's lock. The set holds, by identity, the events inner
  // wrappers of this top-level suite's run handed off that had not reached its wrapper when this registration was
  // removed. The map holds, by identity, each closing event of a test of this top-level suite's run whose messages
  // the test handed off one by one, with those messages, until a wrapper hands the event off. Both live here, not in
  // the emitter, so that they are dropped with the top-level suite's wrapper; removing a registration drops neither,
  // so a closing event sent or passed on after the removal is still written without its recorded messages.
  private var executionOfLatestRun = runOfCreator
  private var isRemoved = false
  private val handedOffBeforeRemoval = Collections.newSetFromMap(new IdentityHashMap[Event, java.lang.Boolean])
  private val closingEventsWithMessagesHandedOff = new IdentityHashMap[Event, TestExecutionMessages]

  /** Starts an execution for a run of the instance. Called under the emitter's lock. */
  def startRunUnderLock(): Execution = {
    val execution = runOfCreator.getOrElse(table.takeExecutionForStartingRun())
    executionOfLatestRun = Some(execution)
    execution
  }

  /** The execution of the instance's latest run, starting one where no site has. Called under the emitter's lock. */
  def startRunUnlessStarted(): Execution = executionOfLatestRun.getOrElse(startRunUnderLock())

  /** Marks this registration removed. Called under the emitter's lock. */
  def markRemoved(): Unit = isRemoved = true

  /** Whether this registration has been removed. Called under the emitter's lock. */
  def hasBeenRemoved: Boolean = isRemoved

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

  def startRun(): Unit = emitter.startRun(this)

  def originAt(scopes: List[ScopePlace]): EventOrigin =
    new OriginInRun(if (scopes.isEmpty) TreeNode.OfSuite else TreeNode.Scope(scopes))

  def startTestExecution(testName: String): EventOrigin = {
    emitter.addTestWhereLacking(table, testName, instance.scopesAndTestsOfRegistrationTree)
    new OriginOfTestExecution(testName)
  }

  def reporterOfTest(testName: String, reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.Test(this, testName), emitter.writeSuiteEvent)

  def reporterAheadOf(reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.SuiteAheadOfSorter(this), emitter.writeSuiteEvent)

  def registerInstanceOfTest(instance: Suite): SuiteRegistration = {
    val registration = new RegisteredSuite(emitter, table, instance, Some(topLevel), Some(emitter.startRunUnlessStarted(this)))
    RegisteredSuites.add(instance, registration)
    registration
  }

  def registerInstanceRunInItsPlace(instance: Suite): SuiteRegistration = {
    val registration = new RegisteredSuite(emitter, table, instance, Some(topLevel), None)
    RegisteredSuites.add(instance, registration)
    registration
  }

  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = {
    instance.scopesAndTestsOfRegistrationTree.foreach { registered =>
      val tags = Suite.autoTagClassAnnotations(tagsOfTests, instance, SuiteStructure.testNamesIn(registered))
      emitter.writeStructureOfTreeWhereChanged(table, registered, testName => filter(testName, tags, table.suiteId))
    }
  }

  def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit =
    emitter.writeStructureWhereChanged(table, SuiteStructure.ofTestNames(keptTestNames))

  def registerNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter = {
    table.nestedSuites.lift(position) match {
      case Some(tableOfNestedSuite) =>
        val registration = new RegisteredSuite(emitter, tableOfNestedSuite, nestedSuite, Some(topLevel), None)
        RegisteredSuites.add(nestedSuite, registration)
        new StructureWrapper(reporter, WrapperNode.NestedSuite(registration), emitter.writeSuiteEvent)
      case None => reporter
    }
  }

  def remove(): Unit = {
    RegisteredSuites.remove(instance, this)
    emitter.forget(this)
  }

  // The suite's own `run` may have built the Status, so `whenCompleted` may be user code. Where it throws, nothing
  // will call back, so the registration is removed at once.
  def removeWhenCompleted(status: Status): Unit = {
    try status.whenCompleted(_ => remove())
    catch {
      case thrown: Throwable =>
        remove()
        TestEventLogEmitter.keptFromCaller(throw thrown)
    }
  }

  // The origin of the events of the suite itself or of one of its scopes, each of which takes the run's execution.
  private final class OriginInRun(node: TreeNode) extends RecordingOrigin(RegisteredSuite.this) {

    def recordFor(event: Event): Unit = emitter.recordOrigin(event, this)

    def record(): RecordedOrigin = {
      val execution = startRunUnlessStarted()
      new RecordedOrigin(RegisteredSuite.this, node, execution, execution)
    }
  }

  // The origin of the events of one execution of a test, which ran in the run in flight when its first event was sent.
  private final class OriginOfTestExecution(testName: String) extends RecordingOrigin(RegisteredSuite.this) {

    private val execution = new Execution
    private val messages = new TestExecutionMessages
    // Read and written only under the emitter's lock.
    private var executionOfItsRun = Option.empty[Execution]

    def recordFor(event: Event): Unit = emitter.recordOrigin(event, this)

    def record(): RecordedOrigin = {
      val ofRun = executionOfItsRun.getOrElse(startRunUnlessStarted())
      executionOfItsRun = Some(ofRun)
      new RecordedOrigin(RegisteredSuite.this, TreeNode.Test(testName), execution, ofRun, Some(messages))
    }

    override def handOffMessage(message: => Event): Unit = emitter.writeTestMessage(RegisteredSuite.this, messages, message)

    override def ofClosingEventWithMessagesHandedOff: EventOrigin = {
      val ofTestExecution = this
      new RecordingOrigin(RegisteredSuite.this) {
        def recordFor(event: Event): Unit = emitter.recordOriginOfClosingEventWithMessagesHandedOff(event, ofTestExecution, messages)
        def record(): RecordedOrigin = ofTestExecution.record()
      }
    }
  }
}

/**
 * The registrations of this process's suite instances, by instance. A suite instance cannot carry its registration,
 * and no argument reaches every site that sends its events, so every run that writes a test event log registers here.
 *
 * A lookup takes no lock: every test of every run of the process makes one. Registering, removing and counting the
 * open event logs take this object's lock.
 */
private[scalatest] object RegisteredSuites extends SuiteRegistrations {

  // A suite instance as a key: a suite's own `equals` and `hashCode` are user code.
  private final class Identity(val instance: Suite) {
    override def hashCode: Int = System.identityHashCode(instance)

    override def equals(other: Any): Boolean = {
      other match {
        case identity: Identity => identity.instance eq instance
        case _ => false
      }
    }
  }

  // Each value is immutable, and replaced only under this object's lock.
  private val registrationsOf = new ConcurrentHashMap[Identity, List[RegisteredSuite]]
  // Read and written only under this object's lock.
  private var openEventLogs = 0

  /** Counts an emitter whose event log has opened. The first makes every site look its suite instances up here. */
  def eventLogOpened(): Unit = synchronized {
    openEventLogs += 1
    if (openEventLogs == 1) {
      SuiteRegistration.lookUpIn(this)
    }
  }

  /** Stops counting an emitter whose event log has closed. After the last, every site finds no registration again. */
  def eventLogClosed(): Unit = synchronized {
    openEventLogs -= 1
    if (openEventLogs == 0) {
      SuiteRegistration.lookUpNowhere()
    }
  }

  def add(instance: Suite, registration: RegisteredSuite): Unit = synchronized {
    registrationsOf.put(new Identity(instance), registration :: registered(instance))
  }

  def remove(instance: Suite, registration: RegisteredSuite): Unit = synchronized {
    registered(instance).filterNot(_ eq registration) match {
      case Nil => registrationsOf.remove(new Identity(instance))
      case remaining => registrationsOf.put(new Identity(instance), remaining)
    }
  }

  def of(suite: Suite): SuiteRegistration = {
    registered(suite) match {
      case registration :: Nil => registration
      case _ => SuiteRegistration.none
    }
  }

  private def registered(instance: Suite): List[RegisteredSuite] =
    Option(registrationsOf.get(new Identity(instance))).getOrElse(Nil)
}
