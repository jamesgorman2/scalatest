package org.scalatest.tools

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.ConfigMap
import org.scalatest.DispatchReporter
import org.scalatest.Reporter
import org.scalatest.Tracker
import org.scalatest.concurrent.Eventually
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.Ordinal
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunStarting
import org.scalatest.events.Summary
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

final class DispatchReporterHandOffSpec extends AnyWordSpec with Matchers with Eventually {

  private def alertsIn(events: ConcurrentLinkedQueue[Event]): Vector[AlertProvided] =
    events.asScala.toVector.collect { case alert: AlertProvided => alert }

  "A DispatchReporter that detects slowpokes" should {
    "hand off to the test event log each slowpoke alert its reporters receive, in the same order" in {
      val handedOff = new ConcurrentLinkedQueue[Event]
      val received = new ConcurrentLinkedQueue[Event]
      val reporter = new Reporter {
        def apply(event: Event): Unit = received.add(event)
      }
      val dispatch = new DispatchReporter(List(reporter), Console.err, true, 1, 1)
      try {
        dispatch.registerTestEventLogHandOff(event => handedOff.add(event))
        dispatch(TestStarting(new Ordinal(1), "SlowSuite", "samples.SlowSuite", Some("samples.SlowSuite"), "sleeps", "sleeps"))
        eventually(timeout(Span(30, Seconds))) {
          alertsIn(received) should not be empty
        }
      } finally {
        dispatch.dispatchDisposeAndWaitUntilDone()
      }
      val receivedAlerts = alertsIn(received)
      alertsIn(handedOff).take(receivedAlerts.size) shouldBe receivedAlerts
    }
  }

  "A DispatchReporter" should {
    "hand off to the test event log each closing run event, with the summary its reporters receive" in {
      val handedOff = new ConcurrentLinkedQueue[Event]
      val received = new ConcurrentLinkedQueue[Event]
      val reporter = new Reporter {
        def apply(event: Event): Unit = received.add(event)
      }
      val dispatch = new DispatchReporter(List(reporter))
      val tracker = new Tracker
      try {
        dispatch.registerTestEventLogHandOff(event => handedOff.add(event))
        dispatch(RunStarting(tracker.nextOrdinal(), 1, ConfigMap.empty))
        dispatch(TestSucceeded(tracker.nextOrdinal(), "Suite", "samples.Suite", Some("samples.Suite"), "passes", "passes", Vector.empty))
        dispatch(RunCompleted(tracker.nextOrdinal(), Some(5L)))
      } finally {
        dispatch.dispatchDisposeAndWaitUntilDone()
      }
      val receivedRunCompleted = received.asScala.toVector.collect { case completed: RunCompleted => completed }
      receivedRunCompleted.map(_.summary) shouldBe Vector(Some(Summary(1, 0, 0, 0, 0, 0, 0, 0)))
      handedOff.asScala.toVector shouldBe receivedRunCompleted
    }
  }
}
