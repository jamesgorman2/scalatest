package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.CoreTag
import org.scalatest.mill.tags.MillCoexistence

@MillCoexistence
final class AddingThePluginSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  Feature("The test command runs a module whose definition names nothing from the plugin") {

    for (scala <- Seq("3.3", "3.9")) {
      val coreTags = if (scala == "3.3") Seq(CoreTag) else Nil

      Scenario(s"Adding the plugin's header line alone lets the test command run a module built with Scala $scala", coreTags*) {
        withFixtureBuild(FixtureName.withoutPluginOnScala(scala)) { build =>
          Given(s"the build does not add the plugin, its build.mill header has no mvnDeps, and the module is built with Scala $scala")
          And("the module is at Mill's default testLogLevel")
          And("its one suite is SqrtPropSpec, an AnyPropSpec whose one property, sqrt of a square is the root, succeeds")
          And("the run writes nothing to stdout or stderr, sends no message outside a suite, and completes")
          build.buildText.linesIterator.filter(_.startsWith("//|")).toSeq shouldBe empty

          When("a header line //| mvnDeps: holding the one entry com.edc::scalatest-mill, at the release of this fork that core.test tests with, is added to its build.mill, with nothing else in the build changed, and the test command runs the module")
          build.addPlugin()
          val result = build.runTestCommand("core.test")

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
