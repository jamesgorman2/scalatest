package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class LibraryWithoutMillSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  private val completedRun = TestRunPart.Events(
    """
    run started
    structure
      suite "SqrtPropSpec", class "samples.SqrtPropSpec"
        test "sqrt of a square is the root", label "- sqrt of a square is the root"
    suite "SqrtPropSpec": SuiteStarting
    test "sqrt of a square is the root": TestStarting
    test "sqrt of a square is the root": TestSucceeded
    suite "SqrtPropSpec": SuiteCompleted
    run ended completed
    """
  )

  Feature("The library writes human-readable output only once every run's event stream has ended") {

    Scenario("A completed run whose event stream is held open") {
      Given("an event log of a completed run")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)

      When("every event has been fed and the event stream is held open")
      addRun(consolidation, completedRun)

      Then("the library has written no human-readable output")
      output.lines shouldBe empty
    }

    Scenario("A completed run whose event stream has ended") {
      Given("an event log of a completed run, every event fed and the event stream ended")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, completedRun).endEventStream()
      output.lines shouldBe empty

      When("the caller ends the consolidation")
      consolidation.end()

      Then("the library writes the human-readable output")
      output.markedText shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripMargin
    }
  }
}
