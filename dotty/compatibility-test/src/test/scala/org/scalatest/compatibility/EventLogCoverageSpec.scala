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

  "The events the emitter received" when {
    val received = List(suiteStarting, testStarting).map(event => "received " + EventLogFields.of(event, withTimeStamp = true))

    "all in the event log in the order received" should {
      "be covered" in {
        EventLogCoverage.missing(received, List(suiteStarting, infoProvided, testStarting), EmitterOrder.AcrossTheRun) shouldBe Nil
      }
    }
    "in the event log in another order" should {
      "be missing from the place received" in {
        EventLogCoverage.missing(received, List(testStarting, suiteStarting), EmitterOrder.AcrossTheRun) shouldBe
          List("received, not in the event log in the emitter's order, though the log holds it earlier: " + EventLogFields.of(testStarting, withTimeStamp = true))
      }
    }
    "in the event log in each suite's order, though not the run's" should {
      val otherSuiteStarting = new TestEventLogEvent.ScalaTestEvent(
        Some(NodeExecution(NodeId(Vector(NodePlace.Suite("other", 0))), Vector(1))),
        DecodedEvent.SuiteStarting(None, "main", 0L)
      )
      val receivedFromTwoSuites = received :+ ("received " + EventLogFields.of(otherSuiteStarting, withTimeStamp = true))

      "be covered where the emitter's order is only each suite's" in {
        EventLogCoverage.missing(receivedFromTwoSuites, List(otherSuiteStarting, suiteStarting, testStarting), EmitterOrder.WithinEachSuite) shouldBe Nil
      }
      "be missing where the emitter's order is the run's" in {
        EventLogCoverage.missing(receivedFromTwoSuites, List(otherSuiteStarting, suiteStarting, testStarting), EmitterOrder.AcrossTheRun) shouldBe
          List("received, not in the event log in the emitter's order, though the log holds it earlier: " + EventLogFields.of(otherSuiteStarting, withTimeStamp = true))
      }
    }
  }

  "The suites' starts" when {
    val otherSuiteStarting = new TestEventLogEvent.ScalaTestEvent(
      Some(NodeExecution(NodeId(Vector(NodePlace.Suite("other", 0))), Vector(1))),
      DecodedEvent.SuiteStarting(None, "main", 4L)
    )
    val received = List(suiteStarting, testStarting, otherSuiteStarting).map(event => "received " + EventLogFields.of(event, withTimeStamp = true))

    "received by the emitter" should {
      "be the suites' IDs, in the order received" in {
        EventLogCoverage.suitesStartedAsReceived(received) shouldBe List("\"s\"", "\"other\"")
      }
    }
    "held by the event log" should {
      "be the suites' IDs, in the log's order" in {
        EventLogCoverage.suitesStartedAsLogged(List(otherSuiteStarting, testStarting, suiteStarting)) shouldBe List("\"other\"", "\"s\"")
      }
    }
  }

  "An event recorded in another" when {
    val recorded = List("recorded " + EventLogFields.of(infoProvided, withTimeStamp = false))

    "in the event log with another timestamp" should {
      "be covered" in {
        val calledEarlier = infoProvided.copy(event = DecodedEvent.InfoProvided("checked", None, None, "main", 0L))
        EventLogCoverage.missing(recorded, List(calledEarlier), EmitterOrder.AcrossTheRun) shouldBe Nil
      }
    }
    "not in the event log" should {
      "be missing" in {
        EventLogCoverage.missing(recorded, List(testStarting), EmitterOrder.AcrossTheRun) shouldBe
          List("recorded, not in the event log: " + EventLogFields.of(infoProvided, withTimeStamp = false))
      }
    }
  }
}
