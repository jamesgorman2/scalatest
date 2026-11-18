package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class AddingThePluginSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  Feature("The test command runs a module whose definition names nothing from the plugin") {

    for (scala <- Seq("3.3", "3.9")) {
      Scenario(s"The plugin is added by its header line to a build on Scala $scala") {
        withTester(s"without-plugin-scala-$scala") { tester =>
          Given(s"the build does not add the plugin, its build.mill header has no mvnDeps, and the module is built with Scala $scala")
          And("the module is at Mill's default testLogLevel")
          And("its one suite is SqrtPropSpec, an AnyPropSpec whose one property, sqrt of a square is the root, succeeds")
          And("the run writes nothing to stdout or stderr, sends no message outside a suite, and completes")
          os.read.lines(tester.workspacePath / "build.mill").filter(_.startsWith("//|")) shouldBe empty

          When("a header line //| mvnDeps: holding the one entry com.edc::scalatest-mill, at the release of this fork that core.test tests with, is added to its build.mill, with nothing else in the build changed, and the test command runs the module")
          addPlugin(tester)
          val result = runTestCommand(tester, "core.test")

          Then("the human-readable output is SqrtPropSpec's")
          withClue(result.debugString) {
            result.treeLines shouldBe Seq(
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
