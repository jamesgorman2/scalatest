package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class AnotherGoalFailsFirstSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SeveralModulesFixture {

  private val sharedSeveralModulesWithBrokenThatDoesNotCompileAndReportFromAnEarlierTestCommand: SharedSandbox =
    sharedSandboxWithReportFromAnEarlierTestCommand(FixtureName.severalModules, addBrokenThatDoesNotCompile) { build =>
      runTestCommand(build)("alpha.test", "beta.test", "-fJ", build.jsonOutputFile.toString)
    }

  private def addBrokenThatDoesNotCompile(build: FixtureBuild): Unit = {
    build.appendToBuild("object broken extends SampleModule")
    os.write(build.workspace / "broken" / "src" / "Broken.scala", "object Broken {\n  val value: Int = notDefinedAnywhere\n}\n", createFolders = true)
  }

  private def runMillCommandLine(build: FixtureBuild, millArguments: String*): TestCommandResult =
    TestCommandResult(build.eval(FixtureBuild.jobsArguments(2) ++ millArguments))

  Feature("Another goal fails first") {

    def inSharedSeveralModulesWithBrokenThatDoesNotCompileAndReportFromAnEarlierTestCommand[A](test: FixtureBuild => A): A =
      inSharedSeveralModules(sharedSeveralModulesWithBrokenThatDoesNotCompileAndReportFromAnEarlierTestCommand) { build =>
        Given("the build's module broken does not compile")
        And("<report> is an absolute path at which a file exists that an earlier test command wrote")
        test(build)
      }

    Scenario("broken.compile before the test command") {
      inSharedSeveralModulesWithBrokenThatDoesNotCompileAndReportFromAnEarlierTestCommand { build =>
        val reportBefore = os.read(build.jsonOutputFile)
        val reportModifiedBefore = os.mtime(build.jsonOutputFile)
        When("mill -j 2 broken.compile + scalatest/test alpha.test -fJ <report> is invoked")
        val result = runMillCommandLine(build, "broken.compile", "+", "scalatest/test", "alpha.test", "-fJ", build.jsonOutputFile.toString)

        Then("Mill reports that broken.compile failed")
        shouldFail(result)
        shouldWriteToTheConsole(result, "broken.compile Compilation failed")

        withClue(result.debugString) {
          And("no file exists in <markers>")
          build.markers shouldBe empty

          And("the file at <report> has the content and modification time it had before mill was invoked")
          os.read(build.jsonOutputFile) shouldBe reportBefore
          os.mtime(build.jsonOutputFile) shouldBe reportModifiedBefore

          And("Mill's console shows no consolidated output")
          result.treeLines shouldBe empty
          result.summaryLinesAnywhereOnTheConsole shouldBe empty
        }
      }
    }

    Scenario("broken.compile before the test command, with --keep-going") {
      inSharedSeveralModulesWithBrokenThatDoesNotCompileAndReportFromAnEarlierTestCommand { build =>
        When("mill -j 2 --keep-going broken.compile + scalatest/test alpha.test -fJ <report> is invoked")
        val result =
          runMillCommandLine(build, "--keep-going", "broken.compile", "+", "scalatest/test", "alpha.test", "-fJ", build.jsonOutputFile.toString)

        Then("Mill reports that broken.compile failed")
        shouldFail(result)
        shouldWriteToTheConsole(result, "broken.compile Compilation failed")

        withClue(result.debugString) {
          And("a file exists in <markers>")
          build.markers should not be empty

          And("the file at <report> holds JSON output whose root holds exactly the module node alpha.test")
          moduleNodeNames(build.jsonOutput) shouldBe Seq("alpha.test")
        }
      }
    }
  }
}
