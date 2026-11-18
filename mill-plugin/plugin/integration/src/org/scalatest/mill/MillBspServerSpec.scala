package org.scalatest.mill

import ch.epfl.scala.bsp4j.JvmTestEnvironmentParams
import ch.epfl.scala.bsp4j.ScalaTestClassesParams
import ch.epfl.scala.bsp4j.TestParams
import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

final class MillBspServerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val requests: Seq[String] = Seq(
    "workspace/buildTargets",
    "buildTarget/scalaTestClasses for core.test",
    "buildTarget/jvmTestEnvironment for core.test",
    "buildTarget/test for core.test"
  )

  private lazy val sessions: BuildComparison[BspSessionRecord] =
    withTester("mill-own-tasks", daemonMode = true) { tester =>
      val withoutPlugin = recordSession(tester, "without-plugin")
      addPlugin(tester)
      BuildComparison(withoutPlugin, recordSession(tester, "with-plugin"))
    }

  // BSP deprecates buildTarget/scalaTestClasses, but Metals and IntelliJ still send it, and Mill still serves it.
  @nowarn("cat=deprecation")
  private def recordSession(tester: IntegrationTester, build: String): BspSessionRecord = {
    MillBuild.removeModuleOutput(tester)
    val stderrLog = tester.workspacePath / os.up / s"${tester.workspacePath.last}-bsp-$build.log"
    try {
      val (responses, notifications) = BspClientSession.withSession(tester, millExecutable, stderrLog) { session =>
        val (targets, buildTargets) = session.exchange(_.workspaceBuildTargets())
        val coreTest = BspClientSession.targetsNamed("core.test", targets.toSeq.flatMap(_.getTargets.asScala))
        withClue(buildTargets) {
          coreTest.asScala should not be empty
        }
        val (_, scalaTestClasses) = session.exchange(_.buildTargetScalaTestClasses(new ScalaTestClassesParams(coreTest)))
        val (_, jvmTestEnvironment) = session.exchange(_.buildTargetJvmTestEnvironment(new JvmTestEnvironmentParams(coreTest)))
        val (_, test) = session.exchange(_.buildTargetTest(new TestParams(coreTest)))
        requests.zip(Seq(buildTargets, scalaTestClasses, jvmTestEnvironment, test)).toMap
      }
      BspSessionRecord(responses, notifications)
    } finally {
      tester.removeProcessIdFile()
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
