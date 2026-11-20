package org.scalatest.mill

import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays

private[mill] trait EventLogFile {

  def name: String

  def exists: Boolean

  def open(): EventLogInput
}

private[mill] trait EventLogInput {

  // Empty at the file's current end, which the emitter may yet append to.
  def read(): Array[Byte]

  def close(): Unit
}

private[mill] object EventLogFile {

  def at(path: Path): EventLogFile =
    new EventLogFile {
      def name: String = path.toString

      def exists: Boolean = Files.exists(path)

      def open(): EventLogInput = new StreamInput(new FileInputStream(path.toFile))
    }

  private final class StreamInput(stream: InputStream) extends EventLogInput {

    private val buffer = new Array[Byte](64 * 1024)

    def read(): Array[Byte] = Arrays.copyOf(buffer, Math.max(stream.read(buffer), 0))

    def close(): Unit = stream.close()
  }
}
