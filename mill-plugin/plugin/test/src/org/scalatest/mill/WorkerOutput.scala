package org.scalatest.mill

import java.nio.charset.StandardCharsets

private[mill] object WorkerOutput {

  def writeStdout(capture: OutputCapture, text: String): Unit = write(capture.stdout, text)

  def writeStderr(capture: OutputCapture, text: String): Unit = write(capture.stderr, text)

  private def write(stream: (Array[Byte], Int) => Unit, text: String): Unit = {
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    stream(bytes, bytes.length)
  }
}
