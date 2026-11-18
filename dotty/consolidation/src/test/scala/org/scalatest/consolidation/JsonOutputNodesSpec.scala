package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.JsonValue
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class JsonOutputNodesSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns with JsonOutputs {

  private def jsonOutputOf(eventLog: String): JsonSink = jsonOutputOfRun(TestRunPart.Events(eventLog))

  private def rowSuiteRunning(eventLinesBetweenSuiteStartAndCompletion: String*): String =
    s"""
    run started
    run: RunStarting
    structure
      suite "RowSuite", class "samples.RowSuite"
        test "adds", label "- adds"
    suite "RowSuite": SuiteStarting
    ${eventLinesBetweenSuiteStartAndCompletion.mkString("\n    ")}
    suite "RowSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """

  private def namesOf(nodes: JsonValue): Vector[JsonValue] = nodes.items.map(_("name"))

  Feature("Suites, scopes and tests in the JSON output") {

    Scenario("A failed test holds its outcome and its closing event's message") {
      Given("a run of suite RowSuite whose test adds fails with the message `2 did not equal 3`")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting""",
        """test "adds": TestFailed(message: "2 did not equal 3")"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome failed and the message")
      adds("outcome") shouldBe text("failed")
      adds("message") shouldBe text("2 did not equal 3")
    }

    Scenario("An ignored test has the end time and thread of its TestIgnored, and no start time or duration") {
      Given("a run of suite RowSuite whose test adds is registered with ignore")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            test "adds", label "- adds", ignored
        suite "RowSuite": SuiteStarting
        test "adds": TestIgnored(threadName: "pool-1", timeStamp: 140)
        suite "RowSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome ignored, an endedAt and a threadName, with no startedAt and no duration")
      adds("outcome") shouldBe text("ignored")
      adds("endedAt") shouldBe integer(140L)
      adds("threadName") shouldBe text("pool-1")
      adds.keys should contain noneOf ("startedAt", "duration")
    }

    Scenario("A pending test holds the outcome pending") {
      Given("a run of suite RowSuite whose test adds runs pending")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting""",
        """test "adds": TestPending"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome pending")
      adds("outcome") shouldBe text("pending")
    }

    Scenario("A scope that closed holds its name, label, location and times, with no outcome or thread, and its test") {
      Given("a run of an AnyFunSpec holding `describe(\"A Stack\") { it(\"pops\") { } }`, the describe at line 6")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "RowSuite": SuiteStarting
        scope "A Stack": ScopeOpened(location: LineInFile(6, "RowSuite.scala", Some("/build/samples/RowSuite.scala")), timeStamp: 100)
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed(timeStamp: 130)
        suite "RowSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val rowSuite = jsonOutputOf(eventLog).node("suite", "RowSuite")

      Then("suite RowSuite's contents is one scope with the scope's values and a duration of its endedAt less its startedAt")
      val scope = rowSuite("contents").only
      scope("kind") shouldBe text("scope")
      scope("name") shouldBe text("A Stack")
      scope("label") shouldBe text("A Stack")
      scope("location") shouldBe objectOf("filePath" -> text("/build/samples/RowSuite.scala"), "lineNumber" -> integer(6L))
      scope("startedAt") shouldBe integer(100L)
      scope("endedAt") shouldBe integer(130L)
      scope("duration") shouldBe integer(30L)
      scope.keys should contain noneOf ("outcome", "threadName")

      And("the scope's contents is test pops, labelled `- pops`")
      val pops = scope("contents").only
      pops("name") shouldBe text("pops")
      pops("label") shouldBe text("- pops")
    }

    Scenario("A pending scope holds the outcome pending") {
      Given("a run of an AnyFunSpec holding `describe(\"A Queue\") { pending }`")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            scope "A Queue", label "A Queue"
        suite "RowSuite": SuiteStarting
        scope "A Queue": ScopeOpened
        scope "A Queue": ScopePending
        suite "RowSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val scope = jsonOutputOf(eventLog).node("scope", "A Queue")

      Then("scope A Queue holds the outcome pending")
      scope("outcome") shouldBe text("pending")
    }

    Scenario("A suite that aborts before its tests run is aborted, and its test is aborted with reason notRun") {
      Given("a run of suite RowSuite, whose beforeAll throws `no fixture` before its one test adds starts")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            test "adds", label "- adds"
        suite "RowSuite": SuiteStarting
        suite "RowSuite": SuiteAborted(message: "no fixture")
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val json = jsonOutputOf(eventLog)

      Then("suite RowSuite holds the outcome aborted")
      json.node("suite", "RowSuite")("outcome") shouldBe text("aborted")

      And("test adds holds the outcome aborted and the reason notRun, with no startedAt or message")
      val adds = json.node("test", "adds")
      adds("outcome") shouldBe text("aborted")
      adds("reason") shouldBe text("notRun")
      adds.keys should contain noneOf ("startedAt", "message")
    }

    Scenario("A suite nested by another sits in that suite's contents, with its test in its own") {
      Given("a run of `class RowSuite extends Suites(new InnerSuite)`, whose InnerSuite's one test adds succeeds")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            suite "InnerSuite", class "samples.InnerSuite"
              test "adds", label "- adds"
        suite "RowSuite": SuiteStarting
        suite "InnerSuite": SuiteStarting
        test "adds": TestStarting
        test "adds": TestSucceeded
        suite "InnerSuite": SuiteCompleted
        suite "RowSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val runNode = jsonOutputOf(eventLog).runNode

      Then("the run node's contents is suite RowSuite, whose contents is suite InnerSuite, whose contents is test adds")
      val rowSuite = runNode("contents").only
      rowSuite("name") shouldBe text("RowSuite")
      val innerSuite = rowSuite("contents").only
      innerSuite("kind") shouldBe text("suite")
      innerSuite("name") shouldBe text("InnerSuite")
      val adds = innerSuite("contents").only
      adds("kind") shouldBe text("test")
      adds("name") shouldBe text("adds")
    }

    Scenario("A test executed twice is two nodes, in the order they started") {
      Given("a run of suite RowSuite whose runTest override runs its one test adds twice")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting(timeStamp: 100)""",
        """test "adds": TestSucceeded(timeStamp: 110)""",
        """test "adds" execution 2: TestStarting(timeStamp: 120)""",
        """test "adds" execution 2: TestSucceeded(timeStamp: 130)"""
      )

      When("the library writes the JSON output")
      val tests = jsonOutputOf(eventLog).node("suite", "RowSuite")("contents").items

      Then("suite RowSuite's contents is two succeeded tests named adds, the first started no later than the second")
      tests.map(_("name")) shouldBe Vector(text("adds"), text("adds"))
      tests.map(_("outcome")) shouldBe Vector(text("succeeded"), text("succeeded"))
      tests.map(_("startedAt")) shouldBe Vector(integer(100L), integer(120L))
    }

    Scenario("A run's top-level suites are ordered by their names' code units, so an uppercase name sorts before a lowercase one") {
      Given("a run of suite alphaSuite, class samples.AlphaSuite, followed by suite ZebraSuite, class samples.ZebraSuite")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "alphaSuite", class "samples.AlphaSuite"
            test "alpha test", label "- alpha test"
        suite "alphaSuite": SuiteStarting
        test "alpha test": TestStarting
        test "alpha test": TestSucceeded
        suite "alphaSuite": SuiteCompleted
        structure
          suite "ZebraSuite", class "samples.ZebraSuite"
            test "zebra test", label "- zebra test"
        suite "ZebraSuite": SuiteStarting
        test "zebra test": TestStarting
        test "zebra test": TestSucceeded
        suite "ZebraSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val runNode = jsonOutputOf(eventLog).runNode

      Then("the run node's contents is suite ZebraSuite then suite alphaSuite, unlike their start order, their classes' order and their names' order ignoring case")
      namesOf(runNode("contents")) shouldBe Vector(text("ZebraSuite"), text("alphaSuite"))
    }

    Scenario("A suite's tests are in declared order, not the order they ran") {
      Given("a run of suite RowSuite whose tests subtracts and adds are registered in that order, and adds runs first")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            test "subtracts", label "- subtracts"
            test "adds", label "- adds"
        suite "RowSuite": SuiteStarting
        test "adds": TestStarting
        test "adds": TestSucceeded
        test "subtracts": TestStarting
        test "subtracts": TestSucceeded
        suite "RowSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val rowSuite = jsonOutputOf(eventLog).node("suite", "RowSuite")

      Then("suite RowSuite's contents is test subtracts then test adds")
      namesOf(rowSuite("contents")) shouldBe Vector(text("subtracts"), text("adds"))
    }

    Scenario("Each node is identified by its place in the tree, with no node ID written") {
      Given("a run of suite RowSuite, holding scope A Stack, holding test pops, which succeeds")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "RowSuite": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        suite "RowSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val json = jsonOutputOf(eventLog)

      Then("each node holds only keys of the layout, none of which identifies it")
      val layoutKeys = Set("kind", "name", "label", "suiteId", "location", "outcome", "reason", "message", "throwable", "startedAt", "endedAt", "duration", "threadName", "contents", "output")
      Vector(json.node("suite", "RowSuite"), json.node("scope", "A Stack"), json.node("test", "pops")).foreach { found =>
        found.keys.toSet.diff(layoutKeys) shouldBe empty
      }
    }

    Scenario("A suite's start and completion assigned to the run, as a DiscoverySuite's are, contribute to no node") {
      Given("a run with a SuiteStarting and a SuiteCompleted assigned to the run, around suite RowSuite")
      val eventLog =
        """
        run started
        run: RunStarting
        run: SuiteStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            test "adds", label "- adds"
        suite "RowSuite": SuiteStarting
        test "adds": TestStarting
        test "adds": TestSucceeded
        suite "RowSuite": SuiteCompleted
        run: SuiteCompleted
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val runNode = jsonOutputOf(eventLog).runNode

      Then("the run node's contents is suite RowSuite alone")
      namesOf(runNode("contents")) shouldBe Vector(text("RowSuite"))
    }

    Scenario("A test aborted by its DistributedTestRunnerSuite before it started holds that SuiteAborted's message and end time") {
      Given("a run of suite RowSuite whose test adds holds only a SuiteAborted, as when a ParallelTestExecution suite's beforeEach throws")
      val eventLog = rowSuiteRunning(
        """test "adds": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - no fixture", timeStamp: 140)"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome aborted, the SuiteAborted's message and its time as endedAt, with no reason")
      adds("outcome") shouldBe text("aborted")
      adds("message") shouldBe text("Exception encountered when invoking run on a nested suite - no fixture")
      adds("endedAt") shouldBe integer(140L)
      adds.keys should not contain "reason"
    }

    Scenario("A test aborted by its DistributedTestRunnerSuite after it started holds that SuiteAborted's message and both times") {
      Given("a run of suite RowSuite whose test adds starts, then holds a SuiteAborted")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting(timeStamp: 100)""",
        """test "adds": SuiteAborted(message: "no fixture", timeStamp: 140)"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome aborted, the SuiteAborted's message, and both times")
      adds("outcome") shouldBe text("aborted")
      adds("message") shouldBe text("no fixture")
      adds("startedAt") shouldBe integer(100L)
      adds("endedAt") shouldBe integer(140L)
    }
  }
}
