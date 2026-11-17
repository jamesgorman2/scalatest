package org.scalatest.mill

import java.nio.charset.StandardCharsets

/** Splits a test event log's bytes, as they are read, into whole lines. Bytes after the last line end are held back. */
private[mill] final class EventLogLines {

  private val splitter = new LineSplitter

  /** The lines the bytes complete, each without its line end. */
  def append(bytes: Array[Byte], length: Int): Vector[String] = {
    val lines = Vector.newBuilder[String]
    splitter.append(bytes, length)(line => lines += new String(line, StandardCharsets.UTF_8))
    lines.result()
  }
}
