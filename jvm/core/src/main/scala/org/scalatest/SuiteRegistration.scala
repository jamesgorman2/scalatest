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
}

private[scalatest] object EventOrigin {

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

  /**
   * Registers a nested suite the instance is about to run, against the node at its position in `nestedSuites`, and
   * returns the reporter the nested suite is started and run with: its structure wrapper over `reporter`.
   */
  def registerNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter

  /** Removes this registration. Called where the instance's run throws. */
  def remove(): Unit

  /** Removes this registration once the `Status` the instance's run returned completes. */
  def removeWhenCompleted(status: Status): Unit

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
    def registerNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter = reporter
    def remove(): Unit = ()
    def removeWhenCompleted(status: Status): Unit = ()
  }

  private val noRegistrations: SuiteRegistrations = new SuiteRegistrations {
    def of(suite: Suite): SuiteRegistration = none
  }

  // A suite instance cannot carry its registration, and `Args` reaches user code, so the sites find it here.
  private val registrations = new AtomicReference[SuiteRegistrations](noRegistrations)

  /** Makes `registered` the registrations every site looks in. Called when a process that writes no test event log starts to write one. */
  def lookUpIn(registered: SuiteRegistrations): Unit = registrations.set(registered)

  /** Has every site find no registration again. Called when a process closes the last test event log it was writing. */
  def lookUpNowhere(): Unit = registrations.set(noRegistrations)

  def of(suite: Suite): SuiteRegistration = registrations.get.of(suite)

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
