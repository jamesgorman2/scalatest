package org.scalatest.tools

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.GivenWhenThen
import org.scalatest.events.BuiltEvent
import org.scalatest.events.EventLogComparison
import org.scalatest.events.RunOutcome
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import sbt.testing.Event as SbtEvent
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Runner as SbtRunner
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef
import scala.util.Try

final class TestEventLogRunSpec extends AnyFeatureSpec with GivenWhenThen with Matchers {

  private enum Launch(val named: String) {
    case ByFramework extends Launch("Framework")
    case ByRunner extends Launch("Runner.run")
  }

  private final case class EndedRun(suiteClassName: String, description: String, launch: Launch, ended: String, outcome: RunOutcome)

  private val runsThatEnd = List(
    EndedRun("samples.SqrtPropSpec", "a suite whose one property succeeds", Launch.ByFramework, "completed", RunOutcome.Completed),
    EndedRun("samples.SqrtPropSpec", "a suite whose one property succeeds", Launch.ByRunner, "completed", RunOutcome.Completed),
    EndedRun("samples.AbortSuite", "a suite whose one test throws OutOfMemoryError", Launch.ByRunner, "aborted", RunOutcome.Aborted),
    EndedRun(
      "org.scalatest.samples.StopSuite",
      "a suite mixing in StopOnFailure whose first test fails",
      Launch.ByRunner,
      "stopped",
      RunOutcome.Stopped
    )
  )

  private final class RecordingLogger extends Logger {
    private var received = Vector.empty[String]
    def lines: Vector[String] = synchronized(received)
    private def record(line: String): Unit = synchronized { received :+= line }
    def ansiCodesSupported(): Boolean = false
    def error(message: String): Unit = record(message)
    def warn(message: String): Unit = record(message)
    def info(message: String): Unit = record(message)
    def debug(message: String): Unit = record(message)
    def trace(throwable: Throwable): Unit = record(String.valueOf(throwable))
  }

  private val ignoringEvents: EventHandler = (_: SbtEvent) => ()

  private def runStartedLine(pid: Long, startTime: String): String = s"""{"item":"runStarted","pid":$pid,"startTime":"$startTime"}"""

  private def runEndedLine(ended: String): String = s"""{"item":"runEnded","outcome":"$ended"}"""

  private def built(notation: String): Vector[BuiltEvent] =
    TestRunBuilder.build(TestRunPart.Events(notation)).fold(reason => fail(reason), _.events)

  private def decodedOrFail(lines: Seq[String]): Vector[TestEventLogEvent] =
    lines.toVector.map(line => TestEventLogDecoder.decode(line).fold(reason => fail(s"$reason, in $line"), identity))

  private def thisProcess: ProcessIdentity = {
    val current = ProcessIdentity.current
    assume(current.isRight, "an emitter needs a JVM that has ProcessHandle")
    current.fold(reason => fail(reason), identity)
  }

  private def noProcessHandleMessage(eventLogFile: Path): String = {
    assume(ProcessIdentity.current.isLeft, "this JVM has ProcessHandle")
    s"Cannot write the test event log $eventLogFile: this JVM has no java.lang.ProcessHandle"
  }

  private val runTimeLimitSeconds = 120L

  // Runner.run blocks for as long as a graphic reporter's window is open, so a run that wrongly opens one must fail.
  private def runnerRun(arguments: Seq[String]): Boolean = {
    val outcome = new AtomicReference[Option[Try[Boolean]]](None)
    val run = new Thread(() => outcome.set(Some(Try(Runner.run(arguments.toArray)))))
    run.setDaemon(true)
    run.start()
    run.join(TimeUnit.SECONDS.toMillis(runTimeLimitSeconds))
    outcome.get match {
      case Some(result) => result.get
      case None =>
        run.interrupt()
        java.awt.Window.getWindows.foreach(_.dispose())
        fail(s"Runner.run did not return within $runTimeLimitSeconds seconds")
    }
  }

  private def runnerArgument(eventLogFile: Path): List[String] = TestEventLogArgument.forFile(eventLogFile.toString)

  private def runpathOf(folder: File): String = folder.getPath.replace(" ", "\\ ")

  private def testClassesRunpath: String =
    runpathOf(new File(classOf[samples.SqrtPropSpec].getProtectionDomain.getCodeSource.getLocation.toURI))

  private def frameworkRunner(arguments: Seq[String], remoteArguments: Seq[String] = Nil): SbtRunner =
    new Framework().runner(arguments.toArray, remoteArguments.toArray, getClass.getClassLoader)

  private def eventLogOf(eventLogFile: Path): Vector[TestEventLogEvent] = decodedOrFail(TemporaryEventLog.lines(eventLogFile))

  private def execute(runner: SbtRunner, suiteClassName: String, logger: Logger): Unit = {
    val fingerprint = new Framework().fingerprints.collectFirst { case subclass: SubclassFingerprint => subclass }.get
    var tasks = runner.tasks(Array(new TaskDef(suiteClassName, fingerprint, true, Array(new SuiteSelector)))).toList
    while (tasks.nonEmpty) {
      tasks = tasks.head.execute(ignoringEvents, Array(logger)).toList ::: tasks.tail
    }
  }

  private def launched(launch: Launch, suiteClassName: String, arguments: Seq[String]): Unit = {
    launch match {
      case Launch.ByFramework =>
        val runner = frameworkRunner(arguments)
        execute(runner, suiteClassName, new RecordingLogger)
        runner.done()
      case Launch.ByRunner =>
        runnerRun(List("-R", testClassesRunpath, "-s", suiteClassName) ++ arguments)
    }
  }

  // Discovery reads every class of a runpath, so the suite is given a runpath that holds nothing else.
  private def withRunpathOfOneSuite[A](folder: Path)(body: String => A): A = {
    val classFile = "samples/discovered/OneTestSpec.class"
    val copy = folder.resolve(classFile)
    Files.createDirectories(copy.getParent)
    val source = getClass.getClassLoader.getResourceAsStream(classFile)
    try Files.copy(source, copy) finally source.close()
    body(runpathOf(folder.toFile))
  }

  Feature("The run in its test event log") {

    Scenario("A Framework runner is constructed") {
      val process = thisProcess
      TemporaryEventLog.in { eventLogFile =>
        When("a Framework runner is constructed with the runner argument")
        val runner = frameworkRunner(runnerArgument(eventLogFile))
        try {
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then("before it is given a task, the event log's first event is the run's start")
          And("that event's PID and process start time are those of the run's process")
          lines.head shouldBe runStartedLine(process.pid, process.startTime.toString)
          val events = decodedOrFail(lines)
          events.head shouldBe TestEventLogEvent.RunStarted(process.pid, process.startTime.toString)
          EventLogComparison.mismatchWhenIs(events.take(1), built("run started")) shouldBe None

          And("the event log holds no structure")
          lines.filter(_.contains(""""item":"structure"""")) shouldBe Vector.empty
          events.collect { case structure: TestEventLogEvent.Structure => structure } shouldBe Vector.empty
        } finally {
          runner.done()
        }
      }
    }

    Scenario("The decoder reads a run's start") {
      When("the decoder is given the line of a run's start")
      val event = TestEventLogDecoder.decode(runStartedLine(4242L, "2026-01-02T03:04:05.678Z"))

      Then("it yields the run's start, with the line's PID and process start time")
      event shouldBe Right(TestEventLogEvent.RunStarted(4242L, "2026-01-02T03:04:05.678Z"))
      EventLogComparison.mismatchWhenIs(event.toSeq, built("run started")) shouldBe None
    }

    runsThatEnd.foreach { run =>
      Scenario(s"A run of ${run.description}, launched by ${run.launch.named}, ends ${run.ended}") {
        thisProcess
        Given(run.description)
        TemporaryEventLog.in { eventLogFile =>
          When(s"${run.launch.named} runs it with the runner argument")
          launched(run.launch, run.suiteClassName, runnerArgument(eventLogFile))
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then(s"the event log's last event is that the run ended ${run.ended}")
          lines.last shouldBe runEndedLine(run.ended)
          EventLogComparison.mismatchWhenIs(decodedOrFail(lines).takeRight(1), built(s"run ended ${run.ended}")) shouldBe None
        }
      }
    }

    runsThatEnd.map(run => run.ended -> run.outcome).distinct.foreach { (ended, outcome) =>
      Scenario(s"The decoder reads that a run ended $ended") {
        When(s"the decoder is given the line of a run that ended $ended")
        val event = TestEventLogDecoder.decode(runEndedLine(ended))

        Then(s"it yields that the run ended $ended")
        event shouldBe Right(TestEventLogEvent.RunEnded(outcome))
        EventLogComparison.mismatchWhenIs(event.toSeq, built(s"run ended $ended")) shouldBe None
      }
    }

    Scenario("A Framework run's events outside every suite are assigned to the run") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("Framework runs it with the runner argument")
        launched(Launch.ByFramework, "samples.SqrtPropSpec", runnerArgument(eventLogFile))

        Then("the event log holds the run's start, its run events assigned to the run, and its end, in that order")
        EventLogComparison.mismatchWhenHoldsInOrder(
          eventLogOf(eventLogFile),
          built(
            """
            run started
            run: RunStarting(testCount: 0)
            run: RunCompleted
            run ended completed
            """
          )
        ) shouldBe None
      }
    }

    Scenario("A Runner.run run of a suite it is given by name writes none of that suite's events") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("Runner.run runs it by name with the runner argument")
        launched(Launch.ByRunner, "samples.SqrtPropSpec", runnerArgument(eventLogFile))

        Then("the event log is the run's start, its run events assigned to the run, and its end")
        EventLogComparison.mismatchWhenIs(
          eventLogOf(eventLogFile),
          built(
            """
            run started
            run: RunStarting(testCount: 1)
            run: RunCompleted
            run ended completed
            """
          )
        ) shouldBe None
      }
    }

    List("sequentially" -> Nil, "in parallel" -> List("-P2")).foreach { (how, parallelArguments) =>
      Scenario(s"The events of a Runner.run run that discovers its suites, $how, outside every suite are assigned to the run") {
        thisProcess
        TemporaryEventLog.in { eventLogFile =>
          Given("a runpath holding one suite, whose one test succeeds")
          withRunpathOfOneSuite(eventLogFile.resolveSibling("runpath")) { runpath =>
            When(s"Runner.run discovers and runs the runpath's suites $how, with the runner argument")
            runnerRun(List("-R", runpath) ++ parallelArguments ++ runnerArgument(eventLogFile))

            Then("the event log holds the run's start, discovery's events, the run events and the discovery suite's own, and the run's end")
            EventLogComparison.mismatchWhenHoldsInOrder(
              eventLogOf(eventLogFile),
              built(
                """
                run started
                run: DiscoveryStarting
                run: DiscoveryCompleted
                run: RunStarting(testCount: 1)
                run: SuiteStarting
                run: SuiteCompleted
                run: RunCompleted
                run ended completed
                """
              )
            ) shouldBe None
          }
        }
      }
    }
  }

  Feature("A run that ends before its runner sends RunStarting") {

    val runsThatAbortEarly = List(
      "a custom reporter class that does not exist" -> ((_: Path) => List("-C", "samples.NoSuchReporter")),
      "a file of suites to run again that does not exist" -> ((folder: Path) => List("-A", folder.resolve("no-such-file").toString))
    )

    runsThatAbortEarly.foreach { (description, argumentsIn) =>
      Scenario(s"A Runner.run run given $description ends aborted") {
        thisProcess
        TemporaryEventLog.in { eventLogFile =>
          When(s"Runner.run is given $description, and the runner argument")
          val allTestsPassed = runnerRun(argumentsIn(eventLogFile.getParent) ++ runnerArgument(eventLogFile))

          Then("it returns that not all tests passed")
          allTestsPassed shouldBe false

          And("the event log is the run's start, its RunAborted assigned to the run, and that the run ended aborted")
          EventLogComparison.mismatchWhenIs(
            eventLogOf(eventLogFile),
            built(
              """
              run started
              run: RunAborted
              run ended aborted
              """
            )
          ) shouldBe None
        }
      }
    }

    Scenario("A Framework runner that fails at construction leaves an event log that holds only the run's start") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        When("a Framework runner is constructed with a custom reporter class that does not exist, and the runner argument")
        Then("its construction fails")
        an[IllegalArgumentException] should be thrownBy frameworkRunner(List("-C", "samples.NoSuchReporter") ++ runnerArgument(eventLogFile))

        And("the event log is the run's start")
        EventLogComparison.mismatchWhenIs(eventLogOf(eventLogFile), built("run started")) shouldBe None

        And("the event log file is not held open, so it can be moved")
        val moved = Files.move(eventLogFile, eventLogFile.resolveSibling("moved.log"))
        TemporaryEventLog.namesIn(moved.getParent) shouldBe Set("moved.log")
      }
    }
  }

  Feature("A sub-process runner") {

    Scenario("A Framework runner given remote arguments writes no event log") {
      TemporaryEventLog.in { eventLogFile =>
        Given("a server for the runner's events, as sbt's main runner opens for a sub-process it forks")
        val server = new ServerSocket(0)
        try {
          When("a Framework runner is constructed with the runner argument and that server as its remote arguments, and is done")
          frameworkRunner(runnerArgument(eventLogFile), List("localhost", server.getLocalPort.toString)).done()

          Then("the event log's folder holds no file")
          TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set.empty
        } finally {
          server.close()
        }
      }
    }
  }

  Feature("A JVM that has no ProcessHandle") {

    Scenario("A Framework runner given the runner argument fails at construction") {
      TemporaryEventLog.in { eventLogFile =>
        val message = noProcessHandleMessage(eventLogFile)

        When("a Framework runner is constructed with the runner argument")
        val failure = the[IllegalStateException] thrownBy frameworkRunner(runnerArgument(eventLogFile))

        Then("it fails, saying why")
        failure should have message message

        And("it creates no file")
        TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set.empty
      }
    }

    Scenario("Runner.run given the runner argument fails before it runs a suite") {
      TemporaryEventLog.in { eventLogFile =>
        val message = noProcessHandleMessage(eventLogFile)

        When("Runner.run is given a suite and the runner argument")
        val failure = the[IllegalStateException] thrownBy
          runnerRun(List("-R", testClassesRunpath, "-s", "samples.SqrtPropSpec") ++ runnerArgument(eventLogFile))

        Then("it fails, saying why")
        failure should have message message

        And("it creates no file")
        TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set.empty
      }
    }
  }

  Feature("The reporter ScalaTest uses when none is configured") {

    Scenario("A Framework run given the runner argument and no reporter writes nothing to the loggers") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("Framework runs it with no argument, and again with the runner argument alone")
        val loggerWithoutArgument = new RecordingLogger
        val runnerWithoutArgument = frameworkRunner(Nil)
        execute(runnerWithoutArgument, "samples.SqrtPropSpec", loggerWithoutArgument)
        runnerWithoutArgument.done()
        val loggerWithArgument = new RecordingLogger
        val runnerWithArgument = frameworkRunner(runnerArgument(eventLogFile))
        execute(runnerWithArgument, "samples.SqrtPropSpec", loggerWithArgument)
        runnerWithArgument.done()

        Then("the loggers receive the suite's lines without the runner argument")
        loggerWithoutArgument.lines.map(_.replaceAll("\u001b\\[[0-9;]*m", "")) shouldBe
          Vector("SqrtPropSpec:", "- sqrt of a square is the root")

        And("the loggers receive nothing with it")
        loggerWithArgument.lines shouldBe Vector.empty
      }
    }

    Scenario("Runner.run rejects the graphic reporter with the runner argument") {
      TemporaryEventLog.in { eventLogFile =>
        When("Runner.run is given -g and the runner argument")
        val rejection = the[IllegalArgumentException] thrownBy runnerRun(List("-g") ++ runnerArgument(eventLogFile))

        Then("it fails, naming both")
        rejection should have message "The graphic reporter (-g) is not supported with --test-event-log."

        And("it writes no event log")
        Files.exists(eventLogFile) shouldBe false
      }
    }
  }
}
