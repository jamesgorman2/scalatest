package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class MillOwnTestTasksSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val tasks: Seq[Seq[String]] = Seq(
    Seq("core.test"),
    Seq("core.test.testForked"),
    Seq("core.test.testOnly", "samples.CalculatorSuite"),
    Seq("core.test.testCached"),
    Seq("core.test.testLocal"),
    Seq("__.test")
  )

  /**
   * Each task's run in the `mill-own-tasks` build without the plugin, then with it. Each runs on a build whose own
   * compilation has finished and whose module has no output yet.
   */
  private lazy val runs: Map[Seq[String], BuildComparison[MillConsole]] =
    withTester("mill-own-tasks") { tester =>
      val withoutPlugin = tasks.map(task => task -> runAlone(tester, task)).toMap
      addPlugin(tester)
      val withPlugin = tasks.map(task => task -> runAlone(tester, task)).toMap
      tasks.map(task => task -> BuildComparison(withoutPlugin(task), withPlugin(task))).toMap
    }

  private def runAlone(tester: IntegrationTester, task: Seq[String]): MillConsole = {
    MillBuild.compileBuild(tester)
    MillBuild.removeModuleOutput(tester)
    MillConsole(tester.eval(task))
  }

  Feature("In a build that adds the plugin, Mill's test tasks behave as they do without it") {

    tasks.foreach { task =>
      val command = s"mill ${task.mkString(" ")}"

      Scenario(s"`$command`") {
        Given("core.test holds CalculatorSuite, whose test writes dividing now to stdout, and StackFlatSpec, whose test fails")
        When(s"`$command` runs in a build that adds the plugin, and in the same build without it")
        val run = runs(task)

        withClue(run.describe(_.debugString)) {
          Then("Mill's result is the same under both")
          run.withPlugin.exitCode shouldBe run.withoutPlugin.exitCode

          And("so is its console output, apart from durations and the lines Mill writes while it compiles the build itself")
          run.withPlugin.stdoutLines shouldBe run.withoutPlugin.stdoutLines
          run.withPlugin.stderrLines shouldBe run.withoutPlugin.stderrLines

          And("neither console holds a line [core.test]")
          all(Seq(run.withoutPlugin, run.withPlugin).flatMap(_.lines).map(_.trim)) should not be "[core.test]"
        }
      }
    }
  }
}
