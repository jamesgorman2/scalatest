package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.matchers.should
import scala.util.matching.Regex

trait FixtureBuilds { this: should.Matchers =>

  protected val pluginVersion: String = sys.env("SCALATEST_MILL_VERSION")
  protected val fixtures: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  protected val millExecutable: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  protected def pluginHeaderLine: String = s"""//| mvnDeps: ["com.edc::scalatest-mill::$pluginVersion"]"""

  protected def withTester[A](fixture: String, daemonMode: Boolean = false)(test: IntegrationTester => A): A = {
    val tester = IntegrationTester(daemonMode = daemonMode, workspaceSourcePath = fixtures / fixture, millExecutable = millExecutable)
    try {
      test(tester)
    } finally {
      tester.close()
    }
  }

  /** The build's header must hold no `mvnDeps`. */
  protected def addPlugin(tester: IntegrationTester): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    os.write.over(buildFile, s"$pluginHeaderLine\n${os.read(buildFile)}")
  }

  /** The build's test module must depend on `com.edc::scalatest::3.2.20`. */
  protected def setModuleArguments(tester: IntegrationTester, moduleArguments: Seq[String]): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    val mvnDeps = """    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")"""
    val testArgsDefault = moduleArguments.map(argument => s"\"$argument\"").mkString("    def testArgsDefault = Seq(", ", ", ")")
    os.write.over(buildFile, os.read(buildFile).replace(mvnDeps, s"$mvnDeps\n$testArgsDefault"))
  }

  protected def runTestCommand(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("scalatest/test") ++ arguments))

  protected def runTestCommandWithColour(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--color", "true", "scalatest/test") ++ arguments))

  /** The tester turns Mill's ticker off, and with it the prefix Mill puts on each line a task writes. */
  protected def runTestCommandWithTicker(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--ticker", "true", "scalatest/test") ++ arguments))

  protected def runTestCommandWithTickerAndColour(tester: IntegrationTester, arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("--ticker", "true", "--color", "true", "scalatest/test") ++ arguments))

  // Mill writes a `testargs` file for each worker it starts.
  protected def anyTestWorkerStarted(tester: IntegrationTester): Boolean =
    os.exists(tester.workspacePath / "out") && os.walk(tester.workspacePath / "out").exists(_.last == "testargs")

  protected def shouldSucceed(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe true
    }

  protected def shouldFailBeforeAnyTestStarts(tester: IntegrationTester, result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe false
      anyTestWorkerStarted(tester) shouldBe false
    }
}

final case class TestCommandResult(eval: IntegrationTester.EvalResult) {

  def succeeded: Boolean = eval.isSuccess

  def stdoutLines: Seq[String] = eval.out.linesIterator.map(TestCommandResult.withoutEscapes).toSeq

  def stderrLines: Seq[String] = eval.err.linesIterator.map(TestCommandResult.withoutEscapes).toSeq

  def prefixedHumanReadableLines: Seq[String] = prefixedTreeLines ++ prefixedSummaryLine

  def consoleLinesOutsideHumanReadableOutput: Seq[String] = {
    val start = stdoutLines.indexOfSlice(prefixedHumanReadableLines)
    val outsideOnStdout =
      if (prefixedHumanReadableLines.isEmpty) stdoutLines else stdoutLines.patch(start, Nil, prefixedHumanReadableLines.size)
    outsideOnStdout ++ stderrLines
  }

  def treeLines: Seq[String] = prefixedTreeLines.map(TestCommandResult.withoutMillPrefix)

  def colourMarkedTreeLines: Seq[String] = colourMarkedHumanReadableLines.take(prefixedTreeLines.size)

  def summaryLine: Option[String] = prefixedSummaryLine.map(TestCommandResult.withoutMillPrefix)

  def colourMarkedSummaryLine: Option[String] = colourMarkedHumanReadableLines.drop(prefixedTreeLines.size).headOption

  // The tree starts at the first line that, without Mill's prefix, is a test run label.
  private def prefixedTreeLines: Seq[String] =
    stdoutLines
      .dropWhile(line => !TestCommandResult.withoutMillPrefix(line).startsWith("["))
      .takeWhile { line =>
        val unprefixed = TestCommandResult.withoutMillPrefix(line)
        unprefixed.startsWith("[") || unprefixed.startsWith(" ")
      }

  private def prefixedSummaryLine: Option[String] =
    stdoutLines
      .drop(stdoutLines.indexOfSlice(prefixedTreeLines) + prefixedTreeLines.size)
      .headOption
      .filter(line => prefixedTreeLines.nonEmpty && TestCommandResult.withoutMillPrefix(line).startsWith("Suites: "))

  def colourMarkedHumanReadableLines: Seq[String] = {
    // `eval.out` has its ANSI escape sequences stripped; the process's own output keeps them.
    val written = eval.result.out.text().linesIterator.toSeq
    val start = written.map(TestCommandResult.withoutEscapes).indexOfSlice(prefixedHumanReadableLines)
    written
      .slice(start, start + prefixedHumanReadableLines.size)
      .map(line => TestCommandResult.colourMarked(TestCommandResult.withoutColouredMillPrefix(line)))
  }

  // Each line is indented relative to `nodeLine`.
  def subtreeOf(nodeLine: String): Seq[String] = {
    val output = treeLines
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

  def colourMarked(line: String): String =
    sgrSequence.replaceAllIn(
      line,
      sequence => Regex.quoteReplacement(colourMarkers.getOrElse(sequence.group(1), s"{sgr ${sequence.group(1)}}"))
    )

  /**
   * Removes each reset that the next colour overrides. Mill drops these when it re-renders a line to add its prefix,
   * and the line's characters keep their colours.
   */
  def withoutResetsBeforeAColour(marked: String): String = marked.replaceAll("""\{reset\}(?=\{(?!reset\}))""", "")

  private def withoutMillPrefix(line: String): String = millPrefix.replaceFirstIn(line, "")

  /** Mill's prefix as written, which Mill colours where it colours its own console output. */
  private val colouredMillPrefix: Regex = "^(?:\u001b\\[[0-9;]*m)*[0-9][0-9-]*\\](?:\u001b\\[[0-9;]*m)* ".r

  private def withoutColouredMillPrefix(line: String): String = colouredMillPrefix.replaceFirstIn(line, "")
}
