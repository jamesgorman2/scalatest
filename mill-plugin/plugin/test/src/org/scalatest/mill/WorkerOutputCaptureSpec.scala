package org.scalatest.mill

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.scalatest.consolidation.CapturedLine
import org.scalatest.matchers.should
import org.scalatest.mill.WorkerOutput.writeStderr
import org.scalatest.mill.WorkerOutput.writeStdout
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable

final class WorkerOutputCaptureSpec extends AnyWordSpec with should.Matchers {

  private def linesOfRunAddedTo(capture: WorkerOutputCapture): mutable.ArrayBuffer[CapturedLine] = {
    val lines = mutable.ArrayBuffer.empty[CapturedLine]
    capture.addTo(line => lines += line)
    lines
  }

  "A worker's output capture" when {
    "lines are completed before the run exists" should {
      "pass them to the run once it is named, tagged with their streams, in the order they were completed" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        writeStderr(capture, "warn: about")
        writeStdout(capture, "dividing now\n")
        writeStderr(capture, " to divide\n")
        val lines = linesOfRunAddedTo(capture)
        lines shouldBe Seq(CapturedLine.Stdout("dividing now\n"), CapturedLine.Stderr("warn: about to divide\n"))
      }
    }

    "lines are completed after the run is named" should {
      "pass each to the run as it is completed, after those held" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        writeStdout(capture, "before\n")
        val lines = linesOfRunAddedTo(capture)
        writeStderr(capture, "after\n")
        lines shouldBe Seq(CapturedLine.Stdout("before\n"), CapturedLine.Stderr("after\n"))
      }
    }

    "its streams end" should {
      "pass on each stream's unterminated last line" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        val lines = linesOfRunAddedTo(capture)
        writeStdout(capture, "last out")
        writeStderr(capture, "last err")
        lines shouldBe empty
        capture.endStreams()
        lines shouldBe Seq(CapturedLine.Stdout("last out"), CapturedLine.Stderr("last err"))
      }
    }

    "capturing has stopped" should {
      "drop each line completed after, passing none to the run" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        val lines = linesOfRunAddedTo(capture)
        writeStdout(capture, "before\n")
        capture.stopCapturing()
        writeStdout(capture, "after\n")
        writeStderr(capture, "after\n")
        lines shouldBe Seq(CapturedLine.Stdout("before\n"))
      }
      "drop the lines it held before the run is named" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        writeStdout(capture, "held\n")
        capture.stopCapturing()
        writeStdout(capture, "after\n")
        linesOfRunAddedTo(capture) shouldBe empty
      }
    }
  }

  "A worker's charset" when {
    "Mill's JVM names a native encoding" should {
      "be that encoding" in {
        WorkerOutputCapture.workerCharset(Map("native.encoding" -> "UTF-16LE").get) shouldBe StandardCharsets.UTF_16LE
      }
    }

    "Mill's JVM names no native encoding" should {
      "be that JVM's default charset" in {
        WorkerOutputCapture.workerCharset(_ => None) shouldBe Charset.defaultCharset()
      }
    }
  }
}
