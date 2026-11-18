package org.scalatest.consolidation

import java.nio.file.Paths
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class RunnerArgumentSpec extends AnyWordSpec with Matchers {

  "The runner argument" when {
    "built for an event log file's absolute path" should {
      "be ScalaTest's flag followed by that path" in {
        val eventLogFile = Paths.get("events.log").toAbsolutePath
        RunnerArgument.forEventLogFile(eventLogFile) shouldBe Seq("--test-event-log", eventLogFile.toString)
      }
    }

    "asked for with a relative path" should {
      "be refused, naming the path" in {
        the[IllegalArgumentException] thrownBy RunnerArgument.forEventLogFile(Paths.get("events.log")) should have message
          "requirement failed: the event log file's path must be absolute, but was events.log"
      }
    }
  }
}
