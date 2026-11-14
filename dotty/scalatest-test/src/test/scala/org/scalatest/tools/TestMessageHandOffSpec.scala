package org.scalatest.tools

import org.scalatest.EventOrigin
import org.scalatest.Filter
import org.scalatest.PathMessageCalls
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.Tracker
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.NoteProvided
import org.scalatest.events.Ordinal
import org.scalatest.events.RecordableEvent
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestMessageHandOffSpec extends AnyWordSpec with Matchers with EventLogRecording {

  private val ordinal = new Ordinal(1)
  private val timeStamp = 1700000000000L
  private val suiteName = "OneTestSpec"
  private val suiteId = "samples.OneTestSpec"

  // Its structure gives the suite node 1 and its test t node 2.
  private final class OneTestSpec extends AnyFunSpec {
    it("t") {}
  }

  private def testStarting: Event =
    TestStarting(ordinal, suiteName, suiteId, Some(suiteId), "t", "t", threadName = "main", timeStamp = timeStamp)

  private def testSucceeded(recorded: Vector[RecordableEvent] = Vector.empty): Event =
    TestSucceeded(ordinal, suiteName, suiteId, Some(suiteId), "t", "t", recorded, threadName = "main", timeStamp = timeStamp)

  private def info(message: String): InfoProvided = InfoProvided(ordinal, message, None, threadName = "main", timeStamp = timeStamp)

  private val threadAndTime = """"threadName":"main","timeStamp":1700000000000}"""

  private def testLine(eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":2,"execution":1,"parentExecution":1,"event":"$eventName",$fields$threadAndTime"""

  private def infoLine(message: String): String = testLine("InfoProvided", s""""message":"$message",""")

  /** A top-level suite registered with an emitter, with the origin of one execution of its test. */
  private final class RegisteredRun(val eventLog: RecordingEventLog, val emitter: TestEventLogEmitter) {
    val wrapperOfSuite: Reporter = emitter.wrapperOfTopLevelSuite(new OneTestSpec, Filter.default, passingNoEventOn)
    val registration: SuiteRegistration = SuiteRegistration.madeWith(wrapperOfSuite)
    val ofTest: EventOrigin = registration.startTestExecution("t")

    /** Records the event's origin as the test's engine site does, and has the suite's wrapper hand it off. */
    def send(event: Event, origin: EventOrigin = ofTest): Unit = {
      origin.recordFor(event)
      wrapperOfSuite(event)
    }

    /** The lines written after the suite's structure. */
    def eventLines: Vector[String] = eventLog.writes.drop(1).map(_.stripSuffix("\n"))
  }

  private val passingNoEventOn: Reporter = EventLogRecording.passingNoEventOn

  private def withRegisteredSuite[A](body: RegisteredRun => A): A = {
    val eventLog = new RecordingEventLog
    val run = new RegisteredRun(eventLog, TestEventLogEmitter.appendingTo(eventLog))
    try body(run) finally run.registration.remove()
  }

  "An emitter handed a test's message as the test calls it" should {
    "write the message at once, assigned as the test's written TestStarting is" in withRegisteredSuite { run =>
      run.send(testStarting)
      run.ofTest.handOffMessage(info("first"))
      run.eventLines shouldBe Vector(testLine("TestStarting"), infoLine("first"))
    }

    "hold a message handed off before the test's TestStarting is written, and write it after that event" in withRegisteredSuite { run =>
      val starting = testStarting
      run.ofTest.recordFor(starting)
      run.ofTest.handOffMessage(info("before the start was written"))
      run.eventLines shouldBe Vector.empty
      run.wrapperOfSuite(starting)
      run.eventLines shouldBe Vector(testLine("TestStarting"), infoLine("before the start was written"))
    }

    "write a held message before the test's closing event, where that event is the first of the test's execution written" in
      withRegisteredSuite { run =>
        run.ofTest.handOffMessage(info("held"))
        run.send(testSucceeded(), run.ofTest.ofClosingEventWithMessagesHandedOff)
        run.eventLines shouldBe Vector(infoLine("held"), testLine("TestSucceeded"))
      }

    "write no message once the test's registration has been removed" in withRegisteredSuite { run =>
      run.send(testStarting)
      run.registration.remove()
      run.ofTest.handOffMessage(info("after the removal"))
      run.eventLines shouldBe Vector(testLine("TestStarting"))
    }

    "write no message once the event log has ended" in withRegisteredSuite { run =>
      run.send(testStarting)
      run.emitter.endWithoutRunEnded()
      run.ofTest.handOffMessage(info("after the end"))
      run.eventLines shouldBe Vector(testLine("TestStarting"))
    }

    "keep from the test what building the message throws, and end the event log" in withRegisteredSuite { run =>
      run.send(testStarting)
      noException should be thrownBy run.ofTest.handOffMessage(throw new IllegalStateException("cannot build"))
      run.eventLog.isClosed shouldBe true
    }
  }

  "An emitter handed a test's closing event whose messages the test handed off one by one" should {
    "write the closing event without the messages it records" in withRegisteredSuite { run =>
      run.send(testStarting)
      run.ofTest.handOffMessage(info("handed off"))
      run.send(testSucceeded(Vector(info("handed off"))), run.ofTest.ofClosingEventWithMessagesHandedOff)
      run.eventLines shouldBe Vector(testLine("TestStarting"), infoLine("handed off"), testLine("TestSucceeded"))
    }
  }

  "A run that writes no test event log" should {
    "never build a message a test hands off" in {
      var built = false
      EventOrigin.none.handOffMessage {
        built = true
        info("never built")
      }
      built shouldBe false
    }

    "give a test's closing event the test's own origin" in {
      EventOrigin.none.ofClosingEventWithMessagesHandedOff shouldBe theSameInstanceAs(EventOrigin.none)
    }
  }

  "A path-style test's call sequence" should {
    val suite = new OneTestSpec

    /** Records an event's message, ordinal and timestamp, as the emitter receives it. */
    final class ReceivingOrigin extends EventOrigin {
      var received = Vector.empty[Event]
      def recordFor(event: Event): Unit = ()
      override def handOffMessage(message: => Event): Unit = received :+= message
    }

    def callOf(message: String, kind: String): (Suite, Reporter, Tracker, String, Int, Boolean) => Event = {
      (_, _, tracker, _, _, _) =>
        kind match {
          case "info" => InfoProvided(tracker.nextOrdinal(), message, None)
          case "note" => NoteProvided(tracker.nextOrdinal(), message, None)
          case _ => MarkupProvided(tracker.nextOrdinal(), message, None)
        }
    }

    "hand off each call's message in the order of the calls, across the recorders" in {
      val calls = new PathMessageCalls
      List("info" -> "first", "note" -> "second", "markup" -> "third").foreach((kind, message) => calls.add(callOf(message, kind)))
      val origin = new ReceivingOrigin
      calls.handOffTo(origin, suite, passingNoEventOn, "t", 1, true)
      origin.received.map(_.getClass.getSimpleName) shouldBe Vector("InfoProvided", "NoteProvided", "MarkupProvided")
    }

    "stamp each message with the time of its call" in {
      val calls = new PathMessageCalls
      val before = System.currentTimeMillis
      calls.add(callOf("first", "info"))
      val after = System.currentTimeMillis
      Thread.sleep(20)
      val origin = new ReceivingOrigin
      calls.handOffTo(origin, suite, passingNoEventOn, "t", 1, true)
      origin.received.map(_.timeStamp).forall(stamp => before <= stamp && stamp <= after) shouldBe true
    }

    "build each message with a tracker of its own, so that each takes the first ordinal" in {
      val calls = new PathMessageCalls
      calls.add(callOf("first", "info"))
      calls.add(callOf("second", "info"))
      val origin = new ReceivingOrigin
      calls.handOffTo(origin, suite, passingNoEventOn, "t", 1, true)
      origin.received.map(_.ordinal) shouldBe Vector(new Ordinal(0), new Ordinal(0))
    }
  }
}
