package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class MillBspServerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val requests: Seq[String] = Seq(
    "workspace/buildTargets",
    "buildTarget/scalaTestClasses for core.test",
    "buildTarget/jvmTestEnvironment for core.test",
    "buildTarget/test for core.test"
  )

  private lazy val sessions: BuildComparison[BspSessionRecord] =
    withFixtureBuild(FixtureName.millOwnTasks, daemonMode = true) { build =>
      val withoutPlugin = recordSession(build, "without-plugin")
      build.addPlugin()
      BuildComparison(withoutPlugin, recordSession(build, "with-plugin"))
    }

  private def recordSession(build: FixtureBuild, pluginState: String): BspSessionRecord = {
    build.removeModuleOutput()
    try {
      val session = build.resultWithBspNotifications(build.workspace / s"bsp-$pluginState.log") { session =>
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
      BspSessionRecord(session.result, session.notifications)
    } finally {
      build.removeProcessIdFile()
    }
  }

  Feature("In a build that adds the plugin, Mill's BSP server answers an IDE's requests as it does without it") {

    requests.filterNot(_ == testRequest).foreach { request =>
      Scenario(request) {
        givenTheFixture()
        When("the session runs in a build that adds the plugin, and in the same build without it")
        Then(s"the client receives the same response to $request under both, apart from durations, timestamps and task IDs")
        sameResponses(request)
      }
    }

    Scenario(s"$testRequest, which Mill 1.3.1 fails with or without the plugin") {
      givenTheFixture()
      When("the session runs in a build that adds the plugin, and in the same build without it")
      Then(s"the client receives an error in response to $testRequest, and the same one under both")
      sessions.withoutPlugin.responses(testRequest) should startWith("error ")
      sameResponses(testRequest)
    }

    Scenario("the notifications of a session that sends every request") {
      givenTheFixture()
      When("the session runs in a build that adds the plugin, and in the same build without it")
      withClue(sessions.describe(_.describe)) {
        Then("the client receives the same notifications in the session under both, apart from durations, timestamps and task IDs")
        sessions.withoutPlugin.notifications should not be empty
        sessions.withPlugin.notifications shouldBe sessions.withoutPlugin.notifications

        And("no notification under either holds [core.test]")
        all(sessions.withoutPlugin.notifications ++ sessions.withPlugin.notifications) should not include "[core.test]"
      }
    }
  }

  private def givenTheFixture(): Unit = {
    Given("a BSP client holds one session with Mill's BSP server, from initialization until the server closes the connection")
    And("in that session the client sends each of workspace/buildTargets, buildTarget/scalaTestClasses for core.test, buildTarget/jvmTestEnvironment for core.test and buildTarget/test for core.test")
  }

  private def testRequest: String = requests.last

  private def sameResponses(request: String): Unit =
    withClue(sessions.describe(_.describe)) {
      sessions.withPlugin.responses(request) shouldBe sessions.withoutPlugin.responses(request)
    }
}
