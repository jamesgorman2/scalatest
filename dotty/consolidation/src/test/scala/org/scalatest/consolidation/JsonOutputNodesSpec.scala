package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.DecodedEvent
import org.scalatest.events.JsonValue
import org.scalatest.events.TestEventLogEvent
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

  private def rowSuiteWithTest(testName: String, eventLinesOfTest: String*): String =
    s"""
    run started
    run: RunStarting
    structure
      suite "RowSuite", class "samples.RowSuite"
        test "$testName", label "- $testName"
    suite "RowSuite": SuiteStarting
    test "$testName": TestStarting
    ${eventLinesOfTest.map(line => "test \"" + testName + "\": " + line).mkString("\n    ")}
    suite "RowSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """

  private def frameKeysOf(throwable: JsonValue): Vector[Vector[String]] = throwable("stackTrace").items.map(_.keys)

  private def withoutStackTraces(throwable: JsonValue): JsonValue =
    throwable match {
      case JsonValue.Object(fields) =>
        JsonValue.Object(fields.collect {
          case ("stackTrace", frames) if frames.items.nonEmpty => "stackTrace" -> text("<frames>")
          case (key, value) => key -> withoutStackTraces(value)
        })
      case JsonValue.Array(items) => JsonValue.Array(items.map(withoutStackTraces))
      case other => other
    }

  Feature("Suites, scopes and tests in the JSON output") {

    Scenario("A failed test holds its outcome, its closing event's message, and its TestFailedException with the failed code's location") {
      Given("a run of suite RowSuite whose test adds fails at line 7 with a TestFailedException `2 did not equal 3`")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting""",
        """test "adds": TestFailed(message: "2 did not equal 3", throwable: org.scalatest.exceptions.TestFailedException "2 did not equal 3" at /build/samples/RowSuite.scala:7)"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome failed and the message")
      adds("outcome") shouldBe text("failed")
      adds("message") shouldBe text("2 did not equal 3")

      And("its throwable, in full, is the TestFailedException with the failed code's location and no stack trace")
      adds("throwable") shouldBe objectOf(
        "id" -> integer(1L),
        "className" -> text("org.scalatest.exceptions.TestFailedException"),
        "message" -> text("2 did not equal 3"),
        "location" -> objectOf("filePath" -> text("/build/samples/RowSuite.scala"), "lineNumber" -> integer(7L))
      )
    }

    Scenario("A canceled test holds its outcome, its closing event's message and its TestCanceledException") {
      Given("a run of suite RowSuite whose test adds runs `cancel(\"no database\")`")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting""",
        """test "adds": TestCanceled(message: "no database", throwable: org.scalatest.exceptions.TestCanceledException "no database" at /build/samples/RowSuite.scala:7)"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome canceled, the message, and a TestCanceledException")
      adds("outcome") shouldBe text("canceled")
      adds("message") shouldBe text("no database")
      adds("throwable")("className") shouldBe text("org.scalatest.exceptions.TestCanceledException")
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

      Then("suite RowSuite's contents is scope A Stack, with its label, location and times, a duration of endedAt less startedAt, and no outcome or thread")
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
        suite "RowSuite": SuiteAborted(message: "no fixture", throwable: java.lang.IllegalStateException "no fixture" frames ["samples.RowSuite.beforeAll(RowSuite.scala:5)"])
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val json = jsonOutputOf(eventLog)

      Then("suite RowSuite holds the outcome aborted, and the IllegalStateException with its message and stack trace")
      val rowSuite = json.node("suite", "RowSuite")
      rowSuite("outcome") shouldBe text("aborted")
      rowSuite("throwable")("className") shouldBe text("java.lang.IllegalStateException")
      rowSuite("throwable")("message") shouldBe text("no fixture")
      rowSuite("throwable")("stackTrace").items should not be empty

      And("test adds holds the outcome aborted and the reason notRun, with no startedAt, message or throwable")
      val adds = json.node("test", "adds")
      adds("outcome") shouldBe text("aborted")
      adds("reason") shouldBe text("notRun")
      adds.keys should contain noneOf ("startedAt", "message", "throwable")
    }

    Scenario("A test's info, note, alert and markup are its output, in the order sent, each without the thread it shares with the test") {
      Given("a run of suite RowSuite whose test talks calls info(\"one\"), note(\"two\"), alert(\"three\") and markup(\"four\"), in that order")
      val eventLog = rowSuiteWithTest(
        "talks",
        """InfoProvided(message: "one", timeStamp: 101)""",
        """NoteProvided(message: "two", timeStamp: 102)""",
        """AlertProvided(message: "three", timeStamp: 103)""",
        """MarkupProvided(text: "four", timeStamp: 104)""",
        "TestSucceeded"
      )

      When("the library writes the JSON output")
      val output = jsonOutputOf(eventLog).node("test", "talks")("output").items

      Then("test talks's output is the four messages in order, each with its kind, message and time, and none with a threadName")
      output shouldBe Vector(
        objectOf("kind" -> text("info"), "message" -> text("one"), "at" -> integer(101L)),
        objectOf("kind" -> text("note"), "message" -> text("two"), "at" -> integer(102L)),
        objectOf("kind" -> text("alert"), "message" -> text("three"), "at" -> integer(103L)),
        objectOf("kind" -> text("markup"), "message" -> text("four"), "at" -> integer(104L))
      )
    }

    Scenario("A message sent from another thread than its test's holds that thread's name") {
      Given("a run of suite RowSuite whose test talks, on thread main, runs info(\"away\") on thread pool-2")
      val eventLog = rowSuiteWithTest("talks", """InfoProvided(message: "away", threadName: "pool-2")""", "TestSucceeded")

      When("the library writes the JSON output")
      val talks = jsonOutputOf(eventLog).node("test", "talks")

      Then("test talks's output is one info holding the message and thread pool-2, which is not the test's thread")
      val info = talks("output").only
      info("kind") shouldBe text("info")
      info("message") shouldBe text("away")
      info("threadName") shouldBe text("pool-2")
      talks("threadName") shouldBe text("main")
    }

    Scenario("A throwable holds its cause and its suppressed throwables, numbered depth-first") {
      Given("a run of suite RowSuite whose test throws throws a RuntimeException `outer`, caused by an IOException `inner`, suppressing an IllegalStateException `closing`")
      val eventLog = rowSuiteWithTest(
        "throws",
        """TestFailed(throwable: java.lang.RuntimeException "outer" frames ["samples.RowSuite.throws(RowSuite.scala:9)"] suppressing [java.lang.IllegalStateException "closing" frames ["samples.RowSuite.close(RowSuite.scala:12)"]] caused by java.io.IOException "inner" frames ["samples.RowSuite.read(RowSuite.scala:15)"])"""
      )

      When("the library writes the JSON output")
      val throws = jsonOutputOf(eventLog).node("test", "throws")

      Then("test throws holds the outcome failed and, in full, the throwable with its cause numbered 2 and its suppressed throwable numbered 3")
      throws("outcome") shouldBe text("failed")
      withoutStackTraces(throws("throwable")) shouldBe objectOf(
        "id" -> integer(1L),
        "className" -> text("java.lang.RuntimeException"),
        "message" -> text("outer"),
        "stackTrace" -> text("<frames>"),
        "cause" -> objectOf("id" -> integer(2L), "className" -> text("java.io.IOException"), "message" -> text("inner"), "stackTrace" -> text("<frames>")),
        "suppressed" -> arrayOf(
          objectOf("id" -> integer(3L), "className" -> text("java.lang.IllegalStateException"), "message" -> text("closing"), "stackTrace" -> text("<frames>"))
        )
      )
    }

    Scenario("A throwable whose cause chain loops back to it ends the loop with a ref") {
      Given("a run of suite RowSuite whose test loops throws b, a RuntimeException caused by a, whose cause is b")
      val events = eventsOf(
        rowSuiteWithTest(
          "loops",
          """TestFailed(throwable: java.lang.RuntimeException "b" frames ["samples.RowSuite.loops(RowSuite.scala:9)"] caused by java.lang.RuntimeException "a" frames ["samples.RowSuite.loops(RowSuite.scala:8)"])"""
        )
      )
      val b = events.collectFirst { case TestEventLogEvent.ScalaTestEvent(_, failed: DecodedEvent.TestFailed) => failed.throwable.get }.get
      val a = b.cause.get
      a.link(Some(b), Vector.empty)

      When("the library writes the JSON output")
      val loops = jsonOutputOfEvents(events).node("test", "loops")

      Then("test loops holds, in full, b, then a as its cause, then a ref to b as a's cause")
      withoutStackTraces(loops("throwable")) shouldBe objectOf(
        "id" -> integer(1L),
        "className" -> text("java.lang.RuntimeException"),
        "message" -> text("b"),
        "stackTrace" -> text("<frames>"),
        "cause" -> objectOf(
          "id" -> integer(2L),
          "className" -> text("java.lang.RuntimeException"),
          "message" -> text("a"),
          "stackTrace" -> text("<frames>"),
          "cause" -> objectOf("ref" -> integer(1L))
        )
      )
    }

    Scenario("A throwable with no message has no message key, and its frames hold no isNative false") {
      Given("a run of suite RowSuite whose test throws throws `new RuntimeException()`")
      val eventLog = rowSuiteWithTest(
        "throws",
        """TestFailed(message: "java.lang.RuntimeException was thrown.", throwable: java.lang.RuntimeException null frames ["samples.RowSuite.throws(RowSuite.scala:9)", "org.scalatest.Transformer.apply(Transformer.scala:22)"])"""
      )

      When("the library writes the JSON output")
      val throwable = jsonOutputOf(eventLog).node("test", "throws")("throwable")

      Then("the throwable has no message key")
      throwable.keys should not contain "message"

      And("each frame holds className and methodName, and none holds isNative false")
      frameKeysOf(throwable).foreach(_ should contain allOf ("className", "methodName"))
      throwable("stackTrace").items.foreach(_.get("isNative") should not contain JsonValue.Bool(false))
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

    Scenario("A test that succeeded, then was aborted by its DistributedTestRunnerSuite, is aborted with that SuiteAborted's message, end time and duration") {
      Given("a run of suite RowSuite whose test adds succeeds, then holds a SuiteAborted, as when a ParallelTestExecution suite's afterEach throws")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting(timeStamp: 100)""",
        """test "adds": TestSucceeded(duration: 8, timeStamp: 108)""",
        """test "adds": SuiteAborted(message: "no teardown", duration: 45, timeStamp: 140)"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome aborted, and the SuiteAborted's message, end time and duration")
      adds("outcome") shouldBe text("aborted")
      adds("message") shouldBe text("no teardown")
      adds("endedAt") shouldBe integer(140L)
      adds("duration") shouldBe integer(45L)
    }

    Scenario("A test that failed, then was aborted by its DistributedTestRunnerSuite, is aborted with that SuiteAborted's message, end time and duration") {
      Given("a run of suite RowSuite whose test adds fails, then holds a SuiteAborted")
      val eventLog = rowSuiteRunning(
        """test "adds": TestStarting(timeStamp: 100)""",
        """test "adds": TestFailed(message: "2 did not equal 3", duration: 8, timeStamp: 108)""",
        """test "adds": SuiteAborted(message: "no teardown", duration: 45, timeStamp: 140)"""
      )

      When("the library writes the JSON output")
      val adds = jsonOutputOf(eventLog).node("test", "adds")

      Then("test adds holds the outcome aborted, and the SuiteAborted's message, end time and duration")
      adds("outcome") shouldBe text("aborted")
      adds("message") shouldBe text("no teardown")
      adds("endedAt") shouldBe integer(140L)
      adds("duration") shouldBe integer(45L)
    }
  }
}
