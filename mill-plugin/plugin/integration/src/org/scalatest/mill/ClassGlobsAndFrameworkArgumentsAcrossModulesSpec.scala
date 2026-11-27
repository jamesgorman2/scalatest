package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.consolidation.CapturedLine
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ClassGlobsAndFrameworkArgumentsAcrossModulesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SeveralModulesFixture {

  private def sharedClassGlobsAcrossModules(setUp: FixtureBuild => Unit): SharedSandbox =
    sharedSandbox(FixtureName.classGlobsAcrossModules, SandboxReset.emptyMarkersFolder.and(SandboxReset.noJsonOutputFile))(setUp)

  private val sharedUnchangedClassGlobsAcrossModules: SharedSandbox = sharedClassGlobsAcrossModules(_ => ())

  private val sharedClassGlobsAcrossModulesWithNoisy: SharedSandbox = sharedClassGlobsAcrossModules(addNoisyToBetaTest)

  private def inSharedClassGlobsAcrossModules[A](sandbox: SharedSandbox)(test: FixtureBuild => A): A = {
    givenTheClassGlobsAcrossModules()
    sandbox.use(test)
  }

  private def givenTheClassGlobsAcrossModules(): Unit = {
    Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
    And("each of those suites is an AnyFunSuite with two tests, fast and slow, both of which succeed, and only fast is tagged samples.Fast")
    And("each of those modules holds the object samples.Fast, which is a Tag and not a test class")
    And("<markers> is an absolute path at which an empty folder exists, and each test, when it runs, creates the file <suite>.<test> in <markers>")
    And("the test command is invoked as mill -j 2 scalatest/test alpha.test beta.test -fJ <report> <arguments>, each argument reaching mill exactly as written, with no shell expansion")
  }

  private def givenNoisyInBetaTest(): Unit = {
    Given("beta.test also holds the class samples.Noisy, a public Java class that extends org.scalatest.funsuite.AnyFunSuite and holds no test")
    And("samples.Noisy's only public constructor takes no argument")
    And("samples.Noisy's static initializer writes the line loading samples.Noisy to stdout")
  }

  private def addNoisyToBetaTest(build: FixtureBuild): Unit =
    os.copy(build.workspace / "noisy" / "Noisy.java", build.workspace / "beta" / "test" / "src" / "samples" / "Noisy.java")

  private def runOnBothModulesParsingJsonOutput(build: FixtureBuild, arguments: String*): (TestCommandResult, JsonNode) = {
    When(s"the test command is invoked with ${arguments.mkString(" ")}")
    build.resultAndJsonOutput(runOnBothModulesWithReport(build, arguments*))
  }

  private def runOnBothModulesWithReport(build: FixtureBuild, arguments: String*): TestCommandResult =
    runTestCommand(build)((Seq("alpha.test", "beta.test", "-fJ", build.jsonOutputFile.toString) ++ arguments)*)

  Feature("Class globs and framework arguments across modules") {

    val argumentsAndMarkers = Seq(
      Seq("--", "samples.Alpha1", "samples.Beta1") -> Seq("Alpha1.fast", "Alpha1.slow", "Beta1.fast", "Beta1.slow"),
      Seq("--", "--", "-n", "samples.Fast") -> Seq("Alpha1.fast", "Alpha2.fast", "Beta1.fast"),
      Seq("--", "*1", "--", "-z", "slow") -> Seq("Alpha1.slow", "Beta1.slow")
    )

    for ((arguments, markers) <- argumentsAndMarkers) {
      Scenario(s"Arguments ${arguments.mkString(" ")}") {
        inSharedClassGlobsAcrossModules(sharedUnchangedClassGlobsAcrossModules) { build =>
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
      inSharedClassGlobsAcrossModules(sharedUnchangedClassGlobsAcrossModules) { build =>
        val (result, json) = runOnBothModulesParsingJsonOutput(build, "--", "samples.Alpha*")

        withClue(result.debugString) {
          Then("the files in <markers> are exactly Alpha1.fast, Alpha1.slow, Alpha2.fast and Alpha2.slow")
          build.markers shouldBe Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow")

          And("the module node beta.test holds no run node, its outcome is aborted, and its failures holds exactly one entry")
          val beta = moduleNode(json, "beta.test")
          runNodesOf(json, "beta.test") shouldBe empty
          beta.outcome shouldBe Some("aborted")
          beta.failureMessages should have size 1

          And("that entry's message is Test selector does not match any test: samples.Alpha* and Run discoveredTestClasses to see available tests, joined by a line feed")
          beta.failureMessages.head shouldBe noTestMatchedMessageNaming("samples.Alpha*")

          And("the module node alpha.test has no outcome and no failures")
          shouldHaveNoKeys(moduleNode(json, "alpha.test"), "outcome", "failures")

          And("the JSON output's root has no outcome and no failures")
          shouldHaveNoKeys(json, "outcome", "failures")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("Framework arguments that exclude every test the class globs keep") {
      inSharedClassGlobsAcrossModules(sharedUnchangedClassGlobsAcrossModules) { build =>
        val (result, json) = runOnBothModulesParsingJsonOutput(build, "--", "*1", "--", "-z", "nothing-matches")

        withClue(result.debugString) {
          Then("no file exists in <markers>")
          build.markers shouldBe empty

          for (module <- Seq("alpha.test", "beta.test")) {
            And(s"the module node $module holds one or more run nodes, and has the outcome aborted and a failures that holds exactly one entry")
            runNodesOf(json, module) should not be empty
            moduleNode(json, module).outcome shouldBe Some("aborted")
            moduleNode(json, module).failureMessages should have size 1
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

    val javaHomes = Seq(
      "left unset" -> None,
      "set to the path of a JDK of Java 17 or later" -> Some(sys.props("java.home"))
    )

    for ((javaHomeDescription, javaHome) <- javaHomes) {
      val sharedSandboxForTheJavaHome = javaHome.fold(sharedClassGlobsAcrossModulesWithNoisy) { path =>
        sharedClassGlobsAcrossModules { build =>
          addNoisyToBetaTest(build)
          addToBetaTest(build, s"def javaHome = Task { Some(PathRef(os.Path(\"\"\"$path\"\"\"))) }")
        }
      }

      Scenario(s"beta.test's discovery output in the JSON output, with javaHome $javaHomeDescription") {
        inSharedClassGlobsAcrossModules(sharedSandboxForTheJavaHome) { build =>
          givenNoisyInBetaTest()
          And(s"beta.test's javaHome is $javaHomeDescription")
          val (result, json) = runOnBothModulesParsingJsonOutput(build, "--", "samples.*", "--", "-z", "nothing-matches")

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

    Scenario("beta.test's runs, output and failure in the human-readable output") {
      inSharedClassGlobsAcrossModules(sharedClassGlobsAcrossModulesWithNoisy) { build =>
        givenNoisyInBetaTest()
        When("the test command is invoked with -oH -- samples.* -- -z nothing-matches")
        val result = runOnBothModulesWithReport(build, "-oH", "--", "samples.*", "--", "-z", "nothing-matches")
        val betaSubtree = result.subtreeOf("[beta.test]")
        val underBeta = betaSubtree.zip(result.colourMarkedSubtreeOf("[beta.test]")).drop(1)
        val (beforeLastFour, lastFour) = underBeta.splitAt(underBeta.size - 4)

        withClue(result.debugString) {
          Then("in the human-readable output on stdout, the lines that follow [beta.test] and are indented more than it end with these lines, in this order:")
          betaSubtree should not be empty
          lastFour.map(_._1) shouldBe Seq(
            "  [stdout] loading samples.Noisy",
            "  *** MODULE ABORTED ***",
            "    Test selector does not match any test: samples.*",
            "    Run discoveredTestClasses to see available tests"
          )
          lastFour.drop(1).map(_._2) shouldBe Seq(
            "  {red}*** MODULE ABORTED ***{reset}",
            "    {red}Test selector does not match any test: samples.*{reset}",
            "    {red}Run discoveredTestClasses to see available tests{reset}"
          )

          And("among the lines that follow [beta.test] and are indented more than it, a line that begins Run and is indented one level more than [beta.test] comes before those four lines")
          beforeLastFour.map(_._1).filter(_.startsWith("  Run ")) should not be empty
        }

        And("the test command fails")
        shouldFail(result)
      }
    }
  }
}
