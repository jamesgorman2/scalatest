package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class JsonOutputFedDirectlySpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns with JsonOutputs {

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

    Scenario("Output and failures attached to the root are written on the root: discovery fails") {
      Given("the library is fed no run")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)

      And("the root is given the stderr line `discovering\\n` as output and `Discovery failed` as a failure")
      consolidation.addRootOutput(CapturedLine.Stderr("discovering\n"))
      consolidation.addRootFailure("Discovery failed")

      When("the library writes the JSON output")
      consolidation.end()

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
