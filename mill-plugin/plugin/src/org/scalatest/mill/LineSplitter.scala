package org.scalatest.mill

import java.io.ByteArrayOutputStream

/** Splits bytes, as they are read, into lines at each `\n` byte, which no line keeps. Bytes after the last `\n` are held. */
private[mill] final class LineSplitter {

  private val held = new ByteArrayOutputStream()

  /** Adds the first `length` bytes of `bytes`, passing `line` the bytes of each line they complete. */
  def append(bytes: Array[Byte], length: Int)(line: Array[Byte] => Unit): Unit =
    for (i <- 0 until length) {
      if (bytes(i) == '\n') {
        line(takeHeld())
      } else {
        held.write(bytes(i))
      }
    }

  /** Takes the bytes held after the last `\n`, if any were read. */
  def takeRest(): Option[Array[Byte]] = Option.when(held.size > 0)(takeHeld())

  private def takeHeld(): Array[Byte] = {
    val bytes = held.toByteArray
    held.reset()
    bytes
  }
}
