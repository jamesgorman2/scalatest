package org.scalatest.consolidation

/** The JSON output of an ended consolidation, written as it is produced rather than held whole as text. */
final class JsonOutput private[consolidation] (root: ConsolidatedRoot) {

  /**
   * Gives the JSON output's text to `output` piece by piece, in order, so that the pieces joined are the whole text. No
   * piece ends part way through a surrogate pair.
   *
   * Throws what `output` throws.
   */
  def writeTo(output: String => Unit): Unit = JsonOutputWriter.write(root, output)
}
