package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class EmittedRunSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns with JsonOutputs with EmittedRuns {

  Feature("A failure message reaches the human-readable output unchanged") {

    Scenario("A multi-line, non-ASCII failure message") {
      Given("a suite whose test fails with a multi-line, non-ASCII message")
      And("the suite runs through `Framework`, outside Mill, given the ScalaTest runner argument that writes the run's test event log to a file")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.UnicodeSuite].getName)

      And("the consolidation library is used directly, without Mill, with colour on")
      When("the library consolidates that test event log as a run under the test run label `core.test`")
      val output = colourMarkedTreeLinesFrom(eventLog)

      Then("the output is under `core.test`, and the lines under the failed test begin with the message in red, ending with the failing code's file and line")
      val failedLine = output.indexOf("    {red}- reports *** FAILED ***{reset}")
      withClue(output.mkString("\n")) {
        output.headOption shouldBe Some("{cyan}[core.test]{reset}")
        failedLine should be >= 0
        output.slice(failedLine + 1, failedLine + 3).mkString("\n") shouldBe
          """      {red}first line{reset}
            |      {red}second line: ü ß 日本 ✓ (UnicodeSuite.scala:7){reset}""".stripExpectedMargin
      }
    }
  }
}
