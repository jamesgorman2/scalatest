package org.scalatest.tools

import org.scalatest.GivenWhenThen
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

  private val ofASuite = Vector("SuiteStarting", "SuiteCompleted")
  private val ofAScope = Vector("ScopeOpened", "ScopeClosed")
  private val ofATest = Vector("TestStarting", "TestSucceeded")

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

  private val nodeAndEventName = """"node":(\[.*?\]),"executions":\[[0-9,]*\],"event":"([A-Za-z]+)"""".r

  private def eventNamesByNodeJsonIn(lines: Seq[String]): Map[String, Vector[String]] =
    lines.toVector
      .flatMap(line => nodeAndEventName.findFirstMatchIn(line).map(found => found.group(1) -> found.group(2)))
      .groupMap(_._1)(_._2)

  private def assertNoEventIsAssignedToAnAncestor(lines: Seq[String], stated: StatedRun): Unit = {
    And("no event is assigned to an ancestor of the suite or test it names")
    eventNamesByNodeJsonIn(lines) shouldBe stated.eventNamesOfEachNode.map((node, events) => node.json -> events)
  }

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
          TestEventLogLine.topLevelSuiteWithId(top, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
          TestEventLogLine.test(pops, top, 0, "A Stack pops", "- A Stack pops")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuiteWithId(top, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
          TestEventLogLine.scope(aStack, 0, "A Stack", "A Stack"),
          TestEventLogLine.test(pops, aStack, 0, "pops", "- pops")
        ),
        nodeEvent(aStack, List(1, 1), "ScopeOpened"),
        nodeEvent(pops, List(1, 1, 1), "TestStarting"),
        nodeEvent(pops, List(1, 1, 1), "TestSucceeded"),
        nodeEvent(aStack, List(1, 1), "ScopeClosed"),
        nodeEvent(top, List(1), "SuiteCompleted")
      ),
      Map(top -> ofASuite, aStack -> ofAScope, pops -> ofATest)
    )
  }

  // Framework's SuiteSortingReporter passes on no event of a suite that sent it no SuiteStarting, such as a helper's.
  private val reportedOfAHelper = Vector.empty[String]

  // Framework's filter keeps only the tests of the suites its task selects, so the helper's suite ID is selected too.
  private val helperSelected = "samples.HelperSuite"

  private def helperUnder(test: NodePath): NodePath = test.suite("samples.HelperSuite")

  private def helperScope(helper: NodePath): NodePath = helper.scope("A helper")

  private def helps(helper: NodePath): NodePath = helper.test("A helper helps")

  private val placesOfTheNode = """"node":\[(.*?)\],"executions"""".r
  private val place = """\{[^}]*\}""".r
  private val executionPath = """"executions":\[([0-9,]*)\]""".r

  /**
   * A test's node path holds no place for its scopes, but its execution path does. No scope sits above the helper in
   * these scenarios, so the helper's place is at the same index in both.
   */
  private def isOfASecondExecutionUnderAHelper(line: String): Boolean = {
    val places = placesOfTheNode.findFirstMatchIn(line).toList.flatMap(node => place.findAllIn(node.group(1)).toList)
    val executions = executionPath.findFirstMatchIn(line).toList.flatMap(_.group(1).split(',').toList.map(_.toInt))
    val helper = places.indexWhere(_.startsWith("""{"suiteId":"samples.HelperSuite","""))
    helper >= 0 && executions.drop(helper + 1).contains(2)
  }

  private def eventsOfASecondExecutionUnderAHelper(lines: Seq[String]): Int =
    lines.count(line => line.contains(""""suiteId":"samples.HelperSuite"""") && line.contains(""""item":"event"""") && isOfASecondExecutionUnderAHelper(line))

  private def helperStructure(helper: NodePath): String =
    TestEventLogLine.structure(
      TestEventLogLine.nestedSuiteWithoutPosition(helper, "HelperSuite", "samples.HelperSuite"),
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
          TestEventLogLine.topLevelSuite("OuterSuite", "samples.OuterSuite"),
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
      ),
      Map(top -> ofASuite, usesAHelper -> ofATest, helperScope(helper) -> ofAScope, helps(helper) -> ofATest)
    )
  }

  private val usesAHelperOnAThread = NodePath.topLevel("samples.HelperOnAThreadSuite").test("uses a helper")
  private val helperOnAThread = helperUnder(usesAHelperOnAThread)
  private val helpsOnAThread = helperOnAThread.test("A helper helps")

  private val helperOnAThreadSuite = {
    val top = usesAHelperOnAThread.parent
    StatedRun(
      Vector("RunStarting", "SuiteStarting HelperOnAThreadSuite", "TestStarting uses a helper") ++ reportedOfAHelper ++
        Vector("TestSucceeded uses a helper", "SuiteCompleted HelperOnAThreadSuite", "RunCompleted"),
      """
      structure
        suite "HelperOnAThreadSuite", class "samples.HelperOnAThreadSuite"
          test "uses a helper", label "- uses a helper"
      test "uses a helper": TestStarting
      structure under test "uses a helper"
        suite "HelperSuite", class "samples.HelperSuite"
          test "A helper helps", label "- A helper helps"
      test "A helper helps": TestStarting
      test "A helper helps": TestSucceeded
      test "uses a helper": TestSucceeded
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("HelperOnAThreadSuite", "samples.HelperOnAThreadSuite"),
          TestEventLogLine.test(usesAHelperOnAThread, top, 0, "uses a helper", "- uses a helper")
        ),
        nodeEvent(usesAHelperOnAThread, List(1, 1), "TestStarting"),
        TestEventLogLine.structure(
          TestEventLogLine.nestedSuiteWithoutPosition(helperOnAThread, "HelperSuite", "samples.HelperSuite"),
          TestEventLogLine.test(helpsOnAThread, helperOnAThread, 0, "A helper helps", "- A helper helps")
        ),
        nodeEvent(helpsOnAThread, List(1, 1, 1, 1), "TestStarting"),
        nodeEvent(helpsOnAThread, List(1, 1, 1, 1), "TestSucceeded"),
        nodeEvent(usesAHelperOnAThread, List(1, 1), "TestSucceeded")
      ),
      Map(top -> ofASuite, usesAHelperOnAThread -> ofATest, helperOnAThread -> ofAScope, helpsOnAThread -> ofATest)
    )
  }

  private val userSuitesTop = NodePath.topLevel("samples.UserSuites")
  private val user1 = userSuitesTop.suite("samples.User1Suite")
  private val user2 = userSuitesTop.suite("samples.User2Suite")
  private val usesOne = user1.test("uses one")
  private val usesTwo = user2.test("uses two")

  private val userSuites = {
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
          TestEventLogLine.topLevelSuite("UserSuites", "samples.UserSuites"),
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
      ),
      Map(
        userSuitesTop -> ofASuite,
        user1 -> ofASuite,
        user2 -> ofASuite,
        usesOne -> ofATest,
        usesTwo -> ofATest,
        helperScope(helperUnder(usesOne)) -> ofAScope,
        helps(helperUnder(usesOne)) -> ofATest,
        helperScope(helperUnder(usesTwo)) -> ofAScope,
        helps(helperUnder(usesTwo)) -> ofATest
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
          TestEventLogLine.topLevelSuite("TwoHelpersSuite", "samples.TwoHelpersSuite"),
          TestEventLogLine.test(first, top, 0, "first", "- first"),
          TestEventLogLine.test(second, top, 1, "second", "- second")
        ),
        nodeEvent(first, List(1, 1), "TestStarting")
      ) ++ helperEvents(first) ++ List(
        nodeEvent(first, List(1, 1), "TestSucceeded"),
        nodeEvent(second, List(1, 1), "TestStarting")
      ) ++ helperEvents(second) ++ List(
        nodeEvent(second, List(1, 1), "TestSucceeded")
      ),
      Map(
        top -> ofASuite,
        first -> ofATest,
        second -> ofATest,
        helperScope(helperUnder(first)) -> ofAScope,
        helps(helperUnder(first)) -> ofATest,
        helperScope(helperUnder(second)) -> ofAScope,
        helps(helperUnder(second)) -> ofATest
      )
    )
  }

  private val flaky = NodePath.topLevel("samples.RetryRunsAHelper").test("flaky")

  private val retryRunsAHelper = {
    val top = flaky.parent
    val helper = helperUnder(flaky)
    def helperEvents(executions: List[Int]): List[String] =
      List(
        nodeEvent(helperScope(helper), executions, "ScopeOpened"),
        nodeEvent(helps(helper), executions :+ executions.last, "TestStarting"),
        nodeEvent(helps(helper), executions :+ executions.last, "TestSucceeded"),
        nodeEvent(helperScope(helper), executions, "ScopeClosed")
      )
    StatedRun(
      Vector("RunStarting", "SuiteStarting RetryRunsAHelper", "TestStarting flaky") ++ reportedOfAHelper ++
        Vector("TestFailed flaky: 1 was not greater than 1, recording InfoProvided trying", "TestStarting flaky") ++ reportedOfAHelper ++
        Vector("TestSucceeded flaky, recording InfoProvided trying", "SuiteCompleted RetryRunsAHelper", "RunCompleted"),
      """
      structure
        suite "RetryRunsAHelper", class "samples.RetryRunsAHelper"
          test "flaky", label "- flaky"
      test "flaky": TestStarting
      structure under test "flaky"
        suite "HelperSuite", class "samples.HelperSuite"
          scope "A helper", label "A helper"
            test "helps", label "- helps"
      scope "A helper", in test "flaky" execution 1: ScopeOpened
      test "helps", in test "flaky" execution 1: TestStarting
      test "helps", in test "flaky" execution 1: TestSucceeded
      scope "A helper", in test "flaky" execution 1: ScopeClosed
      test "flaky": TestFailed
      test "flaky" execution 2: TestStarting
      scope "A helper" execution 2, in test "flaky" execution 2, in suite "HelperSuite" execution 2: ScopeOpened
      test "helps" execution 2, in test "flaky" execution 2, in suite "HelperSuite" execution 2: TestStarting
      test "helps" execution 2, in test "flaky" execution 2, in suite "HelperSuite" execution 2: TestSucceeded
      scope "A helper" execution 2, in test "flaky" execution 2: ScopeClosed
      test "flaky" execution 2: TestSucceeded
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("RetryRunsAHelper", "samples.RetryRunsAHelper"),
          TestEventLogLine.test(flaky, top, 0, "flaky", "- flaky")
        ),
        nodeEvent(flaky, List(1, 1), "TestStarting"),
        helperStructure(helper)
      ) ++ helperEvents(List(1, 1, 1, 1)) ++ List(
        nodeEvent(flaky, List(1, 1), "TestFailed", """"message":"1 was not greater than 1","""),
        nodeEvent(flaky, List(1, 2), "TestStarting")
      ) ++ helperEvents(List(1, 2, 2, 2)) ++ List(
        nodeEvent(flaky, List(1, 2), "TestSucceeded")
      ),
      Map(
        top -> ofASuite,
        flaky -> Vector("TestStarting", "InfoProvided", "TestFailed", "TestStarting", "InfoProvided", "TestSucceeded"),
        helperScope(helper) -> (ofAScope ++ ofAScope),
        helps(helper) -> (ofATest ++ ofATest)
      )
    )
  }

  private val asyncOuterSuite = {
    val top = NodePath.topLevel("samples.AsyncOuterSuite")
    val usesIt = top.test("uses a helper")
    val helper = helperUnder(usesIt)
    StatedRun(
      Vector("RunStarting", "SuiteStarting AsyncOuterSuite", "TestStarting uses a helper") ++ reportedOfAHelper ++
        Vector("TestSucceeded uses a helper", "SuiteCompleted AsyncOuterSuite", "RunCompleted"),
      """
      structure
        suite "AsyncOuterSuite", class "samples.AsyncOuterSuite"
          test "uses a helper", label "- uses a helper"
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
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("AsyncOuterSuite", "samples.AsyncOuterSuite"),
          TestEventLogLine.test(usesIt, top, 0, "uses a helper", "- uses a helper")
        ),
        nodeEvent(usesIt, List(1, 1), "TestStarting"),
        helperStructure(helper),
        nodeEvent(helperScope(helper), List(1, 1, 1, 1), "ScopeOpened"),
        nodeEvent(helps(helper), List(1, 1, 1, 1, 1), "TestStarting"),
        nodeEvent(helps(helper), List(1, 1, 1, 1, 1), "TestSucceeded"),
        nodeEvent(helperScope(helper), List(1, 1, 1, 1), "ScopeClosed"),
        nodeEvent(usesIt, List(1, 1), "TestSucceeded")
      ),
      Map(top -> ofASuite, usesIt -> ofATest, helperScope(helper) -> ofAScope, helps(helper) -> ofATest)
    )
  }

  private val setupSuite = {
    val top = NodePath.topLevel("samples.SetupSuite")
    val works = top.test("works")
    val helper = top.suite("samples.HelperSuite")
    StatedRun(
      Vector("RunStarting", "SuiteStarting SetupSuite", "TestStarting works", "TestSucceeded works", "SuiteCompleted SetupSuite", "RunCompleted"),
      """
      structure
        suite "SetupSuite", class "samples.SetupSuite"
          test "works", label "- works"
      suite "SetupSuite": SuiteStarting
      structure under suite "SetupSuite"
        suite "HelperSuite", class "samples.HelperSuite"
          scope "A helper", label "A helper"
            test "helps", label "- helps"
      scope "A helper": ScopeOpened
      test "helps": TestStarting
      test "helps": TestSucceeded
      scope "A helper": ScopeClosed
      test "works": TestStarting
      test "works": TestSucceeded
      suite "SetupSuite": SuiteCompleted
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("SetupSuite", "samples.SetupSuite"),
          TestEventLogLine.test(works, top, 0, "works", "- works")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        helperStructure(helper),
        nodeEvent(helperScope(helper), List(1, 1, 1), "ScopeOpened"),
        nodeEvent(helps(helper), List(1, 1, 1, 1), "TestStarting"),
        nodeEvent(helps(helper), List(1, 1, 1, 1), "TestSucceeded"),
        nodeEvent(helperScope(helper), List(1, 1, 1), "ScopeClosed"),
        nodeEvent(works, List(1, 1), "TestStarting"),
        nodeEvent(works, List(1, 1), "TestSucceeded"),
        nodeEvent(top, List(1), "SuiteCompleted")
      ),
      Map(top -> ofASuite, works -> ofATest, helperScope(helper) -> ofAScope, helps(helper) -> ofATest)
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
          TestEventLogLine.topLevelSuite("RunsItsNestedSuites", "samples.RunsItsNestedSuites"),
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
      ),
      Map(runsItsNestedSuitesNode -> ofASuite, innerOne -> ofATest, innerTwo -> ofATest)
    )
  }

  // Framework's SuiteSortingReporter passes on no event of a nested suite that sent it no SuiteStarting.
  private val runsItsFirstNestedSuiteTwice = {
    val top = NodePath.topLevel("samples.RunsItsFirstNestedSuiteTwice")
    val declared = top.suite("samples.Inner1Suite")
    val second = top.suite("samples.Inner1Suite", 1)
    val inner2 = top.suite("samples.Inner2Suite")
    StatedRun(
      Vector("RunStarting", "SuiteStarting RunsItsFirstNestedSuiteTwice", "SuiteCompleted RunsItsFirstNestedSuiteTwice", "RunCompleted"),
      """
      structure
        suite "RunsItsFirstNestedSuiteTwice", class "samples.RunsItsFirstNestedSuiteTwice"
          suite "Inner1Suite", class "samples.Inner1Suite"
            test "inner one", label "- inner one"
          suite "Inner2Suite", class "samples.Inner2Suite"
            test "inner two", label "- inner two"
      suite "RunsItsFirstNestedSuiteTwice": SuiteStarting
      test "inner one" #1: TestStarting
      test "inner one" #1: TestSucceeded
      structure under suite "RunsItsFirstNestedSuiteTwice"
        suite "Inner1Suite" #2, class "samples.Inner1Suite"
          test "inner one", label "- inner one"
      test "inner one" #2: TestStarting
      test "inner one" #2: TestSucceeded
      suite "RunsItsFirstNestedSuiteTwice": SuiteCompleted
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("RunsItsFirstNestedSuiteTwice", "samples.RunsItsFirstNestedSuiteTwice"),
          TestEventLogLine.nestedSuite(declared, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(declared.test("inner one"), declared, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(inner2, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(inner2.test("inner two"), inner2, 0, "inner two", "- inner two")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        nodeEvent(declared.test("inner one"), List(1, 1, 1), "TestStarting"),
        nodeEvent(declared.test("inner one"), List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.structure(
          TestEventLogLine.nestedSuiteWithoutPosition(second, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(second.test("inner one"), second, 0, "inner one", "- inner one")
        ),
        nodeEvent(second.test("inner one"), List(1, 1, 1), "TestStarting"),
        nodeEvent(second.test("inner one"), List(1, 1, 1), "TestSucceeded"),
        nodeEvent(top, List(1), "SuiteCompleted")
      ),
      Map(top -> ofASuite, declared.test("inner one") -> ofATest, second.test("inner one") -> ofATest)
    )
  }

  // Framework's SuiteSortingReporter passes on no event of a nested suite that sent it no SuiteStarting.
  private val pairSuites = {
    val top = NodePath.topLevel("samples.PairSuites")
    val first = top.suite("samples.ModeSuite")
    val second = top.suite("samples.ModeSuite", 1)
    StatedRun(
      Vector("RunStarting", "SuiteStarting PairSuites", "SuiteCompleted PairSuites", "RunCompleted"),
      """
      structure
        suite "PairSuites", class "samples.PairSuites"
          suite "ModeSuite", class "samples.ModeSuite"
            test "fast", label "- fast"
          suite "ModeSuite", class "samples.ModeSuite"
            test "slow", label "- slow"
      suite "PairSuites": SuiteStarting
      structure under suite "PairSuites"
        suite "ModeSuite" #1, class "samples.ModeSuite"
          test "slow", label "- slow"
      test "slow" #2: TestStarting
      test "slow" #2: TestSucceeded
      structure under suite "PairSuites"
        suite "ModeSuite" #2, class "samples.ModeSuite"
          test "fast", label "- fast"
      test "fast" #2: TestStarting
      test "fast" #2: TestSucceeded
      suite "PairSuites": SuiteCompleted
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("PairSuites", "samples.PairSuites"),
          TestEventLogLine.nestedSuite(first, 0, "ModeSuite", "samples.ModeSuite"),
          TestEventLogLine.test(first.test("fast"), first, 0, "fast", "- fast"),
          TestEventLogLine.nestedSuite(second, 1, "ModeSuite", "samples.ModeSuite"),
          TestEventLogLine.test(second.test("slow"), second, 0, "slow", "- slow")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        TestEventLogLine.structure(
          TestEventLogLine.nestedSuite(first, 0, "ModeSuite", "samples.ModeSuite"),
          TestEventLogLine.test(first.test("slow"), first, 0, "slow", "- slow")
        ),
        nodeEvent(first.test("slow"), List(1, 1, 1), "TestStarting"),
        nodeEvent(first.test("slow"), List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.structure(
          TestEventLogLine.nestedSuite(second, 1, "ModeSuite", "samples.ModeSuite"),
          TestEventLogLine.test(second.test("fast"), second, 0, "fast", "- fast")
        ),
        nodeEvent(second.test("fast"), List(1, 1, 1), "TestStarting"),
        nodeEvent(second.test("fast"), List(1, 1, 1), "TestSucceeded"),
        nodeEvent(top, List(1), "SuiteCompleted")
      ),
      Map(top -> ofASuite, first.test("slow") -> ofATest, second.test("fast") -> ofATest)
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
          TestEventLogLine.topLevelSuite("TwiceRunPlainSuite", "samples.TwiceRunPlainSuite"),
          TestEventLogLine.test(top.test("b"), top, 0, "b", "- b"),
          TestEventLogLine.test(twiceRunA, top, 1, "a", "- a"),
          TestEventLogLine.test(top.test("c"), top, 2, "c", "- c")
        ),
        nodeEvent(twiceRunA, List(1, 1), "TestStarting"),
        nodeEvent(twiceRunA, List(1, 1), "TestSucceeded"),
        nodeEvent(twiceRunA, List(1, 2), "TestStarting"),
        nodeEvent(twiceRunA, List(1, 2), "TestSucceeded")
      ),
      Map(top -> ofASuite, twiceRunA -> (ofATest ++ ofATest))
    )
  }

  private def testSendingStraightToItsSuitesReporter(suite: String, messageAssignedTo: String, isAssignedToTest: Boolean): StatedRun = {
    val top = NodePath.topLevel(s"samples.$suite")
    val testT = top.test("t")
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
        TestEventLogLine.structure(TestEventLogLine.topLevelSuite(suite, s"samples.$suite"), TestEventLogLine.test(testT, top, 0, "t", "- t")),
        nodeEvent(testT, List(1, 1), "TestStarting"),
        if (isAssignedToTest) nodeEvent(testT, List(1, 1), "InfoProvided", direct) else nodeEvent(top, List(1), "InfoProvided", direct),
        nodeEvent(testT, List(1, 1), "TestSucceeded")
      ),
      {
        if (isAssignedToTest) {
          Map(top -> ofASuite, testT -> Vector("TestStarting", "InfoProvided", "TestSucceeded"))
        } else {
          Map(top -> Vector("SuiteStarting", "InfoProvided", "SuiteCompleted"), testT -> ofATest)
        }
      }
    )
  }

  private def nestedTestSendingStraightToTheOuterSuitesReporter(suite: String, nested: String, messageAssignedTo: String, isAssignedToTest: Boolean): StatedRun = {
    val nestedNode = NodePath.topLevel(s"samples.$suite").suite(s"samples.$nested")
    val testT = nestedNode.test("t")
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
          TestEventLogLine.topLevelSuite(suite, s"samples.$suite"),
          TestEventLogLine.nestedSuite(nestedNode, 0, nested, s"samples.$nested"),
          TestEventLogLine.test(testT, nestedNode, 0, "t", "- t")
        ),
        nodeEvent(testT, List(1, 1, 1), "TestStarting"),
        if (isAssignedToTest) nodeEvent(testT, List(1, 1, 1), "InfoProvided", direct) else nodeEvent(nestedNode, List(1, 1), "InfoProvided", direct),
        nodeEvent(testT, List(1, 1, 1), "TestSucceeded")
      ),
      {
        if (isAssignedToTest) {
          Map(nestedNode.parent -> ofASuite, nestedNode -> ofASuite, testT -> Vector("TestStarting", "InfoProvided", "TestSucceeded"))
        } else {
          Map(nestedNode.parent -> ofASuite, nestedNode -> Vector("SuiteStarting", "InfoProvided", "SuiteCompleted"), testT -> ofATest)
        }
      }
    )
  }

  private val directNamingItsSecondRun = {
    val top = NodePath.topLevel("samples.DirectNamingItsSecondRun")
    val testT = top.test("t")
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
          TestEventLogLine.topLevelSuite("DirectNamingItsSecondRun", "samples.DirectNamingItsSecondRun"),
          TestEventLogLine.test(testT, top, 0, "t", "- t")
        ),
        nodeEvent(testT, List(1, 1), "TestStarting"),
        nodeEvent(testT, List(1, 1), "TestSucceeded"),
        nodeEvent(testT, List(1, 2), "TestStarting"),
        nodeEvent(testT, List(1, 2), "InfoProvided", direct),
        nodeEvent(testT, List(1, 2), "TestSucceeded")
      ),
      Map(top -> ofASuite, testT -> Vector("TestStarting", "TestSucceeded", "TestStarting", "InfoProvided", "TestSucceeded"))
    )
  }

  private val directNamingItsTestAfterRun = {
    val top = NodePath.topLevel("samples.DirectNamingItsTestAfterRun")
    val testT = top.test("t")
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
          TestEventLogLine.topLevelSuite("DirectNamingItsTestAfterRun", "samples.DirectNamingItsTestAfterRun"),
          TestEventLogLine.test(testT, top, 0, "t", "- t")
        ),
        nodeEvent(testT, List(1, 1), "TestStarting"),
        nodeEvent(testT, List(1, 1), "TestSucceeded"),
        nodeEvent(testT, List(1, 1), "InfoProvided", direct)
      ),
      Map(top -> ofASuite, testT -> Vector("TestStarting", "TestSucceeded", "InfoProvided"))
    )
  }

  private def runAndDecoderScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    nestedSuiteIds: List[String] = Nil,
    alsoGiven: List[String] = Nil
  )(alsoThen: Vector[String] => Unit = _ => ()): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      alsoGiven.foreach(step => And(step))
      When("a build tool runs it without the runner argument")
      val reportedWithoutTheArgument = ReportedEvents.of(eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, Nil, nestedSuiteIds))

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reportedWithoutTheArgument shouldBe stated.reported

      TemporaryEventLog.in { eventLogFile =>
        When("a build tool runs it with the runner argument")
        eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, runnerArgument(eventLogFile), nestedSuiteIds)
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then(s"the event log holds, in order, $eventLogHolds")
        StatedLines.mismatchWhenHoldsInOrder(lines, stated.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenHoldsInOrder(decoded(lines), built(stated.eventLogInNotation)) shouldBe None
        assertNoEventIsAssignedToAnAncestor(lines, stated)
        alsoThen(lines)
      }
    }

    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log")
      val events = decoded(stated.eventLogInFormat)

      Then(s"it yields $eventLogHolds")
      EventLogComparison.mismatchWhenIs(events, built(stated.eventLogInNotation)) shouldBe None
    }
  }

  Feature("A suite that runs another instance of itself") {

    runAndDecoderScenarios(
      "A custom @WrapWith wrapper runs the class it wraps",
      "a suite annotated @WrapWith(classOf[DelegatingSuite]) declaring scope 'A Stack' holding test 'pops', where " +
        "DelegatingSuite gives the wrapped instance's names and tests as its own and runs that instance in its place",
      "samples.StackSpec",
      "the wrapper's structure, the suite's SuiteStarting, then a further structure of the wrapped instance's tree, " +
        "in which 'pops' keeps the node path of the wrapper's 'A Stack pops', before the instance's events",
      stackSpec
    ) { lines =>
      And("no structure holds a suite other than StackSpec")
      structuresIn(lines) shouldBe 2
      structuresHolding("StackSpec", lines) shouldBe 2
    }
  }

  Feature("A suite's class name") {

    runAndDecoderScenarios(
      "A suite annotated @WrapWith with a wrapper other than ConfigMapWrapperSuite",
      "an AnyFunSpec annotated @WrapWith(classOf[DelegatingSuite]) declaring scope 'A Stack' holding test 'pops', where " +
        "DelegatingSuite gives the wrapped instance's names and tests as its own and runs that instance in its place",
      "samples.StackSpec",
      "a structure holding the suite, giving it the wrapper's class name, and after it the suite's SuiteCompleted",
      stackSpec
    ) { lines =>
      And("every structure holding StackSpec gives it the wrapper's class name")
      val structuresOfStackSpec = lines.filter(line => line.contains(""""item":"structure"""") && line.contains(""""name":"StackSpec""""))
      structuresOfStackSpec should not be empty
      structuresOfStackSpec.filterNot(_.contains(""""name":"StackSpec","suiteId":"samples.StackSpec","className":"samples.DelegatingSuite"""")) shouldBe empty
    }
  }

  Feature("A suite run from a test") {

    runAndDecoderScenarios(
      "A test runs a helper",
      "a suite whose one test, 'uses a helper', runs a HelperSuite with the Args its test is run with",
      "samples.OuterSuite",
      "the helper's structure under the test that ran it, then its events, assigned to its own nodes and inside the test that ran it",
      outerSuite,
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("no SuiteStarting or SuiteCompleted is assigned to HelperSuite")
      suiteEventsOf("samples.HelperSuite", lines) shouldBe 0
    }

    runAndDecoderScenarios(
      "Tests of two nested suites each run a helper",
      "a Suites nesting User1Suite and User2Suite, whose one test each runs a HelperSuite",
      "samples.UserSuites",
      "a HelperSuite under each nested suite's test, each holding the events of the helper run there",
      userSuites,
      nestedSuiteIds = List("samples.User1Suite", "samples.User2Suite", helperSelected)
    ) { lines =>
      And("the event log holds no other structure holding HelperSuite")
      structuresHolding("HelperSuite", lines) shouldBe 2
    }

    runAndDecoderScenarios(
      "Two tests each run a helper",
      "a suite whose tests 'first' and 'second' each run a HelperSuite",
      "samples.TwoHelpersSuite",
      "a HelperSuite under each test, each holding the events of the helper run there",
      twoHelpersSuite,
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("the event log holds no other structure holding HelperSuite, and no event is of a second execution of 'A helper' or 'helps'")
      structuresHolding("HelperSuite", lines) shouldBe 2
      eventsOfASecondExecutionUnderAHelper(lines) shouldBe 0
    }

    runAndDecoderScenarios(
      "A test run twice runs a helper each time",
      "a suite whose runTest runs its test 'flaky' again where it fails, and whose test runs a HelperSuite, then fails the first time it runs",
      "samples.RetryRunsAHelper",
      "one HelperSuite under the test, with an execution under each of the test's executions",
      retryRunsAHelper,
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("the event log holds no other structure holding HelperSuite")
      structuresHolding("HelperSuite", lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "An async test runs a helper inside a Future",
      "an async suite whose one test, 'uses a helper', runs a HelperSuite inside a Future on the suite's own ExecutionContext",
      "samples.AsyncOuterSuite",
      "the helper's structure under the test that ran it, then its events, assigned to its own nodes and inside the test that ran it",
      asyncOuterSuite,
      nestedSuiteIds = List(helperSelected)
    )()

    runAndDecoderScenarios(
      "A test runs a helper on a thread of its own",
      "a suite whose one test, 'uses a helper', runs a HelperSuite on a thread it starts, and waits for that thread to end",
      "samples.HelperOnAThreadSuite",
      "the helper's structure under the test that ran it, holding its tests directly, then its tests' events, inside the test that ran it",
      helperOnAThreadSuite,
      nestedSuiteIds = List(helperSelected)
    ) { lines =>
      And("no structure holds a scope under HelperSuite")
      lines.count(line => line.contains(""""suiteId":"samples.HelperSuite"""") && line.contains(""""kind":"scope"""")) shouldBe 0
    }
  }

  Feature("Nested suites run in other ways") {

    runAndDecoderScenarios(
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

    runAndDecoderScenarios(
      "A runNestedSuites override runs one nested suite twice",
      "a Suites nesting Inner1Suite and Inner2Suite whose runNestedSuites runs Inner1Suite twice and Inner2Suite never, sending no event",
      "samples.RunsItsFirstNestedSuiteTwice",
      "the structure, the first run's events on the declared Inner1Suite, then a further Inner1Suite beside it, whose own structure precedes the second run's events",
      runsItsFirstNestedSuiteTwice,
      nestedSuiteIds = List("samples.Inner1Suite", "samples.Inner2Suite")
    ) { lines =>
      And("the event log holds no other structure")
      structuresIn(lines) shouldBe 2
    }

    runAndDecoderScenarios(
      "A suite's beforeAll runs a helper",
      "suite SetupSuite, an AnyFunSuite mixing in BeforeAndAfterAll, whose one test, 'works', succeeds",
      "samples.SetupSuite",
      "the helper's structure under the suite, then its events, before those of the suite's own test",
      setupSuite,
      nestedSuiteIds = List(helperSelected),
      alsoGiven = List(
        "suite class HelperSuite, an AnyFunSpec declaring scope 'A helper' holding test 'helps', which succeeds",
        "the beforeAll of SetupSuite calls new HelperSuite().run(None, args) with Args holding a reporter that drops every event"
      )
    )()

    runAndDecoderScenarios(
      "A runNestedSuites override runs two suites sharing an ID in reverse order",
      "a Suites nesting ModeSuite(\"fast\") and then ModeSuite(\"slow\"), whose runNestedSuites runs them itself in reverse order, sending no event",
      "samples.PairSuites",
      "the structure, then each ModeSuite at the place its start gives it, with a further structure of its own test before that test's events",
      pairSuites,
      nestedSuiteIds = List("samples.ModeSuite")
    )()
  }

  Feature("A test run more than once") {

    runAndDecoderScenarios(
      "A suite with no registration tree reports a test twice",
      "a Suite with no registration tree that runs test 'a' twice when it runs its tests, sending the test's TestStarting and TestSucceeded itself",
      "samples.TwiceRunPlainSuite",
      "test 'a' in a first execution and then a second",
      twiceRunPlainSuite
    )()
  }

  Feature("Messages sent straight to a reporter") {

    runAndDecoderScenarios(
      "A test reports to its suite's reporter, with no nameInfo",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided with no nameInfo",
      "samples.DirectNamelessSuite",
      "the message assigned to the suite, between the test's events",
      testSendingStraightToItsSuitesReporter("DirectNamelessSuite", "suite \"DirectNamelessSuite\"", isAssignedToTest = false)
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "A test reports to the reporter its suite keeps, naming its suite",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided naming the suite and no test",
      "samples.DirectNamingItsSuite",
      "the message assigned to the suite",
      testSendingStraightToItsSuitesReporter("DirectNamingItsSuite", "suite \"DirectNamingItsSuite\"", isAssignedToTest = false)
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "A test reports to the reporter the suite nesting it keeps, naming its suite",
      "a Suites whose run keeps args.reporter, nesting a suite whose test 't' sends that reporter an InfoProvided naming the nested suite and no test",
      "samples.DirectSuitesNamingTheSuite",
      "the message assigned to the nested suite",
      nestedTestSendingStraightToTheOuterSuitesReporter("DirectSuitesNamingTheSuite", "NestedDirectNamingItsSuite", "suite \"NestedDirectNamingItsSuite\"", isAssignedToTest = false),
      nestedSuiteIds = List("samples.NestedDirectNamingItsSuite")
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "A test reports to the reporter its suite keeps, naming itself",
      "a suite whose run keeps args.reporter, and whose test 't' sends that reporter an InfoProvided naming the suite and 't'",
      "samples.DirectNamingItsTest",
      "the message assigned to test 't', in its open execution",
      testSendingStraightToItsSuitesReporter("DirectNamingItsTest", "test \"t\"", isAssignedToTest = true)
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "A test reports to the reporter the suite nesting it keeps, naming itself",
      "a Suites whose run keeps args.reporter, nesting a suite whose test 't' sends that reporter an InfoProvided naming the nested suite and 't'",
      "samples.DirectSuitesNamingTheTest",
      "the message assigned to test 't' of the nested suite, in its open execution",
      nestedTestSendingStraightToTheOuterSuitesReporter("DirectSuitesNamingTheTest", "NestedDirectNamingItsTest", "test \"t\"", isAssignedToTest = true),
      nestedSuiteIds = List("samples.NestedDirectNamingItsTest")
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "The test sends the message the second time it runs",
      "a suite whose runTest runs test 't' twice, and whose test sends the reporter its suite keeps an InfoProvided naming 't' only the second time",
      "samples.DirectNamingItsSecondRun",
      "the message in the test's second execution",
      directNamingItsSecondRun
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }

    runAndDecoderScenarios(
      "The message is sent after the test has closed",
      "a suite whose run, after super.run returns, sends the reporter it was given an InfoProvided naming test 't'",
      "samples.DirectNamingItsTestAfterRun",
      "the message in the test's latest execution, after the test closed",
      directNamingItsTestAfterRun
    ) { lines =>
      And("the event log holds no other InfoProvided")
      infosIn(lines) shouldBe 1
    }
  }
}
