package org.scalatest.tools

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.scalatest.ConfigMap
import org.scalatest.DispatchReporter
import org.scalatest.Reporter
import org.scalatest.Tracker
import org.scalatest.concurrent.Eventually
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.Ordinal
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunStarting
import org.scalatest.events.RunStopped
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

  private final class RecordingReporter extends Reporter {
    private val events = new ConcurrentLinkedQueue[Event]
    def apply(event: Event): Unit = events.add(event)
    def received: Vector[Event] = events.asScala.toVector
  }

  // A DispatchReporter whose dispatcher has died never finishes disposing, so a test that would hang fails instead.
  private def disposedWithinTimeLimit(dispatch: DispatchReporter): Unit = {
    val disposing = new Thread(() => dispatch.dispatchDisposeAndWaitUntilDone(), "disposes a DispatchReporter")
    disposing.setDaemon(true)
    disposing.start()
    disposing.join(TimeUnit.SECONDS.toMillis(30))
    withClue("the DispatchReporter did not finish disposing within 30 seconds: ") {
      disposing.isAlive shouldBe false
    }
  }

  "A DispatchReporter" should {
    "hand off to the test event log each closing run event of its own run, with the summary its reporters receive" in {
      val handedOff = new ConcurrentLinkedQueue[Event]
      val reporter = new RecordingReporter
      val dispatch = new DispatchReporter(List(reporter))
      val tracker = new Tracker
      try {
        dispatch.registerTestEventLogHandOff(event => handedOff.add(event))
        dispatch(RunStarting(tracker.nextOrdinal(), 1, ConfigMap.empty))
        dispatch(TestSucceeded(tracker.nextOrdinal(), "Suite", "samples.Suite", Some("samples.Suite"), "passes", "passes", Vector.empty))
        dispatch.reportAndHandOff(RunCompleted(tracker.nextOrdinal(), Some(5L)))
      } finally {
        disposedWithinTimeLimit(dispatch)
      }
      val receivedRunCompleted = reporter.received.collect { case completed: RunCompleted => completed }
      receivedRunCompleted.map(_.summary) shouldBe Vector(Some(Summary(1, 0, 0, 0, 0, 0, 0, 0)))
      handedOff.asScala.toVector shouldBe receivedRunCompleted
    }

    "hand off no closing run event it reports as relayed from another process" in {
      val handedOff = new ConcurrentLinkedQueue[Event]
      val reporter = new RecordingReporter
      val dispatch = new DispatchReporter(List(reporter))
      val relayed = RunStopped(new Ordinal(0))
      val ownRunCompleted = RunCompleted(new Ordinal(0), summary = Some(Summary(0, 0, 0, 0, 0, 0, 0, 0)))
      try {
        dispatch.registerTestEventLogHandOff(event => handedOff.add(event))
        dispatch(relayed)
        dispatch.reportAndHandOff(ownRunCompleted)
      } finally {
        disposedWithinTimeLimit(dispatch)
      }
      reporter.received shouldBe Vector(relayed, ownRunCompleted)
      handedOff.asScala.toVector shouldBe Vector(ownRunCompleted)
    }

    "report a closing run event, then throw to its send site the VirtualMachineError its hand-off throws, and still dispose" in {
      val reporter = new RecordingReporter
      val dispatch = new DispatchReporter(List(reporter))
      val runCompleted = RunCompleted(new Ordinal(0), summary = Some(Summary(0, 0, 0, 0, 0, 0, 0, 0)))
      try {
        dispatch.registerTestEventLogHandOff(_ => throw new OutOfMemoryError("the hand-off ran out of memory"))
        the[OutOfMemoryError] thrownBy dispatch.reportAndHandOff(runCompleted) should have message "the hand-off ran out of memory"
      } finally {
        disposedWithinTimeLimit(dispatch)
      }
      reporter.received shouldBe Vector(runCompleted)
    }

    "report the events after a closing run event whose hand-off leaves the dispatcher's thread interrupted, and still dispose" in {
      val reporter = new RecordingReporter
      val dispatch = new DispatchReporter(List(reporter))
      val runCompleted = RunCompleted(new Ordinal(0), summary = Some(Summary(0, 0, 0, 0, 0, 0, 0, 0)))
      val later = InfoProvided(new Ordinal(0).next, "after the run", None)
      try {
        dispatch.registerTestEventLogHandOff(_ => Thread.currentThread().interrupt())
        dispatch.reportAndHandOff(runCompleted)
        Thread.currentThread().isInterrupted shouldBe false
        dispatch(later)
      } finally {
        disposedWithinTimeLimit(dispatch)
      }
      reporter.received shouldBe Vector(runCompleted, later)
    }
  }
}
