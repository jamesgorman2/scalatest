package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class EnvironmentOfEachWorkerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SingleModuleFixture {

  Feature("The environment of each worker") {

    val sentVariables = Seq("FIXTURE_OUTER", "FIXTURE_FORK", "FIXTURE_BOTH", "MILL_WORKSPACE_ROOT")

    def scalaString(text: String): String = "\"" + text + "\""

    def sendingVariables(names: Seq[String]): String =
      s"""Seq(${names.map(scalaString).mkString(", ")}).map(name => name + "=" + sys.env.getOrElse(name, "absent")).mkString("\\n")"""

    def variablesIn(message: String): Map[String, String] =
      message.linesIterator.map { line =>
        val (name, value) = line.splitAt(line.indexOf('='))
        name -> value.drop(1)
      }.toMap

    def daemonDescription(daemonMode: Boolean): String =
      if (daemonMode) "under a Mill daemon started in another environment" else "with no Mill daemon"

    def startDaemonInAnotherEnvironment(build: FixtureBuild): Unit = build.compile()

    def startDaemonThroughMillBat(build: FixtureBuild): Unit = build.compileThroughMillBat()

    for ((daemonMode, propagate, expectedOuterValue) <- Seq((false, false, "absent"), (true, true, "from-outer"))) {
      val receives = if (propagate) "receives" else "does not receive"
      Scenario(s"With propagateEnv $propagate, a worker $receives FIXTURE_OUTER from the environment in which mill started, ${daemonDescription(daemonMode)}") {
        Given(s"the module's propagateEnv is $propagate")
        And("the module's forkEnv sets FIXTURE_FORK to from-forkEnv and FIXTURE_BOTH to from-forkEnv")
        if (daemonMode) {
          And("a Mill daemon that an earlier mill command started in an environment setting neither FIXTURE_OUTER nor FIXTURE_BOTH")
        }
        And("the environment in which mill starts sets FIXTURE_OUTER to from-outer and FIXTURE_BOTH to from-outer")
        And(s"Alpha1's test fast sends an info message holding the environment variables ${EnglishList.of(sentVariables)}, each absent where it is not set")
        When("the test command runs core.test, writing JSON output, with -- samples.Alpha1")
        withMarkingSuites(daemonMode) { build =>
          build.addToTestModule(
            s"def propagateEnv = $propagate",
            """def forkEnv = Task { Map("FIXTURE_FORK" -> "from-forkEnv", "FIXTURE_BOTH" -> "from-forkEnv") }"""
          )
          makeAlpha1FastSendInfo(build, sendingVariables(sentVariables))
          if (daemonMode) {
            startDaemonInAnotherEnvironment(build)
          }
          val (result, json) = build.resultAndJsonOutput(
            build.runTestCommandWritingJsonOutputWith(
              FixtureBuild.jobsArguments(2),
              Map("FIXTURE_OUTER" -> "from-outer", "FIXTURE_BOTH" -> "from-outer")
            )("--", "samples.Alpha1")
          )
          val variables = infoMessages(json).map(variablesIn)

          Then(s"the info message holds FIXTURE_OUTER $expectedOuterValue, FIXTURE_FORK and FIXTURE_BOTH from-forkEnv, and MILL_WORKSPACE_ROOT the absolute path of the build's workspace")
          withClue(result.debugString) {
            variables.map(_.removed("MILL_WORKSPACE_ROOT")) shouldBe
              Seq(Map("FIXTURE_OUTER" -> expectedOuterValue, "FIXTURE_FORK" -> "from-forkEnv", "FIXTURE_BOTH" -> "from-forkEnv"))
            variables.map(_.get("MILL_WORKSPACE_ROOT").map(os.FilePath(_))) shouldBe Seq(Some(build.workspace))
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }

    for (daemonMode <- Seq(false, true)) {
      val otherThanNoDaemon = if (daemonMode) "" else " other than --no-daemon"
      Scenario(s"On Windows, a worker receives MILL_PARAMS holding the arguments mill.bat was given$otherThanNoDaemon, ${daemonDescription(daemonMode)}") {
        assume(scala.util.Properties.isWin, "mill.bat, which sets MILL_PARAMS, starts Mill on Windows only")
        Given("the build runs on Windows, where mill starts through its mill.bat launcher")
        And("the module's propagateEnv is true")
        And("Alpha1's test fast sends an info message holding the environment variable MILL_PARAMS")
        if (daemonMode) {
          And("a Mill daemon that an earlier mill.bat resolve _ started, in an environment whose MILL_PARAMS is resolve _")
        }
        val noDaemonFirst = if (daemonMode) "" else "--no-daemon first, then "
        When(s"the test command runs core.test through mill.bat, writing JSON output, with $noDaemonFirst-j 2 and -- samples.Alpha1")
        withMarkingSuites(daemonMode) { build =>
          build.addToTestModule("def propagateEnv = true")
          makeAlpha1FastSendInfo(build, """sys.env.getOrElse("MILL_PARAMS", "absent")""")
          if (daemonMode) {
            startDaemonThroughMillBat(build)
          }
          val (result, json) =
            build.resultAndJsonOutput(build.runTestCommandThroughMillBatWritingJsonOutputWith(FixtureBuild.jobsArguments(2))("--", "samples.Alpha1"))

          Then(s"the info message is -j 2 scalatest/test core.test -fJ <report> -- samples.Alpha1, the arguments mill.bat was given$otherThanNoDaemon")
          withClue(result.debugString) {
            infoMessages(json) shouldBe
              Seq(Seq("-j", "2", "scalatest/test", "core.test", "-fJ", build.jsonOutputFile.toString, "--", "samples.Alpha1").mkString(" "))
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }
  }
}
