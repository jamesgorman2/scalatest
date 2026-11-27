package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.Arguments

@Arguments
final class ClassGlobsAndFrameworkArgumentsAcrossModulesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ClassGlobsAcrossModulesFixture {

  private val sharedUnchangedClassGlobsAcrossModules: SharedFixtureBuild = sharedClassGlobsAcrossModules(_ => ())

  Feature("Class globs and framework arguments across modules") {

    val argumentsAndMarkers = Seq(
      Seq("--", "samples.Alpha1", "samples.Beta1") -> Seq("Alpha1.fast", "Alpha1.slow", "Beta1.fast", "Beta1.slow"),
      Seq("--", "--", "-n", "samples.Fast") -> Seq("Alpha1.fast", "Alpha2.fast", "Beta1.fast"),
      Seq("--", "*1", "--", "-z", "slow") -> Seq("Alpha1.slow", "Beta1.slow")
    )

    for ((arguments, markers) <- argumentsAndMarkers) {
      Scenario(s"Arguments ${arguments.mkString(" ")}") {
        inSharedBuild(sharedUnchangedClassGlobsAcrossModules) { build =>
          val (result, _) = runOnBothModulesParsingJsonOutput(build, arguments*)

          Then(s"the files in <markers> are exactly ${EnglishList.of(markers)}")
          withClue(result.debugString) {
            build.markers shouldBe markers
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }

    Scenario("Class globs that match only alpha.test's classes") {
      inSharedBuild(sharedUnchangedClassGlobsAcrossModules) { build =>
        val (result, json) = runOnBothModulesParsingJsonOutput(build, "--", "samples.Alpha*")

        withClue(result.debugString) {
          Then("the files in <markers> are exactly Alpha1.fast, Alpha1.slow, Alpha2.fast and Alpha2.slow")
          build.markers shouldBe Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow")

          And("in the JSON output, the module node beta.test holds no run node, its outcome is aborted, and its failures holds exactly one entry")
          runNodesOf(json, "beta.test") shouldBe empty
          shouldBeAbortedWithOneFailure(json, "beta.test")

          And("that entry's message is Test selector does not match any test: samples.Alpha* and Run discoveredTestClasses to see available tests, joined by a line feed")
          moduleNode(json, "beta.test").failureMessages.head shouldBe noTestMatchedMessageNaming("samples.Alpha*")

          And("in the JSON output, the module node alpha.test has no outcome and no failures")
          shouldHaveNoKeys(moduleNode(json, "alpha.test"), "outcome", "failures")

          And("the JSON output's root has no outcome and no failures")
          shouldHaveNoKeys(json, "outcome", "failures")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("Framework arguments that exclude every test the class globs keep") {
      inSharedBuild(sharedUnchangedClassGlobsAcrossModules) { build =>
        val (result, json) = runOnBothModulesParsingJsonOutput(build, "--", "*1", "--", "-z", "nothing-matches")

        withClue(result.debugString) {
          Then("no file exists in <markers>")
          build.markers shouldBe empty

          And("in the JSON output, the module nodes alpha.test and beta.test each hold one or more run nodes, and each has the outcome aborted and a failures that holds exactly one entry")
          for (module <- Seq("alpha.test", "beta.test")) {
            runNodesOf(json, module) should not be empty
            shouldBeAbortedWithOneFailure(json, module)
          }

          And("each of those entries' message is Test selector does not match any test: *1 and Run discoveredTestClasses to see available tests, joined by a line feed")
          Seq("alpha.test", "beta.test").map(module => moduleNode(json, module).failureMessages.head) shouldBe
            Seq.fill(2)(noTestMatchedMessageNaming("*1"))

          And("the JSON output's root has no outcome and no failures")
          shouldHaveNoKeys(json, "outcome", "failures")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }
  }
}
