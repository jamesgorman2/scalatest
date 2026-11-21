package org.scalatest.mill

import mill.testkit.IntegrationTester

final case class MillConsole(eval: IntegrationTester.EvalResult) {

  def exitCode: Int = eval.exitCode

  def stdoutLines: Seq[String] = eval.out.linesIterator.map(Durations.replaced).toSeq

  def stderrLines: Seq[String] = eval.err.linesIterator.map(Durations.replaced).toSeq

  def lines: Seq[String] = stdoutLines ++ stderrLines

  def debugString: String = eval.debugString
}
