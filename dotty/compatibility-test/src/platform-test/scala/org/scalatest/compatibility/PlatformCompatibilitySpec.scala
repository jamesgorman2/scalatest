package org.scalatest.compatibility

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tags.Slow

@Slow
final class PlatformCompatibilitySpec extends AnyFeatureSpec with GivenWhenThen with Matchers {

  private lazy val runs: PlatformRuns = PlatformRuns.load()

  private val runnerArgumentLists: List[List[String]] =
    Nil :: List("", "DSIV", "FTK", "GW", "DU", "J", "NCXEHLOPQRM").map(characters => List("-o" + characters))

  private val statusesOfTheFixture = List("Success", "Failure", "Ignored", "Pending", "Canceled", "Error")

  Feature(s"Compatibility with ScalaTest 3.2.20 on ${CompatibilityPlatform.name}") {
    runnerArgumentLists.foreach { arguments =>
      val withArguments = if (arguments.isEmpty) "no runner arguments" else "the runner arguments " + arguments.mkString(" ")

      Scenario(s"A build tool given $withArguments sends its event handler and loggers what 3.2.20 sends them") {
        Given(s"a set of suites holding at least one suite of each style the build for ${CompatibilityPlatform.name} ships, compiled once against ScalaTest 3.2.20 for ${CompatibilityPlatform.name}")
        When(s"a build tool runs it as linked with 3.2.20 and as linked with this fork, given $withArguments")
        val output = runs.ofFixture(arguments)

        Then("each run ends normally")
        output.map(_.exitCode) shouldBe Compared(0, 0)

        And("3.2.20's run records the event handler's events, the loggers' lines and the summary")
        List("event handler: ", "logger: ", "summary: ").filterNot(prefix => output.upstream.stdout.exists(_.startsWith(prefix))) shouldBe Nil

        And("the event handler receives an event of every status the fixture is meant to send")
        statusesOfTheFixture.filterNot(status => output.upstream.stdout.exists(_.contains("status=" + status + ","))) shouldBe Nil

        And("the fork's run prints on stdout every line 3.2.20's prints, in the same order")
        Compared.firstDifference(output.map(_.stdout)) shouldBe None

        And("the fork's run prints on stderr every line 3.2.20's prints, in the same order")
        Compared.firstDifference(output.map(_.stderr)) shouldBe None
      }
    }
  }
}
