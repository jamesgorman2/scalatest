package org.scalatest.compatibility

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class UpstreamCompatibilitySpec extends AnyFeatureSpec with GivenWhenThen with Matchers {

  private val runs = new ForkedRuns(ComparisonClasspaths.load())

  private val eventKindsOfTheFixture = List(
    "SuiteStarting",
    "SuiteCompleted",
    "SuiteAborted",
    "ScopeOpened",
    "ScopeClosed",
    "ScopePending",
    "TestStarting",
    "TestSucceeded",
    "TestFailed",
    "TestIgnored",
    "TestPending",
    "TestCanceled",
    "InfoProvided",
    "NoteProvided",
    "AlertProvided",
    "MarkupProvided"
  )

  private val reporterCharacterLists = List("", "DSIV", "FTK", "GW", "DU", "J", "NCXEHLOPQRM")

  private def reporterArguments(characters: String): Seq[String] =
    List("-o" + characters, "-e" + characters, "-f" + characters, "report.txt")

  Feature("Compatibility with ScalaTest 3.2.20") {
    Scenario("A recording reporter of a Runner.run launch receives what 3.2.20 sends it, with the emitter off") {
      Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
      When("Runner.run runs it on 3.2.20 and on this fork, its one reporter a -C recording reporter")
      val events = runs.ofFixture(Launch.RunnerRun, List("-C", "compatibility.recorders.RecordingReporter")).map(_.events)

      Then("the reporter receives on this fork the events it receives on 3.2.20")
      Compared.firstDifference(events) shouldBe None

      And("the events are of every kind the fixture is to send")
      eventKindsOfTheFixture.filterNot(kind => events.upstream.exists(_.startsWith(kind + "("))) shouldBe Nil
    }

    Scenario("The test-interface event handler of a Framework launch receives what 3.2.20 sends it, with the emitter off") {
      Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
      When("Framework runs it on 3.2.20 and on this fork, its runner given no argument")
      val events = runs.ofFixture(Launch.Framework, Nil).map(_.events)

      Then("the event handler receives on this fork the events it receives on 3.2.20")
      events.upstream should not be empty
      Compared.firstDifference(events) shouldBe None
    }

    Scenario("The loggers of a Framework launch given -oDU receive the lines they receive on 3.2.20, with the emitter off") {
      Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
      When("Framework runs it on 3.2.20 and on this fork, its runner given -oDU")
      val loggerLines = runs.ofFixture(Launch.Framework, List("-oDU")).map(output => ReporterText.normalised(output.loggerLines))

      Then("the loggers receive on this fork the lines they receive on 3.2.20")
      loggerLines.upstream should not be empty
      Compared.firstDifference(loggerLines) shouldBe None
    }
  }

  Feature("Behaviour unchanged from ScalaTest 3.2.20") {
    Scenario("The classes of org.scalatest.events keep their fields and constructor parameters") {
      When("the classes of org.scalatest.events are read on 3.2.20 and on this fork")
      val shapes = runs.ofEventClassShapes()

      Then("each class 3.2.20 has keeps its fields and constructor parameters, and no event class is added or removed")
      val upstreamClasses = shapes.upstream.map(classOf).toSet
      val comparable = shapes.copy(fork = shapes.fork.filter(shape => shape.startsWith("event class ") || upstreamClasses.contains(classOf(shape))))
      shapes.upstream should not be empty
      Compared.firstDifference(comparable) shouldBe None
    }

    for {
      launch <- Launch.values.toList
      characters <- reporterCharacterLists
    } {
      val arguments = reporterArguments(characters)
      Scenario(s"The reporters of a $launch launch given ${arguments.mkString(" ")} write what 3.2.20's write, with the emitter off") {
        Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
        When(s"$launch runs it on 3.2.20 and on this fork with ${arguments.mkString(" ")}")
        val output = runs.ofFixture(launch, arguments)
        val text = output.map(_.reporterText)

        Then("each run's reporters write to stdout, to stderr and to the file")
        text.fork.keySet shouldBe text.upstream.keySet
        text.upstream("file report.txt") should not be empty

        And("the fixture's own lines are on each run's stdout and stderr")
        output.map(_.stdout.count(_ == CompatibilityFixture.stdoutLine)) shouldBe Compared(1, 1)
        output.map(_.stderr.count(_ == CompatibilityFixture.stderrLine)) shouldBe Compared(1, 1)

        And("each reporter writes on this fork every line it writes on 3.2.20")
        text.upstream.keys.toList.sorted.flatMap { name =>
          Compared.firstDifference(text.map(lines => ReporterText.normalised(lines(name)))).map(name + ", " + _)
        } shouldBe Nil
      }
    }
  }

  private def classOf(shape: String): String = shape.split(' ').dropWhile(_ != "class")(1)
}
