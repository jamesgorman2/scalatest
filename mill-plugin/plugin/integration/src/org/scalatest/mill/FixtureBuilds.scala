package org.scalatest.mill

import mill.testkit.IntegrationTester
import scala.util.matching.Regex

/** Runs Mill on copies of the fixture builds under the integration tests' resources. */
trait FixtureBuilds {

  protected val pluginVersion: String = sys.env("SCALATEST_MILL_VERSION")
  protected val fixtures: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  private val millExecutable: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  /** The build header line that adds the plugin. */
  protected def pluginHeaderLine: String = s"""//| mvnDeps: ["com.edc::scalatest-mill::$pluginVersion"]"""

  protected def withTester[A](fixture: String, daemonMode: Boolean = false)(test: IntegrationTester => A): A = {
    val tester = IntegrationTester(daemonMode = daemonMode, workspaceSourcePath = fixtures / fixture, millExecutable = millExecutable)
    try {
      test(tester)
    } finally {
      tester.close()
    }
  }

  /** Adds the plugin to the tester's copy of a build whose header has no `mvnDeps`. */
  protected def addPlugin(tester: IntegrationTester): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    os.write.over(buildFile, s"$pluginHeaderLine\n${os.read(buildFile)}")
  }

  protected def runTestCommand(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("scalatest/test") ++ arguments))

  /**
   * Runs the test command with Mill's ticker on, as Mill runs by default. The tester turns it off, and with it the
   * prefix Mill puts on each line a task writes.
   */
  protected def runTestCommandWithTicker(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--ticker", "true", "scalatest/test") ++ arguments))

  /** Whether any test worker was started in the tester's workspace: Mill writes each worker's `testargs`. */
  protected def anyTestWorkerStarted(tester: IntegrationTester): Boolean =
    os.exists(tester.workspacePath / "out") && os.walk(tester.workspacePath / "out").exists(_.last == "testargs")
}

/** What one `mill scalatest/test` wrote. */
final case class TestCommandResult(eval: IntegrationTester.EvalResult) {

  def succeeded: Boolean = eval.isSuccess

  /** Each stdout line, with ANSI escape sequences removed. */
  def stdoutLines: Seq[String] = eval.out.linesIterator.map(TestCommandResult.withoutEscapes).toSeq

  /**
   * The stdout lines of the human-readable output, as written: from the first that, without Mill's prefix, is a test
   * run label, and each indented line after it.
   */
  def humanReadableStdoutLines: Seq[String] =
    stdoutLines
      .dropWhile(line => !TestCommandResult.withoutMillPrefix(line).startsWith("["))
      .takeWhile { line =>
        val unprefixed = TestCommandResult.withoutMillPrefix(line)
        unprefixed.startsWith("[") || unprefixed.startsWith(" ")
      }

  /** The console's lines outside the human-readable output, stdout's then stderr's. */
  def consoleLinesOutsideHumanReadableOutput: Seq[String] = {
    val start = stdoutLines.indexOfSlice(humanReadableStdoutLines)
    val outsideOnStdout =
      if (humanReadableStdoutLines.isEmpty) stdoutLines else stdoutLines.patch(start, Nil, humanReadableStdoutLines.size)
    outsideOnStdout ++ eval.err.linesIterator.map(TestCommandResult.withoutEscapes)
  }

  /** The human-readable output, without Mill's prefix. */
  def humanReadableOutput: Seq[String] = humanReadableStdoutLines.map(TestCommandResult.withoutMillPrefix)

  /**
   * The human-readable output, without Mill's prefix, with each ANSI SGR sequence replaced by a marker naming it, such
   * as `{red}` or `{reset}`.
   */
  def humanReadableOutputMarkingColours: Seq[String] = {
    // `eval.out` has its ANSI escape sequences stripped; the process's own output keeps them.
    val written = eval.result.out.text().linesIterator.toSeq
    val start = written.map(TestCommandResult.withoutEscapes).indexOfSlice(humanReadableStdoutLines)
    written
      .slice(start, start + humanReadableStdoutLines.size)
      .map(line => TestCommandResult.withColourMarkers(TestCommandResult.withoutMillPrefix(line)))
  }

  /** The line `nodeLine` of the human-readable output and every line under it, with its indentation removed. */
  def linesFor(nodeLine: String): Seq[String] = {
    val output = humanReadableOutput
    val start = output.indexWhere(_.trim == nodeLine)
    if (start < 0) {
      Nil
    } else {
      val indentation = output(start).takeWhile(_ == ' ').length
      val under = output.drop(start + 1).takeWhile(line => line.takeWhile(_ == ' ').length > indentation)
      (output(start) +: under).map(_.drop(indentation))
    }
  }

  def debugString: String = eval.debugString
}

object TestCommandResult {

  /** Mill's prefix on a line its task writes, such as `1] `. */
  val millPrefix: scala.util.matching.Regex = """^[0-9][0-9-]*\] """.r

  private val escapeSequence = "\u001b\\[[0-9;]*[A-Za-z]".r

  private def withoutEscapes(line: String): String = escapeSequence.replaceAllIn(line, "")

  private val sgrSequence = "\u001b\\[([0-9;]*)m".r

  private val colourMarkers: Map[String, String] =
    Map("0" -> "{reset}", "31" -> "{red}", "32" -> "{green}", "33" -> "{yellow}", "36" -> "{cyan}")

  private def withColourMarkers(line: String): String =
    sgrSequence.replaceAllIn(
      line,
      sequence => Regex.quoteReplacement(colourMarkers.getOrElse(sequence.group(1), s"{sgr ${sequence.group(1)}}"))
    )

  private def withoutMillPrefix(line: String): String = millPrefix.replaceFirstIn(line, "")
}
