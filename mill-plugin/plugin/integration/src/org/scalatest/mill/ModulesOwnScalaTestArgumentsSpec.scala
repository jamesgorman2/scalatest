package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ModulesOwnScalaTestArgumentsSpec
    extends AnyFeatureSpec
    with GivenWhenThen
    with should.Matchers
    with SingleModuleFixture {

  private val sharedCalculatorAndOtherSuiteBuild: SharedFixtureBuild =
    sharedFixtureBuild(FixtureName.arguments, WorkspaceReset.onlyTheBuildFile)(_.addSuites("CalculatorSuite", "OtherSuite"))

  private lazy val sharedRunWithoutModuleArguments: TestCommandResult = sharedCalculatorAndOtherSuiteBuild.use(_.runTestCommand("core.test"))

  Feature("The module's own ScalaTest arguments") {

    Scenario("A test whose tag the module's own arguments exclude is not shown") {
      Given("SpeedSuite, whose test slow is tagged samples.Slow and writes slow ran, in a module whose own ScalaTest arguments are -l samples.Slow")
      And("Mill colours its console output")
      When("the test command runs core.test")
      val result = withSuites("-l", "samples.Slow")("SpeedSuite") { build =>
        build.runTestCommandWithColour("core.test")
      }

      Then("the human-readable output shows fast alone")
      withClue(result.debugString) {
        result.treeLines shouldBe Seq("[core.test]", "  SpeedSuite:", "    - fast")
      }
    }

    for (moduleArguments <- Seq(Seq("-oD"), Seq("-s", "samples.OtherSuite"))) {
      Scenario(s"The module's own ${moduleArguments.mkString(" ")} leaves the human-readable output unchanged") {
        Given(s"CalculatorSuite and OtherSuite, in a module whose own ScalaTest arguments are ${moduleArguments.mkString(" ")}")
        And("the module's testParallelism is false")
        When("the test command runs core.test, and also runs it without the module's own arguments")
        val withoutArguments = sharedRunWithoutModuleArguments
        val result = sharedCalculatorAndOtherSuiteBuild.use { build =>
          build.setModuleArguments(moduleArguments)
          build.runTestCommand("core.test")
        }

        Then("the human-readable output is the one the test command writes with no module arguments")
        withClue(result.debugString + withoutArguments.debugString) {
          withoutArguments.treeLines.map(_.trim) should contain allOf ("CalculatorSuite:", "OtherSuite:")
          withoutArguments.summaryLine shouldBe defined
          result.treeLines shouldBe withoutArguments.treeLines
          result.summaryLine shouldBe withoutArguments.summaryLine
        }

        And("no line on the console outside the human-readable output holds adds two numbers or Run completed in")
        withClue(result.debugString) {
          result.consoleLinesOutsideHumanReadableOutput.filter(line =>
            line.contains("adds two numbers") || line.contains("Run completed in")
          ) shouldBe empty
        }
      }
    }
  }
}
