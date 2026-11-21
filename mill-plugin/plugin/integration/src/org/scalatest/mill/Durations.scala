package org.scalatest.mill

object Durations {

  private val part: String = """\d+(?:\.\d+)?\s?(?:ms|s|m|h|milliseconds?|seconds?|minutes?|hours?)\b"""

  private val duration = ("""\b""" + part + """(?:,?\s""" + part + ")*").r

  def replaced(line: String): String = duration.replaceAllIn(line, "<duration>")
}
