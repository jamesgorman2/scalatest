package org.scalatest.mill

import java.nio.charset.StandardCharsets

private[mill] final class EventLogLines {

  private val splitter = new LineSplitter

  def append(bytes: Array[Byte], length: Int): Vector[String] = {
    val lines = Vector.newBuilder[String]
    splitter.append(bytes, length)(line => lines += new String(line, StandardCharsets.UTF_8))
    lines.result()
  }
}
