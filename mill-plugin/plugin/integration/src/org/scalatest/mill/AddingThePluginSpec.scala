package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class AddingThePluginSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  Feature("The test command runs a module whose definition names nothing from the plugin") {

    for (scala <- Seq("3.3", "3.9")) {
      Scenario(s"The plugin is added by its header line to a build on Scala $scala") {
        withTester(s"without-plugin-scala-$scala") { tester =>
          Given(s"a build without the plugin, whose header has no mvnDeps, and whose core.test on Scala $scala holds SqrtPropSpec")
          os.read.lines(tester.workspacePath / "build.mill").filter(_.startsWith("//|")) shouldBe empty

          When("the plugin's header line is added to its build.mill, with nothing else changed, and the test command runs core.test")
          addPlugin(tester)
          val result = runTestCommand(tester, "core.test")

          Then("the human-readable output is SqrtPropSpec's")
          withClue(result.debugString) {
            result.humanReadableOutput shouldBe Seq(
              "[core.test]",
              "  SqrtPropSpec:",
              "    - sqrt of a square is the root"
            )
          }
        }
      }
    }
  }
}
