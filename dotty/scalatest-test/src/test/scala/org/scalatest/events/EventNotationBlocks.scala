package org.scalatest.events

private[events] object EventNotationBlocks {

  /** Returns the blocks as one notation, each with its own outermost indentation removed. */
  def joined(blocks: String*): String = {
    blocks.map { block =>
      val lines = block.split('\n').filter(_.trim.nonEmpty)
      val indentation = lines.map(_.takeWhile(_ == ' ').length).min
      lines.map(_.drop(indentation)).mkString("\n")
    }.mkString("\n")
  }
}
