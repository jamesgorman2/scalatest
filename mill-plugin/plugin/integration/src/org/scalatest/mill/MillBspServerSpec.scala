package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.MillCoexistence

@MillCoexistence
final class MillBspServerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val answeredRequests: Seq[(String, String)] = Seq(
    "workspace/buildTargets" -> "lists the build's targets",
    "buildTarget/scalaTestClasses for core.test" -> "lists core.test's ScalaTest classes",
    "buildTarget/jvmTestEnvironment for core.test" -> "gives core.test's JVM test environment"
  )

  private val testRequest: String = "buildTarget/test for core.test"

  private val requests: Seq[String] = answeredRequests.map { case (request, _) => request } :+ testRequest

  private lazy val sessions: BuildComparison[BspSessionRecord] =
    withFixtureBuild(FixtureName.millOwnTasks, daemonMode = true) { build =>
      val withoutPlugin = recordSession(build, "without-plugin")
      build.addPlugin()
      BuildComparison(withoutPlugin, recordSession(build, "with-plugin"))
    }

  private def recordSession(build: FixtureBuild, pluginState: String): BspSessionRecord = {
    build.removeModuleOutput()
    try {
      val recorded = build.resultWithBspNotifications(build.workspace / s"bsp-$pluginState.log") { session =>
        val buildTargets = session.buildTargets()
        val coreTest = buildTargets.result
          .map(_.named("core.test"))
          .filterNot(_.isEmpty)
          .getOrElse(fail(s"no build target is named core.test in ${buildTargets.recorded}"))
        val scalaTestClasses = session.scalaTestClasses(coreTest)
        val jvmTestEnvironment = session.jvmTestEnvironment(coreTest)
        val test = session.test(coreTest)
        requests.zip(Seq(buildTargets.recorded, scalaTestClasses, jvmTestEnvironment, test)).toMap
      }
      BspSessionRecord(recorded.result, recorded.notifications)
    } finally {
      build.removeProcessIdFile()
    }
  }

  Feature("In a build that adds the plugin, Mill's BSP server answers an IDE's requests as it does without it") {

    answeredRequests.foreach { case (request, answer) =>
      Scenario(s"The server $answer the same with the plugin as without it") {
        givenTheSession()
        val compared = whenTheSessionRunsWithAndWithoutThePlugin()
        Then(s"the client receives the same response to $request under both, apart from durations, timestamps and task IDs")
        shouldGiveTheSameResponses(compared, request)
      }
    }

    Scenario("The server fails a request to run core.test's tests with the same error whether or not the build adds the plugin") {
      givenTheSession()
      val compared = whenTheSessionRunsWithAndWithoutThePlugin()
      Then(s"the client receives an error in response to $testRequest, and the same one under both")
      compared.withoutPlugin.responses(testRequest) should startWith("error ")
      shouldGiveTheSameResponses(compared, testRequest)
    }

    Scenario("The server sends the same notifications with the plugin as without it, none holding the module's line") {
      givenTheSession()
      val compared = whenTheSessionRunsWithAndWithoutThePlugin()
      withClue(compared.describe(_.describe)) {
        Then("the client receives the same notifications in the session under both, apart from durations, timestamps and task IDs")
        compared.withoutPlugin.notifications should not be empty
        compared.withPlugin.notifications shouldBe compared.withoutPlugin.notifications

        And("no notification under either holds [core.test]")
        all(compared.withoutPlugin.notifications ++ compared.withPlugin.notifications) should not include "[core.test]"
      }
    }
  }

  private def givenTheSession(): Unit = {
    Given("a BSP client holds one session with Mill's BSP server, from initialization until the server closes the connection")
    And("in that session the client sends each of workspace/buildTargets, buildTarget/scalaTestClasses for core.test, buildTarget/jvmTestEnvironment for core.test and buildTarget/test for core.test")
  }

  private def whenTheSessionRunsWithAndWithoutThePlugin(): BuildComparison[BspSessionRecord] = {
    When("the session runs in a build that adds the plugin, and in the same build without it")
    sessions
  }

  private def shouldGiveTheSameResponses(compared: BuildComparison[BspSessionRecord], request: String): Unit =
    withClue(compared.describe(_.describe)) {
      compared.withPlugin.responses(request) shouldBe compared.withoutPlugin.responses(request)
    }
}
