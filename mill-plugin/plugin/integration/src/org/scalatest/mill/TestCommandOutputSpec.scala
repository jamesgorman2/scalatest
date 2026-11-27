package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandOutputSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val sqrtPropSpecLines: Seq[String] = Seq(
    "SqrtPropSpec:",
    "  - sqrt of a square is the root"
  )

  private lazy val sharedBrokenConstructorRun: TestCommandResult =
    withFixtureBuild(FixtureName.brokenConstructor) { build =>
      build.runTestCommandWithColour("core.test")
    }

  Feature("A suite whose constructor throws is aborted, and the module's other suites still run") {

    Scenario("A suite whose constructor throws is shown aborted with its exception, and the other suite still runs") {
      Given("suite BrokenCtorSpec, class samples.BrokenCtorSpec, an AnyFunSuite whose constructor throws IllegalStateException(\"no config\")")
      And("suite SqrtPropSpec, an AnyPropSpec whose one property, sqrt of a square is the root, succeeds")
      And("Mill colours its console output")
      When("the test command runs core.test")
      val result = sharedBrokenConstructorRun

      Then("the lines for BrokenCtorSpec begin with BrokenCtorSpec: and samples.BrokenCtorSpec *** ABORTED ***")
      val brokenLines = result.subtreeOf("BrokenCtorSpec:")
      withClue(result.debugString) {
        brokenLines.take(2) shouldBe Seq("BrokenCtorSpec:", "  samples.BrokenCtorSpec *** ABORTED ***")
      }

      And("a line under the abort is the exception")
      brokenLines.drop(2).map(_.trim) should contain("java.lang.IllegalStateException: no config")

      And("SqrtPropSpec's lines are those of a module holding it alone")
      result.subtreeOf("SqrtPropSpec:") shouldBe sqrtPropSpecLines
    }
  }

  Feature("Only a run reaches the console, and only through the human-readable output") {

    Scenario("Neither ScalaTest's end-of-run summary nor Mill's list of failed tests reaches the console") {
      Given("core.test holds BrokenCtorSpec, which aborts, and SqrtPropSpec")
      When("the test command runs core.test")
      val result = sharedBrokenConstructorRun

      Then("no line on the console holds ScalaTest's summary or Mill's list of failed tests")
      val console = result.consoleText
      withClue(result.debugString) {
        Seq("Run completed in", "Tests: succeeded", "*** 1 SUITE ABORTED ***", "tests failed:")
          .filter(console.contains) shouldBe empty
      }
    }
  }
}
