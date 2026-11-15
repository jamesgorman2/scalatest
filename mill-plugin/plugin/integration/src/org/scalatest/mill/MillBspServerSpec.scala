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

  /**
   * Each request's exchange with Mill's BSP server in the `mill-own-tasks` build without the plugin, then with it. Each
   * session sends every request in turn, on a build whose module has no output yet.
   */
  private lazy val exchanges: Map[String, BuildComparison[BspExchange]] =
    withTester("mill-own-tasks", daemonMode = true) { tester =>
      val withoutPlugin = exchangeAll(tester, "without-plugin")
      addPlugin(tester)
      val withPlugin = exchangeAll(tester, "with-plugin")
      requests.map(request => request -> BuildComparison(withoutPlugin(request), withPlugin(request))).toMap
    }

  // BSP deprecates buildTarget/scalaTestClasses, but Metals and IntelliJ still send it, and Mill still serves it.
  @nowarn("cat=deprecation")
  private def exchangeAll(tester: IntegrationTester, build: String): Map[String, BspExchange] = {
    MillBuild.removeModuleOutput(tester)
    val stderrLog = tester.workspacePath / os.up / s"${tester.workspacePath.last}-bsp-$build.log"
    try {
      BspClientSession.withSession(tester, millExecutable, stderrLog) { session =>
        val (targets, buildTargets) = session.exchange(_.workspaceBuildTargets())
        val coreTest = BspClientSession.targetsNamed("core.test", targets.toSeq.flatMap(_.getTargets.asScala))
        withClue(buildTargets.describe) {
          coreTest.asScala should not be empty
        }
        val (_, scalaTestClasses) = session.exchange(_.buildTargetScalaTestClasses(new ScalaTestClassesParams(coreTest)))
        val (_, jvmTestEnvironment) = session.exchange(_.buildTargetJvmTestEnvironment(new JvmTestEnvironmentParams(coreTest)))
        val (_, test) = session.exchange(_.buildTargetTest(new TestParams(coreTest)))
        requests.zip(Seq(buildTargets, scalaTestClasses, jvmTestEnvironment, test)).toMap
      }
    } finally {
      tester.removeProcessIdFile()
    }
  }

  Feature("In a build that adds the plugin, Mill's BSP server answers an IDE's requests as it does without it") {

    requests.foreach { request =>
      Scenario(request) {
        Given("core.test holds CalculatorSuite, whose test writes dividing now to stdout, and StackFlatSpec, whose test fails")
        When(s"a BSP client connected to Mill's BSP server sends $request, in the build without the plugin, and in the same build with it")
        val exchange = exchanges(request)

        withClue(exchange.describe(_.describe)) {
          Then("the client receives the same response and notifications under both, apart from durations, timestamps and task IDs")
          exchange.withPlugin.response shouldBe exchange.withoutPlugin.response
          exchange.withPlugin.notifications shouldBe exchange.withoutPlugin.notifications

          And("no notification under either holds [core.test] or Output outside any suite:")
          all(exchange.withoutPlugin.notifications ++ exchange.withPlugin.notifications) should (
            not include "[core.test]" and not include "Output outside any suite:"
          )
        }
      }
    }
  }
}
