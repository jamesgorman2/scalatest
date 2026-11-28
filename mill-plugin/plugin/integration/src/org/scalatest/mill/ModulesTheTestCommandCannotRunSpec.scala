package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.AreaTag
import org.scalatest.mill.tags.Modules

@Modules
final class ModulesTheTestCommandCannotRunSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SharedFixtureBuilds with FixtureBuildSteps {

  private val cannotRunModulesHeader: String = "The test command cannot run these selected test modules:"

  private val modulesWhoseTestsCannotStartHeader: String = "The tests of these selected test modules cannot start:"

  private val sharedModulesTheTestCommandCannotRun: SharedFixtureBuild =
    sharedFixtureBuildWithEarlierReport(FixtureName.modulesTheTestCommandCannotRun, _ => ())(runTestCommandOnAlpha)

  private def inSharedModulesTheTestCommandCannotRun[A](test: FixtureBuild => A): A = {
    givenTheBuild()
    sharedModulesTheTestCommandCannotRun.use(test)
  }

  private def withModulesTheTestCommandCannotRun[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.modulesTheTestCommandCannotRun) { build =>
      givenTheBuild()
      writeEarlierReport(build)(runTestCommandOnAlpha)
      WorkspaceReset.emptyMarkersFolder.restore(build)
      test(build)
    }

  private def runTestCommandOnAlpha(build: FixtureBuild): TestCommandResult = runTestCommandWithTwoJobsWritingJsonOutput(build)("alpha.test")

  private def givenTheBuild(): Unit = {
    givenABuildThatAddsTheMillPlugin()
    And("the build's JVM test module alpha.test tests with com.edc::scalatest at that release and holds the suite Alpha1, an AnyFunSuite whose test fast succeeds")
    And(
      "the build also has these test modules, each with one test that succeeds: " +
        "munit.test, on the JVM, testing with MUnit; " +
        "utest.test, on the JVM, testing with uTest; " +
        "upstream.test, on the JVM, testing with org.scalatest::scalatest 3.2.20; " +
        "other.test, on the JVM, testing with com.edc::scalatest at a release other than the release under test, which a repository the build uses holds; " +
        "js.test, on Scala.js, testing with com.edc::scalatest at the release under test, in NodeJs; " +
        "native.test, on Native, testing with com.edc::scalatest at the release under test"
    )
    And(
      "the build also has the JVM test module unresolved.test, which tests with com.edc::scalatest at the release under test, holds one test that succeeds, " +
        "and whose mvnDeps also holds com.example::absent:0.0.1, which no repository the build uses holds"
    )
    And("<markers> is an absolute path at which an empty folder exists, and each test, when it runs, creates a file in <markers>")
    andAnEarlierTestCommandWroteTheReport()
    And("the test command is invoked as mill -j 2 scalatest/test <selectors> -fJ <report>, each argument reaching mill exactly as written, with no shell expansion")
  }

  private def addScalaTestModule(build: FixtureBuild, module: String, members: String*): Unit =
    build.appendToBuild(moduleWithTestModule(module, "SampleTests, TestModule.ScalaTest", members))

  private def shouldNameModulesTheTestCommandCannotRun(result: TestCommandResult, modules: Seq[String]): Unit =
    withClue(result.debugString) {
      result.consoleText should include(cannotRunModulesHeader)
      modules.filterNot(module => result.consoleLines.exists(line => line.contains(s"  $module ") || line.contains(s"  $module's "))) shouldBe empty
    }

  Feature("Modules the test command cannot run") {

    Scenario("Module selectors alpha.test and other.test") {
      inSharedModulesTheTestCommandCannotRun { build =>
        val result = whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, "alpha.test", "other.test")

        Then("Mill reports that the test command failed, with a message naming other.test")
        shouldFail(result)
        shouldNameModulesTheTestCommandCannotRun(result, Seq("other.test"))

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario("A module selector that resolves to every test module") {
      inSharedModulesTheTestCommandCannotRun { build =>
        val result = whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, "__.test")

        Then("Mill reports that the test command failed, with a message naming each of munit.test, utest.test, upstream.test, other.test, js.test and native.test")
        shouldFail(result)
        shouldNameModulesTheTestCommandCannotRun(result, Seq("munit.test", "utest.test", "upstream.test", "other.test", "js.test", "native.test"))

        And("that message names unresolved.test as a module whose tests cannot start, with a failure that contains com.example:absent_3:0.0.1")
        withClue(result.debugString) {
          val linesAfterHeader = result.consoleLines.dropWhile(line => !line.contains(modulesWhoseTestsCannotStartHeader))
          Seq("  unresolved.test:", "com.example:absent_3:0.0.1").filterNot(text => linesAfterHeader.exists(_.contains(text))) shouldBe empty
        }

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario("A test module that tests with the release under test through a module it depends on") {
      withModulesTheTestCommandCannotRun { build =>
        Given("the build also has the JVM module shared, which is not a test module and whose mvnDeps holds com.edc::scalatest at the release under test")
        build.appendToBuild("object shared extends SampleModule {\n  def mvnDeps = Seq(mvn\"com.edc::scalatest::3.2.20\")\n}")
        And("the build also has the JVM test module indirect.test, whose moduleDeps holds shared, whose own mvnDeps holds nothing of this fork, and which holds one test that succeeds")
        addScalaTestModule(build, "indirect", "def moduleDeps = super.moduleDeps ++ Seq(shared)")
        val result = whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, "alpha.test", "indirect.test")

        withClue(result.debugString) {
          Then("exactly two files exist in <markers>")
          build.markers should have size 2

          And("the file at <report> holds JSON output whose root holds exactly the module nodes alpha.test and indirect.test, in that order")
          build.jsonOutput.contentNames shouldBe Seq("alpha.test", "indirect.test")
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("A test module whose testArgsDefault holds an unrecognised argument", AreaTag.Arguments) {
      withModulesTheTestCommandCannotRun { build =>
        Given(
          "the build also has the JVM test module args.test, which tests with com.edc::scalatest at the release under test, holds one test that succeeds, " +
            "and whose testArgsDefault is --not-a-scalatest-argument"
        )
        addScalaTestModule(build, "args", "def mvnDeps = Seq(mvn\"com.edc::scalatest::3.2.20\")", "def testArgsDefault = Seq(\"--not-a-scalatest-argument\")")
        val result = whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, "alpha.test", "args.test")

        Then("Mill reports that the test command failed, with a message naming --not-a-scalatest-argument")
        shouldFail(result)
        shouldNameModulesTheTestCommandCannotRun(result, Seq("args.test"))
        shouldWriteToTheConsole(result, "--not-a-scalatest-argument")

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario("Module selectors alpha.test and util") {
      withModulesTheTestCommandCannotRun { build =>
        Given("the build also has the JVM module util, which is not a test module and holds no module")
        build.appendToBuild("object util extends SampleModule")
        val result = whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, "alpha.test", "util")

        Then("Mill reports that the test command failed, with a message naming the module selector util")
        shouldFail(result)
        shouldWriteToTheConsole(result, "Module selector util resolves to no test module")

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario("Module selectors alpha.test, util and tool") {
      withModulesTheTestCommandCannotRun { build =>
        Given("the build also has the JVM modules util and tool, neither of which is a test module or holds a module")
        build.appendToBuild("object util extends SampleModule\n\nobject tool extends SampleModule")
        val result = whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, "alpha.test", "util", "tool")

        Then("Mill reports that the test command failed, with a message naming the module selectors util and tool")
        shouldFail(result)
        withClue(result.debugString) {
          Seq("Module selector util resolves to no test module", "Module selector tool resolves to no test module")
            .filterNot(result.consoleText.contains) shouldBe empty
        }

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }
  }
}
