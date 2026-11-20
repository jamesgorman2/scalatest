package org.scalatest.mill

import compatibility.recorders.FrameworkLauncher
import java.time.Instant
import mill.api.PathRef
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

  private val firstWorkerOfFirstForkGroup: Worker = Worker(ForkGroup.at(0), WorkerIndex(0))

  private final case class RunOutsideMill(eventLogLines: Seq[String], stdoutLines: Seq[String], stderrLines: Seq[String])

  private def runClasspath(build: FixtureBuild): Seq[os.Path] = {
    val result = build.eval(Seq("show", "core.test.runClasspath"))
    withClue(result.debugString) {
      result.isSuccess shouldBe true
    }
    upickle.default.read[Seq[PathRef]](result.out).map(_.path)
  }

  private def codeSource(loaded: Class[?]): os.Path = os.Path(java.nio.file.Paths.get(loaded.getProtectionDomain.getCodeSource.getLocation.toURI))

  private def runOutsideMill(build: FixtureBuild, suite: String): RunOutsideMill = {
    val runFolder = build.workspace / "outside-mill"
    os.makeDir.all(runFolder)
    val eventLog = runFolder / "event-log.jsonl"
    // The launcher needs the test interface and the Scala library these tests are compiled with.
    val classpath = Seq(
      codeSource(FrameworkLauncher.getClass),
      codeSource(classOf[sbt.testing.Framework]),
      codeSource(classOf[Option[?]])
    ) ++ runClasspath(build)
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
    consolidation.addRun(module) { feed =>
      addEventLog(feed, run.eventLogLines)
      run.stdoutLines.foreach(line => feed.add(CapturedLine.Stdout(line)))
      run.stderrLines.foreach(line => feed.add(CapturedLine.Stderr(line)))
    }
    consolidation.end()
    output.toVector
  }

  private def addEventLog(feed: RunFeed, eventLogLines: Seq[String]): Unit =
    eventLogLines.foreach(line => feed.add(RunEvent.decode(line)))

  private def eventLogOfTestCommand(build: FixtureBuild): Seq[String] = {
    val eventLogs = build.eventLogFiles
    eventLogs should have size 1
    os.read.lines(eventLogs.head)
  }

  private def time(node: JsonNode, key: String): Option[Instant] = node.field(key).map(value => Instant.ofEpochMilli(value.num.toLong))

  private def jsonOutputReplaying(eventLogLines: Seq[String], json: JsonNode): String = {
    var written = Option.empty[String]
    val buildTool = json("buildTool")
    val consolidation = Consolidation.start(
      _ => (),
      coloured = false,
      jsonOutput = Some { output =>
        val text = new StringBuilder
        output.writeTo(piece => text.append(piece))
        written = Some(text.toString)
      },
      moduleSelectors = Vector(ModuleSelector("core.test", Vector(module))),
      buildTool = Some(BuildTool(buildTool("name").str, buildTool("version").str)),
      startedAt = time(json, "startedAt")
    )
    consolidation.addRun(module, Some(firstWorkerOfFirstForkGroup)) { feed =>
      addEventLog(feed, eventLogLines)
      json.node(NodeKind.Run).capturedLines.foreach(line => feed.add(line))
    }
    val moduleNode = json.node(NodeKind.Module)
    consolidation.timeModule(module, time(moduleNode, "startedAt"), time(moduleNode, "endedAt"))
    consolidation.end(time(json, "endedAt"))
    written.getOrElse(fail("the library wrote no JSON output"))
  }

  Feature("The library writes the same human-readable output without Mill as under the test command") {

    Scenario("Outside Mill, the library writes the same human-readable output as the test command for the same run") {
      withFixtureBuild(FixtureName.libraryWithoutMill) { build =>
        Given("the test command, invoked as mill scalatest/test core.test, has run core.test, a module holding CalculatorSuite")
        val testCommand = build.runTestCommandWithTicker("core.test")
        shouldSucceed(testCommand)
        withClue(testCommand.debugString) {
          testCommand.treeLines should not be empty
        }

        When("CalculatorSuite runs through Framework outside Mill, given the ScalaTest runner argument that turns the emitter on and names the file to which the run writes its test event log")
        val run = runOutsideMill(build, "samples.CalculatorSuite")
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

    Scenario("Replaying the test command's run through the library writes the same JSON text") {
      withJsonOutputFixture("CalculatorSuite") { build =>
        Given("the test command was invoked as mill scalatest/test core.test -fJ <report>, for a module holding CalculatorSuite, whose test prints dividing now")
        val json = build.jsonOutputOfTestCommand()

        When("the run's test event log is fed directly to the library as the run of worker 0 of fork group 0, under module core.test, with module selector core.test resolving to core.test and build tool Mill at the JSON output's version")
        And("the text of each stdio item of the run node's output is fed as a line captured from that item's stream")
        And("the library is given the root's and the module's start and end times as the JSON output gives them")
        val replayed = jsonOutputReplaying(eventLogOfTestCommand(build), json)

        Then("the JSON value the library writes is the JSON output, written as the same text")
        replayed shouldBe os.read(build.report)
      }
    }
  }
}
