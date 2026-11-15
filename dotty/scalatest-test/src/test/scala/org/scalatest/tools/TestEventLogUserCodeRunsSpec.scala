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
import org.scalatest.tools.TestEventLogLine.nodeEvent

final class TestEventLogUserCodeRunsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  /**
   * A run as a criterion states it.
   *
   * @param reported what the run's reporters receive, in order, whether or not the run writes an event log
   * @param eventLogInNotation the events the criterion states, in the event notation, after the structures that name their nodes
   * @param eventLogInFormat those events as the format writes them, one line for each
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

  private val direct = """"message":"direct","""

  private def structuresIn(lines: Seq[String]): Int = lines.count(_.contains(""""item":"structure""""))

  private def structuresHolding(name: String, lines: Seq[String]): Int =
    lines.count(line => line.contains(""""item":"structure"""") && line.contains(s""""name":"$name""""))

  private def suiteEventsOf(suiteId: String, lines: Seq[String]): Int =
    lines.count(line =>
      line.contains(""""item":"event"""") && line.contains(s""""suiteId":"$suiteId"""") &&
        (line.contains(""""event":"SuiteStarting"""") || line.contains(""""event":"SuiteCompleted""""))
    )

  private def infosIn(lines: Seq[String]): Int = lines.count(_.contains(""""event":"InfoProvided""""))

  private def eventsOf(node: NodePath, lines: Seq[String]): Int = lines.count(_.contains(s""""node":${node.json},"""))

  private val stackSpec = {
    val top = NodePath.topLevel("samples.StackSpec")
    val aStack = top.scope("A Stack")
    val pops = top.test("A Stack pops")
    StatedRun(
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
          TestEventLogLine.suiteWithId(top, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
          TestEventLogLine.test(pops, top, 0, "A Stack pops", "- A Stack pops")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        TestEventLogLine.structure(
          TestEventLogLine.suiteWithId(top, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
          TestEventLogLine.scope(aStack, 0, "A Stack", "A Stack"),
          TestEventLogLine.test(pops, aStack, 0, "pops", "- pops")
        ),
        nodeEvent(aStack, List(1, 1), "ScopeOpened"),
        nodeEvent(pops, List(1, 1, 1), "TestStarting"),
        nodeEvent(pops, List(1, 1, 1), "TestSucceeded"),
        nodeEvent(aStack, List(1, 1), "ScopeClosed"),
        nodeEvent(top, List(1), "SuiteCompleted")
      )
    )
  }

  // Framework's SuiteSortingReporter passes on no event of a suite that sent it no SuiteStarting, such as a helper's.
  private val reportedOfAHelper = Vector.empty[String]

  // Framework's filter keeps only the tests of the suites its task selects, so the helper's suite ID is selected too.
  private val helperSelected = "samples.HelperSuite"

  /** The node of the helper a test runs, which sits under that test. */
  private def helperUnder(test: NodePath): NodePath = test.suite("samples.HelperSuite")

  private def helperScope(helper: NodePath): NodePath = helper.scope("A helper")

  private def helps(helper: NodePath): NodePath = helper.test("A helper helps")

  private val placesOfTheNode = """"node":\[(.*?)\],"executions"""".r
  private val place = """\{[^}]*\}""".r
  private val executionPath = """"executions":\[([0-9,]*)\]""".r

  /** Whether an event line's execution path holds a second execution at the place of `A helper` or at a place under it. */
  private def ofASecondExecutionOfAHelper(line: String): Boolean = {
    val places = placesOfTheNode.findFirstMatchIn(line).toList.flatMap(node => place.findAllIn(node.group(1)).toList)
    val executions = executionPath.findFirstMatchIn(line).toList.flatMap(_.group(1).split(',').toList.map(_.toInt))
    val aHelper = places.indexWhere(_.startsWith("""{"scope":"A helper","""))
    aHelper >= 0 && executions.drop(aHelper).contains(2)
  }

  private def eventsOfASecondExecutionOfHelpers(lines: Seq[String]): Int =
    lines.count(line => line.contains(""""suiteId":"samples.HelperSuite"""") && line.contains(""""item":"event"""") && ofASecondExecutionOfAHelper(line))

  private def helperStructure(helper: NodePath): String =
    TestEventLogLine.structure(
      TestEventLogLine.suiteUnder(helper, "HelperSuite", "samples.HelperSuite"),
      TestEventLogLine.scope(helperScope(helper), 0, "A helper", "A helper"),
      TestEventLogLine.test(helps(helper), helperScope(helper), 0, "helps", "- helps")
    )

  private val usesAHelper = NodePath.topLevel("samples.OuterSuite").test("uses a helper")

  private val outerSuite = {
    val top = usesAHelper.parent
    val helper = helperUnder(usesAHelper)
    StatedRun(
      Vector("RunStarting", "SuiteStarting OuterSuite", "TestStarting uses a helper") ++ reportedOfAHelper ++
        Vector("TestSucceeded uses a helper", "SuiteCompleted OuterSuite", "RunCompleted"),
      s"""
      structure
        suite "OuterSuite", class "samples.OuterSuite"
          test "uses a helper", label "- uses a helper"
      suite "OuterSuite": SuiteStarting
      test "uses a helper": TestStarting
      structure under test "uses a helper"
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
          TestEventLogLine.suite("OuterSuite", "samples.OuterSuite"),
          TestEventLogLine.test(usesAHelper, top, 0, "uses a helper", "- uses a helper")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        nodeEvent(usesAHelper, List(1, 1), "TestStarting"),
        helperStructure(helper),
        nodeEvent(helperScope(helper), List(1, 1, 1, 1), "ScopeOpened"),
        nodeEvent(helps(helper), List(1, 1, 1, 1, 1), "TestStarting"),
        nodeEvent(helps(helper), List(1, 1, 1, 1, 1), "TestSucceeded"),
        nodeEvent(helperScope(helper), List(1, 1, 1, 1), "ScopeClosed"),
        nodeEvent(usesAHelper, List(1, 1), "TestSucceeded"),
        nodeEvent(top, List(1), "SuiteCompleted")
      )
    )
  }

  private val userSuites = {
    val top = NodePath.topLevel("samples.UserSuites")
    val user1 = top.suite("samples.User1Suite")
    val user2 = top.suite("samples.User2Suite")
    val usesOne = user1.test("uses one")
    val usesTwo = user2.test("uses two")
    StatedRun(
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
      structure under test "uses one"
        suite "HelperSuite", class "samples.HelperSuite"
          scope "A helper", label "A helper"
            test "helps", label "- helps"
      test "helps" #1: TestStarting
      test "uses one": TestSucceeded
      test "uses two": TestStarting
      structure under test "uses two"
        suite "HelperSuite", class "samples.HelperSuite"
          scope "A helper", label "A helper"
            test "helps", label "- helps"
      test "helps" #2: TestStarting
      test "uses two": TestSucceeded
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.suite("UserSuites", "samples.UserSuites"),
          TestEventLogLine.nestedSuite(user1, 0, "User1Suite", "samples.User1Suite"),
          TestEventLogLine.test(usesOne, user1, 0, "uses one", "- uses one"),
          TestEventLogLine.nestedSuite(user2, 1, "User2Suite", "samples.User2Suite"),
          TestEventLogLine.test(usesTwo, user2, 0, "uses two", "- uses two")
        ),
        nodeEvent(usesOne, List(1, 1, 1), "TestStarting"),
        helperStructure(helperUnder(usesOne)),
        nodeEvent(helps(helperUnder(usesOne)), List(1, 1, 1, 1, 1, 1), "TestStarting"),
        nodeEvent(usesOne, List(1, 1, 1), "TestSucceeded"),
        nodeEvent(usesTwo, List(1, 1, 1), "TestStarting"),
        helperStructure(helperUnder(usesTwo)),
        nodeEvent(helps(helperUnder(usesTwo)), List(1, 1, 1, 1, 1, 1), "TestStarting"),
        nodeEvent(usesTwo, List(1, 1, 1), "TestSucceeded")
      )
    )
  }

  private val twoHelpersSuite = {
    val top = NodePath.topLevel("samples.TwoHelpersSuite")
    val first = top.test("first")
    val second = top.test("second")
    def helperEvents(test: NodePath): List[String] = {
      val helper = helperUnder(test)
      List(
        helperStructure(helper),
        nodeEvent(helperScope(helper), List(1, 1, 1, 1), "ScopeOpened"),
        nodeEvent(helps(helper), List(1, 1, 1, 1, 1), "TestStarting"),
        nodeEvent(helps(helper), List(1, 1, 1, 1, 1), "TestSucceeded"),
        nodeEvent(helperScope(helper), List(1, 1, 1, 1), "ScopeClosed")
      )
    }
    StatedRun(
      Vector("RunStarting", "SuiteStarting TwoHelpersSuite", "TestStarting first") ++ reportedOfAHelper ++
        Vector("TestSucceeded first", "TestStarting second") ++ reportedOfAHelper ++
        Vector("TestSucceeded second", "SuiteCompleted TwoHelpersSuite", "RunCompleted"),
      s"""
      structure
        suite "TwoHelpersSuite", class "samples.TwoHelpersSuite"
          test "first", label "- first"
          test "second", label "- second"
      test "first": TestStarting
      structure under test "first"
        suite "HelperSuite", class "samples.HelperSuite"
          scope "A helper", label "A helper"
            test "helps", label "- helps"
      scope "A helper" #1: ScopeOpened
      test "helps" #1, in test "first" execution 1: TestStarting
      test "helps" #1, in test "first" execution 1: TestSucceeded
      scope "A helper" #1: ScopeClosed
      test "first": TestSucceeded
      test "second": TestStarting
      structure under test "second"
        suite "HelperSuite", class "samples.HelperSuite"
          scope "A helper", label "A helper"
            test "helps", label "- helps"
      scope "A helper" #2: ScopeOpened
      test "helps" #2, in test "second" execution 1: TestStarting
      test "helps" #2, in test "second" execution 1: TestSucceeded
      scope "A helper" #2: ScopeClosed
      test "second": TestSucceeded
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.suite("TwoHelpersSuite", "samples.TwoHelpersSuite"),
          TestEventLogLine.test(first, top, 0, "first", "- first"),
          TestEventLogLine.test(second, top, 1, "second", "- second")
        ),
        nodeEvent(first, List(1, 1), "TestStarting")
      ) ++ helperEvents(first) ++ List(
        nodeEvent(first, List(1, 1), "TestSucceeded"),
        nodeEvent(second, List(1, 1), "TestStarting")
      ) ++ helperEvents(second) ++ List(
        nodeEvent(second, List(1, 1), "TestSucceeded")
      )
    )
  }

  private val runsItsNestedSuitesNode = NodePath.topLevel("samples.RunsItsNestedSuites")
  private val inner1 = runsItsNestedSuitesNode.suite("samples.Inner1Suite")
  private val inner2 = runsItsNestedSuitesNode.suite("samples.Inner2Suite")

  // Framework's SuiteSortingReporter passes on no event of a nested suite that sent it no SuiteStarting.
  private val runsItsNestedSuites = {
    val innerOne = inner1.test("inner one")
    val innerTwo = inner2.test("inner two")
    StatedRun(
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
          TestEventLogLine.suite("RunsItsNestedSuites", "samples.RunsItsNestedSuites"),
          TestEventLogLine.nestedSuite(inner1, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(innerOne, inner1, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(inner2, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(innerTwo, inner2, 0, "inner two", "- inner two")
        ),
        nodeEvent(runsItsNestedSuitesNode, List(1), "SuiteStarting"),
        nodeEvent(innerOne, List(1, 1, 1), "TestStarting"),
        nodeEvent(innerOne, List(1, 1, 1), "TestSucceeded"),
        nodeEvent(innerTwo, List(1, 1, 1), "TestStarting"),
        nodeEvent(innerTwo, List(1, 1, 1), "TestSucceeded"),
        nodeEvent(runsItsNestedSuitesNode, List(1), "SuiteCompleted")
      )
    )
  }

  private val twiceRunA = NodePath.topLevel("samples.TwiceRunPlainSuite").test("a")

  private val twiceRunPlainSuite = {
    val top = twiceRunA.parent
    StatedRun(
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
          TestEventLogLine.suite("TwiceRunPlainSuite", "samples.TwiceRunPlainSuite"),
          TestEventLogLine.test(top.test("b"), top, 0, "b", "- b"),
          TestEventLogLine.test(twiceRunA, top, 1, "a", "- a"),
          TestEventLogLine.test(top.test("c"), top, 2, "c", "- c")
        ),
        nodeEvent(twiceRunA, List(1, 1), "TestStarting"),
        nodeEvent(twiceRunA, List(1, 1), "TestSucceeded"),
        nodeEvent(twiceRunA, List(1, 2), "TestStarting"),
        nodeEvent(twiceRunA, List(1, 2), "TestSucceeded")
      )
    )
  }

  /** A run of a suite whose test `t` sends a message straight to a reporter, which is assigned to the suite or to `t`. */
  private def directRun(suite: String, messageAssignedTo: String, isAssignedToTest: Boolean): StatedRun = {
    val top = NodePath.topLevel(s"samples.$suite")
    val t = top.test("t")
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
        TestEventLogLine.structure(TestEventLogLine.suite(suite, s"samples.$suite"), TestEventLogLine.test(t, top, 0, "t", "- t")),
        nodeEvent(t, List(1, 1), "TestStarting"),
        if (isAssignedToTest) nodeEvent(t, List(1, 1), "InfoProvided", direct) else nodeEvent(top, List(1), "InfoProvided", direct),
        nodeEvent(t, List(1, 1), "TestSucceeded")
      )
    )
  }

  /** A run of a suite nesting one whose test `t` sends a message to the reporter the outer suite's `run` was given. */
  private def nestedDirectRun(suite: String, nested: String, messageAssignedTo: String, isAssignedToTest: Boolean): StatedRun = {
    val nestedNode = NodePath.topLevel(s"samples.$suite").suite(s"samples.$nested")
    val t = nestedNode.test("t")
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
          TestEventLogLine.suite(suite, s"samples.$suite"),
          TestEventLogLine.nestedSuite(nestedNode, 0, nested, s"samples.$nested"),
          TestEventLogLine.test(t, nestedNode, 0, "t", "- t")
        ),
        nodeEvent(t, List(1, 1, 1), "TestStarting"),
        if (isAssignedToTest) nodeEvent(t, List(1, 1, 1), "InfoProvided", direct) else nodeEvent(nestedNode, List(1, 1), "InfoProvided", direct),
        nodeEvent(t, List(1, 1, 1), "TestSucceeded")
      )
    )
  }

  private val directNamingItsSecondRun = {
    val top = NodePath.topLevel("samples.DirectNamingItsSecondRun")
    val t = top.test("t")
    StatedRun(
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
          TestEventLogLine.suite("DirectNamingItsSecondRun", "samples.DirectNamingItsSecondRun"),
          TestEventLogLine.test(t, top, 0, "t", "- t")
        ),
        nodeEvent(t, List(1, 1), "TestStarting"),
        nodeEvent(t, List(1, 1), "TestSucceeded"),
        nodeEvent(t, List(1, 2), "TestStarting"),
        nodeEvent(t, List(1, 2), "InfoProvided", direct),
        nodeEvent(t, List(1, 2), "TestSucceeded")
      )
    )
  }

  private val directNamingItsTestAfterRun = {
    val top = NodePath.topLevel("samples.DirectNamingItsTestAfterRun")
    val t = top.test("t")
    StatedRun(
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
          TestEventLogLine.suite("DirectNamingItsTestAfterRun", "samples.DirectNamingItsTestAfterRun"),
          TestEventLogLine.test(t, top, 0, "t", "- t")
        ),
        nodeEvent(t, List(1, 1), "TestStarting"),
        nodeEvent(t, List(1, 1), "TestSucceeded"),
        nodeEvent(t, List(1, 1), "InfoProvided", direct)
      )
    )
  }

  /**
   * Registers the scenario of the run that writes its events to the event log, and the scenario of the decoder that
   * reads them. The event log holds the stated events in order, and `alsoThen` states what else it holds.
   *
   * @param runPendingUntil the task whose change the emitter needs before its run can pass, if any
   */
  private def eventScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    nestedSuiteIds: List[String] = Nil,
    runPendingUntil: Option[String] = None
  )(alsoThen: Vector[String] => Unit): Unit = {
    Scenario(scenario) {
      runPendingUntil.foreach(_ => pending)
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
      "the helper's structure under the test that ran it, then its events, assigned to its own nodes and inside the test that ran it",
      outerSuite,
      runPendingUntil = Some("Suites at places no structure declared"),
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("no SuiteStarting or SuiteCompleted is assigned to HelperSuite")
      suiteEventsOf("samples.HelperSuite", lines) shouldBe 0
    }

    eventScenarios(
      "Tests of two nested suites each run a helper",
      "a Suites nesting User1Suite and User2Suite, whose one test each runs a HelperSuite",
      "samples.UserSuites",
      "a HelperSuite under each nested suite's test, each holding the events of the helper run there",
      userSuites,
      runPendingUntil = Some("Suites at places no structure declared"),
      nestedSuiteIds = List("samples.User1Suite", "samples.User2Suite", helperSelected)
    ) { lines =>
      And("no other structure holds HelperSuite")
      structuresHolding("HelperSuite", lines) shouldBe 2
    }

    eventScenarios(
      "Two tests each run a helper",
      "a suite whose tests 'first' and 'second' each run a HelperSuite",
      "samples.TwoHelpersSuite",
      "a HelperSuite under each test, each holding the events of the helper run there",
      twoHelpersSuite,
      runPendingUntil = Some("Suites at places no structure declared"),
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("no other structure holds HelperSuite, and no event is of a second execution of 'A helper' or 'helps'")
      structuresHolding("HelperSuite", lines) shouldBe 2
      eventsOfASecondExecutionOfHelpers(lines) shouldBe 0
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
      suiteEventsOf("samples.Inner1Suite", lines) shouldBe 0
      suiteEventsOf("samples.Inner2Suite", lines) shouldBe 0
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
      eventsOf(twiceRunA, lines) shouldBe 4
    }
  }

  Feature("Messages sent straight to a reporter") {

    eventScenarios(
      "A test reports to its suite's reporter, with no nameInfo",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided with no nameInfo",
      "samples.DirectNamelessSuite",
      "the message assigned to the suite, between the test's events",
      directRun("DirectNamelessSuite", "suite \"DirectNamelessSuite\"", isAssignedToTest = false)
    ) { lines =>
      And("no InfoProvided is assigned to test 't'")
      infosIn(lines) shouldBe 1
    }

    eventScenarios(
      "A test reports to the reporter its suite keeps, naming its suite",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided naming the suite and no test",
      "samples.DirectNamingItsSuite",
      "the message assigned to the suite",
      directRun("DirectNamingItsSuite", "suite \"DirectNamingItsSuite\"", isAssignedToTest = false)
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
      directRun("DirectNamingItsTest", "test \"t\"", isAssignedToTest = true)
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
