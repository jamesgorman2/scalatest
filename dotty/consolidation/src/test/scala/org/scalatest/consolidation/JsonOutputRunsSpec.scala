package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.CapturedStream
import org.scalatest.events.JsonValue
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class JsonOutputRunsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns with JsonOutputs {

  private def namesIn(runNode: JsonValue): Vector[JsonValue] = runNode("contents").items.map(_("name"))

  Feature("Runs in the JSON output") {

    Scenario("Every run node is kept, in order of number, with its own suites and its fork group: runs given out of order") {
      Given("run 1, fork group 0, worker index 0, running suites Zeta then Alpha")
      val runOne = runOfSuites("Zeta", "Alpha")
      And("run 2, fork group 0, worker index 1, running suite Mid and writing `two` to stdout")
      val runTwo = runStarts +: suiteRuns("Mid") +: TestRunPart.CapturedLine(CapturedStream.Stdout, "two\n") +: Vector(runEndsCompleted)
      And("run 3, fork group 1, worker index 0, running suites Alpha then Beta")
      val runThree = runOfSuites("Alpha", "Beta")

      When("the runs are added in the order 3, 2, 1 and the library consolidates them")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      addRunInWorker(consolidation, 1, 0, runThree*).endEventStream()
      addRunInWorker(consolidation, 0, 1, runTwo*).endEventStream()
      addRunInWorker(consolidation, 0, 0, runOne*).endEventStream()
      consolidation.end()
      val runNodes = json.module("contents").items

      Then("the module's runs are numbered 1, 2 and 3, in that order")
      runNodes.map(_("number")) shouldBe Vector(integer(1L), integer(2L), integer(3L))

      And("their fork groups are 0, 0 and 1")
      runNodes.map(_("forkGroup")) shouldBe Vector(integer(0L), integer(0L), integer(1L))

      And("run 1 holds Alpha then Zeta, run 2 holds Mid and run 3 holds Alpha then Beta")
      runNodes.map(namesIn) shouldBe Vector(Vector(text("Alpha"), text("Zeta")), Vector(text("Mid")), Vector(text("Alpha"), text("Beta")))

      And("only run 2 has output, the stdout line `two`")
      runNodes.map(_.get("output")) shouldBe Vector(
        None,
        Some(arrayOf(objectOf("kind" -> text("stdio"), "stream" -> text("stdout"), "text" -> text("two\n")))),
        None
      )

      And("each run node's second and third keys are number then forkGroup")
      runNodes.map(_.keys.slice(1, 3)) shouldBe Vector.fill(3)(Vector("number", "forkGroup"))
    }

    Scenario("A run given no fork group has no forkGroup, and a module's runs given none are numbered in the order they are given: runs without a fork group") {
      Given("a run with no worker, running suite Beta")
      And("then a run with no worker, running suite Alpha")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      addRun(consolidation, runOfSuites("Beta")*).endEventStream()
      addRun(consolidation, runOfSuites("Alpha")*).endEventStream()

      When("the library consolidates them")
      consolidation.end()
      val runNodes = json.module("contents").items

      Then("the module's runs are run 1, holding Beta, then run 2, holding Alpha")
      runNodes.map(_("number")) shouldBe Vector(integer(1L), integer(2L))
      runNodes.map(namesIn) shouldBe Vector(Vector(text("Beta")), Vector(text("Alpha")))

      And("neither run node has a forkGroup key")
      all(runNodes.map(_.keys)) should not contain "forkGroup"
    }
  }
}
