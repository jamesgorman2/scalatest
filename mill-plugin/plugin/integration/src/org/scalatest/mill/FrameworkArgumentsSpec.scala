package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.Arguments
import org.scalatest.mill.tags.CoreTag

@Arguments
final class FrameworkArgumentsSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SingleModuleFixture {

  Feature("Framework arguments") {

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

    Scenario("The framework argument -oD fails the test command, naming -oD", CoreTag) {
      Given("Alpha1, Alpha2 and Beta1, each of whose tests creates a file in the markers folder as it runs")
      When("the test command runs core.test, writing JSON output, with -- -- -oD")
      withMarkingSuites { build =>
        val result = runWritingJsonOutput(build, "--", "--", "-oD")

        Then("Mill reports that the test command failed before any test started, with a message naming the argument")
        shouldFailBeforeAnyTestStarts(build, result)
        shouldWriteToTheConsole(result, TestCommandFailure.FrameworkArgumentNotPassedOn("-oD").message)

        And("no file exists in the markers folder")
        withClue(result.debugString) {
          build.markers shouldBe empty
        }

        And("nothing exists at the JSON output file's path")
        withClue(result.debugString) {
          os.exists(build.jsonOutputFile) shouldBe false
        }
      }
    }
  }
}
