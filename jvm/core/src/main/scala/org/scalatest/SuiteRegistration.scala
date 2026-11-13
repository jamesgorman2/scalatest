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

  /** Records this origin beside the event, where the run's test event log is written, and never on the event. Called before the event is reported. */
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
  def runStarting(): Unit

  /** The origin of an event of the suite itself, where `scopes` is empty, and otherwise of the scope at that path from the suite down. */
  def originAt(scopes: List[ScopePlace]): EventOrigin

  /** Starts an execution of a test, and returns the origin of that execution's events. */
  def originOfTestExecution(testName: String): EventOrigin

  /** The reporter a test is run with: the test's structure wrapper over `reporter`. */
  def reporterOfTest(testName: String, reporter: Reporter): Reporter

  /**
   * Registers a nested suite the instance is about to run, against the node at its position in `nestedSuites`, and
   * returns the reporter the nested suite is started and run with: its structure wrapper over `reporter`.
   */
  def reporterOfNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter

  /** Removes this registration. Called where the instance's run throws. */
  def removed(): Unit

  /** Removes this registration once the `Status` the instance's run returned completes. */
  def removedWhenCompleted(status: Status): Unit

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

  /** The registration made with a suite's structure wrapper, or `SuiteRegistration.none` for any other reporter. */
  def madeWith(reporter: Reporter): SuiteRegistration
}

private[scalatest] object SuiteRegistration {

  /** The registration of every suite instance of a run that writes no test event log. It registers and wraps nothing. */
  val none: SuiteRegistration = new SuiteRegistration {
    def runStarting(): Unit = ()
    def originAt(scopes: List[ScopePlace]): EventOrigin = EventOrigin.none
    def originOfTestExecution(testName: String): EventOrigin = EventOrigin.none
    def reporterOfTest(testName: String, reporter: Reporter): Reporter = reporter
    def reporterOfNestedSuite(position: Int, nestedSuite: Suite, reporter: Reporter): Reporter = reporter
    def removed(): Unit = ()
    def removedWhenCompleted(status: Status): Unit = ()
  }

  private val noRegistrations: SuiteRegistrations = new SuiteRegistrations {
    def of(suite: Suite): SuiteRegistration = none
    def madeWith(reporter: Reporter): SuiteRegistration = none
  }

  // A suite instance cannot carry its registration, and `Args` reaches user code, so the sites find it here.
  private val registrations = new AtomicReference[SuiteRegistrations](noRegistrations)

  /** Makes `registered` the registrations every site looks in. Called when a process first writes a test event log. */
  def lookedUpIn(registered: SuiteRegistrations): Unit = registrations.set(registered)

  def of(suite: Suite): SuiteRegistration = registrations.get.of(suite)

  def madeWith(reporter: Reporter): SuiteRegistration = registrations.get.madeWith(reporter)

  /**
   * The registration a site made as it took a suite's structure wrapper, `taken`, in place of the reporter it would
   * have given the suite. Where the site took that reporter itself there is none: the reporter may be the structure
   * wrapper of the suite around this one, whose registration is not the site's to remove.
   */
  def madeTaking(taken: Reporter, inPlaceOf: Reporter): SuiteRegistration =
    if (taken eq inPlaceOf) none else madeWith(taken)
}
