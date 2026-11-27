package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class FrameworkArgumentsSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SingleModuleFixture {

  private def shouldFailBeforeAnyTestStartsWritingNoOutput(build: FixtureBuild, result: TestCommandResult, message: String): Unit = {
    Then("Mill reports that the test command failed before any test started, with a message naming the argument")
    shouldFailBeforeAnyTestStarts(build, result)
    shouldWriteToTheConsole(result, message)

    And("no file exists in the markers folder")
    withClue(result.debugString) {
      build.markers shouldBe empty
    }

    And("nothing exists at the JSON output file's path")
    withClue(result.debugString) {
      os.exists(build.jsonOutputFile) shouldBe false
    }
  }

  private def testsRun(suite: JsonNode): Seq[String] =
    suite.nodes
      .filter(node => node.kind == NodeKind.Test && node.outcome.exists(outcome => outcome == "succeeded" || outcome == "failed"))
      .map(_.name)
      .sorted

  Feature("Framework arguments") {

    def shouldRunExactly(result: TestCommandResult, json: JsonNode, suites: Seq[String], testsInEachSuite: Seq[String]): Unit = {
      withClue(result.debugString) {
        Then(s"the suites run are exactly ${EnglishList.of(suites)}")
        suitesRun(json) shouldBe suites

        And(s"in each of those suites, the tests run are exactly ${EnglishList.of(testsInEachSuite)}")
        suites.map(suite => suite -> testsRun(moduleNode(json).node(NodeKind.Suite, suite))) shouldBe suites.map(_ -> testsInEachSuite)
      }

      And("the test command succeeds")
      shouldSucceed(result)
    }

    Scenario("With no -- after the test command's own arguments, every test runs") {
      Given("Alpha1, Alpha2 and Beta1, each with the tests fast and slow")
      And("each test creates a file in the markers folder as it runs")
      When("the test command runs core.test, writing JSON output, with nothing after -fJ and its path")
      withMarkingSuites { build =>
        val result = runWritingJsonOutput(build)

        Then("the test command succeeds")
        shouldSucceed(result)

        And("the markers folder holds a file for each test of each suite")
        withClue(result.debugString) {
          build.markers shouldBe
            Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow", "Beta1.fast", "Beta1.slow")
        }
      }
    }

    val filteringArguments = Seq(
      Seq("--", "--", "-n", "samples.Fast") -> (Seq("Alpha1", "Alpha2", "Beta1"), Seq("fast")),
      Seq("--", "--", "-l", "samples.Fast") -> (Seq("Alpha1", "Alpha2", "Beta1"), Seq("slow")),
      Seq("--", "samples.Alpha*", "--", "-z", "slow") -> (Seq("Alpha1", "Alpha2"), Seq("slow"))
    )

    for ((arguments, (suites, tests)) <- filteringArguments) {
      Scenario(s"The arguments ${arguments.mkString(" ")} run only the test ${EnglishList.of(tests)} of each of ${EnglishList.of(suites)}") {
        Given("Alpha1, Alpha2 and Beta1, each with the tests fast, tagged samples.Fast, and slow")
        When(s"the test command runs core.test, writing JSON output, with ${arguments.mkString(" ")}")
        withMarkingSuites { build =>
          val (result, json) = runReadingJsonOutput(build, arguments*)

          shouldRunExactly(result, json, suites, tests)
        }
      }
    }

    for (batch <- Seq(false, true)) {
      Scenario(s"A framework argument takes effect over the module's own argument for the same config map key, testBatchFrameworkTasks $batch") {
        Given(s"the module's testArgsDefault is Seq(\"-Dcolour=red\") and its testBatchFrameworkTasks is $batch")
        And("Alpha1's test fast sends an info message holding the config map's value for colour")
        When("the test command runs core.test, writing JSON output, with -- samples.Alpha1 -- -Dcolour=blue")
        withMarkingSuites { build =>
          build.setModuleArguments(Seq("-Dcolour=red"))
          build.addToTestModule(s"def testBatchFrameworkTasks = $batch")
          makeAlpha1FastSendInfo(build, """test.configMap.getRequired[String]("colour")""")
          val (result, json) = runReadingJsonOutput(build, "--", "samples.Alpha1", "--", "-Dcolour=blue")

          Then("the info message is blue")
          withClue(result.debugString) {
            infoMessages(json) shouldBe Seq("blue")
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }

    Scenario("A framework argument replaces the module's own argument for the span scale factor") {
      Given("the module's testArgsDefault is Seq(\"-F\", \"2\")")
      And("Alpha1's test fast sends an info message holding the span scale factor")
      When("the test command runs core.test, writing JSON output, with -- samples.Alpha1 -- -F 3")
      withMarkingSuites { build =>
        build.setModuleArguments(Seq("-F", "2"))
        makeAlpha1FastSendInfo(build, "new org.scalatest.concurrent.ScaledTimeSpans {}.spanScaleFactor.toString")
        val (result, json) = runReadingJsonOutput(build, "--", "samples.Alpha1", "--", "-F", "3")

        Then("the info message is 3.0")
        withClue(result.debugString) {
          infoMessages(json) shouldBe Seq("3.0")
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("The module's own tag filter takes effect beside a framework argument that sets the span scale factor") {
      Given("Alpha1, Alpha2 and Beta1, each with the tests fast, tagged samples.Fast, and slow")
      And("the module's testArgsDefault is Seq(\"-l\", \"samples.Fast\")")
      When("the test command runs core.test, writing JSON output, with -- -- -F 3")
      withMarkingSuites { build =>
        build.setModuleArguments(Seq("-l", "samples.Fast"))
        val (result, json) = runReadingJsonOutput(build, "--", "--", "-F", "3")

        shouldRunExactly(result, json, Seq("Alpha1", "Alpha2", "Beta1"), Seq("slow"))
      }
    }

    val rejectedFrameworkArguments = Seq(
      Seq("-oD") -> "-oD",
      Seq("-s", "samples.Alpha1") -> "-s",
      Seq("--unknown") -> "--unknown",
      Seq("-n") -> "-n",
      Seq("-P") -> "-P",
      Seq("foo") -> "foo"
    )

    for ((frameworkArguments, named) <- rejectedFrameworkArguments) {
      Scenario(s"The framework argument ${frameworkArguments.mkString(" ")} fails the test command, naming $named") {
        Given("Alpha1, Alpha2 and Beta1, each of whose tests creates a file in the markers folder as it runs")
        When(s"the test command runs core.test, writing JSON output, with -- -- ${frameworkArguments.mkString(" ")}")
        withMarkingSuites { build =>
          val result = runWritingJsonOutput(build, ("--" +: "--" +: frameworkArguments)*)

          shouldFailBeforeAnyTestStartsWritingNoOutput(build, result, TestCommandFailure.FrameworkArgumentNotPassedOn(named).message)
        }
      }
    }

    for (arguments <- Seq(Seq("-n", "samples.Fast", "--", "samples.Alpha1"), Seq("-n", "samples.Fast"))) {
      Scenario(s"A tag filter among the test command's own arguments, in ${arguments.mkString(" ")}, fails the test command, naming -n") {
        Given("Alpha1, Alpha2 and Beta1, each of whose tests creates a file in the markers folder as it runs")
        When(s"the test command runs core.test, writing JSON output, with ${arguments.mkString(" ")}")
        withMarkingSuites { build =>
          val result = runWritingJsonOutput(build, arguments*)

          shouldFailBeforeAnyTestStartsWritingNoOutput(build, result, TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n").message)
        }
      }
    }
  }
}
