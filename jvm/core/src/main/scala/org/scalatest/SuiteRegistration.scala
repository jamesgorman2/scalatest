package org.scalatest

import java.util.concurrent.atomic.AtomicReference
import org.scalatest.events.Event

/** A scope among its parent's scopes: the text it is registered with, and its place, from 0, among those of that text. */
private[scalatest] final case class ScopePlace(text: String, placeAmongScopesOfItsText: Int)

/**
 * What the site that sends an event knows of it, for a run that writes a test event log: the suite instance, the
 * scope or test the event is for, and the executions it belongs to.
 */
private[scalatest] abstract class EventOrigin {

  /** Records this origin against the event with the emitter of the run's test event log, leaving the event unchanged. Called before the event is reported. */
  def recordFor(event: Event): Unit

  /**
   * Hands a message of a test's execution to the emitter as the test calls it, and to no reporter. The message is
   * built only where the run writes a test event log. Called with the origin a test execution's start returned.
   */
  def handOffMessage(message: => Event): Unit = preparedHandOffOfMessage(message)()

  /**
   * Prepares `handOffMessage` without handing the message off: builds and encodes the message where the run writes a
   * test event log, and returns the step that hands it to the emitter. Elsewhere it builds nothing.
   */
  def preparedHandOffOfMessage(message: => Event): () => Unit = EventOrigin.nothingToHandOff

  /**
   * The origin of a test's closing event, whose recorded messages the test has already handed off one by one, so the
   * emitter writes the closing event without them.
   */
  def ofClosingEventWithMessagesHandedOff: EventOrigin = this
}

private[scalatest] object EventOrigin {

  /** The prepared hand-off of a message that is handed to no emitter. */
  val nothingToHandOff: () => Unit = () => ()

  /** The origin of every event of a run that writes no test event log. It records nothing. */
  val none: EventOrigin = new EventOrigin {
    def recordFor(event: Event): Unit = ()
  }
}

/**
 * One registration of a suite instance, by the site that runs it, against the suite's node in a run's test event
 * log. The engine sites that send the instance's events find it by the instance.
 */
private[scalatest] abstract class SuiteRegistration {

  /** Starts an execution for a run of the instance. Called by the first engine site the run reaches. */
  def startRun(): Unit

  /** The origin of an event of the suite itself, where `scopes` is empty, and otherwise of the scope at that path from the suite down. */
  def originAt(scopes: List[ScopePlace]): EventOrigin

  /** Starts an execution of a test, and returns the origin of that execution's events. */
  def startTestExecution(testName: String): EventOrigin

  /** The reporter a test is run with: the test's structure wrapper over `reporter`. */
  def reporterOfTest(testName: String, reporter: Reporter): Reporter

  /** The reporter a site that made this registration runs the instance with: the suite's structure wrapper over `reporter`. */
  def reporterOfSuite(reporter: Reporter): Reporter

  /**
   * The suite's structure wrapper over `reporter`, a reporter that holds the suite's events back, such as a
   * `TestSortingReporter`. The wrapper hands each event off as it is sent, before `reporter` can hold it back.
   */
  def reporterAheadOf(reporter: Reporter): Reporter

  /**
   * Registers `instance`, which this suite instance created to run one test, against the suite's node and in the
   * execution of the current run. The instance's run starts no execution of its own. The caller removes the returned
   * registration.
   */
  def registerInstanceOfTest(instance: Suite): SuiteRegistration

  /**
   * Registers `instance`, which this suite instance constructed to run in its place, against the suite's node.
   * The caller removes the returned registration.
   */
  def registerInstanceRunInItsPlace(instance: Suite): SuiteRegistration

  /**
   * Registers a nested suite the instance is about to run, against the node at its position in `nestedSuites`, and
   * returns the reporter the nested suite is started and run with: its structure wrapper over `reporter`.
   */
  def registerNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter

  /**
   * Writes a further structure of the suite where the scopes and tests the filter keeps from the instance's
   * registration tree differ from the suite's latest structure. A test an earlier structure of the suite filtered
   * keeps that result. Any other is filtered by `filter` over `tagsOfTests`, the tags the instance registered its
   * tests with. Calls no member of the instance that a suite outside ScalaTest can override.
   */
  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit

  /**
   * For an instance with no registration tree, writes a further structure of the suite where the tests the filter
   * keeps, in the order run, differ from the suite's latest structure.
   */
  def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit

  /**
   * Keeps the suite name the site read for the instance's run, as 3.2.20 reads it there, for a registration that
   * does not yet know the suite node of the instance.
   */
  def suiteNameRead(suiteName: String): Unit

  /** Keeps the suite ID the site read for the instance's run, as `suiteNameRead` keeps its suite name. */
  def suiteIdRead(suiteId: String): Unit

  /** Removes this registration. Called where the instance's run throws. */
  def remove(): Unit

  /** Removes this registration once the `Status` the instance's run returned completes. */
  def removeWhenCompleted(status: Status): Unit

  /** Runs the instance, removing this registration where the run throws, and otherwise once its `Status` completes. */
  final def removedOnceRun(run: => Status): Status = {
    val status =
      try run
      catch {
        case thrown: Throwable =>
          remove()
          throw thrown
      }
    removeWhenCompleted(status)
    status
  }

  /** The `Args` a test is run with: `args`, reporting to the test's structure wrapper. */
  final def argsOfTest(testName: String, args: Args): Args = {
    val reporter = reporterOfTest(testName, args.reporter)
    if (reporter eq args.reporter) args else args.copy(reporter = reporter)
  }
}

/** The registrations of a process's suite instances. */
private[scalatest] abstract class SuiteRegistrations {

  /** The instance's registration, or `SuiteRegistration.none` where it has none or several. */
  def of(suite: Suite): SuiteRegistration

  /**
   * Registers an instance that has no registration, not yet knowing its suite node, and returns that registration.
   * None where the instance has one or several.
   */
  def registeredUnlessRegistered(suite: Suite): Option[SuiteRegistration]
}

/** A reporter that knows the registration made with it: a structure wrapper, in a run that writes a test event log. */
private[scalatest] trait ReporterOfRegisteredSuite extends Reporter {

  /** The registration a site made as it took this reporter for a suite, or `SuiteRegistration.none` where it took it for a test. */
  def registrationOfItsSuite: SuiteRegistration
}

private[scalatest] object SuiteRegistration {

  /** The registration of every suite instance of a run that writes no test event log. It registers and wraps nothing. */
  val none: SuiteRegistration = new SuiteRegistration {
    def startRun(): Unit = ()
    def originAt(scopes: List[ScopePlace]): EventOrigin = EventOrigin.none
    def startTestExecution(testName: String): EventOrigin = EventOrigin.none
    def reporterOfTest(testName: String, reporter: Reporter): Reporter = reporter
    def reporterOfSuite(reporter: Reporter): Reporter = reporter
    def reporterAheadOf(reporter: Reporter): Reporter = reporter
    def registerInstanceOfTest(instance: Suite): SuiteRegistration = none
    def registerInstanceRunInItsPlace(instance: Suite): SuiteRegistration = none
    def registerNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter = reporter
    def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = ()
    def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit = ()
    def suiteNameRead(suiteName: String): Unit = ()
    def suiteIdRead(suiteId: String): Unit = ()
    def remove(): Unit = ()
    def removeWhenCompleted(status: Status): Unit = ()
  }

  private val noRegistrations: SuiteRegistrations = new SuiteRegistrations {
    def of(suite: Suite): SuiteRegistration = none
    def registeredUnlessRegistered(suite: Suite): Option[SuiteRegistration] = None
  }

  // A suite instance cannot carry its registration, and `Args` reaches user code, so the sites find it here.
  private val registrations = new AtomicReference[SuiteRegistrations](noRegistrations)

  /** Makes `registered` the registrations every site looks in. Called when a process that writes no test event log starts to write one. */
  def lookUpIn(registered: SuiteRegistrations): Unit = registrations.set(registered)

  /** Has every site find no registration again. Called when a process closes the last test event log it was writing. */
  def lookUpNowhere(): Unit = registrations.set(noRegistrations)

  def of(suite: Suite): SuiteRegistration = registrations.get.of(suite)

  /**
   * Runs an instance with the `Args` it is given. Where no site has registered the instance, as for one user code
   * creates and runs, it first registers the instance, not yet knowing its suite node, and runs it with its suite's
   * structure wrapper over `args.reporter`. It then removes that registration where the run throws, and otherwise
   * once its `Status` completes.
   */
  def runRegistered(suite: Suite, args: Args)(run: Args => Status): Status = {
    val made = if (args eq null) None else registrations.get.registeredUnlessRegistered(suite)
    made match {
      case Some(registration) => registration.removedOnceRun(run(args.copy(reporter = registration.reporterOfSuite(args.reporter))))
      case None => run(args)
    }
  }

  /** The registration made with a suite's structure wrapper, or `none` for any other reporter. */
  def madeWith(reporter: Reporter): SuiteRegistration =
    reporter match {
      case ofRegisteredSuite: ReporterOfRegisteredSuite => ofRegisteredSuite.registrationOfItsSuite
      case _ => none
    }

  /**
   * The registration a site made as it took a suite's structure wrapper, `taken`, in place of the reporter it would
   * have given the suite. Where the site took that reporter itself there is none: the reporter may be the structure
   * wrapper of the suite around this one, whose registration is not the site's to remove.
   */
  def madeTaking(taken: Reporter, inPlaceOf: Reporter): SuiteRegistration =
    if (taken eq inPlaceOf) none else madeWith(taken)
}
