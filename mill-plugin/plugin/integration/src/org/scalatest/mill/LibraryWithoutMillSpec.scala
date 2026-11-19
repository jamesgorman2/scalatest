package org.scalatest.mill

import compatibility.recorders.FrameworkLauncher
import java.io.StringWriter
import java.time.Instant
import mill.api.PathRef
import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.consolidation.BuildTool
import org.scalatest.consolidation.CapturedLine
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.ModuleSelector
import org.scalatest.consolidation.RunEvent
import org.scalatest.consolidation.RunFeed
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import scala.collection.mutable

final class LibraryWithoutMillSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val module: TestRunLabel = TestRunLabel("core.test")

  private val firstWorkerOfFirstForkGroup: Worker =
    ForkGroup.from(0).flatMap(forkGroup => WorkerIndex.from(0).map(Worker(forkGroup, _))).fold(fail(_), identity)

  private final case class RunOutsideMill(eventLogLines: Seq[String], stdoutLines: Seq[String], stderrLines: Seq[String])

  private def runClasspath(tester: IntegrationTester): Seq[os.Path] = {
    val result = tester.eval(Seq("show", "core.test.runClasspath"))
    withClue(result.debugString) {
      result.isSuccess shouldBe true
    }
    upickle.default.read[Seq[PathRef]](result.out).map(_.path)
  }

  private def codeSource(loaded: Class[?]): os.Path = os.Path(java.nio.file.Paths.get(loaded.getProtectionDomain.getCodeSource.getLocation.toURI))

  private def runOutsideMill(tester: IntegrationTester, suite: String): RunOutsideMill = {
    val runFolder = tester.workspacePath / os.up / s"${tester.workspacePath.last}-outside-mill"
    os.makeDir.all(runFolder)
    val eventLog = runFolder / "event-log.jsonl"
    // The launcher needs the test interface and the Scala library these tests are compiled with.
    val classpath = Seq(
      codeSource(FrameworkLauncher.getClass),
      codeSource(classOf[sbt.testing.Framework]),
      codeSource(classOf[Option[?]])
    ) ++ runClasspath(tester)
    val result = os.call(
      cmd = Seq(
        (os.Path(sys.props("java.home")) / "bin" / "java").toString,
        "-cp",
        classpath.mkString(java.io.File.pathSeparator),
        s"-Dcompatibility.suites=$suite",
        s"-Dcompatibility.loggerLines=${runFolder / "logger-lines.txt"}",
        s"-Dcompatibility.events=${runFolder / "events.txt"}",
        s"-Dcompatibility.summary=${runFolder / "summary.txt"}",
        "compatibility.recorders.FrameworkLauncher"
      ) ++ RunnerArgument.forEventLogFile(eventLog.toNIO),
      cwd = runFolder,
      check = false
    )
    withClue(s"${result.out.text()}\n${result.err.text()}") {
      result.exitCode shouldBe 0
    }
    RunOutsideMill(os.read.lines(eventLog), result.out.lines(), result.err.lines())
  }

  private def humanReadableOutputOf(run: RunOutsideMill): Seq[String] = {
    val output = mutable.ArrayBuffer.empty[String]
    val consolidation = Consolidation.start(line => output += line, coloured = true)
    val feed = consolidation.addRun(module)
    addEventLog(feed, run.eventLogLines)
    run.stdoutLines.foreach(line => feed.add(CapturedLine.Stdout(line)))
    run.stderrLines.foreach(line => feed.add(CapturedLine.Stderr(line)))
    feed.endEventStream()
    consolidation.end()
    output.toVector
  }

  private def addEventLog(feed: RunFeed, eventLogLines: Seq[String]): Unit =
    eventLogLines.foreach { line =>
      RunEvent.decode(line) match {
        case Right(event) => feed.add(event)
        case Left(reason) => fail(s"the event log line $line cannot be decoded: $reason")
      }
    }

  private def eventLogOfTestCommand(tester: IntegrationTester): Seq[String] = {
    val eventLogs = os.walk(tester.workspacePath / "out").filter(_.last == JvmTestModuleRun.eventLogFileName)
    eventLogs should have size 1
    os.read.lines(eventLogs.head)
  }

  private def capturedLines(run: ujson.Value): Seq[CapturedLine] =
    run.obj.get("output").toSeq.flatMap(_.arr).filter(_("kind").str == "stdio").map { item =>
      item("stream").str match {
        case "stdout" => CapturedLine.Stdout(item("text").str)
        case "stderr" => CapturedLine.Stderr(item("text").str)
        case stream => fail(s"the output item $item names the stream $stream")
      }
    }

  private def time(node: ujson.Value, key: String): Option[Instant] = node.obj.get(key).map(value => Instant.ofEpochMilli(value.num.toLong))

  /** The JSON text the library writes to a [[StringWriter]] when fed the event log and the run's captured lines, as the run of the module's one worker, and given the JSON output's build tool and its root's and module's times. */
  private def jsonOutputReplaying(eventLogLines: Seq[String], json: ujson.Value): String = {
    var written = Option.empty[String]
    val buildTool = json("buildTool")
    val consolidation = Consolidation.start(
      _ => (),
      coloured = false,
      jsonOutput = Some { output =>
        val writer = new StringWriter
        output.writeTo(writer)
        written = Some(writer.toString)
      },
      moduleSelectors = Vector(ModuleSelector("core.test", Vector(module))),
      buildTool = Some(BuildTool(buildTool("name").str, buildTool("version").str)),
      startedAt = time(json, "startedAt")
    )
    val feed = consolidation.addRun(module, Some(firstWorkerOfFirstForkGroup))
    addEventLog(feed, eventLogLines)
    capturedLines(node(json, "run")).foreach(line => feed.add(line))
    feed.endEventStream()
    val moduleNode = node(json, "module")
    consolidation.timeModule(module, time(moduleNode, "startedAt"), time(moduleNode, "endedAt"))
    consolidation.end(time(json, "endedAt"))
    written.getOrElse(fail("the library wrote no JSON output"))
  }

  Feature("The library writes the same human-readable output without Mill as under the test command") {

    Scenario("One run, with and without Mill") {
      withTester("library-without-mill") { tester =>
        Given("the test command, invoked as mill scalatest/test core.test, has run core.test, a module holding CalculatorSuite")
        val testCommand = TestCommandResult(tester.eval(Seq("--ticker", "true", "scalatest/test", "core.test")))
        withClue(testCommand.debugString) {
          testCommand.succeeded shouldBe true
          testCommand.treeLines should not be empty
        }

        When("CalculatorSuite runs through Framework outside Mill, given the ScalaTest runner argument that turns the emitter on and names the file to which the run writes its test event log")
        val run = runOutsideMill(tester, "samples.CalculatorSuite")
        And("the events this fork reads back from that test event log, and the lines the run wrote to stdout and stderr, are fed to the consolidation library directly, under test run label core.test, with colour on")
        val output = humanReadableOutputOf(run)

        Then("the library's human-readable output is the test command's, with the prefix Mill puts on every line the test command's own task writes removed from each line")
        withClue(testCommand.debugString) {
          testCommand.colourMarkedSummaryLine shouldBe defined
          output.map(line => TestCommandResult.withoutResetsBeforeAColour(TestCommandResult.colourMarked(line))) shouldBe
            testCommand.colourMarkedHumanReadableLines.map(TestCommandResult.withoutResetsBeforeAColour)
        }
      }
    }
  }

  Feature("The same JSON output with and without Mill") {

    Scenario("Replaying the run") {
      withJsonOutputFixture("CalculatorSuite") { tester =>
        Given("the test command was invoked as mill scalatest/test core.test -fJ <report>, for a module holding CalculatorSuite, whose test prints dividing now")
        val json = jsonOutputOfTestCommand(tester)

        When("the run's test event log is fed directly to the library under module core.test, as the run of worker 0 of fork group 0, with module selector core.test resolving to core.test, and build tool Mill at the version the JSON output gives")
        And("the text of each stdio item of the run node's output is fed as a line captured from that item's stream")
        And("the library is given the root's and the module's start and end times as the JSON output gives them")
        val replayed = jsonOutputReplaying(eventLogOfTestCommand(tester), json)

        Then("the JSON value the library writes is the JSON output, written as the same text")
        replayed shouldBe os.read(report(tester))
      }
    }
  }
}
