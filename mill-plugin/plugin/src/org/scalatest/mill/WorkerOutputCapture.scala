package org.scalatest.mill

import java.nio.charset.Charset
import org.scalatest.consolidation.CapturedLine
import scala.collection.mutable

/** Lines captured before the worker's run exists are held until [[addTo]] names the run. */
private[mill] final class WorkerOutputCapture(charset: Charset) {

  private val held = mutable.ArrayBuffer.empty[CapturedLine]
  private var run: Option[CapturedLine => Unit] = None
  private val stdoutLines = new StreamLines(charset, text => add(CapturedLine.Stdout(text)))
  private val stderrLines = new StreamLines(charset, text => add(CapturedLine.Stderr(text)))

  /** Called only by stdout's reader. */
  def stdout(bytes: Array[Byte], length: Int): Unit = stdoutLines.append(bytes, length)

  /** Called only by stderr's reader. */
  def stderr(bytes: Array[Byte], length: Int): Unit = stderrLines.append(bytes, length)

  /** Called once neither stream's reader is running. */
  def endStreams(): Unit = {
    stdoutLines.end()
    stderrLines.end()
  }

  def addTo(run: CapturedLine => Unit): Unit =
    synchronized {
      held.foreach(run)
      held.clear()
      this.run = Some(run)
    }

  private def add(line: CapturedLine): Unit =
    synchronized {
      run match {
        case Some(addToRun) => addToRun(line)
        case None => held += line
      }
    }
}

private[mill] object WorkerOutputCapture {

  /**
   * The charset a worker writes a piped stdout and stderr in: the platform's native encoding, which Mill's JVM names in
   * `native.encoding`, else that JVM's default charset.
   */
  def workerCharset: Charset =
    Option(System.getProperty("native.encoding")).map(Charset.forName).getOrElse(Charset.defaultCharset())
}
