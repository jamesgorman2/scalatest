package org.scalatest.mill

import java.nio.charset.StandardCharsets
import org.scalatest.consolidation.CapturedLine
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable

final class WorkerOutputCaptureSpec extends AnyWordSpec with should.Matchers {

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  private def stdout(capture: WorkerOutputCapture, text: String): Unit = capture.stdout(bytes(text), bytes(text).length)

  private def stderr(capture: WorkerOutputCapture, text: String): Unit = capture.stderr(bytes(text), bytes(text).length)

  private def run(capture: WorkerOutputCapture): mutable.ArrayBuffer[CapturedLine] = {
    val lines = mutable.ArrayBuffer.empty[CapturedLine]
    capture.addTo(line => lines += line)
    lines
  }

  "A worker's output capture" when {
    "lines are completed before the run exists" should {
      "pass them to the run once it is named, tagged with their streams, in the order they were completed" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        stderr(capture, "warn: about")
        stdout(capture, "dividing now\n")
        stderr(capture, " to divide\n")
        val lines = run(capture)
        lines shouldBe Seq(CapturedLine.Stdout("dividing now"), CapturedLine.Stderr("warn: about to divide"))
      }
    }

    "lines are completed after the run is named" should {
      "pass each to the run as it is completed, after those held" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        stdout(capture, "before\n")
        val lines = run(capture)
        stderr(capture, "after\n")
        lines shouldBe Seq(CapturedLine.Stdout("before"), CapturedLine.Stderr("after"))
      }
    }

    "its streams end" should {
      "pass on each stream's unterminated last line" in {
        val capture = new WorkerOutputCapture(StandardCharsets.UTF_8)
        val lines = run(capture)
        stdout(capture, "last out")
        stderr(capture, "last err")
        lines shouldBe empty
        capture.endStreams()
        lines shouldBe Seq(CapturedLine.Stdout("last out"), CapturedLine.Stderr("last err"))
      }
    }
  }
}
