package org.scalatest.tools

import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.scalatest.GivenWhenThen
import org.scalatest.events.EventLogComparison
import org.scalatest.events.Ordinal
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStopped
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt.testing.Runner as SbtRunner
import scala.util.Try

final class TestEventLogRunSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  private final case class EndedRun(suiteClassName: String, description: String, launch: Launch, ended: String)

  private val runsThatEnd = List(
    EndedRun("samples.SqrtPropSpec", "a suite whose one property succeeds", Launch.ByFramework, "completed"),
    EndedRun("samples.SqrtPropSpec", "a suite whose one property succeeds", Launch.ByRunner, "completed"),
    EndedRun("samples.AbortSuite", "a suite whose one test throws OutOfMemoryError", Launch.ByRunner, "aborted"),
    EndedRun(
      "org.scalatest.samples.StopSuite",
      "a suite mixing in StopOnFailure whose first test fails",
      Launch.ByRunner,
      "stopped"
    )
  )

  private def noProcessHandleMessage(eventLogFile: Path): String = {
    val noProcessHandle = "this JVM has no java.lang.ProcessHandle"
    val unreadable = Try(ProcessIdentity.current).failed.toOption.map(_.getMessage)
    assume(unreadable.contains(noProcessHandle), "this scenario needs a JVM that has no java.lang.ProcessHandle")
    s"Cannot write the test event log $eventLogFile: $noProcessHandle"
  }

  // Neither thread is a daemon. The one that waits for a sub-process's events ends when it reads RunCompleted.
  private def endThreadsLeftByDoneThrowing(runner: SbtRunner, subProcessServer: Array[String]): Unit = {
    val scalaTestRunner = runner.asInstanceOf[Framework#ScalaTestRunner]
    val socket = new Socket(subProcessServer(0), subProcessServer(1).toInt)
    try {
      val events = new ObjectOutputStream(socket.getOutputStream)
      events.writeObject(RunCompleted(new Ordinal(1)))
      events.flush()
      scalaTestRunner.serverThread.get.foreach(_.join(TimeUnit.SECONDS.toMillis(runTimeLimitSeconds)))
    } finally {
      socket.close()
      scalaTestRunner.dispatchReporter.dispatchDisposeAndWaitUntilDone()
    }
  }

  Feature("The run in its test event log") {

    Scenario("A build tool's runner is constructed") {
      val process = ProcessHandle.current()
      val startTime = process.info().startInstant()
      assume(startTime.isPresent, "this process has no start time")
      val pid = process.pid()
      val startTimeText = startTime.get.toString
      TemporaryEventLog.in { eventLogFile =>
        When("a build tool's runner is constructed with the runner argument")
        val runner = frameworkRunner(runnerArgument(eventLogFile))
        try {
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then("before it is given a task, the event log's first event is the run's start")
          And("that event's PID and process start time are those ProcessHandle.current() gives in the run's process")
          lines.head shouldBe TestEventLogLine.runStarted(pid, startTimeText)
          val events = decoded(lines)
          events.head shouldBe TestEventLogEvent.RunStarted(pid, startTimeText)
          EventLogComparison.mismatchWhenIs(events.take(1), built("run started")) shouldBe None

          And("the event log holds no structure")
          lines.filter(_.contains(""""item":"structure"""")) shouldBe Vector.empty
          events.collect { case structure: TestEventLogEvent.Structure => structure } shouldBe Vector.empty
        } finally {
          runner.done()
        }
      }
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
          lines.last shouldBe TestEventLogLine.runEnded(run.ended)
          EventLogComparison.mismatchWhenIs(decoded(lines).takeRight(1), built(s"run ended ${run.ended}")) shouldBe None
        }
      }
    }

    Scenario("A build tool's run's events outside every suite are assigned to the run") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("a build tool runs it with the runner argument")
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

    Scenario("A Runner.run run of a suite it is given by name writes that suite's structure and events between its run events") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("Runner.run runs it by name with the runner argument")
        launched(Launch.ByRunner, "samples.SqrtPropSpec", runnerArgument(eventLogFile))

        Then("the event log is the run's start, its run events assigned to the run with the suite's structure and events between them, and its end")
        EventLogComparison.mismatchWhenIs(
          eventLogOf(eventLogFile),
          built(
            """
            run started
            run: RunStarting(testCount: 1)
            structure
              suite "SqrtPropSpec", class "samples.SqrtPropSpec"
                test "sqrt of a square is the root", label "- sqrt of a square is the root"
            suite "SqrtPropSpec": SuiteStarting
            test "sqrt of a square is the root": TestStarting
            test "sqrt of a square is the root": TestSucceeded
            suite "SqrtPropSpec": SuiteCompleted
            run: RunCompleted
            run ended completed
            """
          )
        ) shouldBe None
      }
    }

    List("sequentially" -> Nil, "in parallel" -> List("-P2")).foreach { (how, parallelArguments) =>
      Scenario(s"The events outside every suite are assigned to the run when Runner.run discovers its suites and runs them $how") {
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

    Scenario("A build tool's runner that fails at construction leaves an event log that holds only the run's start") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        When("a build tool's runner is constructed with a custom reporter class that does not exist, and the runner argument")
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

  Feature("A build tool's run whose done throws before it sends RunCompleted") {

    Scenario("A build tool's runner whose done is interrupted while it waits for a sub-process's events closes its event log") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        Given("a build tool's runner constructed with the runner argument, waiting for the events of a sub-process")
        val runner = frameworkRunner(runnerArgument(eventLogFile))
        val subProcessServer = runner.remoteArgs()
        try {
          When("its done is called by a thread that has been interrupted")
          Then("done throws InterruptedException")
          Thread.currentThread.interrupt()
          try an[InterruptedException] should be thrownBy runner.done() finally Thread.interrupted()

          And("the event log is the run's start and its RunStarting, without the run's end")
          EventLogComparison.mismatchWhenIs(
            eventLogOf(eventLogFile),
            built(
              """
              run started
              run: RunStarting(testCount: 0)
              """
            )
          ) shouldBe None

          And("the event log file is not held open, so it can be moved")
          val moved = Files.move(eventLogFile, eventLogFile.resolveSibling("moved.log"))
          TemporaryEventLog.namesIn(moved.getParent) shouldBe Set("moved.log")
        } finally {
          endThreadsLeftByDoneThrowing(runner, subProcessServer)
        }
      }
    }
  }

  Feature("A run whose sub-process stops") {

    Scenario("A build tool's runner relays a sub-process's RunStopped and still ends its event log with its own RunCompleted") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        Given("a build tool's runner constructed with the runner argument, waiting for the events of a sub-process")
        val runner = frameworkRunner(runnerArgument(eventLogFile))
        val subProcessServer = runner.remoteArgs()

        When("the sub-process sends RunStopped, and the runner is done")
        val socket = new Socket(subProcessServer(0), subProcessServer(1).toInt)
        try {
          val events = new ObjectOutputStream(socket.getOutputStream)
          events.writeObject(RunStopped(new Ordinal(0)))
          events.flush()
          runner.asInstanceOf[Framework#ScalaTestRunner].serverThread.get.foreach(_.join(TimeUnit.SECONDS.toMillis(runTimeLimitSeconds)))
        } finally {
          socket.close()
        }
        runner.done()

        Then("the event log is the run's start, its RunStarting, its RunCompleted and its end, completed")
        EventLogComparison.mismatchWhenIs(
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
  }

  Feature("A sub-process runner") {

    Scenario("A build tool's runner given remote arguments writes no event log") {
      TemporaryEventLog.in { eventLogFile =>
        Given("a server for the runner's events, as sbt's main runner opens for a sub-process it forks")
        val server = new ServerSocket(0)
        try {
          When("a build tool's runner is constructed with the runner argument and that server as its remote arguments, and is done")
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

    Scenario("A build tool's runner given the runner argument fails at construction") {
      TemporaryEventLog.in { eventLogFile =>
        val message = noProcessHandleMessage(eventLogFile)

        When("a build tool's runner is constructed with the runner argument")
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

    Scenario("A build tool's run given the runner argument and no reporter writes nothing to the loggers") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("a build tool runs it with no argument, and again with the runner argument alone")
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

    Scenario("A build tool's run given the runner argument returns no end-of-run summary") {
      thisProcess
      Given("a suite whose one property succeeds")
      TemporaryEventLog.in { eventLogFile =>
        When("a build tool runs it with no argument, and again with the runner argument alone")
        val runnerWithoutArgument = frameworkRunner(Nil)
        execute(runnerWithoutArgument, "samples.SqrtPropSpec", new RecordingLogger)
        val summaryWithoutArgument = runnerWithoutArgument.done()
        val runnerWithArgument = frameworkRunner(runnerArgument(eventLogFile))
        execute(runnerWithArgument, "samples.SqrtPropSpec", new RecordingLogger)
        val summaryWithArgument = runnerWithArgument.done()

        Then("done returns the end-of-run summary without the runner argument")
        summaryWithoutArgument should include("Run completed in")

        And("done returns nothing with it")
        summaryWithArgument shouldBe ""
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

final class TestEventLogRunDecoderSpec extends AnyWordSpec with Matchers with TestEventLogRuns {

  "The decoder, given the line of a run's start" should {
    "yield the run's start, with the line's PID and process start time" in {
      val event = TestEventLogDecoder.decode(TestEventLogLine.runStarted(4242L, "2026-01-02T03:04:05.678Z"))
      event shouldBe TestEventLogEvent.RunStarted(4242L, "2026-01-02T03:04:05.678Z")
      EventLogComparison.mismatchWhenIs(Seq(event), built("run started")) shouldBe None
    }
  }

  List("completed" -> RunOutcome.Completed, "aborted" -> RunOutcome.Aborted, "stopped" -> RunOutcome.Stopped).foreach { (ended, outcome) =>
    s"The decoder, given the line of a run that ended $ended" should {
      s"yield that the run ended $ended" in {
        val event = TestEventLogDecoder.decode(TestEventLogLine.runEnded(ended))
        event shouldBe TestEventLogEvent.RunEnded(outcome)
        EventLogComparison.mismatchWhenIs(Seq(event), built(s"run ended $ended")) shouldBe None
      }
    }
  }
}
