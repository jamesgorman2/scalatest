package org.scalatest.mill

import org.scalatest.consolidation.ScalaTestArgument

private[mill] final case class AllowedFlag(flag: String, separateValueCount: Int) {

  def hasItsSeparateValues(argument: ScalaTestArgument.Flagged): Boolean = argument.arguments.size == 1 + separateValueCount
}
