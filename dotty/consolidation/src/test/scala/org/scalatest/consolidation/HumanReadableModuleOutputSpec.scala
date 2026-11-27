package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class HumanReadableModuleOutputSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ConsolidatedRuns {

  Feature("A module's output and failures") {

    Scenario("A module's captured lines, then its abort with each failure under it, follow the lines for its runs") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      And("a failure, a stdout line, a stderr line and a failure of two lines attached to `core.test`, in that order")
      When("the library consolidates it")
      val output = colourMarkedOutputAfter { consolidation =>
        addSqrtPropSpecRun(consolidation)
        consolidation.addModuleFailure(module, "first failure")
        consolidation.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy\n"))
        consolidation.addModuleOutput(module, CapturedLine.Stderr("warn: slow discovery\n"))
        consolidation.addModuleFailure(module, "Test selector does not match any test: samples.*\nRun discoveredTestClasses to see available tests")
      }

      Then("the module's captured lines sit one level under its line, in the order attached")
      And("`*** MODULE ABORTED ***` follows them in red at that level, with each failure's lines in red one level deeper, in the order attached")
      output.tree shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}
          |  [stdout] loading samples.Noisy
          |  {yellow}[stderr] warn: slow discovery{reset}
          |  {red}*** MODULE ABORTED ***{reset}
          |    {red}first failure{reset}
          |    {red}Test selector does not match any test: samples.*{reset}
          |    {red}Run discoveredTestClasses to see available tests{reset}""".stripExpectedMargin
    }

    Scenario("A module's captured lines and abort follow the lines of a run that shows its line") {
      Given("an event log of a run that ends aborted, consolidated as a run of the module `core.test`")
      And("a stdout line and a failure attached to `core.test`")
      When("the library consolidates it")
      val output = colourMarkedOutputAfter { consolidation =>
        consolidation.addRunOf(TestRunPart.Events("run started\nrun ended aborted"))
        consolidation.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy"))
        consolidation.addModuleFailure(module, "no test result")
      }

      Then("the run's line and its abort come first, then the module's captured line and abort, each one level under the module's line")
      output.tree shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {red}*** RUN ABORTED ***{reset}
          |  [stdout] loading samples.Noisy
          |  {red}*** MODULE ABORTED ***{reset}
          |    {red}no test result{reset}""".stripExpectedMargin
    }

    Scenario("A module given a failure but no run shows its line and its abort, among the other modules in order by name") {
      Given("an event log of a completed run of SqrtPropSpec, consolidated as a run of the module `core.test`")
      And("a failure attached to the module `app.test`, which has no run, after that run was added")
      When("the library consolidates it")
      val output = colourMarkedOutputAfter { consolidation =>
        addSqrtPropSpecRun(consolidation)
        consolidation.addModuleFailure(ModuleName("app.test"), "app.test.compile failed")
      }

      Then("the line of `app.test` comes first, with its abort and failure under it, and the output holds no root's line")
      output.tree shouldBe
        """{cyan}[app.test]{reset}
          |  {red}*** MODULE ABORTED ***{reset}
          |    {red}app.test.compile failed{reset}
          |{cyan}[core.test]{reset}
          |  {green}SqrtPropSpec:{reset}
          |    {green}- sqrt of a square is the root{reset}""".stripExpectedMargin
    }

    Scenario("A module given output but no run shows its line and its captured lines, without an abort") {
      Given("a stdout line attached to the module `core.test`, which has no run")
      When("the library consolidates it")
      val output = colourMarkedOutputAfter(_.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy")))

      Then("the module's line holds only the captured line")
      output.tree shouldBe
        """{cyan}[core.test]{reset}
          |  [stdout] loading samples.Noisy""".stripExpectedMargin
    }

    Scenario("A module's output and abort sit one level deeper under the root's line") {
      Given("a stdout line and a failure attached to the module `core.test`, which has no run, and a failure attached to the root")
      When("the library consolidates it")
      val output = colourMarkedOutputAfter { consolidation =>
        consolidation.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy"))
        consolidation.addModuleFailure(module, "module failure")
        consolidation.addRootFailure("root failure")
      }

      Then("the module's captured line and abort sit under the module's line, itself under the root's line, before the root's abort")
      output.tree shouldBe
        """{cyan}Consolidated tests:{reset}
          |  {cyan}[core.test]{reset}
          |    [stdout] loading samples.Noisy
          |    {red}*** MODULE ABORTED ***{reset}
          |      {red}module failure{reset}
          |  {red}*** CONSOLIDATED TESTS ABORTED ***{reset}
          |    {red}root failure{reset}""".stripExpectedMargin
    }
  }
}
