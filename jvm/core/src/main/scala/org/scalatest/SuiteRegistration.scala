package org.scalatest

import org.scalatest.events.Event

private[scalatest] final case class ScopePlace(text: String, index: Int)

private[scalatest] abstract class EventOrigin {

  def handOff(event: Event): Unit

  def handOffMessage(message: => Event): Unit = preparedHandOffOfMessage(message)()

  def preparedHandOffOfMessage(message: => Event): () => Unit = EventOrigin.nothingToHandOff

  def ofClosingEventWithMessagesHandedOff: EventOrigin = this

  def runningInItsExecution[A](body: => A): A = body
}

private[scalatest] object EventOrigin {

  val nothingToHandOff: () => Unit = () => ()

  val none: EventOrigin = new EventOrigin {
    def handOff(event: Event): Unit = ()
  }
}

private[scalatest] abstract class InvocationContext {

  def registrationOf(suite: Suite): Option[SuiteRegistration]

  def register(suite: Suite): SuiteRegistration
}

private[scalatest] object InvocationContext {

  private val current: ThreadLocal[Option[InvocationContext]] =
    new ThreadLocal[Option[InvocationContext]] {
      override def initialValue(): Option[InvocationContext] = None
    }

  def get: Option[InvocationContext] = current.get

  def within[A](context: InvocationContext)(body: => A): A = replacedBy(Some(context))(body)

  def cleared[A](body: => A): A = replacedBy(None)(body)

  def carriedTo(runnable: Runnable): Runnable =
    current.get.fold(runnable) { context =>
      new Runnable {
        def run(): Unit = replacedBy(Some(context))(runnable.run())
      }
    }

  private def replacedBy[A](context: Option[InvocationContext])(body: => A): A = {
    val previous = current.get
    current.set(context)
    try body finally current.set(previous)
  }
}

private[scalatest] abstract class SuiteRegistration {

  def startRun(): Unit

  def originAt(scopes: List[ScopePlace]): EventOrigin

  def originOfStartAndEnd: EventOrigin

  def startTestExecution(testName: String): EventOrigin

  def reporterOfTest(testName: String, reporter: Reporter): Reporter

  def reporterOfSuite(reporter: Reporter): Reporter

  def reporterAheadOf(reporter: Reporter): Reporter

  def startWith(reporter: Reporter): SuiteStart

  def runningInItsContext[A](body: => A): A

  def updateStructureFromRegistrationTree(tagsOfTests: => Map[String, Set[String]], filter: Filter): Unit

  def updateStructureFromTestNames(keptTests: => Seq[(String, Boolean)]): Unit

  def suiteNameRead(suiteName: String): Unit

  def suiteIdRead(suiteId: String): Unit

  final def filtered(filter: Filter, testName: String, suite: Suite): (Boolean, Boolean) = {
    // The tags and suite ID are read in the order ScalaTest 3.2.20 passes them to the filter.
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

private[scalatest] abstract class SuiteStart {

  def reporter: Reporter

  def namesRead(suiteName: String, suiteId: String): Unit

  protected def originOfStartAndEnd: EventOrigin = EventOrigin.none

  final def report(event: Event): Unit = {
    originOfStartAndEnd.handOff(event)
    reporter(event)
  }

  def run(run: => Status): Status
}

private[scalatest] object SuiteStart {

  def withoutEventLog(suiteReporter: Reporter): SuiteStart =
    new SuiteStart {
      def reporter: Reporter = suiteReporter
      def namesRead(suiteName: String, suiteId: String): Unit = ()
      def run(run: => Status): Status = InvocationContext.cleared(run)
    }

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
