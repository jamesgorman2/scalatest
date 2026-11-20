package org.scalatest.mill

import org.scalatest.consolidation.Consolidation
import scala.collection.mutable

private[mill] final class ConsolidationUnderTest {

  private val lines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]

  val consolidation: Consolidation = Consolidation.start(line => lines += line, coloured = false)

  def output: Seq[String] = lines.toSeq

  def outputWithoutSummaryLine: Seq[String] = output.dropRight(1)
}
