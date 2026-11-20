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

  def colourMarkedSummaryLine: Option[String] = colourMarkedHumanReadableLines.drop(prefixedTreeLines.size).headOption

  private def prefixedTreeLines: Seq[String] =
    stdoutLines
      .dropWhile(line => !TestCommandResult.isTestRunLabel(TestCommandResult.withoutMillPrefix(line)))
      .takeWhile { line =>
        val unprefixed = TestCommandResult.withoutMillPrefix(line)
        TestCommandResult.isTestRunLabel(unprefixed) || unprefixed.startsWith(" ")
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

  private val colourSequence = "\u001b\\[([0-9;]*)m".r

  private val colourMarkers: Map[String, String] =
    // `39` resets only the foreground, the one colour the human-readable output sets. Mill rewrites each `0` in a line
    // it prefixes as `39`.
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

  private def withoutMillPrefix(line: String): String = millPrefix.replaceFirstIn(line, "")

  private def isTestRunLabel(unprefixed: String): Boolean = unprefixed.startsWith("[")

  // Mill colours its prefix where it colours its own console output.
  private val colouredMillPrefix: Regex = "^(?:\u001b\\[[0-9;]*m)*[0-9][0-9-]*\\](?:\u001b\\[[0-9;]*m)* ".r

  private def withoutColouredMillPrefix(line: String): String = colouredMillPrefix.replaceFirstIn(line, "")
}
