package org.scalatest.mill

import mill.testkit.IntegrationTester
import scala.util.matching.Regex

final case class TestCommandResult(eval: IntegrationTester.EvalResult) {

  def succeeded: Boolean = eval.isSuccess

  def stdoutLines: Seq[String] = eval.out.linesIterator.toSeq

  def stderrLines: Seq[String] = eval.err.linesIterator.toSeq

  def consoleLines: Seq[String] = stdoutLines ++ stderrLines

  def consoleText: String = eval.out + eval.err

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

  def summaryLinesAnywhereOnTheConsole: Seq[String] =
    consoleLines.map(TestCommandResult.withoutMillPrefix).filter(TestCommandResult.isSummaryLine)

  def colourMarkedSummaryLine: Option[String] = colourMarkedHumanReadableLines.drop(prefixedTreeLines.size).headOption

  private def prefixedTreeLines: Seq[String] =
    stdoutLines
      .dropWhile(line => !TestCommandResult.isModuleLine(TestCommandResult.withoutMillPrefix(line)))
      .takeWhile { line =>
        val unprefixed = TestCommandResult.withoutMillPrefix(line)
        TestCommandResult.isModuleLine(unprefixed) || unprefixed.startsWith(" ")
      }

  private def prefixedSummaryLine: Option[String] =
    stdoutLines
      .drop(stdoutLines.indexOfSlice(prefixedTreeLines) + prefixedTreeLines.size)
      .headOption
      .filter(line => prefixedTreeLines.nonEmpty && TestCommandResult.isSummaryLine(TestCommandResult.withoutMillPrefix(line)))

  def colourMarkedHumanReadableLines: Seq[String] = {
    // `eval.out` has its ANSI escape sequences stripped; the process's own output keeps them.
    val written = eval.result.out.text().linesIterator.toSeq
    val start = written.map(TestCommandResult.withoutEscapes).indexOfSlice(prefixedHumanReadableLines)
    written
      .slice(start, start + prefixedHumanReadableLines.size)
      .map(line => TestCommandResult.colourMarked(TestCommandResult.withoutColouredMillPrefix(line)))
  }

  def subtreeOf(nodeLine: String): Seq[String] = linesOfSubtree(nodeLine, treeLines)

  def colourMarkedSubtreeOf(nodeLine: String): Seq[String] = linesOfSubtree(nodeLine, colourMarkedTreeLines)

  // The subtree is found in `treeLines` and taken from `renderedTreeLines`, which holds the same lines, one for one, as
  // the caller renders them, such as colour-marked.
  private def linesOfSubtree(nodeLine: String, renderedTreeLines: Seq[String]): Seq[String] = {
    val start = treeLines.indexWhere(_.trim == nodeLine)
    if (start < 0) {
      Nil
    } else {
      val indentation = TestCommandResult.indentationOf(treeLines(start))
      val underCount = treeLines.drop(start + 1).takeWhile(line => TestCommandResult.indentationOf(line) > indentation).size
      renderedTreeLines.slice(start, start + 1 + underCount).map(_.drop(indentation))
    }
  }

  def debugString: String = eval.debugString
}

object TestCommandResult {

  val millPrefix: Regex = """^[0-9][0-9-]*\] """.r

  private val escapeSequence = "\u001b\\[[0-9;]*[A-Za-z]".r

  private def withoutEscapes(line: String): String = escapeSequence.replaceAllIn(line, "")

  private val colourSequence = "\u001b\\[([0-9;]*)m".r

  private val colourMarkers: Map[String, String] =
    // Mill rewrites each `0` in a line it prefixes as `39`, which resets only the foreground; the human-readable output
    // sets only the foreground, so both are a reset.
    Map("0" -> "{reset}", "39" -> "{reset}", "31" -> "{red}", "32" -> "{green}", "33" -> "{yellow}", "36" -> "{cyan}")

  def colourMarked(line: String): String =
    colourSequence.replaceAllIn(
      line,
      sequence => Regex.quoteReplacement(colourMarkers.getOrElse(sequence.group(1), s"{sgr ${sequence.group(1)}}"))
    )

  def withoutResetsBeforeAColour(marked: String): String =
    // Mill drops each reset directly followed by a colour when it re-renders a line to add its prefix; the line's
    // characters keep their colours.
    marked.replaceAll("""\{reset\}(?=\{(?!reset\}))""", "")

  def indentationOf(line: String): Int = line.takeWhile(_ == ' ').length

  private def withoutMillPrefix(line: String): String = millPrefix.replaceFirstIn(line, "")

  private def isModuleLine(unprefixed: String): Boolean = unprefixed.startsWith("[")

  private def isSummaryLine(unprefixed: String): Boolean = unprefixed.startsWith("Suites: ")

  // Mill colours its prefix where it colours its own console output.
  private val colouredMillPrefix: Regex = "^(?:\u001b\\[[0-9;]*m)*[0-9][0-9-]*\\](?:\u001b\\[[0-9;]*m)* ".r

  private def withoutColouredMillPrefix(line: String): String = colouredMillPrefix.replaceFirstIn(line, "")
}
