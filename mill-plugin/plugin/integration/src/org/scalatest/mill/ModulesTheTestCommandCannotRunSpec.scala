package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.AreaTag
import org.scalatest.mill.tags.Modules

@Modules
final class ModulesTheTestCommandCannotRunSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SharedFixtureBuilds {

  private val cannotRunModulesHeader: String = "The test command cannot run these selected test modules:"

  private val modulesWhoseTestsCannotStartHeader: String = "The tests of these selected test modules cannot start:"

  private val sharedModulesTheTestCommandCannotRun: SharedFixtureBuild =
    sharedFixtureBuildWithEarlierReport(FixtureName.modulesTheTestCommandCannotRun, _ => ())(invokeTestCommandOnAlpha)

  private def inSharedModulesTheTestCommandCannotRun[A](test: FixtureBuild => A): A = {
    givenTheBuild()
    sharedModulesTheTestCommandCannotRun.use(test)
  }

  private def withModulesTheTestCommandCannotRun[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.modulesTheTestCommandCannotRun) { build =>
      givenTheBuild()
      writeEarlierReport(build)(invokeTestCommandOnAlpha)
      os.remove.all(build.markersFolder)
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  private def invokeTestCommandOnAlpha(build: FixtureBuild): TestCommandResult = invokeTestCommand(build, Seq("alpha.test"))

  private def givenTheBuild(): Unit = {
    Given("a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry com.edc::scalatest-mill of its build.mill header's mvnDeps")
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
    And("<report> is an absolute path at which a file exists that an earlier test command wrote")
    And("the test command is invoked as mill -j 2 scalatest/test <selectors> -fJ <report>, each argument reaching mill exactly as written, with no shell expansion")
  }

  private def invokeTestCommand(build: FixtureBuild, selectors: Seq[String]): TestCommandResult =
    build.runTestCommandWithMillArguments(FixtureBuild.jobsArguments(2))((selectors :+ "-fJ" :+ build.jsonOutputFile.toString)*)

  private def runTestCommand(build: FixtureBuild, selectors: String*): TestCommandResult = {
    When(s"the test command is invoked with the module selector${if (selectors.size == 1) "" else "s"} ${selectors.mkString(" ")}")
    invokeTestCommand(build, selectors)
  }

  private def addTestModule(build: FixtureBuild, module: String, members: String*): Unit =
    build.appendToBuild(
      (Seq(s"object $module extends SampleModule {", "  object test extends SampleTests, TestModule.ScalaTest {") ++
        members.map("    " + _) ++
        Seq("  }", "}")).mkString("\n")
    )

  private def shouldNameModulesTheTestCommandCannotRun(result: TestCommandResult, modules: Seq[String]): Unit =
    withClue(result.debugString) {
      result.consoleText should include(cannotRunModulesHeader)
      modules.foreach(module => result.consoleLines.exists(line => line.contains(s"  $module ") || line.contains(s"  $module's ")) shouldBe true)
    }

  private def shouldStartNoTestAndWriteNoConsolidatedOutput(build: FixtureBuild, result: TestCommandResult): Unit =
    withClue(result.debugString) {
      And("no file exists in <markers>")
      build.markers shouldBe empty

      And("nothing exists at <report>")
      os.exists(build.jsonOutputFile) shouldBe false

      And("Mill's console shows no consolidated output")
      result.treeLines shouldBe empty
      result.summaryLinesAnywhereOnTheConsole shouldBe empty
    }

  Feature("Modules the test command cannot run") {

    Scenario("Module selectors alpha.test and other.test") {
      inSharedModulesTheTestCommandCannotRun { build =>
        val result = runTestCommand(build, "alpha.test", "other.test")

        Then("Mill reports that the test command failed, with a message naming other.test")
        shouldFail(result)
        shouldNameModulesTheTestCommandCannotRun(result, Seq("other.test"))

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario("A module selector that resolves to every test module") {
      inSharedModulesTheTestCommandCannotRun { build =>
        val result = runTestCommand(build, "__.test")

        Then("Mill reports that the test command failed, with a message naming each of munit.test, utest.test, upstream.test, other.test, js.test and native.test")
        shouldFail(result)
        shouldNameModulesTheTestCommandCannotRun(result, Seq("munit.test", "utest.test", "upstream.test", "other.test", "js.test", "native.test"))

        And("that message names unresolved.test as a module whose tests cannot start, with a failure that contains com.example:absent_3:0.0.1")
        withClue(result.debugString) {
          val linesAfterHeader = result.consoleLines.dropWhile(line => !line.contains(modulesWhoseTestsCannotStartHeader))
          linesAfterHeader.exists(_.contains("  unresolved.test:")) shouldBe true
          linesAfterHeader.exists(_.contains("com.example:absent_3:0.0.1")) shouldBe true
        }

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario("A test module that tests with the release under test through a module it depends on") {
      withModulesTheTestCommandCannotRun { build =>
        Given("the build also has the JVM module shared, which is not a test module and whose mvnDeps holds com.edc::scalatest at the release under test")
        build.appendToBuild("object shared extends SampleModule {\n  def mvnDeps = Seq(mvn\"com.edc::scalatest::3.2.20\")\n}")
        And("the build also has the JVM test module indirect.test, whose moduleDeps holds shared, whose own mvnDeps holds nothing of this fork, and which holds one test that succeeds")
        addTestModule(build, "indirect", "def moduleDeps = super.moduleDeps ++ Seq(shared)")
        val result = runTestCommand(build, "alpha.test", "indirect.test")

        withClue(result.debugString) {
          Then("exactly two files exist in <markers>")
          build.markers should have size 2

          And("the file at <report> holds JSON output whose root holds exactly the module nodes alpha.test and indirect.test, in that order")
          build.jsonOutput.contents.map(_.name) shouldBe Seq("alpha.test", "indirect.test")
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
        addTestModule(build, "args", "def mvnDeps = Seq(mvn\"com.edc::scalatest::3.2.20\")", "def testArgsDefault = Seq(\"--not-a-scalatest-argument\")")
        val result = runTestCommand(build, "alpha.test", "args.test")

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
        val result = runTestCommand(build, "alpha.test", "util")

        Then("Mill reports that the test command failed, with a message naming the module selector util")
        shouldFail(result)
        shouldWriteToTheConsole(result, "Module selector util resolves to no test module")

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }
  }
}
