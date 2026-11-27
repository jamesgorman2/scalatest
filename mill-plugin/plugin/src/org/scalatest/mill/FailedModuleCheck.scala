package org.scalatest.mill

import org.scalatest.consolidation.ModuleName

private[mill] final case class FailedModuleCheck(module: ModuleName, message: String) {

  def lines: Seq[String] = s"${module.value}:" +: message.linesIterator.map("  " + _).toSeq
}
