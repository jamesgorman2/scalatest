package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ClassGlobsSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SingleModuleFixture {

  private def noTestMatchedFailuresOfModule(json: JsonNode): Seq[String] =
    moduleNode(json).failureMessages.filter(_.startsWith("Test selector does not match any test"))

  private def makeTestsSendWorkingDirectory(build: FixtureBuild, suite: SampleSuite, tests: SampleTest*): Unit =
    tests.foreach(test => build.replaceInSample(s"$suite.scala", test.withEmptyBody, test.sendingWorkingDirectory))

  private def runWithClassGlobsReadingJsonOutput(build: FixtureBuild, classGlobs: String*): (TestCommandResult, JsonNode) =
    runReadingJsonOutput(build, ("--" +: classGlobs)*)

  private def shouldHoldOneRunOf(json: JsonNode, suites: Seq[String]): JsonNode = {
    val contents = moduleNode(json).contents
    contents.map(_.kind) shouldBe Seq(NodeKind.Run)
    contents.head.contentNames.sorted shouldBe suites
    contents.head
  }

  private def shouldSendOnlyTheModuleFoldersSandbox(build: FixtureBuild, json: JsonNode): Unit = {
    val messages = infoMessages(json)
    messages should not be empty
    all(messages.map(os.Path(_))) shouldBe build.moduleFolder / "sandbox"
  }

  Feature("Class globs") {

    Scenario("Class globs that select one fork group's classes start only that group's worker, testBatchFrameworkTasks true") {
      val forkGrouping = """Seq(Seq("samples.Beta1"), Seq("samples.Alpha1", "samples.Alpha2"))"""
      Given(s"the module's testForkGrouping is $forkGrouping and its testBatchFrameworkTasks is true")
      And("each test sends an info message holding the system property user.dir")
      When("the test command runs core.test, writing JSON output, with -- samples.Alpha*")
      withMarkingSuites { build =>
        build.addToTestModule(
          s"def testForkGrouping = Task { $forkGrouping }",
          "def testBatchFrameworkTasks = true"
        )
        SampleSuite.values.foreach(suite => makeTestsSendWorkingDirectory(build, suite, SampleTest.Fast, SampleTest.Slow))
        val (result, json) = runWithClassGlobsReadingJsonOutput(build, "samples.Alpha*")

        withClue(result.debugString) {
          Then("the module node holds exactly one run node, of fork group 1, holding Alpha1 and Alpha2")
          shouldHoldOneRunOf(json, Seq("Alpha1", "Alpha2")).forkGroup shouldBe Some(1)

          And("every info message is the path of the folder sandbox in the module folder")
          shouldSendOnlyTheModuleFoldersSandbox(build, json)
        }
      }
    }

    Scenario("Class globs that select one class under test parallelism run it in one worker, in the module folder") {
      Given("the module's testParallelism is on")
      And("Alpha1's test fast sends an info message holding the system property user.dir")
      When("the test command runs core.test, writing JSON output, with -- samples.Alpha1")
      withMarkingSuites { build =>
        build.replaceInBuild("def testParallelism = false", "def testParallelism = true")
        makeTestsSendWorkingDirectory(build, SampleSuite.Alpha1, SampleTest.Fast)
        val (result, json) = runWithClassGlobsReadingJsonOutput(build, "samples.Alpha1")

        withClue(result.debugString) {
          Then("the module node holds exactly one run node, holding Alpha1 only")
          shouldHoldOneRunOf(json, Seq("Alpha1"))

          And("the info message is the path of the folder sandbox in the module folder")
          shouldSendOnlyTheModuleFoldersSandbox(build, json)
        }
      }
    }

    Scenario("The class glob samples.Fast, which matches no test class, fails the test command") {
      Given("Alpha1, Alpha2 and Beta1, each of whose tests creates a file in the markers folder as it runs")
      And("samples.Fast, a Tag object that is not a test class")
      When("the test command runs core.test, writing JSON output, with -- samples.Fast")
      withMarkingSuites { build =>
        val (result, json) = runWithClassGlobsReadingJsonOutput(build, "samples.Fast")

        Then("the module node core.test holds exactly one failure, the one Mill's testOnly fails with, naming samples.Fast")
        withClue(result.debugString) {
          moduleNode(json).failureMessages shouldBe Seq(noTestMatchedMessageNaming("samples.Fast"))
        }

        And("the JSON output's root holds no failure")
        withClue(result.debugString) {
          json.failureMessages shouldBe empty
        }

        And("no file exists in the markers folder, and no test worker started")
        withClue(result.debugString) {
          build.markers shouldBe empty
          build.anyTestWorkerStarted shouldBe false
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("The only matched suite aborting before its tests run counts as a test result") {
      Given("Broken1, whose beforeAll throws before its one test, fast, runs")
      When("the test command runs core.test, writing JSON output, with -- samples.Broken1")
      withMarkingSuites { build =>
        build.addSuites("Broken1")
        val (result, json) = runWithClassGlobsReadingJsonOutput(build, "samples.Broken1")

        withClue(result.debugString) {
          Then("the JSON output holds the suite Broken1, aborted, with a message")
          val broken1 = moduleNode(json).node(NodeKind.Suite, "Broken1")
          broken1.outcome shouldBe Some("aborted")
          broken1.holds("message") shouldBe true

          And("the module node core.test holds no failure whose message begins Test selector does not match any test")
          noTestMatchedFailuresOfModule(json) shouldBe empty
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("The JVM running the only matched suite dying before its tests run is not reported as class globs that match no test") {
      Given("Halting1, whose beforeAll halts the JVM before its one test, fast, runs")
      When("the test command runs core.test, writing JSON output, with -- samples.Halting1")
      withMarkingSuites { build =>
        build.addSuites("Halting1")
        val (result, json) = runWithClassGlobsReadingJsonOutput(build, "samples.Halting1")

        Then("the module node core.test holds no failure whose message begins Test selector does not match any test")
        withClue(result.debugString) {
          noTestMatchedFailuresOfModule(json) shouldBe empty
        }

        And("the test command fails")
        shouldFail(result)
      }
    }
  }
}
