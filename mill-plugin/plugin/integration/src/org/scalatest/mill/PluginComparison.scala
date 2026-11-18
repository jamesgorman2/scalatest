package org.scalatest.mill

import mill.testkit.IntegrationTester

final case class BuildComparison[A](withoutPlugin: A, withPlugin: A) {

  def describe(show: A => String): String =
    s"""Without the plugin:
       |${show(withoutPlugin)}
       |With the plugin:
       |${show(withPlugin)}""".stripMargin
}

object MillBuild {

  /** A later invocation then writes nothing about compiling the build. */
  def compileBuild(tester: IntegrationTester): Unit = {
    val result = tester.eval(Seq("resolve", "_"))
    require(result.isSuccess, s"the build did not compile:\n${result.debugString}")
  }

  def removeModuleOutput(tester: IntegrationTester): Unit = os.remove.all(tester.workspacePath / "out" / "core")
}

final case class MillConsole(eval: IntegrationTester.EvalResult) {

  def exitCode: Int = eval.exitCode

  def stdoutLines: Seq[String] = eval.out.linesIterator.map(Durations.replaced).toSeq

  def stderrLines: Seq[String] = eval.err.linesIterator.map(Durations.replaced).toSeq

  def lines: Seq[String] = stdoutLines ++ stderrLines

  def debugString: String = eval.debugString
}

object Durations {

  private val part: String = """\d+(?:\.\d+)?\s?(?:ms|s|m|h|milliseconds?|seconds?|minutes?|hours?)\b"""

  // One duration may have several parts, such as `1 second, 23 milliseconds` or `1m 2s`.
  private val duration = ("""\b""" + part + """(?:,?\s""" + part + ")*").r

  def replaced(line: String): String = duration.replaceAllIn(line, "<duration>")
}
