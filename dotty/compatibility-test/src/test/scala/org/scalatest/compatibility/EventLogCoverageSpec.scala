package org.scalatest.compatibility

import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace
import org.scalatest.events.TestEventLogEvent
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class EventLogCoverageSpec extends AnyWordSpec with Matchers {

  private val suite = NodeExecution(NodeId(Vector(NodePlace.Suite("s", 0))), Vector(1))
  private val test = NodeExecution(NodeId(Vector(NodePlace.Suite("s", 0), NodePlace.Test("t"))), Vector(1, 1))

  private val suiteStarting = new TestEventLogEvent.ScalaTestEvent(Some(suite), DecodedEvent.SuiteStarting(None, "main", 1L))
  private val testStarting = new TestEventLogEvent.ScalaTestEvent(Some(test), DecodedEvent.TestStarting(None, "main", 2L))
  private val infoProvided = new TestEventLogEvent.ScalaTestEvent(Some(test), DecodedEvent.InfoProvided("checked", None, None, "main", 3L))

  "An event of the event log" should {
    "be written with its node and every field" in {
      EventLogFields.of(infoProvided, withTimeStamp = true) shouldBe
        """test "s" "t" InfoProvided(message="checked", throwable=None, location=None, threadName="main", timeStamp=3)"""
    }
    "be written without its timestamp where asked" in {
      EventLogFields.of(infoProvided, withTimeStamp = false) shouldBe
        """test "s" "t" InfoProvided(message="checked", throwable=None, location=None, threadName="main")"""
    }
  }

  "The events a reporter received" when {
    val received = List(suiteStarting, testStarting).map(event => "received " + EventLogFields.of(event, withTimeStamp = true))

    "all in the event log in the order received" should {
      "be covered" in {
        EventLogCoverage.missing(received, List(suiteStarting, infoProvided, testStarting)) shouldBe Nil
      }
    }
    "in the event log in another order" should {
      "be missing from the place received" in {
        EventLogCoverage.missing(received, List(testStarting, suiteStarting)) shouldBe
          List("received, not in the event log in the order received, though it is earlier: " + EventLogFields.of(testStarting, withTimeStamp = true))
      }
    }
  }

  "An event recorded in another" when {
    val recorded = List("recorded " + EventLogFields.of(infoProvided, withTimeStamp = false))

    "in the event log with another timestamp" should {
      "be covered" in {
        val calledEarlier = infoProvided.copy(event = DecodedEvent.InfoProvided("checked", None, None, "main", 0L))
        EventLogCoverage.missing(recorded, List(calledEarlier)) shouldBe Nil
      }
    }
    "not in the event log" should {
      "be missing" in {
        EventLogCoverage.missing(recorded, List(testStarting)) shouldBe
          List("recorded, not in the event log: " + EventLogFields.of(infoProvided, withTimeStamp = false))
      }
    }
  }
}
