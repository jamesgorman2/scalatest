package org.scalatest.tools

import java.util.concurrent.ConcurrentHashMap
import org.scalatest.EventOrigin
import org.scalatest.Reporter
import org.scalatest.ScopePlace
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.SuiteRegistrations
import org.scalatest.events.Event

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
 */
private[scalatest] final class RecordedOrigin(
  val registration: RegisteredSuite,
  val node: TreeNode,
  val execution: Execution,
  val executionOfRun: Execution
)

/** The origin of the events one engine site sends. The emitter asks it for what to keep beside each of them. */
private[scalatest] abstract class RecordingOrigin extends EventOrigin {

  /** Called under the emitter's lock. */
  def recorded(): RecordedOrigin
}

/**
 * The registration of a suite instance against its suite's node table, in a run that writes a test event log.
 *
 * @param ofTopLevelSuite the registration of the top-level suite this instance runs within, or none where it is that suite
 */
private[scalatest] final class RegisteredSuite(
  emitter: TestEventLogEmitter,
  val table: SuiteNodeTable,
  instance: Suite,
  ofTopLevelSuite: Option[RegisteredSuite]
) extends SuiteRegistration {

  /** The registration of the top-level suite this instance runs within: its own where it is that suite. */
  val topLevel: RegisteredSuite = ofTopLevelSuite.getOrElse(this)

  // Read and written only under the emitter's lock.
  private var executionOfLatestRun = Option.empty[Execution]

  /** Starts an execution for a run of the instance. Called under the emitter's lock. */
  def startRun(): Execution = {
    val execution = table.executionOfStartingRun()
    executionOfLatestRun = Some(execution)
    execution
  }

  /** The execution of the instance's latest run, starting one where no site has. Called under the emitter's lock. */
  def executionOfRun(): Execution = executionOfLatestRun.getOrElse(startRun())

  def runStarting(): Unit = emitter.runStarting(this)

  def originAt(scopes: List[ScopePlace]): EventOrigin =
    new OriginInRun(if (scopes.isEmpty) TreeNode.OfSuite else TreeNode.Scope(scopes))

  def originOfTestExecution(testName: String): EventOrigin = new OriginOfTestExecution(testName)

  def reporterOfTest(testName: String, reporter: Reporter): Reporter =
    new StructureWrapper(reporter, WrapperNode.Test(this, testName), emitter.suiteEvent)

  def reporterOfNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter = {
    table.nestedSuites.lift(position) match {
      case Some(tableOfNestedSuite) =>
        val registration = new RegisteredSuite(emitter, tableOfNestedSuite, nestedSuite, Some(topLevel))
        RegisteredSuites.add(nestedSuite, registration)
        new StructureWrapper(reporter, WrapperNode.NestedSuite(registration), emitter.suiteEvent)
      case None => reporter
    }
  }

  def remove(): Unit = {
    RegisteredSuites.remove(instance, this)
    emitter.forget(this)
  }

  // `whenCompleted` is the method of a Status the suite's own `run` may have built. Where it throws, nothing will
  // call back, so the registration is removed at once.
  def removeWhenCompleted(status: Status): Unit = {
    try status.whenCompleted(_ => remove())
    catch {
      case thrown: Throwable =>
        remove()
        TestEventLogEmitter.keptFromCaller(throw thrown)
    }
  }

  // The origin of the events of the suite itself or of one of its scopes, each of which takes the run's execution.
  private final class OriginInRun(node: TreeNode) extends RecordingOrigin {

    def recordFor(event: Event): Unit = emitter.recordOrigin(event, this)

    def recorded(): RecordedOrigin = {
      val execution = executionOfRun()
      new RecordedOrigin(RegisteredSuite.this, node, execution, execution)
    }
  }

  // The origin of the events of one execution of a test, which ran in the run in flight when its first event was sent.
  private final class OriginOfTestExecution(testName: String) extends RecordingOrigin {

    private val execution = new Execution
    // Read and written only under the emitter's lock.
    private var executionOfItsRun = Option.empty[Execution]

    def recordFor(event: Event): Unit = emitter.recordOrigin(event, this)

    def recorded(): RecordedOrigin = {
      val ofRun = executionOfItsRun.getOrElse(executionOfRun())
      executionOfItsRun = Some(ofRun)
      new RecordedOrigin(RegisteredSuite.this, TreeNode.Test(testName), execution, ofRun)
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

  /** Counts an emitter whose event log has opened. The first has every site look its suite instance up here. */
  def eventLogOpened(): Unit = synchronized {
    openEventLogs += 1
    if (openEventLogs == 1) {
      SuiteRegistration.lookUpIn(this)
    }
  }

  /** Counts out an emitter whose event log has closed. After the last, every site finds no registration again. */
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
