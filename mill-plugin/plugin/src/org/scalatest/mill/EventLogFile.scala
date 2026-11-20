package org.scalatest.mill

import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays

/** A worker's test event log file, which the worker's emitter moves into place and then appends to. */
private[mill] trait EventLogFile {

  def exists: Boolean

  /**
   * Opens the file to be read from its start.
   *
   * @throws java.io.FileNotFoundException where the file cannot be opened
   */
  def open(): EventLogInput
}

/** An event log file opened to be read. */
private[mill] trait EventLogInput {

  /** The file's next bytes; none at the file's current end, though a later read may find more. */
  def read(): Array[Byte]

  def close(): Unit
}

private[mill] object EventLogFile {

  def at(path: Path): EventLogFile =
    new EventLogFile {
      def exists: Boolean = Files.exists(path)

      def open(): EventLogInput = new StreamInput(new FileInputStream(path.toFile))

      override def toString: String = path.toString
    }

  private final class StreamInput(stream: InputStream) extends EventLogInput {

    private val buffer = new Array[Byte](64 * 1024)

    def read(): Array[Byte] = Arrays.copyOf(buffer, Math.max(stream.read(buffer), 0))

    def close(): Unit = stream.close()
  }
}
