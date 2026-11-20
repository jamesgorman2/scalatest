package org.scalatest.mill

import java.io.ByteArrayOutputStream

private[mill] final class LineSplitter {

  private val held = new ByteArrayOutputStream()

  def append(bytes: Array[Byte], length: Int)(line: Array[Byte] => Unit): Unit =
    for (i <- 0 until length) {
      if (bytes(i) == '\n') {
        line(takeHeld())
      } else {
        held.write(bytes(i))
      }
    }

  def takeRest(): Option[Array[Byte]] = Option.when(held.size > 0)(takeHeld())

  private def takeHeld(): Array[Byte] = {
    val bytes = held.toByteArray
    held.reset()
    bytes
  }
}
