package org.scalatest.mill

import mill.testkit.IntegrationTester
import scala.util.matching.Regex

/** Runs Mill on copies of the fixture builds under the integration tests' resources. */
trait FixtureBuilds {

  protected val pluginVersion: String = sys.env("SCALATEST_MILL_VERSION")
  protected val fixtures: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  protected val millExecutable: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

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

  /** Sets the module's `testArgsDefault` in the tester's copy of a build whose test module depends on this fork at 3.2.20. */
  protected def setModuleArguments(tester: IntegrationTester, moduleArguments: Seq[String]): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    val mvnDeps = """    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")"""
    val testArgsDefault = moduleArguments.map(argument => s"\"$argument\"").mkString("    def testArgsDefault = Seq(", ", ", ")")
    os.write.over(buildFile, os.read(buildFile).replace(mvnDeps, s"$mvnDeps\n$testArgsDefault"))
  }

  protected def runTestCommand(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("scalatest/test") ++ arguments))

  /** Runs the test command with Mill colouring its own console output. */
  protected def runTestCommandWithColour(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--color", "true", "scalatest/test") ++ arguments))

  /**
   * Runs the test command with Mill's ticker on, as Mill runs by default. The tester turns it off, and with it the
   * prefix Mill puts on each line a task writes.
   */
  protected def runTestCommandWithTicker(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--ticker", "true", "scalatest/test") ++ arguments))

  /** Runs the test command with Mill's ticker on and Mill colouring its own console output. */
  protected def runTestCommandWithTickerAndColour(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--ticker", "true", "--color", "true", "scalatest/test") ++ arguments))

  /** Whether any test worker was started in the tester's workspace: Mill writes each worker's `testargs`. */
  protected def anyTestWorkerStarted(tester: IntegrationTester): Boolean =
    os.exists(tester.workspacePath / "out") && os.walk(tester.workspacePath / "out").exists(_.last == "testargs")
}

/** What one `mill scalatest/test` wrote. */
final case class TestCommandResult(eval: IntegrationTester.EvalResult) {

  def succeeded: Boolean = eval.isSuccess

  /** Each stdout line, with ANSI escape sequences removed. */
  def stdoutLines: Seq[String] = eval.out.linesIterator.map(TestCommandResult.withoutEscapes).toSeq

  /** Each stderr line, with ANSI escape sequences removed. */
  def stderrLines: Seq[String] = eval.err.linesIterator.map(TestCommandResult.withoutEscapes).toSeq

  /**
   * The stdout lines of the human-readable output, as written: from the first that, without Mill's prefix, is a test
   * run label, each indented line after it, then the summary line.
   */
  def humanReadableStdoutLines: Seq[String] = treeStdoutLines ++ summaryStdoutLine

  /** The console's lines outside the human-readable output, stdout's then stderr's. */
  def consoleLinesOutsideHumanReadableOutput: Seq[String] = {
    val start = stdoutLines.indexOfSlice(humanReadableStdoutLines)
    val outsideOnStdout =
      if (humanReadableStdoutLines.isEmpty) stdoutLines else stdoutLines.patch(start, Nil, humanReadableStdoutLines.size)
    outsideOnStdout ++ stderrLines
  }

  /** The human-readable output apart from its summary line, without Mill's prefix. */
  def humanReadableOutput: Seq[String] = treeStdoutLines.map(TestCommandResult.withoutMillPrefix)

  /**
   * The human-readable output apart from its summary line, without Mill's prefix, with each ANSI SGR sequence replaced
   * by a marker naming it, such as `{red}` or `{reset}`.
   */
  def humanReadableOutputMarkingColours: Seq[String] = humanReadableStdoutLinesMarkingColours.take(treeStdoutLines.size)

  /** The summary line, the last line of the human-readable output, without Mill's prefix. */
  def summaryLine: Option[String] = summaryStdoutLine.map(TestCommandResult.withoutMillPrefix)

  /**
   * The human-readable output with its summary line, without Mill's prefix, with each ANSI SGR sequence replaced by a
   * marker naming it.
   */
  def humanReadableOutputWithSummaryLineMarkingColours: Seq[String] = humanReadableStdoutLinesMarkingColours

  /** The summary line, without Mill's prefix, with each ANSI SGR sequence replaced by a marker naming it. */
  def summaryLineMarkingColours: Option[String] = humanReadableStdoutLinesMarkingColours.drop(treeStdoutLines.size).headOption

  private def treeStdoutLines: Seq[String] =
    stdoutLines
      .dropWhile(line => !TestCommandResult.withoutMillPrefix(line).startsWith("["))
      .takeWhile { line =>
        val unprefixed = TestCommandResult.withoutMillPrefix(line)
        unprefixed.startsWith("[") || unprefixed.startsWith(" ")
      }

  // The summary line is the one unindented line after the tree's lines.
  private def summaryStdoutLine: Option[String] =
    stdoutLines
      .drop(stdoutLines.indexOfSlice(treeStdoutLines) + treeStdoutLines.size)
      .headOption
      .filter(line => treeStdoutLines.nonEmpty && TestCommandResult.withoutMillPrefix(line).startsWith("Suites: "))

  private def humanReadableStdoutLinesMarkingColours: Seq[String] = {
    // `eval.out` has its ANSI escape sequences stripped; the process's own output keeps them.
    val written = eval.result.out.text().linesIterator.toSeq
    val start = written.map(TestCommandResult.withoutEscapes).indexOfSlice(humanReadableStdoutLines)
    written
      .slice(start, start + humanReadableStdoutLines.size)
      .map(line => TestCommandResult.withColourMarkers(TestCommandResult.withoutColouredMillPrefix(line)))
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

  /**
   * `0` and `39` both map to `{reset}`: the human-readable output colours only the foreground, and Mill turns the `0`
   * that ends each colour in a line it prefixes into `39`.
   */
  private val colourMarkers: Map[String, String] =
    Map("0" -> "{reset}", "39" -> "{reset}", "31" -> "{red}", "32" -> "{green}", "33" -> "{yellow}", "36" -> "{cyan}")

  /** The line with each ANSI SGR sequence replaced by a marker naming it, such as `{red}` or `{reset}`. */
  def withColourMarkers(line: String): String =
    sgrSequence.replaceAllIn(
      line,
      sequence => Regex.quoteReplacement(colourMarkers.getOrElse(sequence.group(1), s"{sgr ${sequence.group(1)}}"))
    )

  /**
   * The marked line with each `{reset}` directly followed by another colour removed. Mill, re-rendering a line it
   * prefixes, drops a foreground reset that the next colour overrides, so the line's characters keep their colours.
   */
  def withResetsBeforeAColourRemoved(marked: String): String = marked.replaceAll("""\{reset\}(?=\{(?!reset\}))""", "")

  private def withoutMillPrefix(line: String): String = millPrefix.replaceFirstIn(line, "")

  /** Mill's prefix as written, which Mill colours where it colours its own console output. */
  private val colouredMillPrefix: Regex = "^(?:\u001b\\[[0-9;]*m)*[0-9][0-9-]*\\](?:\u001b\\[[0-9;]*m)* ".r

  private def withoutColouredMillPrefix(line: String): String = colouredMillPrefix.replaceFirstIn(line, "")
}
