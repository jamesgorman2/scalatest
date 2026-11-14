package org.scalatest.tools

import org.scalatest.EventOrigin
import org.scalatest.Filter
import org.scalatest.PathMessageCalls
import org.scalatest.PathMessageRecordingAlerter
import org.scalatest.PathMessageRecordingDocumenter
import org.scalatest.PathMessageRecordingInformer
import org.scalatest.PathMessageRecordingNotifier
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.Tracker
import org.scalatest.events.AlertProvided
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

  // Its structure gives the suite node 1 and its test t node 2. Its names are those the events this spec builds carry.
  private final class OneTestSpec extends AnyFunSpec {
    override def suiteName: String = TestMessageHandOffSpec.this.suiteName

    override def suiteId: String = TestMessageHandOffSpec.this.suiteId

    it("t") {}
  }

  private def testStarting: Event =
    TestStarting(ordinal, suiteName, suiteId, Some(suiteId), "t", "t", threadName = "main", timeStamp = timeStamp)

  private def testSucceeded(recorded: Vector[RecordableEvent] = Vector.empty): Event =
    TestSucceeded(ordinal, suiteName, suiteId, Some(suiteId), "t", "t", recorded, threadName = "main", timeStamp = timeStamp)

  private def info(message: String): InfoProvided = InfoProvided(ordinal, message, None, threadName = "main", timeStamp = timeStamp)

  private def note(message: String): NoteProvided = NoteProvided(ordinal, message, None, threadName = "main", timeStamp = timeStamp)

  private def alert(message: String): AlertProvided = AlertProvided(ordinal, message, None, threadName = "main", timeStamp = timeStamp)

  private def markup(message: String): MarkupProvided = MarkupProvided(ordinal, message, None, threadName = "main", timeStamp = timeStamp)

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

    /** The event and message of each line written after the suite's structure. */
    def eventsAndMessages: Vector[String] = eventLines.map { line =>
      val eventName = """"event":"(\w+)"""".r.findFirstMatchIn(line).map(_.group(1)).getOrElse("")
      """"(?:message|text)":"([^"]*)"""".r.findFirstMatchIn(line).fold(eventName)(found => s"$eventName ${found.group(1)}")
    }
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

    "write the messages in the order of the test's calls, where a reporter ahead of every wrapper holds the test's events back" in
      withRegisteredSuite { run =>
        val (starting, noted, alerted) = (testStarting, note("second"), alert("third"))
        run.ofTest.recordFor(starting)
        run.ofTest.handOffMessage(info("first"))
        run.ofTest.recordFor(noted)
        run.ofTest.recordFor(alerted)
        run.ofTest.handOffMessage(markup("fourth"))
        run.eventLines shouldBe Vector.empty
        run.wrapperOfSuite(starting)
        run.eventsAndMessages shouldBe Vector("TestStarting", "InfoProvided first")
        run.wrapperOfSuite(noted)
        run.wrapperOfSuite(alerted)
        run.eventsAndMessages shouldBe
          Vector("TestStarting", "InfoProvided first", "NoteProvided second", "AlertProvided third", "MarkupProvided fourth")
      }

    "write a message held behind an event that never reaches a wrapper before the test's closing event" in withRegisteredSuite { run =>
      run.send(testStarting)
      run.ofTest.recordFor(note("never passed on"))
      run.ofTest.handOffMessage(info("held"))
      run.eventsAndMessages shouldBe Vector("TestStarting")
      run.send(testSucceeded(Vector(info("held"))), run.ofTest.ofClosingEventWithMessagesHandedOff)
      run.eventsAndMessages shouldBe Vector("TestStarting", "InfoProvided held", "TestSucceeded")
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

    "build and encode the message when its hand-off is prepared, and write it only when the prepared hand-off runs" in
      withRegisteredSuite { run =>
        run.send(testStarting)
        var built = false
        val preparedHandOff = run.ofTest.preparedHandOffOfMessage {
          built = true
          info("prepared")
        }
        built shouldBe true
        run.eventLines shouldBe Vector(testLine("TestStarting"))
        preparedHandOff()
        run.eventLines shouldBe Vector(testLine("TestStarting"), infoLine("prepared"))
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

    "write the closing event without the messages it records, where it is sent after the test's registration is removed" in
      withRegisteredSuite { run =>
        run.send(testStarting)
        run.ofTest.handOffMessage(info("handed off"))
        run.registration.remove()
        run.send(testSucceeded(Vector(info("handed off"))), run.ofTest.ofClosingEventWithMessagesHandedOff)
        run.eventsAndMessages shouldBe Vector("TestStarting", "InfoProvided handed off", "TestSucceeded")
      }

    "write the messages held for it before it, where its origin was dropped when the test's registration was removed" in
      withRegisteredSuite { run =>
        val starting = testStarting
        val closing = testSucceeded(Vector(info("held")))
        run.ofTest.recordFor(starting)
        run.ofTest.handOffMessage(info("held"))
        run.ofTest.ofClosingEventWithMessagesHandedOff.recordFor(closing)
        run.registration.remove()
        run.wrapperOfSuite(closing)
        run.eventsAndMessages shouldBe Vector("InfoProvided held", "TestSucceeded")
      }
  }

  "A run that writes no test event log" should {
    "never build a message a test hands off" in {
      var built = false
      EventOrigin.none.preparedHandOffOfMessage {
        built = true
        info("never built")
      }()
      built shouldBe false
    }

    "give a test's closing event the test's own origin" in {
      EventOrigin.none.ofClosingEventWithMessagesHandedOff shouldBe theSameInstanceAs(EventOrigin.none)
    }
  }

  "A path-style test's call sequence" should {
    val suite = new OneTestSpec

    def messageOf(event: Event): String = {
      event match {
        case info: InfoProvided => info.message
        case note: NoteProvided => note.message
        case alert: AlertProvided => alert.message
        case markup: MarkupProvided => markup.text
        case other => other.toString
      }
    }

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

    "hand off each call's message in the order of the calls, across the four recorders" in {
      val calls = new PathMessageCalls
      val informer = PathMessageRecordingInformer(
        (message, _, _, _, _, _, tracker, _, _, _, _) => InfoProvided(tracker.nextOrdinal(), message, None),
        calls
      )
      val notifier = PathMessageRecordingNotifier(
        (message, _, _, _, _, _, tracker, _, _, _, _) => NoteProvided(tracker.nextOrdinal(), message, None),
        calls
      )
      val alerter = PathMessageRecordingAlerter(
        (message, _, _, _, _, _, tracker, _, _, _, _) => AlertProvided(tracker.nextOrdinal(), message, None),
        calls
      )
      val documenter = PathMessageRecordingDocumenter(
        (message, _, _, _, _, tracker, _, _, _, _) => MarkupProvided(tracker.nextOrdinal(), message, None),
        calls
      )
      informer("first")
      notifier("second")
      alerter("third")
      documenter("fourth")
      informer("fifth")
      val origin = new ReceivingOrigin
      calls.handOffTo(origin, suite, passingNoEventOn, "t", 1, true)
      origin.received.map(event => (event.getClass.getSimpleName, messageOf(event))) shouldBe Vector(
        ("InfoProvided", "first"),
        ("NoteProvided", "second"),
        ("AlertProvided", "third"),
        ("MarkupProvided", "fourth"),
        ("InfoProvided", "fifth")
      )
    }

    "stamp each message with the time of its call" in {
      val calls = new PathMessageCalls
      val before = System.currentTimeMillis
      calls.add(callOf("first", "info"))
      val after = System.currentTimeMillis
      Thread.sleep(20)
      val origin = new ReceivingOrigin
      calls.handOffTo(origin, suite, passingNoEventOn, "t", 1, true)
      val stamps = origin.received.map(_.timeStamp)
      stamps should have size 1
      all(stamps) should (be >= before and be <= after)
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
