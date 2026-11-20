package org.scalatest.mill

object Durations {

  private val part: String = """\d+(?:\.\d+)?\s?(?:ms|s|m|h|milliseconds?|seconds?|minutes?|hours?)\b"""

  // One duration may have several parts, such as `1 second, 23 milliseconds` or `1m 2s`.
  private val duration = ("""\b""" + part + """(?:,?\s""" + part + ")*").r

  def replaced(line: String): String = duration.replaceAllIn(line, "<duration>")
}
