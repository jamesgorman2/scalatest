package org.scalatest.compatibility

import compatibility.normalisation.ReporterText
import org.scalatest.GivenWhenThen
import org.scalatest.events.DecodedEvent
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tags.Slow
import org.scalatest.tools.TestEventLogArgument

import java.util.concurrent.Executors

@Slow
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

  private def reporterDestinations(launch: Launch): List[String] =
    launch match {
      case Launch.RunnerRun => List("stdout", "stderr", "file report.txt")
      case Launch.Framework => List("loggers", "stderr", "file report.txt")
    }

  Feature("Compatibility with ScalaTest 3.2.20") {
    Scenario("A recording reporter of a Runner.run launch receives what 3.2.20 sends it, with the emitter off") {
      Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
      When("each run is launched through Runner.run, its one reporter a -C recording reporter compiled against 3.2.20")
      And("this fork's run is not given the runner argument")
      val events = runs.ofFixture(Launch.RunnerRun, List("-C", "compatibility.recorders.RecordingReporter")).map(_.events)

      Then("the reporter receives on this fork the events it receives on 3.2.20")
      Compared.firstDifference(events) shouldBe None

      And("the events include every kind the fixture is meant to send")
      eventKindsOfTheFixture.filterNot(kind => events.upstream.exists(_.startsWith(kind + "("))) shouldBe Nil
    }

    Emitter.values.toList.foreach { emitter =>
      Scenario(s"The test-interface event handler of a Framework launch receives what 3.2.20 sends it, ${described(emitter)}") {
        Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
        When("each run is launched through Framework, with no other argument given to its runner")
        And(s"this fork's run ${if (emitter == Emitter.On) "is also given" else "is not given"} the runner argument")
        val events = runs.ofFixture(Launch.Framework, Nil, emitter).map(_.events)

        Then("the event handler receives on this fork the events it receives on 3.2.20")
        events.upstream should not be empty
        Compared.firstDifference(events) shouldBe None
      }
    }

    Scenario("The loggers of a Framework launch given -oDU receive the lines they receive on 3.2.20, with the emitter off") {
      Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
      When("each run is launched through Framework, with -oDU given to its runner")
      And("this fork's run is not given the runner argument")
      val output = runs.ofFixture(Launch.Framework, List("-oDU"))
      val loggerLines = output.map(run => ReporterText.normalised(run.loggerLines))

      Then("the lines the runner's test-interface loggers receive on this fork are those they receive on 3.2.20, apart from the durations the lines show")
      And("each run of stack-frame lines, the at lines printed for a throwable's frames, counts in that comparison as one line, whatever it holds")
      loggerLines.upstream should not be empty
      Compared.firstDifference(loggerLines) shouldBe None

      And("the runner's done returns on this fork the summary it returns on 3.2.20")
      firstDifferenceOfSummaries(output) shouldBe None
    }
  }

  Feature("What the event log keeps of each event") {
    Launch.values.toList.foreach { launch =>
      Scenario(s"The event log of a ${launch.named} launch holds every event the emitter receives, in the emitter's order") {
        Given("a set of suites holding at least one suite of each style, compiled against ScalaTest 3.2.20")
        And("a test of an AnyFunSuite and a test of a PathAnyFunSpec each send a message, then wait before they close")
        When(s"${launch.named} runs them on this fork with the runner argument and a -C reporter that records every field the event log keeps")
        val output = runs.ofFixtureOnFork(
          launch,
          List("-C", "compatibility.recorders.EventFieldsRecordingReporter"),
          Emitter.On,
          CompatibilityFixture.messageCallTimesSuiteClassNames
        )
        val logged = decoded(output.eventLog).collect { case event: TestEventLogEvent.ScalaTestEvent => event }

        Then(s"the event log holds each event the emitter receives, with the fields the reporter records, in the emitter's order ${emitterOrderDescribed(launch)}")
        And("the timeStamp of each event in the event log that is not a message recorded in a test's closing event equals the timeStamp of the event as the emitter received it")
        And("the timestamp of each message recorded in a test's closing event is the time the test called the message")
        output.eventFields.count(_.startsWith("received ")) should be > 0
        output.eventFields.count(_.startsWith("recorded ")) should be > 0
        EventLogCoverage.missing(output.eventFields, logged, emitterOrderIn(launch)) shouldBe Nil
        timedMessagesNotStampedWithTheirCall(logged) shouldBe Nil
      }
    }
  }

  Feature("The event log and reporters") {
    List(
      List("-oDF"),
      List("-oNCXEHLOPQRM"),
      List("-e", "-C", "compatibility.recorders.RecordingReporter")
    ).foreach { reporters =>
      Scenario(s"A Runner.run launch writes the same event log with ${reporters.mkString(" ")} as with no reporter argument") {
        Given("a set of suites holding at least one suite of each style, compiled against ScalaTest 3.2.20")
        When(s"Runner.run runs it on this fork with the runner argument, once with no reporter argument and once with ${reporters.mkString(" ")}")
        val withoutReporters = runs.ofFixtureOnFork(Launch.RunnerRun, Nil, Emitter.On).eventLog
        val withReporters = runs.ofFixtureOnFork(Launch.RunnerRun, reporters, Emitter.On).eventLog

        Then("the two event logs hold the same events, apart from timestamps, durations and thread names")
        withoutReporters should not be empty
        val comparable = Compared(withoutReporters, withReporters).map(_.map(withoutTimesAndThreads))
        Compared.firstDifference(comparable) shouldBe None
      }
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

    Launch.values.toList.foreach { launch =>
      Scenario(s"A $launch launch not given the runner argument creates no file and reports no thread that 3.2.20 does not") {
        Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
        When(s"$launch runs it on 3.2.20 and on this fork, with a -f reporter and a -C reporter that records the run's threads")
        val output = runs.ofFixture(launch, List("-f", "report.txt", "-C", "compatibility.recorders.ThreadRecordingReporter"))

        Then("the fork's run leaves in its working directory only the files 3.2.20's leaves")
        output.upstream.files.keySet shouldBe Set("report.txt")
        output.fork.files.keySet.diff(output.upstream.files.keySet) shouldBe Set.empty

        And("Thread.getAllStackTraces in the fork's run reports no thread that it does not report for the same run on 3.2.20")
        output.upstream.threadNames should contain("ScalaTest-dispatcher")
        output.fork.threadNames.diff(output.upstream.threadNames) shouldBe Set.empty
      }
    }

    for {
      launch <- Launch.values.toList
      characters <- reporterCharacterLists
    } {
      val arguments = reporterArguments(characters)
      Scenario(s"The reporters of a $launch launch given ${arguments.mkString(" ")} write what 3.2.20's write, with the emitter off") {
        Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
        When(s"$launch runs it on 3.2.20 and on this fork with ${arguments.mkString(" ")}, with the emitter off")
        val output = runs.ofFixture(launch, arguments)
        val text = output.map(_.reporterText)

        val destinations = reporterDestinations(launch)
        Then(s"each run's reporters write to ${destinations.mkString(", ")}")
        text.fork.keySet shouldBe text.upstream.keySet
        destinations.filter(name => text.upstream(name).isEmpty || text.fork(name).isEmpty) shouldBe Nil

        And("each run writes the fixture's own lines once, one to stdout and one to stderr")
        output.map(run => RunOutput.timesWritten(CompatibilityFixture.stdoutLine, run.stdout)) shouldBe Compared(1, 1)
        output.map(run => RunOutput.timesWritten(CompatibilityFixture.stderrLine, run.stderr)) shouldBe Compared(1, 1)

        And("each reporter writes on this fork every line it writes on 3.2.20")
        text.upstream.keys.toList.sorted.flatMap { name =>
          Compared.firstDifference(text.map(lines => ReporterText.normalised(lines(name)))).map(name + ", " + _)
        } shouldBe Nil

        if (launch == Launch.Framework) {
          And("the runner's done returns on this fork the summary it returns on 3.2.20")
          firstDifferenceOfSummaries(output) shouldBe None
        }
      }
    }

    Scenario("The reporter of a Framework launch given no reporter argument writes what 3.2.20's writes, with the emitter off") {
      Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
      When("Framework runs it on 3.2.20 and on this fork with no reporter argument, with the emitter off")
      val output = runs.ofFixture(Launch.Framework, Nil)
      val text = output.map(_.reporterText)

      Then("the reporter ScalaTest uses when no reporter is configured writes to the loggers on each run")
      output.map(_.loggerLines.isEmpty) shouldBe Compared(false, false)

      And("each run writes the fixture's own lines once, one to stdout and one to stderr")
      output.map(run => RunOutput.timesWritten(CompatibilityFixture.stdoutLine, run.stdout)) shouldBe Compared(1, 1)
      output.map(run => RunOutput.timesWritten(CompatibilityFixture.stderrLine, run.stderr)) shouldBe Compared(1, 1)

      And("it writes on this fork every line, in every colour, that it writes on 3.2.20, each run of stack-frame lines counting as one line")
      text.fork.keySet shouldBe text.upstream.keySet
      text.upstream.keys.toList.sorted.flatMap { name =>
        Compared.firstDifference(text.map(lines => ReporterText.normalised(lines(name)))).map(name + ", " + _)
      } shouldBe Nil

      And("the runner's done returns on this fork the summary it returns on 3.2.20")
      firstDifferenceOfSummaries(output) shouldBe None
    }

    Launch.values.toList.foreach { launch =>
      Scenario(s"A $launch launch given the runner argument and no reporter argument prints nothing") {
        Given("the compatibility fixture, compiled against ScalaTest 3.2.20")
        When(s"$launch runs it on this fork with the runner argument and no reporter argument")
        val output = runs.ofFixtureOnFork(launch, Nil, Emitter.On)

        Then("it writes the fixture's own lines once, one to stdout and one to stderr")
        RunOutput.timesWritten(CompatibilityFixture.stdoutLine, output.stdout) shouldBe 1
        RunOutput.timesWritten(CompatibilityFixture.stderrLine, output.stderr) shouldBe 1

        And("no reporter writes to stdout, stderr, the loggers or a file")
        output.reporterText.filter((_, lines) => lines.nonEmpty) shouldBe empty
      }
    }

    Launch.values.toList.foreach { launch =>
      Scenario(s"A $launch launch accepts and rejects each argument list without the runner argument as 3.2.20's does") {
        Given("argument lists that do not hold the runner argument, which 3.2.20 accepts, and others that it rejects")
        val argumentLists = argumentListsOf(launch)

        When(s"$launch is given each, on 3.2.20 and on this fork")
        val outcomes = inParallel(argumentLists.map(arguments => () => arguments -> runs.ofArguments(launch, arguments)))

        Then("this fork accepts each list 3.2.20 accepts, and rejects each list 3.2.20 rejects with the same message")
        outcomes.flatMap((arguments, outcome) => firstDifferenceOfOutcomes(outcome).map(arguments.mkString("[", " ", "]: ") + _)) shouldBe Nil

        And("3.2.20 accepts some of the lists and rejects others")
        outcomes.map((_, outcome) => isAccepted(outcome.upstream)).toSet shouldBe Set(true, false)
      }

      Scenario(s"A $launch launch given the runner argument once, with a file, accepts or rejects the rest of the list as 3.2.20's does") {
        Given("argument lists that do not hold the runner argument, which 3.2.20 accepts, and others that it rejects")
        val argumentLists = argumentListsOf(launch)

        When(s"$launch is given each on 3.2.20, and on this fork after the runner argument naming a file")
        val outcomes = inParallel(argumentLists.map(arguments => () => arguments -> runs.ofArgumentsGivingTheForkTheRunnerArgument(launch, arguments)))

        Then("this fork accepts each list whose rest 3.2.20 accepts")
        outcomes.collect { case (arguments, outcome) if isAccepted(outcome.upstream) && !isAccepted(outcome.fork) => arguments } shouldBe Nil

        And("it rejects each list whose rest 3.2.20 rejects, with the message 3.2.20 gives for the rest")
        outcomes.flatMap { (arguments, outcome) =>
          if (isAccepted(outcome.upstream)) None else firstDifferenceOfOutcomes(outcome).map(arguments.mkString("[", " ", "]: ") + _)
        } shouldBe Nil

        And("3.2.20 accepts some of the lists and rejects others")
        outcomes.map((_, outcome) => isAccepted(outcome.upstream)).toSet shouldBe Set(true, false)
      }

      Scenario(s"A $launch launch rejects the runner argument without a file, or given twice") {
        Given("an argument list that holds the runner argument's flag with no file after it, and one that holds the runner argument twice")
        val runnerArgumentLists = List[java.nio.file.Path => Seq[String]](
          _ => List("-o", TestEventLogArgument.flag),
          eventLogFile => "-o" :: TestEventLogArgument.forFile(eventLogFile.toString) ::: TestEventLogArgument.forFile(eventLogFile.toString)
        )

        When(s"$launch is given each on this fork")
        val outcomes = inParallel(runnerArgumentLists.map(arguments => () => runs.ofArgumentsNamingAFile(launch, arguments)))

        Then("this fork rejects each")
        outcomes.map(outcome => isAccepted(outcome.fork)) shouldBe List(false, false)
      }
    }
  }

  private val argumentListsOfBothLaunches: List[List[String]] = List(
    List("-o"),
    List("-oDF", "-eW", "-f", "report.txt"),
    List("-o", "-n", "TagA TagB", "-l", "TagC"),
    List("-o", "-DkeyA=valueA"),
    List("-o", "-P4"),
    List("-o", "-PS"),
    List("-o", "-F", "2.5"),
    List("-o", "-T", "5"),
    List("-o", "-W", "60", "60"),
    List("-o", "-S", "42"),
    List("-o", "-y", "org.scalatest.funsuite.AnyFunSuite"),
    List("-o", "-m", "compatibility.fixture"),
    List("-o", "-w", "compatibility.fixture"),
    List("-o", "-q", "Spec"),
    List("-o", "-R", "classes"),
    List("-o", "-s", "compatibility.fixture.FirstNestedSpec", "-z", "succeeds"),
    List("-o", "-C", "compatibility.recorders.RecordingReporter"),
    List("-o", "-u", "junit-reports"),
    List("-x"),
    List("unexpected"),
    List("-oZ"),
    List("-oA"),
    List("-o", "-o"),
    List("-o", "-W"),
    List("-o", "-W", "sixty", "60"),
    List("-o", "-F"),
    List("-o", "-F", "large"),
    List("-o", "-S", "seed"),
    List("-o", "-T", "soon"),
    List("-o", "-Pmany"),
    List("-o", "-z", "succeeds"),
    List("-o", "-s"),
    List("-o", "-C"),
    List("-o", "-C", "compatibility.recorders.NoSuchReporter"),
    List("-o", "-f")
  )

  // Only Framework is given -g: Runner.run on 3.2.20 opens the graphic reporter's window and waits for it to be closed.
  private def argumentListsOf(launch: Launch): List[List[String]] =
    launch match {
      case Launch.RunnerRun => argumentListsOfBothLaunches
      case Launch.Framework => argumentListsOfBothLaunches :+ List("-g")
    }

  private def isAccepted(outcome: ArgumentsOutcome): Boolean =
    outcome.exitCode == 0 && outcome.outcome.exists(line => line == "accepted" || line.startsWith("returned "))

  private def firstDifferenceOfOutcomes(outcome: Compared[ArgumentsOutcome]): Option[String] = {
    def described(run: ArgumentsOutcome): Seq[String] =
      List("exit code " + run.exitCode) ++
        run.outcome ++
        ReporterText.normalised(run.stdout.linesIterator.toList).map("stdout: " + _) ++
        ReporterText.normalised(run.stderr.linesIterator.toList).map("stderr: " + _)
    Compared.firstDifference(outcome.map(described))
  }

  private def inParallel[A](work: Seq[() => A]): Seq[A] = {
    val pool = Executors.newFixedThreadPool(4)
    try {
      work.map(task => pool.submit(() => task())).map(_.get())
    } finally {
      pool.shutdown()
    }
  }

  // Framework's SuiteSortingReporter sits between the emitter and the recording reporter, and reorders events across suites.
  private def emitterOrderIn(launch: Launch): EmitterOrder =
    launch match {
      case Launch.RunnerRun => EmitterOrder.AcrossTheRun
      case Launch.Framework => EmitterOrder.WithinEachSuite
    }

  private def emitterOrderDescribed(launch: Launch): String =
    emitterOrderIn(launch) match {
      case EmitterOrder.AcrossTheRun => "across the run"
      case EmitterOrder.WithinEachSuite => "within each suite"
    }

  private def described(emitter: Emitter): String =
    emitter match {
      case Emitter.Off => "with the emitter off"
      case Emitter.On => "with the emitter on"
    }

  private def decoded(lines: Seq[String]): Seq[TestEventLogEvent] = {
    lines should not be empty
    lines.map(line => TestEventLogDecoder.decode(line).fold(reason => fail(s"$reason, in $line"), identity))
  }

  private val timesAndThreads = List(
    """"(timeStamp|duration|pid)":\d+""".r,
    """"(threadName|startTime)":"(?:[^"\\]|\\.)*"""".r
  )

  private def timedMessagesNotStampedWithTheirCall(logged: Seq[TestEventLogEvent.ScalaTestEvent]): Seq[String] = {
    val calls = logged.collect {
      case TestEventLogEvent.ScalaTestEvent(Some(execution), DecodedEvent.InfoProvided(CompatibilityFixture.timedMessageCalled(before, after), _, _, _, _)) =>
        (execution, before.toLong, after.toLong)
    }
    calls should have size CompatibilityFixture.messageCallTimesSuiteClassNames.size.toLong
    calls.flatMap { (execution, before, after) =>
      val messageStamp = logged.collectFirst {
        case TestEventLogEvent.ScalaTestEvent(Some(`execution`), DecodedEvent.InfoProvided(CompatibilityFixture.timedMessage, _, _, _, timeStamp)) => timeStamp
      }
      val closingStamp = logged.collectFirst {
        case TestEventLogEvent.ScalaTestEvent(Some(`execution`), DecodedEvent.TestSucceeded(_, _, _, timeStamp)) => timeStamp
      }
      (messageStamp, closingStamp) match {
        case (Some(stamp), Some(closed)) if before <= stamp && stamp <= after && closed > after => None
        case _ => Some(s"${execution.node}: called between $before and $after, stamped $messageStamp, closed $closingStamp")
      }
    }
  }

  private def withoutTimesAndThreads(line: String): String =
    timesAndThreads.foldLeft(line)((text, value) => value.replaceAllIn(text, "\"$1\":<not compared>"))

  private def firstDifferenceOfSummaries(output: Compared[RunOutput]): Option[String] = {
    val summaries = output.map(run => ReporterText.normalised(run.summary))
    summaries.upstream should not be empty
    Compared.firstDifference(summaries)
  }

  private def classOf(shape: String): String = shape.split(' ').dropWhile(_ != "class")(1)
}
