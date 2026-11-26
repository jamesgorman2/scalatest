package org.scalatest.mill

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.Arrays
import org.scalatest.consolidation.CapturedLine
import scala.collection.mutable

private[mill] final class OutputCapture(charset: Charset) {

  private val held = mutable.ArrayBuffer.empty[CapturedLine]
  private var run: Option[CapturedLine => Unit] = None
  private var capturing = true
  private val stdoutLines = new StreamLines(charset, text => add(CapturedLine.Stdout(text)))
  private val stderrLines = new StreamLines(charset, text => add(CapturedLine.Stderr(text)))

  def stdout(bytes: Array[Byte], length: Int): Unit = append(stdoutLines, bytes, length)

  def stderr(bytes: Array[Byte], length: Int): Unit = append(stderrLines, bytes, length)

  def stdoutPrintStream(): PrintStream = printStreamOver(stdout)

  def stderrPrintStream(): PrintStream = printStreamOver(stderr)

  private def printStreamOver(append: (Array[Byte], Int) => Unit): PrintStream = new PrintStream(new AppendingStream(append), true, charset)

  def endStreams(): Unit =
    synchronized {
      stdoutLines.end()
      stderrLines.end()
    }

  def addTo(run: CapturedLine => Unit): Unit =
    synchronized {
      held.foreach(run)
      held.clear()
      this.run = Some(run)
    }

  def stopCapturing(): Unit =
    synchronized {
      // A failed worker, as when Mill cancels the task, can go on writing after its run has ended, and a line added to
      // an ended run throws on Mill's pumping thread.
      capturing = false
      held.clear()
    }

  def stopAndTakeLines(): Seq[CapturedLine] =
    synchronized {
      // Each append takes this lock too, so no write lands between ending the streams and stopping, and none is cut
      // part-way.
      endStreams()
      val lines = held.toSeq
      stopCapturing()
      lines
    }

  private def append(lines: StreamLines, bytes: Array[Byte], length: Int): Unit =
    synchronized {
      if (capturing) {
        lines.append(bytes, length)
      }
    }

  private def add(line: CapturedLine): Unit =
    if (capturing) {
      run match {
        case Some(addToRun) => addToRun(line)
        case None => held += line
      }
    }
}

private final class AppendingStream(append: (Array[Byte], Int) => Unit) extends OutputStream {

  override def write(byte: Int): Unit = append(Array(byte.toByte), 1)

  override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = append(Arrays.copyOfRange(bytes, offset, offset + length), length)
}

private[mill] object OutputCapture {

  def workerCharset(systemProperty: String => Option[String]): Charset =
    // A worker writes a piped stdout and stderr in the platform's native encoding, which Mill's JVM names in
    // `native.encoding`.
    systemProperty("native.encoding").map(Charset.forName).getOrElse(Charset.defaultCharset())
}
