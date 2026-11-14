package org.scalatest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.Ordinal
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class MessageRecordingInformerSpec extends AnyWordSpec with Matchers {

  private val noEventReporter: Reporter = new Reporter {
    def apply(event: Event): Unit = ()
  }

  private val infoOf: MessageRecorder.RecordedMessageEventFun =
    (message, payload, _, _, _, _) => InfoProvided(new Ordinal(1), message, None, payload = payload)

  private val markupOf: MessageRecorder.RecordedMessageEventFun =
    (message, _, _, _, _, _) => MarkupProvided(new Ordinal(1), message, None)

  /** A hand-off that keeps each message handed to it. */
  private final class KeptHandOff {
    @volatile var messages = Vector.empty[String]
    val handOff: MessageRecorder.MessageHandOff = (message, _, _) => messages :+= message
  }

  "A test's informer and documenter" should {
    "hand each message off as the test calls it" in {
      val recorder = new MessageRecorder(noEventReporter)
      val kept = new KeptHandOff
      MessageRecordingInformer(recorder, infoOf, kept.handOff)("first")
      MessageRecordingDocumenter(recorder, markupOf, kept.handOff)("second")
      kept.messages shouldBe Vector("first", "second")
    }

    "hand off no message the test calls after its recorded events are taken for its closing event" in {
      val recorder = new MessageRecorder(noEventReporter)
      val kept = new KeptHandOff
      recorder.recordedEvents(false, false)
      MessageRecordingInformer(recorder, infoOf, kept.handOff)("after the close")
      MessageRecordingDocumenter(recorder, markupOf, kept.handOff)("after the close")
      kept.messages shouldBe Vector.empty
    }

    "finish handing off a message before the recorded events that hold it can be taken" in {
      val recorder = new MessageRecorder(noEventReporter)
      val handOffStarted = new CountDownLatch(1)
      val handOffMayFinish = new CountDownLatch(1)
      @volatile var handedOff = Vector.empty[String]
      val informer = MessageRecordingInformer(recorder, infoOf, (message, _, _) => {
        handOffStarted.countDown()
        handOffMayFinish.await()
        handedOff :+= message
      })
      val caller = new Thread(() => informer("called"))
      caller.start()
      handOffStarted.await(10, TimeUnit.SECONDS) shouldBe true

      @volatile var taken = Option.empty[Vector[String]]
      val closer = new Thread(() => taken = Some(recorder.recordedEvents(false, false).toVector.collect { case info: InfoProvided => info.message }))
      closer.start()
      closer.join(200)
      taken shouldBe None

      handOffMayFinish.countDown()
      closer.join(10000)
      caller.join(10000)
      taken shouldBe Some(Vector("called"))
      handedOff shouldBe Vector("called")
    }
  }
}
