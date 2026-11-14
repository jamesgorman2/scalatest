package org.scalatest.mill

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Splits a test event log's bytes, as they are read, into whole lines. Bytes after the last line end are held back. */
private[mill] final class EventLogLines {

  private val heldBack = new ByteArrayOutputStream()

  /** The lines the bytes complete, each without its line end. */
  def append(bytes: Array[Byte], length: Int): Vector[String] = {
    val lines = Vector.newBuilder[String]
    for (i <- 0 until length) {
      if (bytes(i) == '\n') {
        lines += heldBack.toString(StandardCharsets.UTF_8)
        heldBack.reset()
      } else {
        heldBack.write(bytes(i))
      }
    }
    lines.result()
  }
}
