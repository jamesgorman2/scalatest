package org.scalatest.compatibility

object ReporterText {

  val stackFrames: String = "<stack frames>"

  private val colour = "\u001b\\[[0-9;]*m".r
  private val stackFrameLine = """(?:(?:error|warn|info|debug|summary): )?\s*at \S.*\(.*\)""".r
  private val duration = {
    val unit = """\d+ (?:milliseconds?|seconds?|minutes?|hours?)"""
    s"$unit(?:, $unit)*".r
  }
  private val jsonValuesNotCompared = List(
    """"depth": -?\d+""".r -> """"depth": <depth>""",
    """"stackTraces": \[(?:\{[^{}]*\}(?:, )?)*\]""".r -> s""""stackTraces": $stackFrames""",
    """"duration": \d+""".r -> """"duration": <duration>""",
    """"timeStamp": \d+""".r -> """"timeStamp": <timestamp>""",
    """"threadName": "(?:[^"\\]|\\.)*"""".r -> """"threadName": <thread name>"""
  )

  def normalised(lines: Seq[String]): Seq[String] =
    lines.foldRight(List.empty[String]) { (line, following) =>
      if (!isStackFrame(line)) {
        withoutValuesNotCompared(line) :: following
      } else if (following.headOption.contains(stackFrames)) {
        following
      } else {
        stackFrames :: following
      }
    }

  private def isStackFrame(line: String): Boolean = stackFrameLine.matches(colour.replaceAllIn(line, ""))

  private def withoutValuesNotCompared(line: String): String =
    jsonValuesNotCompared.foldLeft(duration.replaceAllIn(line, "<duration>")) {
      case (text, (value, placeholder)) => value.replaceAllIn(text, java.util.regex.Matcher.quoteReplacement(placeholder))
    }
}
