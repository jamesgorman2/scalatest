package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class JsonOutputFedDirectlySpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ConsolidatedRuns with JsonOutputs {

  Feature("Runs and the root fed directly to the consolidation library") {

    Seq(
      ("completed", "run: RunCompleted", "completed", Vector.empty[(String, String)]),
      ("stopped", "run: RunStopped", "stopped", Vector("outcome" -> "stopped")),
      ("aborted", "run: RunAborted(message: \"boom\")", "aborted", Vector("outcome" -> "aborted", "message" -> "boom"))
    ).foreach { (how, closingEvent, runEnded, held) =>
      Scenario(s"A run node records how the run ended: a run that ended $how") {
        Given(s"a run fed directly to the library whose events are runStarted, RunStarting, $closingEvent and runEnded marking the run $how")
        val eventLog =
          s"""
          run started
          run: RunStarting
          $closingEvent
          run ended $runEnded
          """

        When("the library writes the JSON output")
        val runNode = jsonOutputOfRun(TestRunPart.Events(eventLog)).runNode

        Then(if (held.isEmpty) "the run node has no outcome" else s"the run node holds ${held.map((key, value) => s"\"$key\": \"$value\"").mkString(" and ")}")
        held.foreach((key, value) => runNode(key) shouldBe text(value))
        if (held.isEmpty) {
          runNode.keys should not contain "outcome"
        }
      }
    }

    Scenario("A run node's output holds its messages and captured lines in the order the library received them: a message between two captured lines") {
      Given("a run fed directly to the library whose events are runStarted, RunStarting, an InfoProvided `between` of no suite, RunCompleted and runEnded marking the run completed")
      And("the line `before\\n` captured from stdout fed before the InfoProvided, and the line `after\\r\\n` captured from stderr after it")
      val parts = Vector(
        TestRunPart.Events(
          """
          run started
          run: RunStarting
          """
        ),
        TestRunPart.CapturedLine(CapturedStream.Stdout, "before\n"),
        TestRunPart.Events("""run: InfoProvided(message: "between", threadName: "main", timeStamp: 120)"""),
        TestRunPart.CapturedLine(CapturedStream.Stderr, "after\r\n"),
        TestRunPart.Events(
          """
          run: RunCompleted
          run ended completed
          """
        )
      )

      When("the library writes the JSON output")
      val runNode = jsonOutputOfRun(parts*).runNode

      Then("the run node's output is, in full, the stdout line, the info with its time and thread, and the stderr line")
      runNode("output") shouldBe arrayOf(
        objectOf("kind" -> text("stdio"), "stream" -> text("stdout"), "text" -> text("before\n")),
        objectOf("kind" -> text("info"), "message" -> text("between"), "at" -> integer(120L), "threadName" -> text("main")),
        objectOf("kind" -> text("stdio"), "stream" -> text("stderr"), "text" -> text("after\r\n"))
      )
    }

    Scenario("A test open when its suite aborts is aborted, ended by the suite's abort, with no message of its own") {
      Given("a run fed directly to the library in which suite S aborts with message `boom` while its test t is running")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "S", class "samples.S"
            test "t", label "- t"
        suite "S": SuiteStarting(timeStamp: 100)
        test "t": TestStarting(timeStamp: 110)
        suite "S": SuiteAborted(message: "boom", timeStamp: 150)
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val json = jsonOutputOfRun(TestRunPart.Events(eventLog))

      Then("suite S holds the outcome aborted and the message boom")
      val suite = json.node(NodeKind.Suite, "S")
      suite("outcome") shouldBe text("aborted")
      suite("message") shouldBe text("boom")

      And("test t is aborted with no message, has a startedAt, ends at the SuiteAborted's time, and has a duration of endedAt less startedAt")
      val test = json.node(NodeKind.Test, "t")
      test("outcome") shouldBe text("aborted")
      test("startedAt") shouldBe integer(110L)
      test("endedAt") shouldBe integer(150L)
      test("duration") shouldBe integer(40L)
      test.keys should not contain "message"
    }

    Scenario("A scope open when its suite aborts is aborted") {
      Given("a run fed directly to the library in which suite S aborts with message `boom` while its scope A is open")
      val eventLog =
        """
        run started
        run: RunStarting
        structure
          suite "S", class "samples.S"
            scope "A", label "A"
              test "t", label "- t"
        suite "S": SuiteStarting
        scope "A": ScopeOpened
        suite "S": SuiteAborted(message: "boom")
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val scope = jsonOutputOfRun(TestRunPart.Events(eventLog)).node(NodeKind.Scope, "A")

      Then("scope A holds the outcome aborted, with no message")
      scope("outcome") shouldBe text("aborted")
      scope.keys should not contain "message"
    }

    Scenario("Output and failures attached to the root are written on the root: discovery fails") {
      Given("the library is fed no run")
      And("the root is given the stderr line `discovering\\n` as output and `Discovery failed` as a failure")
      When("the library writes the JSON output")
      val json = jsonOutputAfter { consolidation =>
        consolidation.addRootOutput(CapturedLine.Stderr("discovering\n"))
        consolidation.addRootFailure("Discovery failed")
      }

      Then("the root holds, in order, its outcome, failures and output")
      val root = json.root
      root.keys.filter(Set("outcome", "failures", "output")) shouldBe Vector("outcome", "failures", "output")
      root("outcome") shouldBe text("aborted")
      root("failures") shouldBe arrayOf(objectOf("message" -> text("Discovery failed")))
      root("output") shouldBe arrayOf(objectOf("kind" -> text("stdio"), "stream" -> text("stderr"), "text" -> text("discovering\n")))
    }

    Scenario("The root has a build tool and times only where the library's caller gives them") {
      Given("a run fed directly to the library whose events are runStarted, RunStarting, RunCompleted and runEnded marking the run completed")
      val eventLog =
        """
        run started
        run: RunStarting
        run: RunCompleted
        run ended completed
        """

      When("the library writes the JSON output")
      val json = jsonOutputOfRun(TestRunPart.Events(eventLog))

      Then("the root has no buildTool, startedAt, endedAt or duration key")
      json.root.keys should contain noneOf ("buildTool", "startedAt", "endedAt", "duration")

      And("the module has no startedAt, endedAt or duration key")
      json.module.keys should contain noneOf ("startedAt", "endedAt", "duration")
    }
  }
}
