package org.scalatest.tools

import org.scalatest.GivenWhenThen
import org.scalatest.events.AlertProvided
import org.scalatest.events.DecodedEvent
import org.scalatest.events.Event
import org.scalatest.events.EventLogComparison
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.RecordableEvent
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunStarting
import org.scalatest.events.ScopeClosed
import org.scalatest.events.ScopeOpened
import org.scalatest.events.SuiteAborted
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestFailed
import org.scalatest.events.TestIgnored
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class TestEventLogNodeEventsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  /**
   * A run as a criterion states it.
   *
   * @param reported what the run's reporters receive, in order, whether or not the run writes an event log
   * @param eventLogInNotation the run's whole event log, in the event notation
   * @param eventLogInFormat that event log as the format writes it, one line for each event, the first suite's node ID being 1
   */
  private final case class StatedRun(reported: Vector[String], eventLogInNotation: String, eventLogInFormat: List[String])

  private def recordedIn(recorded: Seq[RecordableEvent]): String = {
    val messages = recorded.collect { case info: InfoProvided => s"InfoProvided ${info.message}" }
    if (messages.isEmpty) "" else messages.mkString(", recording ", ", ", "")
  }

  /** What a run's reporters were told, in the order they were told it. */
  private def reported(events: Vector[Event]): Vector[String] = {
    events.collect {
      case _: RunStarting => "RunStarting"
      case _: RunCompleted => "RunCompleted"
      case aborted: RunAborted => s"RunAborted: ${aborted.message}"
      case starting: SuiteStarting => s"SuiteStarting ${starting.suiteName}"
      case completed: SuiteCompleted => s"SuiteCompleted ${completed.suiteName}"
      case aborted: SuiteAborted => s"SuiteAborted ${aborted.suiteName}: ${aborted.message}"
      case opened: ScopeOpened => s"ScopeOpened ${opened.message}"
      case closed: ScopeClosed => s"ScopeClosed ${closed.message}"
      case starting: TestStarting => s"TestStarting ${starting.testName}"
      case succeeded: TestSucceeded => s"TestSucceeded ${succeeded.testName}${recordedIn(succeeded.recordedEvents)}"
      case failed: TestFailed => s"TestFailed ${failed.testName}: ${failed.message}${recordedIn(failed.recordedEvents)}"
      case ignored: TestIgnored => s"TestIgnored ${ignored.testName}"
      case info: InfoProvided => s"InfoProvided ${info.message}"
      case markup: MarkupProvided => s"MarkupProvided ${markup.text}"
      case other => other.getClass.getSimpleName
    }
  }

  private val runStarted = TestEventLogLine.runStarted(1L, "2026-01-02T03:04:05.678Z")
  private val runStartingOfFramework = TestEventLogLine.runEvent("RunStarting", """"testCount":0,"configMap":{},""")
  private val runCompleted = TestEventLogLine.runEvent("RunCompleted")
  private val runEndedCompleted = TestEventLogLine.runEnded("completed")

  private def frameworkRunOf(lines: String*): List[String] =
    runStarted :: runStartingOfFramework :: lines.toList ::: List(runCompleted, runEndedCompleted)

  private def suiteStarting(node: Long): String = TestEventLogLine.suiteEvent(node, 1, "SuiteStarting")

  private def suiteCompleted(node: Long): String = TestEventLogLine.suiteEvent(node, 1, "SuiteCompleted")

  private def inFirstExecution(node: Long, eventName: String, fields: String = ""): String =
    TestEventLogLine.scopeOrTestEvent(node, 1, 1, eventName, fields)

  private def startingAndSucceeded(node: Long): List[String] =
    List(inFirstExecution(node, "TestStarting"), inFirstExecution(node, "TestSucceeded"))

  private def thrown(className: String, message: String): String =
    s""""throwable":{"id":1,"className":"$className","message":"$message"},"""

  /**
   * Registers the scenario of the run that writes its events to the event log, and the scenario of the decoder that
   * reads them.
   *
   * @param nestedSuiteIds the suites the suite nests, which Framework is told to run the tests of
   */
  private def eventScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    launch: Launch = Launch.ByFramework,
    arguments: List[String] = Nil,
    nestedSuiteIds: List[String] = Nil
  ): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      When(s"${launch.named} runs it without the runner argument")
      val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(launch, suiteClassName, arguments, nestedSuiteIds))

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reportedWithoutTheArgument shouldBe stated.reported

      TemporaryEventLog.in { eventLogFile =>
        When(s"${launch.named} runs it with ${("the runner argument" :: arguments).mkString(" and ")}")
        val reportedWithTheArgument =
          reported(eventsReportedWhenLaunched(launch, suiteClassName, arguments ++ runnerArgument(eventLogFile), nestedSuiteIds))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the run's reporters are told the same")
        reportedWithTheArgument shouldBe stated.reported

        And(s"the event log is $eventLogHolds")
        StatedLines.mismatchWhenIs(lines, stated.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(stated.eventLogInNotation)) shouldBe None
      }
    }

    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(stated.eventLogInFormat)

      Then(s"it yields $eventLogHolds")
      EventLogComparison.mismatchWhenIs(events, built(stated.eventLogInNotation)) shouldBe None
    }
  }

  private val stackFunSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting StackFunSpec",
      "ScopeOpened A Stack",
      "ScopeOpened when empty",
      "TestStarting A Stack when empty is empty",
      "TestSucceeded A Stack when empty is empty",
      "TestStarting A Stack when empty pops",
      "TestSucceeded A Stack when empty pops",
      "ScopeClosed when empty",
      "ScopeClosed A Stack",
      "SuiteCompleted StackFunSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "StackFunSpec", class "samples.StackFunSpec"
        scope "A Stack", label "A Stack"
          scope "when empty", label "when empty"
            test "is empty", label "- is empty"
            test "pops", label "- pops"
    suite "StackFunSpec": SuiteStarting
    scope "A Stack": ScopeOpened
    scope "when empty": ScopeOpened
    test "is empty": TestStarting
    test "is empty": TestSucceeded
    test "pops": TestStarting
    test "pops": TestSucceeded
    scope "when empty": ScopeClosed
    scope "A Stack": ScopeClosed
    suite "StackFunSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "StackFunSpec", "samples.StackFunSpec"),
        TestEventLogLine.scope(2, 1, 0, "A Stack", "A Stack"),
        TestEventLogLine.scope(3, 2, 0, "when empty", "when empty"),
        TestEventLogLine.test(4, 3, 0, "is empty", "- is empty"),
        TestEventLogLine.test(5, 3, 1, "pops", "- pops")
      ),
      suiteStarting(1),
      inFirstExecution(2, "ScopeOpened"),
      inFirstExecution(3, "ScopeOpened"),
      inFirstExecution(4, "TestStarting"),
      inFirstExecution(4, "TestSucceeded"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      inFirstExecution(3, "ScopeClosed"),
      inFirstExecution(2, "ScopeClosed"),
      suiteCompleted(1)
    )
  )

  private val setupFailsSpec = StatedRun(
    Vector("RunStarting", "SuiteStarting SetupFailsSpec", "SuiteAborted SetupFailsSpec: database unreachable", "RunCompleted"),
    """
    run started
    run: RunStarting
    structure
      suite "SetupFailsSpec", class "samples.SetupFailsSpec"
        scope "A repo", label "A repo"
          test "read", label "- should read"
          test "write", label "- should write"
    suite "SetupFailsSpec": SuiteStarting
    suite "SetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "SetupFailsSpec", "samples.SetupFailsSpec"),
        TestEventLogLine.scope(2, 1, 0, "A repo", "A repo"),
        TestEventLogLine.test(3, 2, 0, "read", "- should read"),
        TestEventLogLine.test(4, 2, 1, "write", "- should write")
      ),
      suiteStarting(1),
      TestEventLogLine.suiteEvent(
        1,
        1,
        "SuiteAborted",
        """"message":"database unreachable",""" + thrown("java.lang.RuntimeException", "database unreachable")
      )
    )
  )

  // Framework's SuiteSortingReporter gives the run's reporters a nested suite's events after those of the suite that nests it.
  private val outerSuites = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting OuterSuites",
      "SuiteCompleted OuterSuites",
      "SuiteStarting Inner1Suite",
      "TestStarting inner one",
      "TestSucceeded inner one",
      "SuiteCompleted Inner1Suite",
      "SuiteStarting Inner2Suite",
      "TestStarting inner two",
      "TestSucceeded inner two",
      "SuiteCompleted Inner2Suite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "OuterSuites", class "samples.OuterSuites"
        suite "Inner1Suite", class "samples.Inner1Suite"
          test "inner one", label "- inner one"
        suite "Inner2Suite", class "samples.Inner2Suite"
          test "inner two", label "- inner two"
    suite "OuterSuites": SuiteStarting
    suite "Inner1Suite": SuiteStarting
    test "inner one": TestStarting
    test "inner one": TestSucceeded
    suite "Inner1Suite": SuiteCompleted
    suite "Inner2Suite": SuiteStarting
    test "inner two": TestStarting
    test "inner two": TestSucceeded
    suite "Inner2Suite": SuiteCompleted
    suite "OuterSuites": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "OuterSuites", "samples.OuterSuites"),
        TestEventLogLine.nestedSuite(2, 1, 0, "Inner1Suite", "samples.Inner1Suite"),
        TestEventLogLine.test(3, 2, 0, "inner one", "- inner one"),
        TestEventLogLine.nestedSuite(4, 1, 1, "Inner2Suite", "samples.Inner2Suite"),
        TestEventLogLine.test(5, 4, 0, "inner two", "- inner two")
      ) :: suiteStarting(1) :: suiteStarting(2) :: startingAndSucceeded(3)) :::
        (suiteCompleted(2) :: suiteStarting(4) :: startingAndSucceeded(5)) :::
        List(suiteCompleted(4), suiteCompleted(1))*
    )
  )

  private val forwardingSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting ForwardingSuite",
      "TestStarting a",
      "TestSucceeded a",
      "TestStarting b",
      "TestSucceeded b",
      "SuiteCompleted ForwardingSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "ForwardingSuite", class "samples.ForwardingSuite"
        test "a", label "- a"
        test "b", label "- b"
    suite "ForwardingSuite": SuiteStarting
    test "a": TestStarting
    test "a": TestSucceeded
    test "b": TestStarting
    test "b": TestSucceeded
    suite "ForwardingSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "ForwardingSuite", "samples.ForwardingSuite"),
        TestEventLogLine.test(2, 1, 0, "a", "- a"),
        TestEventLogLine.test(3, 1, 1, "b", "- b")
      ) :: suiteStarting(1) :: startingAndSucceeded(2)) ::: startingAndSucceeded(3) ::: List(suiteCompleted(1))*
    )
  )

  private val keepsItsRunsReporterSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting KeepsItsRunsReporterSuite",
      "TestStarting a",
      "TestSucceeded a",
      "SuiteCompleted KeepsItsRunsReporterSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "KeepsItsRunsReporterSuite", class "samples.KeepsItsRunsReporterSuite"
        test "a", label "- a"
    suite "KeepsItsRunsReporterSuite": SuiteStarting
    test "a": TestStarting
    test "a": TestSucceeded
    suite "KeepsItsRunsReporterSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "KeepsItsRunsReporterSuite", "samples.KeepsItsRunsReporterSuite"),
        TestEventLogLine.test(2, 1, 0, "a", "- a")
      ) :: suiteStarting(1) :: startingAndSucceeded(2)) ::: List(suiteCompleted(1))*
    )
  )

  private val keepsItsRunsReporterAsyncSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting KeepsItsRunsReporterAsyncSpec",
      "TestStarting t",
      "TestSucceeded t",
      "TestStarting u",
      "TestSucceeded u",
      "SuiteCompleted KeepsItsRunsReporterAsyncSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "KeepsItsRunsReporterAsyncSpec", class "samples.KeepsItsRunsReporterAsyncSpec"
        test "t", label "- t"
        test "u", label "- u"
    suite "KeepsItsRunsReporterAsyncSpec": SuiteStarting
    test "t": TestStarting
    test "t": TestSucceeded
    test "u": TestStarting
    test "u": TestSucceeded
    suite "KeepsItsRunsReporterAsyncSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "KeepsItsRunsReporterAsyncSpec", "samples.KeepsItsRunsReporterAsyncSpec"),
        TestEventLogLine.test(2, 1, 0, "t", "- t"),
        TestEventLogLine.test(3, 1, 1, "u", "- u")
      ),
      suiteStarting(1),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      suiteCompleted(1)
    )
  )

  private val filteredSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting FilteredSuite",
      "TestStarting fast",
      "TestSucceeded fast",
      "TestIgnored later",
      "SuiteCompleted FilteredSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "FilteredSuite", class "samples.FilteredSuite"
        test "fast", label "- fast"
        test "later", label "- later"
    suite "FilteredSuite": SuiteStarting
    test "fast": TestStarting
    test "fast": TestSucceeded
    test "later": TestIgnored
    suite "FilteredSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "FilteredSuite", "samples.FilteredSuite"),
        TestEventLogLine.test(2, 1, 0, "fast", "- fast"),
        TestEventLogLine.test(3, 1, 1, "later", "- later")
      ) :: suiteStarting(1) :: startingAndSucceeded(2)) ::: List(inFirstExecution(3, "TestIgnored"), suiteCompleted(1))*
    )
  )

  private val nestedSuiteAborted = "Exception encountered when invoking run on a nested suite - database unreachable"

  private val setupFailsSuites = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting SetupFailsSuites",
      "SuiteCompleted SetupFailsSuites",
      "SuiteStarting SetupFailsSpec",
      s"SuiteAborted SetupFailsSpec: $nestedSuiteAborted",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "SetupFailsSuites", class "samples.SetupFailsSuites"
        suite "SetupFailsSpec", class "samples.SetupFailsSpec"
          scope "A repo", label "A repo"
            test "read", label "- should read"
            test "write", label "- should write"
    suite "SetupFailsSuites": SuiteStarting
    suite "SetupFailsSpec": SuiteStarting
    suite "SetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
    suite "SetupFailsSuites": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "SetupFailsSuites", "samples.SetupFailsSuites"),
        TestEventLogLine.nestedSuite(2, 1, 0, "SetupFailsSpec", "samples.SetupFailsSpec"),
        TestEventLogLine.scope(3, 2, 0, "A repo", "A repo"),
        TestEventLogLine.test(4, 3, 0, "read", "- should read"),
        TestEventLogLine.test(5, 3, 1, "write", "- should write")
      ),
      suiteStarting(1),
      suiteStarting(2),
      TestEventLogLine.suiteEvent(
        2,
        1,
        "SuiteAborted",
        s""""message":"$nestedSuiteAborted",""" + thrown("java.lang.RuntimeException", "database unreachable")
      ),
      suiteCompleted(1)
    )
  )

  private val brokenCtorSpec = StatedRun(
    Vector("RunStarting", "SuiteStarting BrokenCtorSpec", "SuiteAborted BrokenCtorSpec: no config", "RunCompleted"),
    """
    run started
    run: RunStarting
    structure
      suite "BrokenCtorSpec", class "samples.BrokenCtorSpec"
    suite "BrokenCtorSpec": SuiteStarting
    suite "BrokenCtorSpec": SuiteAborted(throwable: java.lang.IllegalStateException "no config")
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(TestEventLogLine.suite(1, "BrokenCtorSpec", "samples.BrokenCtorSpec")),
      suiteStarting(1),
      TestEventLogLine.suiteEvent(1, 1, "SuiteAborted", """"message":"no config",""" + thrown("java.lang.IllegalStateException", "no config"))
    )
  )

  private val runAborted = "An exception or error caused a run to abort: Java heap space "

  private val abortSuite = StatedRun(
    Vector("RunStarting", "SuiteStarting AbortSuite", "TestStarting throws on pop", s"RunAborted: $runAborted"),
    """
    run started
    run: RunStarting(testCount: 1)
    structure
      suite "AbortSuite", class "samples.AbortSuite"
        test "throws on pop", label "- throws on pop"
    suite "AbortSuite": SuiteStarting
    test "throws on pop": TestStarting
    run: RunAborted(throwable: java.lang.OutOfMemoryError "Java heap space")
    run ended aborted
    """,
    List(
      runStarted,
      TestEventLogLine.runEvent("RunStarting", """"testCount":1,"configMap":{},"""),
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "AbortSuite", "samples.AbortSuite"),
        TestEventLogLine.test(2, 1, 0, "throws on pop", "- throws on pop")
      ),
      suiteStarting(1),
      inFirstExecution(2, "TestStarting"),
      TestEventLogLine.runEvent("RunAborted", s""""message":"$runAborted",""" + thrown("java.lang.OutOfMemoryError", "Java heap space")),
      TestEventLogLine.runEnded("aborted")
    )
  )

  private val twiceSuites = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting TwiceSuites",
      "SuiteCompleted TwiceSuites",
      "SuiteStarting Inner1Suite",
      "TestStarting inner one",
      "TestSucceeded inner one",
      "SuiteStarting Inner1Suite",
      "TestStarting inner one",
      "TestSucceeded inner one",
      "SuiteCompleted Inner1Suite",
      "SuiteCompleted Inner1Suite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "TwiceSuites", class "samples.TwiceSuites"
        suite "Inner1Suite", class "samples.Inner1Suite"
          test "inner one", label "- inner one"
        suite "Inner1Suite", class "samples.Inner1Suite"
          test "inner one", label "- inner one"
    suite "TwiceSuites": SuiteStarting
    suite "Inner1Suite" #1: SuiteStarting
    test "inner one" #1: TestStarting
    test "inner one" #1: TestSucceeded
    suite "Inner1Suite" #1: SuiteCompleted
    suite "Inner1Suite" #2: SuiteStarting
    test "inner one" #2: TestStarting
    test "inner one" #2: TestSucceeded
    suite "Inner1Suite" #2: SuiteCompleted
    suite "TwiceSuites": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "TwiceSuites", "samples.TwiceSuites"),
        TestEventLogLine.nestedSuite(2, 1, 0, "Inner1Suite", "samples.Inner1Suite"),
        TestEventLogLine.test(3, 2, 0, "inner one", "- inner one"),
        TestEventLogLine.nestedSuite(4, 1, 1, "Inner1Suite", "samples.Inner1Suite"),
        TestEventLogLine.test(5, 4, 0, "inner one", "- inner one")
      ) :: suiteStarting(1) :: suiteStarting(2) :: startingAndSucceeded(3)) :::
        (suiteCompleted(2) :: suiteStarting(4) :: startingAndSucceeded(5)) :::
        List(suiteCompleted(4), suiteCompleted(1))*
    )
  )

  private val retrySuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting RetrySuite",
      "TestStarting flaky",
      "TestFailed flaky: 1 was not greater than 1, recording InfoProvided trying",
      "TestStarting flaky",
      "TestSucceeded flaky, recording InfoProvided trying",
      "SuiteCompleted RetrySuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "RetrySuite", class "samples.RetrySuite"
        test "flaky", label "- flaky"
    suite "RetrySuite": SuiteStarting
    test "flaky": TestStarting
    test "flaky": InfoProvided(message: "trying")
    test "flaky": TestFailed
    test "flaky" execution 2: TestStarting
    test "flaky" execution 2: InfoProvided(message: "trying")
    test "flaky" execution 2: TestSucceeded
    suite "RetrySuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(TestEventLogLine.suite(1, "RetrySuite", "samples.RetrySuite"), TestEventLogLine.test(2, 1, 0, "flaky", "- flaky")),
      suiteStarting(1),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "InfoProvided", """"message":"trying","""),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "TestFailed", """"message":"1 was not greater than 1","""),
      TestEventLogLine.scopeOrTestEvent(2, 2, 1, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 1, "InfoProvided", """"message":"trying","""),
      TestEventLogLine.scopeOrTestEvent(2, 2, 1, "TestSucceeded"),
      suiteCompleted(1)
    )
  )

  private val scopeInfoFunSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting ScopeInfoFunSpec",
      "ScopeOpened A Stack",
      "TestStarting A Stack is empty",
      "TestSucceeded A Stack is empty",
      "InfoProvided an info in a scope",
      "ScopeOpened when full",
      "TestStarting A Stack when full pops",
      "TestSucceeded A Stack when full pops",
      "ScopeClosed when full",
      "ScopeClosed A Stack",
      "SuiteCompleted ScopeInfoFunSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "ScopeInfoFunSpec", class "samples.ScopeInfoFunSpec"
        scope "A Stack", label "A Stack"
          test "is empty", label "- is empty"
          scope "when full", label "when full"
            test "pops", label "- pops"
    suite "ScopeInfoFunSpec": SuiteStarting
    scope "A Stack": ScopeOpened
    test "is empty": TestStarting
    test "is empty": TestSucceeded
    scope "A Stack": InfoProvided(message: "an info in a scope")
    scope "when full": ScopeOpened
    test "pops": TestStarting
    test "pops": TestSucceeded
    scope "when full": ScopeClosed
    scope "A Stack": ScopeClosed
    suite "ScopeInfoFunSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "ScopeInfoFunSpec", "samples.ScopeInfoFunSpec"),
        TestEventLogLine.scope(2, 1, 0, "A Stack", "A Stack"),
        TestEventLogLine.test(3, 2, 0, "is empty", "- is empty"),
        TestEventLogLine.scope(4, 2, 1, "when full", "when full"),
        TestEventLogLine.test(5, 4, 0, "pops", "- pops")
      ),
      suiteStarting(1),
      inFirstExecution(2, "ScopeOpened"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(2, "InfoProvided", """"message":"an info in a scope","""),
      inFirstExecution(4, "ScopeOpened"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      inFirstExecution(4, "ScopeClosed"),
      inFirstExecution(2, "ScopeClosed"),
      suiteCompleted(1)
    )
  )

  private val bothStreamsSuite = StatedRun(
    Vector.empty,
    """
    run started
    run: RunStarting
    structure
      suite "BothStreamsSuite", class "samples.BothStreamsSuite"
        test "writes", label "- writes"
    suite "BothStreamsSuite": SuiteStarting
    test "writes": TestStarting
    test "writes": TestSucceeded
    suite "BothStreamsSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "BothStreamsSuite", "samples.BothStreamsSuite"),
        TestEventLogLine.test(2, 1, 0, "writes", "- writes")
      ) :: suiteStarting(1) :: startingAndSucceeded(2)) ::: List(suiteCompleted(1))*
    )
  )

  private val unregisteredSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting UnregisteredSuite",
      "TestStarting b",
      "TestSucceeded b",
      "TestIgnored a",
      "SuiteCompleted UnregisteredSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "UnregisteredSuite", class "samples.UnregisteredSuite"
        test "b", label "- b"
        test "a", label "- a"
    suite "UnregisteredSuite": SuiteStarting
    test "b": TestStarting
    test "b": TestSucceeded
    test "a": TestIgnored
    suite "UnregisteredSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      (TestEventLogLine.structure(
        TestEventLogLine.suite(1, "UnregisteredSuite", "samples.UnregisteredSuite"),
        TestEventLogLine.test(2, 1, 0, "b", "- b"),
        TestEventLogLine.test(3, 1, 1, "a", "- a")
      ) :: suiteStarting(1) :: startingAndSucceeded(2)) ::: List(inFirstExecution(3, "TestIgnored"), suiteCompleted(1))*
    )
  )

  private val announcingSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting AnnouncingSuite",
      "InfoProvided setting up",
      "TestStarting runs",
      "TestSucceeded runs",
      "InfoProvided declared after the test",
      "SuiteCompleted AnnouncingSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "AnnouncingSuite", class "samples.AnnouncingSuite"
        test "runs", label "- runs"
    suite "AnnouncingSuite": SuiteStarting
    suite "AnnouncingSuite": InfoProvided(message: "setting up")
    test "runs": TestStarting
    test "runs": TestSucceeded
    suite "AnnouncingSuite": InfoProvided(message: "declared after the test")
    suite "AnnouncingSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(TestEventLogLine.suite(1, "AnnouncingSuite", "samples.AnnouncingSuite"), TestEventLogLine.test(2, 1, 0, "runs", "- runs")),
      suiteStarting(1),
      TestEventLogLine.suiteEvent(1, 1, "InfoProvided", """"message":"setting up","""),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      TestEventLogLine.suiteEvent(1, 1, "InfoProvided", """"message":"declared after the test","""),
      suiteCompleted(1)
    )
  )

  private val stackAsyncFunSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting StackAsyncFunSpec",
      "ScopeOpened A Stack",
      "TestStarting A Stack is empty",
      "TestSucceeded A Stack is empty",
      "TestIgnored A Stack pops",
      "ScopeClosed A Stack",
      "SuiteCompleted StackAsyncFunSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "StackAsyncFunSpec", class "samples.StackAsyncFunSpec"
        scope "A Stack", label "A Stack"
          test "is empty", label "- is empty"
          test "pops", label "- pops"
    suite "StackAsyncFunSpec": SuiteStarting
    scope "A Stack": ScopeOpened
    test "is empty": TestStarting
    test "is empty": TestSucceeded
    test "pops": TestIgnored
    scope "A Stack": ScopeClosed
    suite "StackAsyncFunSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "StackAsyncFunSpec", "samples.StackAsyncFunSpec"),
        TestEventLogLine.scope(2, 1, 0, "A Stack", "A Stack"),
        TestEventLogLine.test(3, 2, 0, "is empty", "- is empty"),
        TestEventLogLine.test(4, 2, 1, "pops", "- pops")
      ),
      suiteStarting(1),
      inFirstExecution(2, "ScopeOpened"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(4, "TestIgnored"),
      inFirstExecution(2, "ScopeClosed"),
      suiteCompleted(1)
    )
  )

  private val twinScopesFunSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting TwinScopesFunSpec",
      "ScopeOpened s",
      "TestStarting s t",
      "TestSucceeded s t",
      "ScopeClosed s",
      "ScopeOpened s",
      "TestStarting s u",
      "TestSucceeded s u",
      "ScopeClosed s",
      "SuiteCompleted TwinScopesFunSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "TwinScopesFunSpec", class "samples.TwinScopesFunSpec"
        scope "s", label "s"
          test "t", label "- t"
        scope "s", label "s"
          test "u", label "- u"
    suite "TwinScopesFunSpec": SuiteStarting
    scope "s" #1: ScopeOpened
    test "t": TestStarting
    test "t": TestSucceeded
    scope "s" #1: ScopeClosed
    scope "s" #2: ScopeOpened
    test "u": TestStarting
    test "u": TestSucceeded
    scope "s" #2: ScopeClosed
    suite "TwinScopesFunSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "TwinScopesFunSpec", "samples.TwinScopesFunSpec"),
        TestEventLogLine.scope(2, 1, 0, "s", "s"),
        TestEventLogLine.test(3, 2, 0, "t", "- t"),
        TestEventLogLine.scope(4, 1, 1, "s", "s"),
        TestEventLogLine.test(5, 4, 0, "u", "- u")
      ),
      suiteStarting(1),
      inFirstExecution(2, "ScopeOpened"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(2, "ScopeClosed"),
      inFirstExecution(4, "ScopeOpened"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      inFirstExecution(4, "ScopeClosed"),
      suiteCompleted(1)
    )
  )

  private val runsTwiceSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting RunsTwiceSuite",
      "TestStarting t",
      "TestSucceeded t",
      "TestStarting t",
      "TestSucceeded t",
      "SuiteCompleted RunsTwiceSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "RunsTwiceSuite", class "samples.RunsTwiceSuite"
        test "t", label "- t"
    suite "RunsTwiceSuite": SuiteStarting
    test "t": TestStarting
    test "t": TestSucceeded
    test "t" execution 2, parent execution 2: TestStarting
    test "t" execution 2, parent execution 2: TestSucceeded
    suite "RunsTwiceSuite" execution 2: SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(TestEventLogLine.suite(1, "RunsTwiceSuite", "samples.RunsTwiceSuite"), TestEventLogLine.test(2, 1, 0, "t", "- t")),
      suiteStarting(1),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "TestSucceeded"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 2, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 2, "TestSucceeded"),
      TestEventLogLine.suiteEvent(1, 2, "SuiteCompleted")
    )
  )

  private val twinScopesAsyncFunSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting TwinScopesAsyncFunSpec",
      "ScopeOpened s",
      "TestStarting s t",
      "TestSucceeded s t",
      "ScopeClosed s",
      "ScopeOpened s",
      "TestStarting s u",
      "TestSucceeded s u",
      "ScopeClosed s",
      "SuiteCompleted TwinScopesAsyncFunSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "TwinScopesAsyncFunSpec", class "samples.TwinScopesAsyncFunSpec"
        scope "s", label "s"
          test "t", label "- t"
        scope "s", label "s"
          test "u", label "- u"
    suite "TwinScopesAsyncFunSpec": SuiteStarting
    scope "s" #1: ScopeOpened
    test "t": TestStarting
    test "t": TestSucceeded
    scope "s" #1: ScopeClosed
    scope "s" #2: ScopeOpened
    test "u": TestStarting
    test "u": TestSucceeded
    scope "s" #2: ScopeClosed
    suite "TwinScopesAsyncFunSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "TwinScopesAsyncFunSpec", "samples.TwinScopesAsyncFunSpec"),
        TestEventLogLine.scope(2, 1, 0, "s", "s"),
        TestEventLogLine.test(3, 2, 0, "t", "- t"),
        TestEventLogLine.scope(4, 1, 1, "s", "s"),
        TestEventLogLine.test(5, 4, 0, "u", "- u")
      ),
      suiteStarting(1),
      inFirstExecution(2, "ScopeOpened"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(2, "ScopeClosed"),
      inFirstExecution(4, "ScopeOpened"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      inFirstExecution(4, "ScopeClosed"),
      suiteCompleted(1)
    )
  )

  private val runsTwiceAsyncSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting RunsTwiceAsyncSpec",
      "TestStarting t",
      "TestSucceeded t",
      "TestStarting t",
      "TestSucceeded t",
      "SuiteCompleted RunsTwiceAsyncSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "RunsTwiceAsyncSpec", class "samples.RunsTwiceAsyncSpec"
        test "t", label "- t"
    suite "RunsTwiceAsyncSpec": SuiteStarting
    test "t": TestStarting
    test "t": TestSucceeded
    test "t" execution 2, parent execution 2: TestStarting
    test "t" execution 2, parent execution 2: TestSucceeded
    suite "RunsTwiceAsyncSpec" execution 2: SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "RunsTwiceAsyncSpec", "samples.RunsTwiceAsyncSpec"),
        TestEventLogLine.test(2, 1, 0, "t", "- t")
      ),
      suiteStarting(1),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "TestSucceeded"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 2, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 2, "TestSucceeded"),
      TestEventLogLine.suiteEvent(1, 2, "SuiteCompleted")
    )
  )

  private val stackPathFunSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting StackPathFunSpec",
      "ScopeOpened A Stack",
      "TestStarting A Stack is empty",
      "TestSucceeded A Stack is empty",
      "ScopeOpened when full",
      "TestStarting A Stack when full pops",
      "TestSucceeded A Stack when full pops",
      "ScopeClosed when full",
      "ScopeClosed A Stack",
      "SuiteCompleted StackPathFunSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "StackPathFunSpec", class "samples.StackPathFunSpec"
        scope "A Stack", label "A Stack"
          test "is empty", label "- is empty"
          scope "when full", label "when full"
            test "pops", label "- pops"
    suite "StackPathFunSpec": SuiteStarting
    scope "A Stack": ScopeOpened
    test "is empty": TestStarting
    test "is empty": TestSucceeded
    scope "when full": ScopeOpened
    test "pops": TestStarting
    test "pops": TestSucceeded
    scope "when full": ScopeClosed
    scope "A Stack": ScopeClosed
    suite "StackPathFunSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "StackPathFunSpec", "samples.StackPathFunSpec"),
        TestEventLogLine.scope(2, 1, 0, "A Stack", "A Stack"),
        TestEventLogLine.test(3, 2, 0, "is empty", "- is empty"),
        TestEventLogLine.scope(4, 2, 1, "when full", "when full"),
        TestEventLogLine.test(5, 4, 0, "pops", "- pops")
      ),
      suiteStarting(1),
      inFirstExecution(2, "ScopeOpened"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(4, "ScopeOpened"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      inFirstExecution(4, "ScopeClosed"),
      inFirstExecution(2, "ScopeClosed"),
      suiteCompleted(1)
    )
  )

  private val markupDocSpec = StatedRun(
    Vector("RunStarting", "SuiteStarting MarkupDocSpec", "MarkupProvided A stack pops", "SuiteCompleted MarkupDocSpec", "RunCompleted"),
    """
    run started
    run: RunStarting
    structure
      suite "MarkupDocSpec", class "org.scalatest.samples.MarkupDocSpec"
    suite "MarkupDocSpec": SuiteStarting
    suite "MarkupDocSpec": MarkupProvided(text: "A stack pops")
    suite "MarkupDocSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      TestEventLogLine.structure(TestEventLogLine.suite(1, "MarkupDocSpec", "org.scalatest.samples.MarkupDocSpec")),
      suiteStarting(1),
      TestEventLogLine.suiteEvent(1, 1, "MarkupProvided", """"text":"A stack pops","""),
      suiteCompleted(1)
    )
  )

  private val discoveredWithAddSuiteFirst = StatedRun(
    Vector(
      "DiscoveryStarting",
      "DiscoveryCompleted",
      "RunStarting",
      "SuiteStarting DiscoverySuite",
      "SuiteStarting AddSuite",
      "TestStarting adds",
      "TestSucceeded adds",
      "SuiteCompleted AddSuite",
      "SuiteStarting SqrtPropSpec",
      "TestStarting sqrt of a square is the root",
      "TestSucceeded sqrt of a square is the root",
      "SuiteCompleted SqrtPropSpec",
      "SuiteCompleted DiscoverySuite",
      "RunCompleted"
    ),
    """
    run started
    run: DiscoveryStarting
    run: DiscoveryCompleted
    run: RunStarting(testCount: 2)
    run: SuiteStarting
    structure
      suite "AddSuite", class "samples.discovered.AddSuite"
        test "adds", label "- adds"
    suite "AddSuite": SuiteStarting
    test "adds": TestStarting
    test "adds": TestSucceeded
    suite "AddSuite": SuiteCompleted
    structure
      suite "SqrtPropSpec", class "samples.discovered.SqrtPropSpec"
        test "sqrt of a square is the root", label "- sqrt of a square is the root"
    suite "SqrtPropSpec": SuiteStarting
    test "sqrt of a square is the root": TestStarting
    test "sqrt of a square is the root": TestSucceeded
    suite "SqrtPropSpec": SuiteCompleted
    run: SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    List(
      runStarted,
      TestEventLogLine.runEvent("DiscoveryStarting"),
      TestEventLogLine.runEvent("DiscoveryCompleted"),
      TestEventLogLine.runEvent("RunStarting", """"testCount":2,"configMap":{},"""),
      TestEventLogLine.runEvent("SuiteStarting"),
      TestEventLogLine.structure(TestEventLogLine.suite(1, "AddSuite", "samples.discovered.AddSuite"), TestEventLogLine.test(2, 1, 0, "adds", "- adds")),
      suiteStarting(1),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      suiteCompleted(1),
      TestEventLogLine.structure(
        TestEventLogLine.suite(3, "SqrtPropSpec", "samples.discovered.SqrtPropSpec"),
        TestEventLogLine.test(4, 3, 0, "sqrt of a square is the root", "- sqrt of a square is the root")
      ),
      suiteStarting(3),
      inFirstExecution(4, "TestStarting"),
      inFirstExecution(4, "TestSucceeded"),
      suiteCompleted(3),
      TestEventLogLine.runEvent("SuiteCompleted"),
      runCompleted,
      runEndedCompleted
    )
  )

  private val discoveredWithSqrtPropSpecFirst = StatedRun(
    Vector(
      "DiscoveryStarting",
      "DiscoveryCompleted",
      "RunStarting",
      "SuiteStarting DiscoverySuite",
      "SuiteStarting SqrtPropSpec",
      "TestStarting sqrt of a square is the root",
      "TestSucceeded sqrt of a square is the root",
      "SuiteCompleted SqrtPropSpec",
      "SuiteStarting AddSuite",
      "TestStarting adds",
      "TestSucceeded adds",
      "SuiteCompleted AddSuite",
      "SuiteCompleted DiscoverySuite",
      "RunCompleted"
    ),
    """
    run started
    run: DiscoveryStarting
    run: DiscoveryCompleted
    run: RunStarting(testCount: 2)
    run: SuiteStarting
    structure
      suite "SqrtPropSpec", class "samples.discovered.SqrtPropSpec"
        test "sqrt of a square is the root", label "- sqrt of a square is the root"
    suite "SqrtPropSpec": SuiteStarting
    test "sqrt of a square is the root": TestStarting
    test "sqrt of a square is the root": TestSucceeded
    suite "SqrtPropSpec": SuiteCompleted
    structure
      suite "AddSuite", class "samples.discovered.AddSuite"
        test "adds", label "- adds"
    suite "AddSuite": SuiteStarting
    test "adds": TestStarting
    test "adds": TestSucceeded
    suite "AddSuite": SuiteCompleted
    run: SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    List(
      runStarted,
      TestEventLogLine.runEvent("DiscoveryStarting"),
      TestEventLogLine.runEvent("DiscoveryCompleted"),
      TestEventLogLine.runEvent("RunStarting", """"testCount":2,"configMap":{},"""),
      TestEventLogLine.runEvent("SuiteStarting"),
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "SqrtPropSpec", "samples.discovered.SqrtPropSpec"),
        TestEventLogLine.test(2, 1, 0, "sqrt of a square is the root", "- sqrt of a square is the root")
      ),
      suiteStarting(1),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      suiteCompleted(1),
      TestEventLogLine.structure(TestEventLogLine.suite(3, "AddSuite", "samples.discovered.AddSuite"), TestEventLogLine.test(4, 3, 0, "adds", "- adds")),
      suiteStarting(3),
      inFirstExecution(4, "TestStarting"),
      inFirstExecution(4, "TestSucceeded"),
      suiteCompleted(3),
      TestEventLogLine.runEvent("SuiteCompleted"),
      runCompleted,
      runEndedCompleted
    )
  )

  // Discovery gives the runpath's suites in no stated order.
  private val runsOfDiscoveredSuites = List(discoveredWithAddSuiteFirst, discoveredWithSqrtPropSpecFirst)

  private val cannotLoadBrokenCtorSpec =
    "Unable to load a Suite class samples.discovered.BrokenCtorSpec that was discovered in the runpath: no config"

  private val brokenCtorSpecDiscovered = StatedRun(
    Vector(
      "DiscoveryStarting",
      "DiscoveryCompleted",
      "RunStarting",
      "SuiteStarting DiscoverySuite",
      "SuiteStarting BrokenCtorSpec",
      s"SuiteAborted BrokenCtorSpec: Exception encountered when invoking run on a nested suite - $cannotLoadBrokenCtorSpec",
      "SuiteCompleted DiscoverySuite",
      "RunCompleted"
    ),
    """
    run started
    run: DiscoveryStarting
    run: DiscoveryCompleted
    run: RunStarting(testCount: 0)
    run: SuiteStarting
    structure
      suite "BrokenCtorSpec", class "samples.discovered.BrokenCtorSpec"
    suite "BrokenCtorSpec": SuiteStarting
    suite "BrokenCtorSpec": SuiteAborted(throwable: java.lang.RuntimeException caused by java.lang.IllegalStateException "no config")
    run: SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    List(
      runStarted,
      TestEventLogLine.runEvent("DiscoveryStarting"),
      TestEventLogLine.runEvent("DiscoveryCompleted"),
      TestEventLogLine.runEvent("RunStarting", """"testCount":0,"configMap":{},"""),
      TestEventLogLine.runEvent("SuiteStarting"),
      TestEventLogLine.structure(TestEventLogLine.suite(1, "BrokenCtorSpec", "samples.discovered.BrokenCtorSpec")),
      suiteStarting(1),
      TestEventLogLine.suiteEvent(
        1,
        1,
        "SuiteAborted",
        s""""message":"Exception encountered when invoking run on a nested suite - $cannotLoadBrokenCtorSpec",""" +
          s""""throwable":{"id":1,"className":"java.lang.RuntimeException","message":"$cannotLoadBrokenCtorSpec",""" +
          """"cause":{"id":2,"className":"java.lang.IllegalStateException","message":"no config"}},"""
      ),
      TestEventLogLine.runEvent("SuiteCompleted"),
      runCompleted,
      runEndedCompleted
    )
  )

  private def classFilesOf(simpleNames: String*): List[String] = simpleNames.toList.map(name => s"samples/discovered/$name.class")

  private def isAlert(event: TestEventLogEvent): Boolean = {
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.AlertProvided) => true
      case _ => false
    }
  }

  private val slowSuiteWithoutItsAlerts = StatedRun(
    Vector.empty,
    """
    run started
    run: RunStarting
    structure
      suite "SlowSuite", class "samples.SlowSuite"
        test "sleeps", label "- sleeps"
    suite "SlowSuite": SuiteStarting
    test "sleeps": TestStarting
    test "sleeps": TestSucceeded
    suite "SlowSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    Nil
  )

  private def runnerRunOf(testCount: Int, lines: String*): List[String] =
    runStarted :: TestEventLogLine.runEvent("RunStarting", s""""testCount":$testCount,"configMap":{},""") :: lines.toList ::: List(runCompleted, runEndedCompleted)

  /** One event, in the notation and as the format writes it. */
  private final case class StatedEvent(inNotation: String, inFormat: String)

  private def startingAndSucceededOf(test: String, node: Long): List[StatedEvent] = {
    List("TestStarting", "TestSucceeded").map(eventName => StatedEvent(s"""test "$test": $eventName""", inFirstExecution(node, eventName)))
  }

  // Every order of the sequences' events that keeps each sequence's own events in order.
  // Recurses once for each event.
  private def interleavings(sequences: List[List[StatedEvent]]): List[List[StatedEvent]] = {
    sequences.filter(_.nonEmpty) match {
      case Nil => List(Nil)
      case remaining =>
        remaining.indices.toList.flatMap { index =>
          interleavings(remaining.updated(index, remaining(index).tail)).map(remaining(index).head :: _)
        }
    }
  }

  private def notationOf(events: List[StatedEvent]): String = events.map(_.inNotation).mkString("\n")

  private val parFunSuiteRuns = interleavings(List(startingAndSucceededOf("a", 2), startingAndSucceededOf("b", 3))).map { order =>
    StatedRun(
      Vector(
        "RunStarting",
        "SuiteStarting ParFunSuite",
        "TestStarting a",
        "TestSucceeded a",
        "TestStarting b",
        "TestSucceeded b",
        "SuiteCompleted ParFunSuite",
        "RunCompleted"
      ),
      s"""
      |run started
      |run: RunStarting(testCount: 2)
      |structure
      |  suite "ParFunSuite", class "samples.ParFunSuite"
      |    test "a", label "- a"
      |    test "b", label "- b"
      |suite "ParFunSuite": SuiteStarting
      |${notationOf(order)}
      |suite "ParFunSuite": SuiteCompleted
      |run: RunCompleted
      |run ended completed
      |""".stripMargin,
      runnerRunOf(
        2,
        (TestEventLogLine.structure(
          TestEventLogLine.suite(1, "ParFunSuite", "samples.ParFunSuite"),
          TestEventLogLine.test(2, 1, 0, "a", "- a"),
          TestEventLogLine.test(3, 1, 1, "b", "- b")
        ) :: suiteStarting(1) :: order.map(_.inFormat)) ::: List(suiteCompleted(1))*
      )
    )
  }

  // Each test runs in an instance of its own, one after another, in any order.
  private val shuffledSuiteRuns = List("a" -> 2L, "b" -> 3L, "c" -> 4L).permutations.toList.map { tests =>
    val order = tests.flatMap((test, node) => startingAndSucceededOf(test, node))
    StatedRun(
      Vector(
        "RunStarting",
        "SuiteStarting ShuffledSuite",
        "TestStarting a",
        "TestSucceeded a",
        "TestStarting b",
        "TestSucceeded b",
        "TestStarting c",
        "TestSucceeded c",
        "SuiteCompleted ShuffledSuite",
        "RunCompleted"
      ),
      s"""
      |run started
      |run: RunStarting
      |structure
      |  suite "ShuffledSuite", class "samples.ShuffledSuite"
      |    test "a", label "- a"
      |    test "b", label "- b"
      |    test "c", label "- c"
      |suite "ShuffledSuite": SuiteStarting
      |${notationOf(order)}
      |suite "ShuffledSuite": SuiteCompleted
      |run: RunCompleted
      |run ended completed
      |""".stripMargin,
      frameworkRunOf(
        (TestEventLogLine.structure(
          TestEventLogLine.suite(1, "ShuffledSuite", "samples.ShuffledSuite"),
          TestEventLogLine.test(2, 1, 0, "a", "- a"),
          TestEventLogLine.test(3, 1, 1, "b", "- b"),
          TestEventLogLine.test(4, 1, 2, "c", "- c")
        ) :: suiteStarting(1) :: order.map(_.inFormat)) ::: List(suiteCompleted(1))*
      )
    )
  }

  private val abortedRunningOneTest = "Exception encountered when invoking run on a nested suite - no fixture"

  private val parAbortSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting ParAbortSuite",
      s"SuiteAborted DistributedTestRunnerSuite: $abortedRunningOneTest",
      "SuiteCompleted ParAbortSuite",
      "RunCompleted"
    ),
    s"""
    run started
    run: RunStarting(testCount: 1)
    structure
      suite "ParAbortSuite", class "samples.ParAbortSuite"
        test "a", label "- a"
    suite "ParAbortSuite": SuiteStarting
    test "a": SuiteAborted(message: "$abortedRunningOneTest", throwable: java.lang.RuntimeException "no fixture")
    suite "ParAbortSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    runnerRunOf(
      1,
      TestEventLogLine.structure(TestEventLogLine.suite(1, "ParAbortSuite", "samples.ParAbortSuite"), TestEventLogLine.test(2, 1, 0, "a", "- a")),
      suiteStarting(1),
      inFirstExecution(2, "SuiteAborted", s""""message":"$abortedRunningOneTest",""" + thrown("java.lang.RuntimeException", "no fixture")),
      suiteCompleted(1)
    )
  )

  /**
   * Registers two scenarios: a run whose tests hand off their events in no fixed order, and the decoder reading the
   * event log of each order.
   *
   * @param runs the run in each order its tests may hand off their events. Every one tells its reporters the same.
   */
  private def eventScenariosInAnyOrder(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    runs: List[StatedRun],
    launch: Launch,
    arguments: List[String]
  ): Unit = {
    val reportedInEveryOrder = runs.map(_.reported).distinct
    Scenario(scenario) {
      thisProcess
      reportedInEveryOrder should have size 1
      Given(suite)
      When(s"${launch.named} runs it without the runner argument")
      val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(launch, suiteClassName, arguments))

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reportedWithoutTheArgument shouldBe reportedInEveryOrder.head

      TemporaryEventLog.in { eventLogFile =>
        When(s"${launch.named} runs it with ${("the runner argument" :: arguments).mkString(" and ")}")
        val reportedWithTheArgument = reported(eventsReportedWhenLaunched(launch, suiteClassName, arguments ++ runnerArgument(eventLogFile)))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the run's reporters are told the same")
        reportedWithTheArgument shouldBe reportedInEveryOrder.head

        And(s"the event log is $eventLogHolds, in one of the orders the tests may run in")
        val runInOrderWritten = runs.filter(run => StatedLines.mismatchWhenIs(lines, run.eventLogInFormat).isEmpty)
        withClue(s"The event log is:\n${lines.mkString("\n")}\n") {
          runInOrderWritten should have size 1
        }
        EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(runInOrderWritten.head.eventLogInNotation)) shouldBe None
      }
    }

    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log, in each order the tests may run in")
      val mismatches = runs.flatMap(run => EventLogComparison.mismatchWhenIs(decodedOrFail(run.eventLogInFormat), built(run.eventLogInNotation)))

      Then(s"it yields $eventLogHolds")
      mismatches shouldBe Nil
    }
  }

  private def slowSuiteAlertedBy(alert: String): List[String] = {
    List(
      TestEventLogLine.structure(TestEventLogLine.suite(1, "SlowSuite", "samples.SlowSuite"), TestEventLogLine.test(2, 1, 0, "sleeps", "- sleeps")),
      inFirstExecution(2, "TestStarting"),
      alert,
      inFirstExecution(2, "TestSucceeded")
    )
  }

  private val slowSuiteAlerted = StatedRun(
    Vector.empty,
    """
    structure
      suite "SlowSuite", class "samples.SlowSuite"
        test "sleeps", label "- sleeps"
    test "sleeps": TestStarting
    run: AlertProvided
    test "sleeps": TestSucceeded
    """,
    slowSuiteAlertedBy(
      TestEventLogLine.runEvent("AlertProvided", """"message":"Test still running after 1 second: suite name: SlowSuite, test name: sleeps.",""")
    )
  )

  // The time a slowpoke alert's message gives is the run's own.
  private val slowSuiteAlertedWithAnyMessage = slowSuiteAlertedBy(TestEventLogLine.runEvent("AlertProvided"))

  Feature("Each suite's structure") {

    Scenario("Suites Runner discovers are top-level") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        Given("a runpath holding two suites, an AnyFunSuite whose one test succeeds and an AnyPropSpec whose one property succeeds")
        withRunpathOf(eventLogFile.resolveSibling("runpath"), classFilesOf("AddSuite", "SqrtPropSpec")) { runpath =>
          When("Runner.run discovers and runs the runpath's suites without the runner argument, and again with it")
          val arguments = List("-R", runpath, "-C", classOf[RunRecordingReporter].getName)
          val reportedWithoutTheArgument = reported(RunRecordingReporter.eventsReportedIn(runnerRun(arguments)))
          val reportedWithTheArgument = reported(RunRecordingReporter.eventsReportedIn(runnerRun(arguments ++ runnerArgument(eventLogFile))))
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then("the run's reporters are told what ScalaTest 3.2.20 tells them, both times")
          runsOfDiscoveredSuites.map(_.reported) should contain(reportedWithoutTheArgument)
          reportedWithTheArgument shouldBe reportedWithoutTheArgument

          And("each suite has its own structure, whose root is that suite, before its events, which are assigned to it and its test")
          And("no structure holds the discovery suite, whose SuiteStarting and SuiteCompleted are assigned to the run")
          val runInOrderDiscovered = runsOfDiscoveredSuites.filter(_.reported == reportedWithTheArgument)
          runInOrderDiscovered.map(run => StatedLines.mismatchWhenIs(lines, run.eventLogInFormat)) shouldBe List(None)
          runInOrderDiscovered.map(run => EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(run.eventLogInNotation))) shouldBe List(None)
        }
      }
    }

    Scenario("The decoder reads the event log of: Suites Runner discovers are top-level") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(discoveredWithAddSuiteFirst.eventLogInFormat)

      Then("it yields each suite's own structure and events, and the discovery suite's events assigned to the run")
      EventLogComparison.mismatchWhenIs(events, built(discoveredWithAddSuiteFirst.eventLogInNotation)) shouldBe None
    }

    eventScenarios(
      "A suite whose tests run",
      "an AnyFunSpec declaring scope A Stack, holding scope when empty, holding tests is empty and pops, each succeeding",
      "samples.StackFunSpec",
      "the suite's structure, then each event of the suite, its scopes and its tests assigned to its own node, between the run's events",
      stackFunSpec
    )

    eventScenarios(
      "A suite whose beforeAll throws",
      "an AnyWordSpec declaring scope A repo, holding tests read and write, whose beforeAll throws",
      "samples.SetupFailsSpec",
      "the suite's structure, then the suite's SuiteStarting and SuiteAborted assigned to the suite, and no event of its scope or tests",
      setupFailsSpec
    )

    eventScenarios(
      "Nested suites sit under the suite that nests them",
      "a suite with no tests that nests two suites, each with one test that succeeds",
      "samples.OuterSuites",
      "one structure holding all three suites, then each suite's events assigned to that suite and each test's to that test",
      outerSuites,
      nestedSuiteIds = List("samples.Inner1Suite", "samples.Inner2Suite")
    )

    eventScenarios(
      "Sibling scopes of one text",
      "an AnyFunSpec declaring scope s twice, the first holding test t and the second test u",
      "samples.TwinScopesFunSpec",
      "the suite's structure, then the events of each scope and its test assigned to that scope and test, in declared order",
      twinScopesFunSpec
    )

    eventScenarios(
      "Sibling scopes of one text in an async suite",
      "an AsyncFunSpec declaring scope s twice, the first holding test t and the second test u",
      "samples.TwinScopesAsyncFunSpec",
      "the suite's structure, then the events of each scope and its test assigned to that scope and test, in declared order",
      twinScopesAsyncFunSpec
    )

    eventScenarios(
      "A path suite whose tests run",
      "a PathAnyFunSpec declaring scope A Stack, holding test is empty and scope when full, which holds test pops, each test succeeding",
      "samples.StackPathFunSpec",
      "the suite's structure, then each event of the suite, its scopes and its tests assigned to its own node, each in a first execution",
      stackPathFunSpec
    )

    eventScenarios(
      "A suite with no registration tree",
      "a Suite that overrides testNames as b and a, tags a to be ignored, and overrides runTest to report TestStarting and TestSucceeded itself",
      "samples.UnregisteredSuite",
      "the suite's structure and events, with the events its runTest reports for b assigned to b, and a TestIgnored assigned to a",
      unregisteredSuite
    )

    eventScenarios(
      "An async suite whose tests run one after another",
      "an AsyncFunSpec declaring scope A Stack, holding test is empty, which succeeds, and test pops, which is ignored",
      "samples.StackAsyncFunSpec",
      "the suite's structure, then each event of the suite, its scope and its tests assigned to its own node",
      stackAsyncFunSpec
    )

    eventScenarios(
      "A suite wraps its reporter in its own",
      "an AnyFunSuite whose tests a and b succeed, and whose run passes on its reporter wrapped in one that forwards each event",
      "samples.ForwardingSuite",
      "the suite's structure and events, with one TestStarting and one TestSucceeded assigned to each of a and b",
      forwardingSuite
    )

    eventScenarios(
      "A suite runs its tests with the reporter its run was given",
      "an AnyFunSuite whose runTest passes on, in place of the reporter it is given, the reporter the suite's run was given",
      "samples.KeepsItsRunsReporterSuite",
      "the suite's structure and events, with the test's TestStarting and TestSucceeded assigned to the test",
      keepsItsRunsReporterSuite
    )

    eventScenarios(
      "An async suite runs its tests with the reporter its run was given",
      "an AsyncFunSpec with tests t and u, whose runTest passes on, in place of the reporter it is given, the reporter the suite's run was given",
      "samples.KeepsItsRunsReporterAsyncSpec",
      "the suite's structure and events, with each test's TestStarting and TestSucceeded assigned to that test",
      keepsItsRunsReporterAsyncSpec
    )

    eventScenarios(
      "A suite with a tag excluded and a test ignored",
      "an AnyFunSuite declaring tests fast, slow and later, of which slow is tagged samples.Slow and later is ignored",
      "samples.FilteredSuite",
      "the suite's structure and events, with a TestIgnored assigned to later and no event assigned to slow",
      filteredSuite,
      arguments = List("-l", "samples.Slow")
    )

    eventScenariosInAnyOrder(
      "Tests run in parallel by Runner",
      "an AnyFunSuite mixing in ParallelTestExecution, whose tests a and b succeed",
      "samples.ParFunSuite",
      "one structure holding the suite and no DistributedTestRunnerSuite, then the suite's events, " +
        "with one TestStarting and one TestSucceeded assigned to each of a and b",
      parFunSuiteRuns,
      Launch.ByRunner,
      List("-P2")
    )

    eventScenariosInAnyOrder(
      "The events of tests in random order are assigned to their own nodes",
      "an AnyFunSuite mixing in RandomTestOrder, registering tests a, b and c in that order, each succeeding",
      "samples.ShuffledSuite",
      "the suite's structure, then its events, with one TestStarting and one TestSucceeded assigned to each of a, b and c",
      shuffledSuiteRuns,
      Launch.ByFramework,
      Nil
    )

    eventScenarios(
      "The suite running one test in parallel throws",
      "an AnyFunSuite mixing in ParallelTestExecution, whose one test is a, and whose run throws when given a test name",
      "samples.ParAbortSuite",
      "the suite's structure and events, with the SuiteAborted of the suite that ran a assigned to a",
      parAbortSuite,
      launch = Launch.ByRunner,
      arguments = List("-P2")
    )
  }

  Feature("A suite's class name") {

    eventScenarios(
      "A nested suite's beforeAll throws",
      "a suite with no tests nesting an AnyWordSpec whose beforeAll throws",
      "samples.SetupFailsSuites",
      "a structure holding the nested suite with its class name, and later that suite's SuiteAborted assigned to it",
      setupFailsSuites,
      nestedSuiteIds = List("samples.SetupFailsSpec")
    )

    eventScenarios(
      "A suite's constructor throws",
      "a suite class whose constructor throws",
      "samples.BrokenCtorSpec",
      "a structure holding the suite with the class that failed to construct, and later the suite's SuiteAborted assigned to it",
      brokenCtorSpec
    )
  }

  Feature("A suite's class name, under discovery") {

    Scenario("A suite's constructor throws under discovery") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        Given("a runpath holding one suite class, whose constructor throws")
        withRunpathOf(eventLogFile.resolveSibling("runpath"), classFilesOf("BrokenCtorSpec", "BrokenCtorSpec$")) { runpath =>
          When("Runner.run discovers and runs the runpath's suites without the runner argument, and again with it")
          val arguments = List("-R", runpath, "-C", classOf[RunRecordingReporter].getName)
          val whereTheConstructorThrows = Some(samples.discovered.BrokenCtorSpec.threadGroupItsConstructorThrowsIn)
          val reportedWithoutTheArgument =
            reported(RunRecordingReporter.eventsReportedIn(runnerRun(arguments, whereTheConstructorThrows)))
          val reportedWithTheArgument =
            reported(RunRecordingReporter.eventsReportedIn(runnerRun(arguments ++ runnerArgument(eventLogFile), whereTheConstructorThrows)))
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then("the run's reporters are told what ScalaTest 3.2.20 tells them, both times")
          reportedWithoutTheArgument shouldBe brokenCtorSpecDiscovered.reported
          reportedWithTheArgument shouldBe brokenCtorSpecDiscovered.reported

          And("the event log holds a structure holding the suite with the class that failed to construct, and later the suite's SuiteAborted assigned to it")
          StatedLines.mismatchWhenIs(lines, brokenCtorSpecDiscovered.eventLogInFormat) shouldBe None
          EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(brokenCtorSpecDiscovered.eventLogInNotation)) shouldBe None
        }
      }
    }

    Scenario("The decoder reads the event log of: A suite's constructor throws under discovery") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(brokenCtorSpecDiscovered.eventLogInFormat)

      Then("it yields the suite's structure, and its SuiteAborted assigned to it, with the throwable and its cause")
      EventLogComparison.mismatchWhenIs(events, built(brokenCtorSpecDiscovered.eventLogInNotation)) shouldBe None
    }
  }

  Feature("The run in its test event log") {

    Scenario("A slowpoke alert") {
      thisProcess
      Given("an AnyFunSuite whose one test sleeps 3 seconds, then succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("Framework runs it with the runner argument, -W 1 1 and a recording reporter")
        val events = eventsReportedWhenLaunched(Launch.ByFramework, "samples.SlowSuite", List("-W", "1", "1") ++ runnerArgument(eventLogFile))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("each line of the event log decodes to one event")
        val eventLog = decodedOrFail(lines)

        And("the event log holds the test's TestStarting, an AlertProvided assigned to the run, and the test's TestSucceeded, in that order")
        StatedLines.mismatchWhenHoldsInOrder(lines, slowSuiteAlertedWithAnyMessage) shouldBe None
        EventLogComparison.mismatchWhenHoldsInOrder(eventLog, built(slowSuiteAlerted.eventLogInNotation)) shouldBe None

        And("it holds an AlertProvided assigned to the run for each one the reporter receives while the test runs, in the same order and with the same message")
        // An alert the timer sends as the run ends can reach the reporter after the event log has closed. One the
        // reporter receives before the test's TestSucceeded was handed off before the test ended.
        val alertsReported = events.takeWhile(!_.isInstanceOf[TestSucceeded]).collect { case alert: AlertProvided => alert.message }
        val alertsInEventLog = eventLog.collect { case TestEventLogEvent.ScalaTestEvent(execution, alert: DecodedEvent.AlertProvided) =>
          execution.map(_.node) -> alert.message
        }
        alertsReported.head should startWith("Test still running after ")
        alertsReported.head should endWith(": suite name: SlowSuite, test name: sleeps.")
        alertsInEventLog.take(alertsReported.size) shouldBe alertsReported.map(message => None -> message)

        And("no AlertProvided is assigned to the test or its suite")
        alertsInEventLog.filter((node, _) => node.isDefined) shouldBe Vector.empty

        And("its other events are the run's, and those of the suite and its test assigned to their own nodes")
        EventLogComparison.mismatchWhenIs(eventLog.filterNot(isAlert), built(slowSuiteWithoutItsAlerts.eventLogInNotation)) shouldBe None
      }
    }

    Scenario("The decoder reads the event log of: A slowpoke alert") {
      When("the decoder is given the lines of the suite's structure, its test's TestStarting, a slowpoke alert and the test's TestSucceeded")
      val events = decodedOrFail(slowSuiteAlerted.eventLogInFormat)

      Then("it yields the alert assigned to the run, between the test's events")
      EventLogComparison.mismatchWhenIs(events, built(slowSuiteAlerted.eventLogInNotation)) shouldBe None
    }

    eventScenarios(
      "Runner.run aborts on an OutOfMemoryError",
      "an AnyFunSuite whose one test throws OutOfMemoryError",
      "samples.AbortSuite",
      "the test's TestStarting assigned to the test, then the RunAborted assigned to the run and that the run ended aborted, " +
        "with no SuiteAborted and no closing event of the test",
      abortSuite,
      launch = Launch.ByRunner
    )
  }

  Feature("Nested suites run in other ways") {

    eventScenarios(
      "A Suites nests one instance twice",
      "a suite with no tests whose two nested suites are one instance of an AnyFunSuite with one test",
      "samples.TwiceSuites",
      "a structure holding the nested suite twice, then the events of each of its runs assigned to one of those two suites and its test, in the order nested",
      twiceSuites,
      nestedSuiteIds = List("samples.Inner1Suite")
    )
  }

  Feature("A test run more than once") {

    eventScenarios(
      "A suite runs a failed test again",
      "an AnyFunSuite whose runTest runs a test a second time where the first fails, and whose one test calls info, then fails the first time and succeeds the second",
      "samples.RetrySuite",
      "the test's TestStarting, InfoProvided and TestFailed in its first execution, then its TestStarting, InfoProvided and TestSucceeded in its second",
      retrySuite
    )
  }

  Feature("A suite run more than once") {

    eventScenarios(
      "A suite's run runs its tests twice",
      "an AnyFunSuite with one test, whose run calls super.run twice",
      "samples.RunsTwiceSuite",
      "the test's events in its first execution, then in its second, which ran in a second execution of the suite, in which the suite completes",
      runsTwiceSuite
    )

    eventScenarios(
      "An async suite's run runs its tests twice",
      "an AsyncFunSpec with one test, whose run calls super.run twice",
      "samples.RunsTwiceAsyncSpec",
      "the test's events in its first execution, then in its second, which ran in a second execution of the suite, in which the suite completes",
      runsTwiceAsyncSpec
    )
  }

  Feature("Messages") {

    eventScenarios(
      "A DocSpec's markup",
      "a DocSpec, which is private to ScalaTest's package, whose document is one markup snippet",
      "org.scalatest.samples.MarkupDocSpec",
      "the markup assigned to the suite, between its SuiteStarting and its SuiteCompleted",
      markupDocSpec
    )

    eventScenarios(
      "A suite's own messages",
      "an AnyFunSuite that calls info before each test, and declares an info after its one test",
      "samples.AnnouncingSuite",
      "each info assigned to the suite, the first before the test's TestStarting and the second after its TestSucceeded",
      announcingSuite
    )

    eventScenarios(
      "An info between two children of a scope",
      "an AnyFunSpec whose scope A Stack declares test is empty, then calls info, then declares scope when full",
      "samples.ScopeInfoFunSpec",
      "the info assigned to scope A Stack, after the TestSucceeded of is empty and before the ScopeOpened of when full",
      scopeInfoFunSpec
    )
  }

  Feature("Captured output") {

    Scenario("A test writes to both streams") {
      thisProcess
      Given("an AnyFunSuite whose test writes a line to stdout and a line to stderr, then succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("Framework runs it with the runner argument, in a JVM of its own")
        val output = outputOfFrameworkRunInItsOwnJvm("samples.BothStreamsSuite", runnerArgument(eventLogFile), eventLogFile.getParent)
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the event log is the run's and the suite's events, and holds no captured output")
        StatedLines.mismatchWhenIs(lines, bothStreamsSuite.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(bothStreamsSuite.eventLogInNotation)) shouldBe None
        lines.filter(line => line.contains("dividing now") || line.contains("warn: careful")) shouldBe Vector.empty

        And("the JVM's stdout holds the line written to stdout, and its stderr the line written to stderr")
        output.stdout should contain("dividing now")
        output.stderr should contain("warn: careful")
        output.stdout should not contain "warn: careful"
        output.stderr should not contain "dividing now"
      }
    }

    Scenario("The decoder reads the event log of: A test writes to both streams") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(bothStreamsSuite.eventLogInFormat)

      Then("it yields the run's and the suite's events")
      EventLogComparison.mismatchWhenIs(events, built(bothStreamsSuite.eventLogInNotation)) shouldBe None
    }
  }
}
