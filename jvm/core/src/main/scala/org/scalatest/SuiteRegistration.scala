package org.scalatest

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

  /**
   * Runs a test's body in the invocation context of the test execution this origin is of, so that a suite the body
   * runs starts in that context. Called with the origin a test execution's start returned.
   */
  def runningTheTest[A](body: => A): A = body
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
 * The invocation context the code on a thread runs in, in a run that writes a test event log: a suite instance's run,
 * outside its tests, or one execution of a test. A suite instance whose run starts in it is registered in it.
 */
private[scalatest] abstract class InvocationContext {

  /** The registration of `suite` this context is the run of, where it is one. */
  def registrationOf(suite: Suite): Option[SuiteRegistration]

  /** Registers `suite`, an instance whose run starts in this context. */
  def register(suite: Suite): SuiteRegistration
}

private[scalatest] object InvocationContext {

  // Set only while a run's emitter is on, and cleared around a top-level suite of a run that writes no test event log.
  private val current = new ThreadLocal[InvocationContext]

  /** The context the code on this thread runs in, where one is set. */
  def get: Option[InvocationContext] = Option(current.get)

  /** Runs `body` in `context`, then restores the context this thread had. */
  def within[A](context: InvocationContext)(body: => A): A = replacedBy(context)(body)

  /** Runs `body` in no context, then restores the context this thread had. */
  def cleared[A](body: => A): A = replacedBy(null)(body)

  /** `runnable`, run on another thread in the context this thread runs in now. */
  def carriedTo(runnable: Runnable): Runnable = {
    val context = current.get
    if (context eq null) runnable else () => replacedBy(context)(runnable.run())
  }

  private def replacedBy[A](context: InvocationContext)(body: => A): A = {
    val previous = current.get
    current.set(context)
    try body finally current.set(previous)
  }
}

/**
 * One registration of a suite instance's run against the suite's node in a run's test event log. The first site of
 * the run that finds an invocation context set registers the instance in it, and runs the rest of the run in the
 * context of that registration, where each later site finds it.
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

  /** The reporter the site that registered the instance runs it with: the suite's structure wrapper over `reporter`. */
  def reporterOfSuite(reporter: Reporter): Reporter

  /**
   * The suite's structure wrapper over `reporter`, a reporter that holds the suite's events back, such as a
   * `TestSortingReporter`. The wrapper hands each event off as it is sent, before `reporter` can hold it back.
   */
  def reporterAheadOf(reporter: Reporter): Reporter

  /**
   * How the site that registered this instance as a nested suite starts it: with the suite's structure wrapper over
   * `reporter`, and in the context of this registration.
   */
  def startWith(reporter: Reporter): SuiteStart

  /** Runs `body` in the invocation context of this registration's run. */
  def runningInItsContext[A](body: => A): A

  /**
   * Writes a further structure of the suite where the scopes and tests the filter keeps from the instance's
   * registration tree differ from the suite's latest structure. A test an earlier structure of the suite filtered
   * keeps that result. Any other is filtered by `filter` over `tagsOfTests`, the tags the instance registered its
   * tests with. Calls no member of the instance that a suite outside ScalaTest can override. Where the suite ID of
   * the run is not yet known, the structure is written once it is.
   */
  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit

  /**
   * For an instance with no registration tree, writes a further structure of the suite where the tests the filter
   * keeps, in the order run, differ from the suite's latest structure.
   */
  def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit

  /** Keeps the suite name a site read for the instance's run, where it is the first read of it. */
  def suiteNameRead(suiteName: String): Unit

  /** Keeps the suite ID a site read for the instance's run, where it is the first read of it, which fixes its node. */
  def suiteIdRead(suiteId: String): Unit

  /**
   * What `filter` says of one test of `suite`, the instance of this registration's run. It reads the suite's tags and
   * suite ID in the order 3.2.20 passes them to the filter, and keeps the suite ID it read.
   */
  final def filtered(filter: Filter, testName: String, suite: Suite): (Boolean, Boolean) = {
    val theTags = suite.tags
    val theSuiteId = suite.suiteId
    suiteIdRead(theSuiteId)
    filter(testName, theTags, theSuiteId)
  }

  /** The `Args` a test is run with: `args`, reporting to the test's structure wrapper. */
  final def argsOfTest(testName: String, args: Args): Args = {
    val reporter = reporterOfTest(testName, args.reporter)
    if (reporter eq args.reporter) args else args.copy(reporter = reporter)
  }
}

private[scalatest] object SuiteRegistration {

  /** The registration of every suite instance whose run finds no invocation context. It registers and wraps nothing. */
  val none: SuiteRegistration = new SuiteRegistration {
    def startRun(): Unit = ()
    def originAt(scopes: List[ScopePlace]): EventOrigin = EventOrigin.none
    def startTestExecution(testName: String): EventOrigin = EventOrigin.none
    def reporterOfTest(testName: String, reporter: Reporter): Reporter = reporter
    def reporterOfSuite(reporter: Reporter): Reporter = reporter
    def reporterAheadOf(reporter: Reporter): Reporter = reporter
    def startWith(reporter: Reporter): SuiteStart = SuiteStart.inTheContextItFinds(reporter)
    def runningInItsContext[A](body: => A): A = body
    def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = ()
    def updateStructureFromTestNames(keptTestNames: => Seq[String]): Unit = ()
    def suiteNameRead(suiteName: String): Unit = ()
    def suiteIdRead(suiteId: String): Unit = ()
  }

  /** The registration of `suite` the invocation context this thread runs in is the run of, else `none`. */
  def inContext(suite: Suite): SuiteRegistration = InvocationContext.get.flatMap(_.registrationOf(suite)).getOrElse(none)

  /**
   * Runs `suite` from a site that registers it where no earlier site of its run has: `run` takes the registration of
   * the run and the `Args` to run with.
   *  - Where the context is that registration's run, `run` takes it and `args`.
   *  - Where another context is set, the suite is registered in it, and `run` runs in the new registration's context,
   *    with its suite's structure wrapper over `args.reporter`.
   *  - Where none is set, `run` takes `none` and `args`.
   */
  def registeredRun(suite: Suite, args: Args)(run: (SuiteRegistration, Args) => Status): Status = {
    InvocationContext.get match {
      case None => run(none, args)
      case Some(context) =>
        context.registrationOf(suite) match {
          case Some(registration) => run(registration, args)
          case None =>
            val registration = context.register(suite)
            val registeredArgs = if (args eq null) args else args.copy(reporter = registration.reporterOfSuite(args.reporter))
            registration.runningInItsContext(run(registration, registeredArgs))
        }
    }
  }
}

/** How a site starts a suite it runs: the reporter it gives the suite, and the invocation context it runs the suite in. */
private[scalatest] abstract class SuiteStart {

  /** The reporter the suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` are sent to, and that the suite is run with. */
  def reporter: Reporter

  /** Takes the suite name and suite ID the site read as it built the suite's `SuiteStarting`. */
  def namesRead(suiteName: String, suiteId: String): Unit

  /** Runs the suite, through `run`, in the context the suite starts in. */
  def run(run: => Status): Status
}

private[scalatest] object SuiteStart {

  /** The start of a top-level suite of a run that writes no test event log: it runs in no context. */
  def withoutEventLog(suiteReporter: Reporter): SuiteStart =
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = ()
      def run(run: => Status): Status = InvocationContext.cleared(run)
    }

  /** The start of a suite in the context the site finds, which takes the names it reads where it is the suite's run. */
  def inTheContextItFinds(suite: Suite, suiteReporter: Reporter): SuiteStart =
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = {
        val registration = SuiteRegistration.inContext(suite)
        registration.suiteNameRead(suiteName)
        registration.suiteIdRead(suiteId)
      }
      def run(run: => Status): Status = run
    }

  /** The start of a suite in the context the site finds, which keeps no names. */
  def inTheContextItFinds(suiteReporter: Reporter): SuiteStart =
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = ()
      def run(run: => Status): Status = run
    }

  /** The start of a nested suite: registered in the context the site runs in, where one is set. */
  def ofNestedSuite(nestedSuite: Suite, reporter: Reporter): SuiteStart =
    InvocationContext.get.fold(inTheContextItFinds(reporter))(_.register(nestedSuite).startWith(reporter))
}
