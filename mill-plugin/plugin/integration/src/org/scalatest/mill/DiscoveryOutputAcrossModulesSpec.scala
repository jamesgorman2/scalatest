package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.consolidation.CapturedLine
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.AreaTag
import org.scalatest.mill.tags.Capture

@Capture
final class DiscoveryOutputAcrossModulesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ClassGlobsAcrossModulesFixture {

  private val sharedClassGlobsAcrossModulesWithNoisy: SharedFixtureBuild = sharedClassGlobsAcrossModules(addNoisyToBetaTest)

  private def givenNoisyInBetaTest(): Unit = {
    Given("beta.test also holds the class samples.Noisy, a public Java class that extends org.scalatest.funsuite.AnyFunSuite and holds no test")
    And("samples.Noisy's only public constructor takes no argument")
    And("samples.Noisy's static initializer writes the line loading samples.Noisy to stdout")
  }

  private def addNoisyToBetaTest(build: FixtureBuild): Unit =
    os.copy(build.workspace / "noisy" / "Noisy.java", build.workspace / "beta" / "test" / "src" / "samples" / "Noisy.java")

  Feature("Class globs and framework arguments across modules") {

    final case class JavaHome(description: String, path: Option[os.Path])

    val javaHomes = Seq(
      JavaHome("left unset", None),
      JavaHome("set to the path of a JDK of Java 17 or later", Some(os.Path(sys.props("java.home"))))
    )

    for (javaHome <- javaHomes) {
      val javaHomeDescription = javaHome.description
      val sharedBuildForTheJavaHome = javaHome.path.fold(sharedClassGlobsAcrossModulesWithNoisy) { path =>
        sharedClassGlobsAcrossModules { build =>
          addNoisyToBetaTest(build)
          addToTestModule(build, "beta", s"def javaHome = Task { Some(PathRef(os.Path(\"\"\"$path\"\"\"))) }")
        }
      }

      Scenario(s"beta.test's discovery output in the JSON output, with javaHome $javaHomeDescription") {
        inSharedBuild(sharedBuildForTheJavaHome) { build =>
          givenNoisyInBetaTest()
          And(s"beta.test's javaHome is $javaHomeDescription")
          val (result, json) = whenTheTestCommandIsInvokedOnBothModulesParsingJsonOutput(build, "--", "samples.*", "--", "-z", "nothing-matches")

          withClue(result.debugString) {
            Then("in the JSON output, the module node beta.test has an output that holds exactly one item, a stdio item whose stream is stdout and whose text is loading samples.Noisy followed by a line terminator")
            val betaOutput = moduleNode(json, "beta.test").output
            betaOutput.map(_.kind) shouldBe Seq(OutputItemKind.Stdio)
            betaOutput.head.capturedLine match {
              case CapturedLine.Stdout(text) => text should fullyMatch regex """loading samples\.Noisy(\r\n|\n|\r)"""
              case line => fail(s"$line is not on stdout")
            }

            And("in the JSON output, the module node alpha.test has no output")
            shouldHaveNoKeys(moduleNode(json, "alpha.test"), "output")

            And("the JSON output's root has no output")
            shouldHaveNoKeys(json, "output")
          }
        }
      }
    }

    Scenario("beta.test's runs, output and failure in the human-readable output", AreaTag.HumanReadable) {
      inSharedBuild(sharedClassGlobsAcrossModulesWithNoisy) { build =>
        givenNoisyInBetaTest()
        val result = whenTheTestCommandIsInvokedOnBothModulesWritingJsonOutput(build, "-oH", "--", "samples.*", "--", "-z", "nothing-matches")
        val betaSubtree = result.subtreeOf("[beta.test]")
        val underBeta = betaSubtree.drop(1)
        val (beforeLastFour, lastFour) = underBeta.splitAt(underBeta.size - 4)
        val colourMarkedLastFour = result.colourMarkedSubtreeOf("[beta.test]").drop(1).takeRight(4)

        withClue(result.debugString) {
          Then("in the human-readable output on stdout, the lines that follow [beta.test] and are indented more than it end with these lines, in this order:")
          betaSubtree should not be empty
          lastFour shouldBe Seq(
            "  [stdout] loading samples.Noisy",
            "  *** MODULE ABORTED ***",
            "    Test selector does not match any test: samples.*",
            "    Run discoveredTestClasses to see available tests"
          )
          colourMarkedLastFour.drop(1) shouldBe Seq(
            "  {red}*** MODULE ABORTED ***{reset}",
            "    {red}Test selector does not match any test: samples.*{reset}",
            "    {red}Run discoveredTestClasses to see available tests{reset}"
          )

          And("among the lines that follow [beta.test] and are indented more than it, a line that begins Run and is indented one level more than [beta.test] comes before those four lines")
          beforeLastFour.filter(_.startsWith("  Run ")) should not be empty
        }

        And("the test command fails")
        shouldFail(result)
      }
    }
  }
}
