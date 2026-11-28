package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.MillCoexistence

@MillCoexistence
final class MillOwnTestTasksSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val tasks: Seq[Seq[String]] = Seq(
    Seq("core.test"),
    Seq("core.test.testForked"),
    Seq("core.test.testOnly", "samples.CalculatorSuite"),
    Seq("core.test.testCached"),
    Seq("core.test.testLocal"),
    Seq("__.test")
  )

  private lazy val runs: Map[Seq[String], BuildComparison[MillConsole]] =
    withFixtureBuild(FixtureName.millOwnTasks) { build =>
      val withoutPlugin = runEachAlone(build)
      build.addPlugin()
      val withPlugin = runEachAlone(build)
      tasks.map(task => task -> BuildComparison(withoutPlugin(task), withPlugin(task))).toMap
    }

  private def runEachAlone(build: FixtureBuild): Map[Seq[String], MillConsole] = {
    build.compile()
    tasks.map(task => task -> runAlone(build, task)).toMap
  }

  private def runAlone(build: FixtureBuild, task: Seq[String]): MillConsole = {
    build.removeModuleOutput()
    MillConsole(build.eval(task))
  }

  Feature("In a build that adds the plugin, Mill's test tasks behave as they do without it") {

    tasks.foreach { task =>
      val command = s"mill ${task.mkString(" ")}"

      Scenario(s"`$command` gives the same result and console output with the plugin as without it") {
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
