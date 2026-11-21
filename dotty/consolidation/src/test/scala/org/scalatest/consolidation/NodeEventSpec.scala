package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import org.scalatest.events.DecodedEvent
import org.scalatest.events.TopOfClass
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class NodeEventSpec extends AnyWordSpec with should.Matchers {

  private val location = Some(TopOfClass("samples.RowSuite"))

  "A node's event" when {
    "read from a SuiteStarting" should {
      "carry its time, location and thread, and no duration" in {
        NodeEvent.of(DecodedEvent.SuiteStarting(location, "main", 100)) shouldBe NodeEvent(Instant.ofEpochMilli(100), None, location, "main")
      }
    }
    "read from a TestSucceeded" should {
      "carry its time, duration, location and thread" in {
        NodeEvent.of(DecodedEvent.TestSucceeded(Some(5), location, "pool-1", 100)) shouldBe
          NodeEvent(Instant.ofEpochMilli(100), Some(Duration.ofMillis(5)), location, "pool-1")
      }
    }
    "read from a ScopeClosed" should {
      "carry no duration" in {
        NodeEvent.of(DecodedEvent.ScopeClosed(location, "main", 100)).duration shouldBe None
      }
    }
  }
}
