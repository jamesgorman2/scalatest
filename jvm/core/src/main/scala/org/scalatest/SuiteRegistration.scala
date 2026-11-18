package org.scalatest

import org.scalatest.events.Event

/**
 * A scope among its parent's scopes. `text` is the text it is registered with; `index` is its order among the parent's
 * scopes of the same `text`.
 */
private[scalatest] final case class ScopePlace(text: String, index: Int)

/**
 * What the site that sends an event knows of it, for a run that writes a test event log: the suite instance, the
 * scope or test the event is for, and the executions it belongs to.
 */
private[scalatest] abstract class EventOrigin {

  /** The emitter writes the event at once. Called before the event is reported. */
  def handOff(event: Event): Unit

  /**
   * Hands the message to no reporter. It is built only where the run writes a test event log. Called with the origin a
   * test execution's start returned.
   */
  def handOffMessage(message: => Event): Unit = preparedHandOffOfMessage(message)()

  /** Builds and encodes the message only where the run writes a test event log, and returns the step that hands it off. */
  def preparedHandOffOfMessage(message: => Event): () => Unit = EventOrigin.nothingToHandOff

  // The emitter writes the closing event without the recorded messages.
  def ofClosingEventWithMessagesHandedOff: EventOrigin = this

  /**
   * Runs `body` in the invocation context of the execution this origin is of:
   *  - a test execution's, so that a suite the test's body runs starts in it;
   *  - a scope execution's, so that a test or scope `body` runs names it.
   * Called with the origin a test execution's or a scope's start returned.
   */
  def runningInItsExecution[A](body: => A): A = body
}

private[scalatest] object EventOrigin {

  val nothingToHandOff: () => Unit = () => ()

  /** The origin of every event of a run that writes no test event log. */
  val none: EventOrigin = new EventOrigin {
    def handOff(event: Event): Unit = ()
  }
}

/**
 * The invocation context the code on a thread runs in, in a run that writes a test event log: a suite instance's run
 * outside its tests, with the scope execution it is in where it has one, or one execution of a test. A suite instance
 * whose run starts in it is registered in it.
 */
private[scalatest] abstract class InvocationContext {

  def registrationOf(suite: Suite): Option[SuiteRegistration]

  def register(suite: Suite): SuiteRegistration
}

private[scalatest] object InvocationContext {

  // Set only while a run's emitter is on, and cleared around a top-level suite of a run that writes no test event log.
  private val current = new ThreadLocal[InvocationContext]

  def get: Option[InvocationContext] = Option(current.get)

  def within[A](context: InvocationContext)(body: => A): A = replacedBy(context)(body)

  def cleared[A](body: => A): A = replacedBy(null)(body)

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

  // Called by the first engine site the run reaches.
  def startRun(): Unit

  /**
   * The origin of an event of the suite itself, where `scopes` is empty. Otherwise it starts an execution of the scope
   * at that path from the suite down, within the execution of the scope above it that the invocation context names,
   * and returns the origin of that execution's events.
   */
  def originAt(scopes: List[ScopePlace]): EventOrigin

  /**
   * The origin of the instance's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted`, sent by the site that
   * registered it as a nested suite. Its `SuiteStarting` opens the execution the instance's run takes.
   */
  def originOfStartAndEnd: EventOrigin

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
   * keeps, in the order run, differ from the suite's latest structure. Each test name is paired with whether the
   * filter ignores it, as `Filter.apply` returns them. A test the filter ignores is marked ignored.
   */
  def updateStructureFromTestNames(keptTests: => Seq[(String, Boolean)]): Unit

  // Only the first read of the run counts.
  def suiteNameRead(suiteName: String): Unit

  // Only the first read of the run counts, and it fixes the suite's node.
  def suiteIdRead(suiteId: String): Unit

  // Reads the suite's tags and suite ID in the order 3.2.20 passes them to the filter.
  final def filtered(filter: Filter, testName: String, suite: Suite): (Boolean, Boolean) = {
    val theTags = suite.tags
    val theSuiteId = suite.suiteId
    suiteIdRead(theSuiteId)
    filter(testName, theTags, theSuiteId)
  }

  final def argsOfTest(testName: String, args: Args): Args = {
    val reporter = reporterOfTest(testName, args.reporter)
    if (reporter eq args.reporter) args else args.copy(reporter = reporter)
  }
}

private[scalatest] object SuiteRegistration {

  /** The registration of every suite instance whose run finds no invocation context. */
  val none: SuiteRegistration = new SuiteRegistration {
    def startRun(): Unit = ()
    def originAt(scopes: List[ScopePlace]): EventOrigin = EventOrigin.none
    def originOfStartAndEnd: EventOrigin = EventOrigin.none
    def startTestExecution(testName: String): EventOrigin = EventOrigin.none
    def reporterOfTest(testName: String, reporter: Reporter): Reporter = reporter
    def reporterOfSuite(reporter: Reporter): Reporter = reporter
    def reporterAheadOf(reporter: Reporter): Reporter = reporter
    def startWith(reporter: Reporter): SuiteStart = SuiteStart.inTheContextItFinds(reporter)
    def runningInItsContext[A](body: => A): A = body
    def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit = ()
    def updateStructureFromTestNames(keptTests: => Seq[(String, Boolean)]): Unit = ()
    def suiteNameRead(suiteName: String): Unit = ()
    def suiteIdRead(suiteId: String): Unit = ()
  }

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

  protected def originOfStartAndEnd: EventOrigin = EventOrigin.none

  final def report(event: Event): Unit = {
    originOfStartAndEnd.handOff(event)
    reporter(event)
  }

  /** Runs the suite, through `run`, in the context the suite starts in. */
  def run(run: => Status): Status
}

private[scalatest] object SuiteStart {

  def withoutEventLog(suiteReporter: Reporter): SuiteStart =
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = ()
      def run(run: => Status): Status = InvocationContext.cleared(run)
    }

  /**
   * The start of a suite in the context the site finds. Where that context is the suite's run, it keeps the names
   * the site reads, and the suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` take its registration's
   * origin, wherever they are reported from.
   */
  def inTheContextItFinds(suite: Suite, suiteReporter: Reporter): SuiteStart = {
    val registration = SuiteRegistration.inContext(suite)
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = {
        registration.suiteNameRead(suiteName)
        registration.suiteIdRead(suiteId)
      }
      override protected def originOfStartAndEnd: EventOrigin = registration.originOfStartAndEnd
      def run(run: => Status): Status = run
    }
  }

  def inTheContextItFinds(suiteReporter: Reporter): SuiteStart =
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = ()
      def run(run: => Status): Status = run
    }

  def ofNestedSuite(nestedSuite: Suite, reporter: Reporter): SuiteStart =
    InvocationContext.get.fold(inTheContextItFinds(reporter))(_.register(nestedSuite).startWith(reporter))
}
