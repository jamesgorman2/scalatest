package org.scalatest.consolidation

/** The JSON output of an ended consolidation, written as it is produced rather than held whole as text. */
final class JsonOutput private[consolidation] (root: ConsolidatedRoot) {

  /**
   * Gives the whole of the JSON output's text to `output`, in order, piece by piece. No
   * piece ends part way through a surrogate pair.
   *
   * Throws what `output` throws.
   */
  def writeTo(output: String => Unit): Unit = JsonOutputWriter.write(root, output)
}
