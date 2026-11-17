package org.scalatest.mill

import compatibility.recorders.FrameworkLauncher
import mill.api.PathRef
import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.consolidation.CapturedLine
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.RunEvent
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import scala.collection.mutable

final class LibraryWithoutMillSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  /** What a run through `Framework` outside Mill wrote: its test event log's lines, and its stdout and stderr lines. */
  private final case class RunOutsideMill(eventLogLines: Seq[String], stdoutLines: Seq[String], stderrLines: Seq[String])

  private def runClasspath(tester: IntegrationTester): Seq[os.Path] = {
    val result = tester.eval(Seq("show", "core.test.runClasspath"))
    withClue(result.debugString) {
      result.isSuccess shouldBe true
    }
    upickle.default.read[Seq[PathRef]](result.out).map(_.path)
  }

  private def codeSource(loaded: Class[?]): os.Path = os.Path(java.nio.file.Paths.get(loaded.getProtectionDomain.getCodeSource.getLocation.toURI))

  /**
   * Runs `suite` through `Framework`, given the runner argument, in a JVM of its own outside Mill. The JVM's classpath is
   * the module's run classpath, behind the fixture's launcher, the test interface and the Scala library these tests are
   * compiled with, which the launcher needs.
   */
  private def runOutsideMill(tester: IntegrationTester, suite: String): RunOutsideMill = {
    val runFolder = tester.workspacePath / os.up / s"${tester.workspacePath.last}-outside-mill"
    os.makeDir.all(runFolder)
    val eventLog = runFolder / "event-log.jsonl"
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

  /** The coloured human-readable output the library writes for the run, under test run label `core.test`. */
  private def consolidated(run: RunOutsideMill): Seq[String] = {
    val output = mutable.ArrayBuffer.empty[String]
    val consolidation = Consolidation.start(line => output += line, coloured = true)
    val feed = consolidation.addRun(TestRunLabel("core.test"))
    run.eventLogLines.foreach { line =>
      RunEvent.decode(line) match {
        case Right(event) => feed.add(event)
        case Left(reason) => fail(s"the event log line $line cannot be decoded: $reason")
      }
    }
    run.stdoutLines.foreach(line => feed.add(CapturedLine.Stdout(line)))
    run.stderrLines.foreach(line => feed.add(CapturedLine.Stderr(line)))
    feed.endEventStream()
    consolidation.end()
    output.toVector
  }

  Feature("The library writes the same human-readable output without Mill as under the test command") {

    Scenario("One run, with and without Mill") {
      withTester("library-without-mill") { tester =>
        Given("the test command, invoked as mill scalatest/test core.test, has run core.test, a module holding CalculatorSuite")
        val testCommand = TestCommandResult(tester.eval(Seq("--ticker", "true", "scalatest/test", "core.test")))
        withClue(testCommand.debugString) {
          testCommand.succeeded shouldBe true
          testCommand.humanReadableOutput should not be empty
        }

        When("CalculatorSuite runs through Framework outside Mill, given the ScalaTest runner argument that turns the emitter on and names the file to which the run writes its test event log")
        val run = runOutsideMill(tester, "samples.CalculatorSuite")
        And("the events this fork reads back from that test event log, and the lines the run wrote to stdout and stderr, are fed to the consolidation library directly, under test run label core.test, with colour on")
        val output = consolidated(run)

        Then("the library's human-readable output, apart from its summary line, is the test command's, with the prefix Mill puts on every line the test command's own task writes removed from each line")
        withClue(testCommand.debugString) {
          output.dropRight(1).map(TestCommandResult.withColourMarkers) shouldBe testCommand.humanReadableOutputMarkingColours
        }
      }
    }
  }
}
