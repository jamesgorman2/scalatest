package org.scalatest.tools

import org.scalatest.GivenWhenThen
import org.scalatest.events.Event
import org.scalatest.events.EventLogComparison
import org.scalatest.events.InfoProvided
import org.scalatest.events.ScopeClosed
import org.scalatest.events.ScopeOpened
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class TestEventLogUserCodeRunsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  /**
   * A run as a criterion states it.
   *
   * @param reported what the run's reporters receive, in order, whether or not the run writes an event log
   * @param eventLogInNotation the events the criterion states, in the event notation, after the structures that name their nodes
   * @param eventLogInFormat those events as the format writes them, one line for each, the first suite's node ID being 1
   */
  private final case class StatedRun(reported: Vector[String], eventLogInNotation: String, eventLogInFormat: List[String])

  /** What a run's reporters were told, in the order they were told it. */
  private def reported(events: Vector[Event]): Vector[String] = {
    events.collect {
      case starting: SuiteStarting => s"SuiteStarting ${starting.suiteName}"
      case completed: SuiteCompleted => s"SuiteCompleted ${completed.suiteName}"
      case opened: ScopeOpened => s"ScopeOpened ${opened.message}"
      case closed: ScopeClosed => s"ScopeClosed ${closed.message}"
      case starting: TestStarting => s"TestStarting ${starting.testName}"
      case succeeded: TestSucceeded => s"TestSucceeded ${succeeded.testName}"
      case info: InfoProvided => s"InfoProvided ${info.message}"
      case other => other.getClass.getSimpleName
    }
  }

  private def suiteEvent(node: Long, eventName: String): String = TestEventLogLine.suiteEvent(node, 1, eventName)

  private def inFirstExecution(node: Long, eventName: String): String = TestEventLogLine.scopeOrTestEvent(node, 1, 1, eventName)

  private val direct = """"message":"direct","""

  private def structuresIn(lines: Seq[String]): Int = lines.count(_.contains(""""item":"structure""""))

  private def structuresHolding(name: String, lines: Seq[String]): Int =
    lines.count(line => line.contains(""""item":"structure"""") && line.contains(s""""name":"$name""""))

  private def suiteEventsOf(node: Long, lines: Seq[String]): Int =
    lines.count(line => line.contains(s""""node":$node,""") && (line.contains(""""event":"SuiteStarting"""") || line.contains(""""event":"SuiteCompleted"""")))

  private def infosIn(lines: Seq[String]): Int = lines.count(_.contains(""""event":"InfoProvided""""))

  private def eventsOf(node: Long, lines: Seq[String]): Int = lines.count(_.contains(s""""node":$node,"""))

  private val stackSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting StackSpec",
      "ScopeOpened A Stack",
      "TestStarting A Stack pops",
      "TestSucceeded A Stack pops",
      "ScopeClosed A Stack",
      "SuiteCompleted StackSpec",
      "RunCompleted"
    ),
    """
    structure
      suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
        test "A Stack pops", label "- A Stack pops"
    suite "StackSpec": SuiteStarting
    structure
      suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
        scope "A Stack", label "A Stack"
          test "pops", label "- pops"
    scope "A Stack": ScopeOpened
    test "pops": TestStarting
    test "pops": TestSucceeded
    scope "A Stack": ScopeClosed
    suite "StackSpec": SuiteCompleted
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suiteWithId(1, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
        TestEventLogLine.test(2, 1, 0, "A Stack pops", "- A Stack pops")
      ),
      suiteEvent(1, "SuiteStarting"),
      TestEventLogLine.structure(
        TestEventLogLine.suiteWithId(1, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
        TestEventLogLine.scope(3, 1, 0, "A Stack", "A Stack"),
        TestEventLogLine.test(2, 3, 0, "pops", "- pops")
      ),
      inFirstExecution(3, "ScopeOpened"),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      inFirstExecution(3, "ScopeClosed"),
      suiteEvent(1, "SuiteCompleted")
    )
  )

  // Framework's SuiteSortingReporter passes on no event of a suite that sent it no SuiteStarting, such as a helper's.
  private val reportedOfAHelper = Vector.empty[String]

  // Framework's filter keeps only the tests of the suites its task selects, so the helper's suite ID is selected too.
  private val helperSelected = "samples.HelperSuite"

  private def helperUnder(suiteNode: Long, parent: Long): String =
    TestEventLogLine.structure(
      TestEventLogLine.suiteUnder(suiteNode, parent, "HelperSuite", "samples.HelperSuite"),
      TestEventLogLine.scope(suiteNode + 1, suiteNode, 0, "A helper", "A helper"),
      TestEventLogLine.test(suiteNode + 2, suiteNode + 1, 0, "helps", "- helps")
    )

  private val outerSuite = StatedRun(
    Vector("RunStarting", "SuiteStarting OuterSuite", "TestStarting uses a helper") ++ reportedOfAHelper ++
      Vector("TestSucceeded uses a helper", "SuiteCompleted OuterSuite", "RunCompleted"),
    s"""
    structure
      suite "OuterSuite", class "samples.OuterSuite"
        test "uses a helper", label "- uses a helper"
    suite "OuterSuite": SuiteStarting
    test "uses a helper": TestStarting
    structure under suite "OuterSuite"
      suite "HelperSuite", class "samples.HelperSuite"
        scope "A helper", label "A helper"
          test "helps", label "- helps"
    scope "A helper": ScopeOpened
    test "helps": TestStarting
    test "helps": TestSucceeded
    scope "A helper": ScopeClosed
    test "uses a helper": TestSucceeded
    suite "OuterSuite": SuiteCompleted
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "OuterSuite", "samples.OuterSuite"),
        TestEventLogLine.test(2, 1, 0, "uses a helper", "- uses a helper")
      ),
      suiteEvent(1, "SuiteStarting"),
      inFirstExecution(2, "TestStarting"),
      helperUnder(3, 1),
      inFirstExecution(4, "ScopeOpened"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      inFirstExecution(4, "ScopeClosed"),
      inFirstExecution(2, "TestSucceeded"),
      suiteEvent(1, "SuiteCompleted")
    )
  )

  private val userSuites = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting UserSuites",
      "SuiteCompleted UserSuites",
      "SuiteStarting User1Suite",
      "TestStarting uses one"
    ) ++ reportedOfAHelper ++ Vector(
      "TestSucceeded uses one",
      "SuiteCompleted User1Suite",
      "SuiteStarting User2Suite",
      "TestStarting uses two"
    ) ++ reportedOfAHelper ++ Vector("TestSucceeded uses two", "SuiteCompleted User2Suite", "RunCompleted"),
    s"""
    structure
      suite "UserSuites", class "samples.UserSuites"
        suite "User1Suite", class "samples.User1Suite"
          test "uses one", label "- uses one"
        suite "User2Suite", class "samples.User2Suite"
          test "uses two", label "- uses two"
    test "uses one": TestStarting
    structure under suite "User1Suite"
      suite "HelperSuite", class "samples.HelperSuite"
        scope "A helper", label "A helper"
          test "helps", label "- helps"
    test "helps" #1: TestStarting
    test "uses one": TestSucceeded
    test "uses two": TestStarting
    structure under suite "User2Suite"
      suite "HelperSuite", class "samples.HelperSuite"
        scope "A helper", label "A helper"
          test "helps", label "- helps"
    test "helps" #2: TestStarting
    test "uses two": TestSucceeded
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "UserSuites", "samples.UserSuites"),
        TestEventLogLine.nestedSuite(2, 1, 0, "User1Suite", "samples.User1Suite"),
        TestEventLogLine.test(3, 2, 0, "uses one", "- uses one"),
        TestEventLogLine.nestedSuite(4, 1, 1, "User2Suite", "samples.User2Suite"),
        TestEventLogLine.test(5, 4, 0, "uses two", "- uses two")
      ),
      inFirstExecution(3, "TestStarting"),
      helperUnder(6, 2),
      inFirstExecution(8, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(5, "TestStarting"),
      helperUnder(9, 4),
      inFirstExecution(11, "TestStarting"),
      inFirstExecution(5, "TestSucceeded")
    )
  )

  private val twoHelpersSuite = StatedRun(
    Vector("RunStarting", "SuiteStarting TwoHelpersSuite", "TestStarting first") ++ reportedOfAHelper ++
      Vector("TestSucceeded first", "TestStarting second") ++ reportedOfAHelper ++
      Vector("TestSucceeded second", "SuiteCompleted TwoHelpersSuite", "RunCompleted"),
    s"""
    structure
      suite "TwoHelpersSuite", class "samples.TwoHelpersSuite"
        test "first", label "- first"
        test "second", label "- second"
    test "first": TestStarting
    structure under suite "TwoHelpersSuite"
      suite "HelperSuite", class "samples.HelperSuite"
        scope "A helper", label "A helper"
          test "helps", label "- helps"
    scope "A helper": ScopeOpened
    test "helps", parent execution 1: TestStarting
    test "helps", parent execution 1: TestSucceeded
    scope "A helper": ScopeClosed
    test "first": TestSucceeded
    test "second": TestStarting
    scope "A helper" execution 2: ScopeOpened
    test "helps" execution 2, parent execution 2: TestStarting
    test "helps" execution 2, parent execution 2: TestSucceeded
    scope "A helper" execution 2: ScopeClosed
    test "second": TestSucceeded
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "TwoHelpersSuite", "samples.TwoHelpersSuite"),
        TestEventLogLine.test(2, 1, 0, "first", "- first"),
        TestEventLogLine.test(3, 1, 1, "second", "- second")
      ),
      inFirstExecution(2, "TestStarting"),
      helperUnder(4, 1),
      inFirstExecution(5, "ScopeOpened"),
      inFirstExecution(6, "TestStarting"),
      inFirstExecution(6, "TestSucceeded"),
      inFirstExecution(5, "ScopeClosed"),
      inFirstExecution(2, "TestSucceeded"),
      inFirstExecution(3, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(5, 2, 2, "ScopeOpened"),
      TestEventLogLine.scopeOrTestEvent(6, 2, 2, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(6, 2, 2, "TestSucceeded"),
      TestEventLogLine.scopeOrTestEvent(5, 2, 2, "ScopeClosed"),
      inFirstExecution(3, "TestSucceeded")
    )
  )

  // Framework's SuiteSortingReporter passes on no event of a nested suite that sent it no SuiteStarting.
  private val runsItsNestedSuites = StatedRun(
    Vector("RunStarting", "SuiteStarting RunsItsNestedSuites", "SuiteCompleted RunsItsNestedSuites", "RunCompleted"),
    """
    structure
      suite "RunsItsNestedSuites", class "samples.RunsItsNestedSuites"
        suite "Inner1Suite", class "samples.Inner1Suite"
          test "inner one", label "- inner one"
        suite "Inner2Suite", class "samples.Inner2Suite"
          test "inner two", label "- inner two"
    suite "RunsItsNestedSuites": SuiteStarting
    test "inner one": TestStarting
    test "inner one": TestSucceeded
    test "inner two": TestStarting
    test "inner two": TestSucceeded
    suite "RunsItsNestedSuites": SuiteCompleted
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "RunsItsNestedSuites", "samples.RunsItsNestedSuites"),
        TestEventLogLine.nestedSuite(2, 1, 0, "Inner1Suite", "samples.Inner1Suite"),
        TestEventLogLine.test(3, 2, 0, "inner one", "- inner one"),
        TestEventLogLine.nestedSuite(4, 1, 1, "Inner2Suite", "samples.Inner2Suite"),
        TestEventLogLine.test(5, 4, 0, "inner two", "- inner two")
      ),
      suiteEvent(1, "SuiteStarting"),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      inFirstExecution(5, "TestStarting"),
      inFirstExecution(5, "TestSucceeded"),
      suiteEvent(1, "SuiteCompleted")
    )
  )

  private val twiceRunPlainSuite = StatedRun(
    Vector("RunStarting", "SuiteStarting TwiceRunPlainSuite", "TestStarting a", "TestSucceeded a", "TestStarting a", "TestSucceeded a", "SuiteCompleted TwiceRunPlainSuite", "RunCompleted"),
    """
    structure
      suite "TwiceRunPlainSuite", class "samples.TwiceRunPlainSuite"
        test "b", label "- b"
        test "a", label "- a"
        test "c", label "- c"
    test "a": TestStarting
    test "a": TestSucceeded
    test "a" execution 2: TestStarting
    test "a" execution 2: TestSucceeded
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "TwiceRunPlainSuite", "samples.TwiceRunPlainSuite"),
        TestEventLogLine.test(2, 1, 0, "b", "- b"),
        TestEventLogLine.test(3, 1, 1, "a", "- a"),
        TestEventLogLine.test(4, 1, 2, "c", "- c")
      ),
      inFirstExecution(3, "TestStarting"),
      inFirstExecution(3, "TestSucceeded"),
      TestEventLogLine.scopeOrTestEvent(3, 2, 1, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(3, 2, 1, "TestSucceeded")
    )
  )

  /** A run of a suite whose test `t` sends a message straight to a reporter, which is assigned to `messageNode`, the suite's or `t`'s. */
  private def directRun(suite: String, messageAssignedTo: String, messageNode: Long, isAssignedToTest: Boolean): StatedRun = {
    val reportedOfT = Vector("TestStarting t", "InfoProvided direct", "TestSucceeded t")
    StatedRun(
      Vector("RunStarting", s"SuiteStarting $suite") ++ reportedOfT ++ Vector(s"SuiteCompleted $suite", "RunCompleted"),
      s"""
      structure
        suite "$suite", class "samples.$suite"
          test "t", label "- t"
      test "t": TestStarting
      $messageAssignedTo: InfoProvided(message: "direct")
      test "t": TestSucceeded
      """,
      List(
        TestEventLogLine.structure(TestEventLogLine.suite(1, suite, s"samples.$suite"), TestEventLogLine.test(2, 1, 0, "t", "- t")),
        inFirstExecution(2, "TestStarting"),
        if (isAssignedToTest) TestEventLogLine.scopeOrTestEvent(messageNode, 1, 1, "InfoProvided", direct)
        else TestEventLogLine.suiteEvent(messageNode, 1, "InfoProvided", direct),
        inFirstExecution(2, "TestSucceeded")
      )
    )
  }

  /** A run of a suite nesting one whose test `t` sends a message to the reporter the outer suite's `run` was given. */
  private def nestedDirectRun(suite: String, nested: String, messageAssignedTo: String, isAssignedToTest: Boolean): StatedRun = {
    StatedRun(
      Vector(
        "RunStarting",
        s"SuiteStarting $suite",
        s"SuiteCompleted $suite",
        s"SuiteStarting $nested",
        "TestStarting t",
        "InfoProvided direct",
        "TestSucceeded t",
        s"SuiteCompleted $nested",
        "RunCompleted"
      ),
      s"""
      structure
        suite "$suite", class "samples.$suite"
          suite "$nested", class "samples.$nested"
            test "t", label "- t"
      test "t": TestStarting
      $messageAssignedTo: InfoProvided(message: "direct")
      test "t": TestSucceeded
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.suite(1, suite, s"samples.$suite"),
          TestEventLogLine.nestedSuite(2, 1, 0, nested, s"samples.$nested"),
          TestEventLogLine.test(3, 2, 0, "t", "- t")
        ),
        inFirstExecution(3, "TestStarting"),
        if (isAssignedToTest) TestEventLogLine.scopeOrTestEvent(3, 1, 1, "InfoProvided", direct)
        else TestEventLogLine.suiteEvent(2, 1, "InfoProvided", direct),
        inFirstExecution(3, "TestSucceeded")
      )
    )
  }

  private val directNamingItsSecondRun = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting DirectNamingItsSecondRun",
      "TestStarting t",
      "TestSucceeded t",
      "TestStarting t",
      "InfoProvided direct",
      "TestSucceeded t",
      "SuiteCompleted DirectNamingItsSecondRun",
      "RunCompleted"
    ),
    """
    structure
      suite "DirectNamingItsSecondRun", class "samples.DirectNamingItsSecondRun"
        test "t", label "- t"
    test "t": TestStarting
    test "t": TestSucceeded
    test "t" execution 2: TestStarting
    test "t" execution 2: InfoProvided(message: "direct")
    test "t" execution 2: TestSucceeded
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "DirectNamingItsSecondRun", "samples.DirectNamingItsSecondRun"),
        TestEventLogLine.test(2, 1, 0, "t", "- t")
      ),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 1, "TestStarting"),
      TestEventLogLine.scopeOrTestEvent(2, 2, 1, "InfoProvided", direct),
      TestEventLogLine.scopeOrTestEvent(2, 2, 1, "TestSucceeded")
    )
  )

  private val directNamingItsTestAfterRun = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting DirectNamingItsTestAfterRun",
      "TestStarting t",
      "TestSucceeded t",
      "InfoProvided direct",
      "SuiteCompleted DirectNamingItsTestAfterRun",
      "RunCompleted"
    ),
    """
    structure
      suite "DirectNamingItsTestAfterRun", class "samples.DirectNamingItsTestAfterRun"
        test "t", label "- t"
    test "t": TestStarting
    test "t": TestSucceeded
    test "t": InfoProvided(message: "direct")
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "DirectNamingItsTestAfterRun", "samples.DirectNamingItsTestAfterRun"),
        TestEventLogLine.test(2, 1, 0, "t", "- t")
      ),
      inFirstExecution(2, "TestStarting"),
      inFirstExecution(2, "TestSucceeded"),
      TestEventLogLine.scopeOrTestEvent(2, 1, 1, "InfoProvided", direct)
    )
  )

  /**
   * Registers the scenario of the run that writes its events to the event log, and the scenario of the decoder that
   * reads them. The event log holds the stated events in order, and `alsoThen` states what else it holds.
   */
  private def eventScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    nestedSuiteIds: List[String] = Nil
  )(alsoThen: Vector[String] => Unit): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      When("Framework runs it without the runner argument")
      val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, Nil, nestedSuiteIds))

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reportedWithoutTheArgument shouldBe stated.reported

      TemporaryEventLog.in { eventLogFile =>
        When("Framework runs it with the runner argument")
        val reportedWithTheArgument = reported(eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, runnerArgument(eventLogFile), nestedSuiteIds))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the run's reporters are told the same")
        reportedWithTheArgument shouldBe stated.reported

        And(s"the event log holds, in order, $eventLogHolds")
        StatedLines.mismatchWhenHoldsInOrder(lines, stated.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenHoldsInOrder(decodedOrFail(lines), built(stated.eventLogInNotation)) shouldBe None
        alsoThen(lines)
      }
    }

    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(stated.eventLogInFormat)

      Then(s"it yields $eventLogHolds")
      EventLogComparison.mismatchWhenIs(events, built(stated.eventLogInNotation)) shouldBe None
    }
  }

  Feature("A suite that runs another instance of itself") {

    eventScenarios(
      "A custom @WrapWith wrapper runs the class it wraps",
      "a suite annotated @WrapWith(classOf[DelegatingSuite]) declaring scope 'A Stack' holding test 'pops', where " +
        "DelegatingSuite gives the wrapped instance's names and tests as its own and runs that instance in its place",
      "samples.StackSpec",
      "the wrapper's structure, the suite's SuiteStarting, then a further structure of the wrapped instance's tree, " +
        "in which 'pops' keeps the node ID of the wrapper's 'A Stack pops', before the instance's events",
      stackSpec
    ) { lines =>
      And("no structure holds a suite other than StackSpec")
      structuresIn(lines) shouldBe 2
      structuresHolding("StackSpec", lines) shouldBe 2
    }
  }

  Feature("A suite run from a test") {

    eventScenarios(
      "A test runs a helper",
      "a suite whose one test, 'uses a helper', runs a HelperSuite with the Args its test is run with",
      "samples.OuterSuite",
      "the helper's structure under the outer suite, then its events, assigned to its own nodes and inside the test that ran it",
      outerSuite,
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("no SuiteStarting or SuiteCompleted is assigned to HelperSuite")
      suiteEventsOf(3, lines) shouldBe 0
    }

    eventScenarios(
      "Tests of two nested suites each run a helper",
      "a Suites nesting User1Suite and User2Suite, whose one test each runs a HelperSuite",
      "samples.UserSuites",
      "a HelperSuite under each nested suite, each holding the events of the helper run there",
      userSuites,
      nestedSuiteIds = List("samples.User1Suite", "samples.User2Suite", helperSelected)
    ) { lines =>
      And("no other structure holds HelperSuite")
      structuresHolding("HelperSuite", lines) shouldBe 2
    }

    eventScenarios(
      "Two tests each run a helper",
      "a suite whose tests 'first' and 'second' each run a HelperSuite",
      "samples.TwoHelpersSuite",
      "one HelperSuite under the suite, whose scope and test have a second execution for the second test's helper",
      twoHelpersSuite,
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("no other structure holds HelperSuite")
      structuresHolding("HelperSuite", lines) shouldBe 1
    }
  }

  Feature("Nested suites run in other ways") {

    eventScenarios(
      "A runNestedSuites override sends no SuiteStarting",
      "a Suites nesting Inner1Suite and Inner2Suite whose runNestedSuites runs each of them itself, sending no event",
      "samples.RunsItsNestedSuites",
      "the structure, then each nested suite's test events assigned to that suite's tests",
      runsItsNestedSuites,
      nestedSuiteIds = List("samples.Inner1Suite", "samples.Inner2Suite")
    ) { lines =>
      And("no SuiteStarting or SuiteCompleted is assigned to Inner1Suite or Inner2Suite, and no further structure is written")
      suiteEventsOf(2, lines) shouldBe 0
      suiteEventsOf(4, lines) shouldBe 0
      structuresIn(lines) shouldBe 1
    }
  }

  Feature("A test run more than once") {

    eventScenarios(
      "A suite with no registration tree reports a test twice",
      "a Suite with no registration tree whose runTests runs test 'a' twice, its runTest sending TestStarting and TestSucceeded itself",
      "samples.TwiceRunPlainSuite",
      "test 'a' in a first execution and then a second",
      twiceRunPlainSuite
    ) { lines =>
      And("no other event is assigned to 'a'")
      eventsOf(3, lines) shouldBe 4
    }
  }

  Feature("Messages sent straight to a reporter") {

    eventScenarios(
      "A test reports to its suite's reporter, with no nameInfo",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided with no nameInfo",
      "samples.DirectNamelessSuite",
      "the message assigned to the suite, between the test's events",
      directRun("DirectNamelessSuite", "suite \"DirectNamelessSuite\"", 1, isAssignedToTest = false)
    ) { lines =>
      And("no InfoProvided is assigned to test 't'")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "A test reports to the reporter its suite keeps, naming its suite",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided naming the suite and no test",
      "samples.DirectNamingItsSuite",
      "the message assigned to the suite",
      directRun("DirectNamingItsSuite", "suite \"DirectNamingItsSuite\"", 1, isAssignedToTest = false)
    ) { lines =>
      And("it holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "A test reports to the reporter the suite nesting it keeps, naming its suite",
      "a Suites whose run keeps args.reporter, nesting a suite whose test 't' sends that reporter an InfoProvided naming the nested suite and no test",
      "samples.DirectSuitesNamingTheSuite",
      "the message assigned to the nested suite",
      nestedDirectRun("DirectSuitesNamingTheSuite", "NestedDirectNamingItsSuite", "suite \"NestedDirectNamingItsSuite\"", isAssignedToTest = false),
      nestedSuiteIds = List("samples.NestedDirectNamingItsSuite")
    ) { lines =>
      And("it holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "A test reports to the reporter its suite keeps, naming itself",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided naming the suite and 't'",
      "samples.DirectNamingItsTest",
      "the message assigned to test 't', in its open execution",
      directRun("DirectNamingItsTest", "test \"t\"", 2, isAssignedToTest = true)
    ) { lines =>
      And("it holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "A test reports to the reporter the suite nesting it keeps, naming itself",
      "a Suites whose run keeps args.reporter, nesting a suite whose test 't' sends that reporter an InfoProvided naming the nested suite and 't'",
      "samples.DirectSuitesNamingTheTest",
      "the message assigned to test 't' of the nested suite, in its open execution",
      nestedDirectRun("DirectSuitesNamingTheTest", "NestedDirectNamingItsTest", "test \"t\"", isAssignedToTest = true),
      nestedSuiteIds = List("samples.NestedDirectNamingItsTest")
    ) { lines =>
      And("it holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "The test sends the message the second time it runs",
      "a suite whose runTest runs test 't' twice, and whose test sends the reporter its suite keeps an InfoProvided naming 't' only the second time",
      "samples.DirectNamingItsSecondRun",
      "the message in the test's second execution",
      directNamingItsSecondRun
    ) { lines =>
      And("it holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "The message is sent after the test has closed",
      "a suite whose run, after super.run returns, sends the reporter it was given an InfoProvided naming test 't'",
      "samples.DirectNamingItsTestAfterRun",
      "the message in the test's latest execution, after the test closed",
      directNamingItsTestAfterRun
    ) { lines =>
      And("it holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }
  }
}
