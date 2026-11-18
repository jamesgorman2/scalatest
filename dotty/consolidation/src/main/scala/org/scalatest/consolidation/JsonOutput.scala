package org.scalatest.consolidation

import java.io.Writer

/** The JSON output of an ended consolidation, written as it is produced rather than held whole as text. */
final class JsonOutput private[consolidation] (root: ConsolidatedRoot) {

  /**
   * Writes the JSON output's text to the writer, which this neither flushes nor closes.
   *
   * @throws java.io.IOException what the writer throws
   */
  def writeTo(writer: Writer): Unit = JsonOutputWriter.write(root, writer)
}
