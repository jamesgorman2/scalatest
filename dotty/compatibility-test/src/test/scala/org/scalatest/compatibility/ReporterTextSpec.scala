package org.scalatest.compatibility

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ReporterTextSpec extends AnyWordSpec with Matchers {

  "Reporter text" when {
    "normalised" should {
      "replace each run of stack-frame lines with one placeholder" in {
        val lines = List(
          "- fails *** FAILED ***",
          "  java.lang.IllegalStateException: outer",
          "  at samples.FailSpec.f(FailSpec.scala:9)",
          "  at java.base/java.lang.Thread.run(Thread.java:1583)",
          "  ...",
          "  Cause: java.io.IOException: inner",
          "  at samples.FailSpec.g(FailSpec.scala:12)"
        )
        ReporterText.normalised(lines) shouldBe List(
          "- fails *** FAILED ***",
          "  java.lang.IllegalStateException: outer",
          "<stack frames>",
          "  ...",
          "  Cause: java.io.IOException: inner",
          "<stack frames>"
        )
      }
      "replace coloured stack-frame lines and those a logger received" in {
        val lines = List(
          "info: \u001b[31m  at samples.FailSpec.f(FailSpec.scala:9)\u001b[0m",
          "\u001b[31m  at samples.FailSpec.g(FailSpec.scala:12)\u001b[0m"
        )
        ReporterText.normalised(lines) shouldBe List("<stack frames>")
      }
      "keep the colour of every other line" in {
        ReporterText.normalised(List("\u001b[32m- succeeds\u001b[0m")) shouldBe List("\u001b[32m- succeeds\u001b[0m")
      }
      "keep a line that only begins like a stack frame" in {
        ReporterText.normalised(List("  at the start of a sentence")) shouldBe List("  at the start of a sentence")
      }
      "blank durations" in {
        val lines = List("- succeeds (5 milliseconds)", "Run completed in 1 second, 204 milliseconds.", "- is quick (1 millisecond)")
        ReporterText.normalised(lines) shouldBe List("- succeeds (<duration>)", "Run completed in <duration>.", "- is quick (<duration>)")
      }
      "blank the values of a JSON line that are not compared" in {
        val line =
          """{ "eventType": "TestFailed", "duration": 12, "throwable": { "className": "E",  "message": "m", "depth": 3, "stackTraces": [{ "className": "A", "toString": "A.f(A.scala:1)" }, { "className": "B", "toString": "B.g(B.scala:2)" }] }, "threadName": "main \"quoted\"", "timeStamp": 1794567890123 }"""
        ReporterText.normalised(List(line)) shouldBe List(
          """{ "eventType": "TestFailed", "duration": <duration>, "throwable": { "className": "E",  "message": "m", "depth": <depth>, "stackTraces": <stack frames> }, "threadName": <thread name>, "timeStamp": <timestamp> }"""
        )
      }
    }
  }
}
