package org.scalatest.mill

import java.nio.charset.Charset
import org.scalatest.consolidation.CapturedLine
import scala.collection.mutable

private[mill] final class WorkerOutputCapture(charset: Charset) {

  private val held = mutable.ArrayBuffer.empty[CapturedLine]
  private var run: Option[CapturedLine => Unit] = None
  private var capturing = true
  private val stdoutLines = new StreamLines(charset, text => add(CapturedLine.Stdout(text)))
  private val stderrLines = new StreamLines(charset, text => add(CapturedLine.Stderr(text)))

  def stdout(bytes: Array[Byte], length: Int): Unit = stdoutLines.append(bytes, length)

  def stderr(bytes: Array[Byte], length: Int): Unit = stderrLines.append(bytes, length)

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

  def stopCapturing(): Unit =
    synchronized {
      // A failed worker, as when Mill cancels the task, can go on writing after its run has ended, and a line added to
      // an ended run throws on Mill's pumping thread.
      capturing = false
      held.clear()
    }

  private def add(line: CapturedLine): Unit =
    synchronized {
      if (capturing) {
        run match {
          case Some(addToRun) => addToRun(line)
          case None => held += line
        }
      }
    }
}

private[mill] object WorkerOutputCapture {

  def workerCharset(systemProperty: String => Option[String]): Charset =
    // A worker writes a piped stdout and stderr in the platform's native encoding, which Mill's JVM names in
    // `native.encoding`.
    systemProperty("native.encoding").map(Charset.forName).getOrElse(Charset.defaultCharset())
}
