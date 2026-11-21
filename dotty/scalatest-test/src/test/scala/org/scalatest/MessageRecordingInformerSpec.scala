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

  private val waitLimitSeconds = 10L

  private def joined(thread: Thread): Boolean = {
    thread.join(TimeUnit.SECONDS.toMillis(waitLimitSeconds))
    !thread.isAlive
  }

  private val noEventReporter: Reporter = new Reporter {
    def apply(event: Event): Unit = ()
  }

  private val infoOf: MessageRecorder.RecordedMessageEventFun =
    (message, payload, _, _, _, _) => InfoProvided(new Ordinal(1), message, None, payload = payload)

  private val markupOf: MessageRecorder.RecordedMessageEventFun =
    (message, _, _, _, _, _) => MarkupProvided(new Ordinal(1), message, None)

  private final class MessageKeepingHandOff {
    @volatile var messages: Vector[String] = Vector.empty
    val handOff: MessageRecorder.MessageHandOff = (message, _, _) => () => messages :+= message
  }

  "A test's informer and documenter" should {
    "hand each message off as the test calls it" in {
      val recorder = new MessageRecorder(noEventReporter)
      val kept = new MessageKeepingHandOff
      MessageRecordingInformer(recorder, infoOf, kept.handOff)("first")
      MessageRecordingDocumenter(recorder, markupOf, kept.handOff)("second")
      kept.messages shouldBe Vector("first", "second")
    }

    "hand off no message the test calls after its recorded events are taken for its closing event" in {
      val recorder = new MessageRecorder(noEventReporter)
      val kept = new MessageKeepingHandOff
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
      val informer = MessageRecordingInformer(recorder, infoOf, (message, _, _) => () => {
        handOffStarted.countDown()
        handOffMayFinish.await()
        handedOff :+= message
      })
      val caller = new Thread(() => informer("called"))
      caller.start()
      handOffStarted.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true

      @volatile var taken = Option.empty[Vector[String]]
      val closer = new Thread(() => taken = Some(recorder.recordedEvents(false, false).toVector.collect { case info: InfoProvided => info.message }))
      closer.start()
      closer.join(200)
      (closer.isAlive, taken) shouldBe ((true, None))

      handOffMayFinish.countDown()
      (joined(closer), joined(caller)) shouldBe ((true, true))
      taken shouldBe Some(Vector("called"))
      handedOff shouldBe Vector("called")
    }

    "prepare a message's hand-off without holding the lock that records messages and takes the recorded events" in {
      val recorder = new MessageRecorder(noEventReporter)
      val preparingStarted = new CountDownLatch(1)
      val preparingMayFinish = new CountDownLatch(1)
      @volatile var handedOff = Vector.empty[String]
      val handOff: MessageRecorder.MessageHandOff = (message, _, _) => {
        if (message == "slow to prepare") {
          preparingStarted.countDown()
          preparingMayFinish.await()
        }
        () => handedOff :+= message
      }
      val slowCaller = new Thread(() => MessageRecordingInformer(recorder, infoOf, handOff)("slow to prepare"))
      slowCaller.start()
      preparingStarted.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true

      @volatile var taken = Option.empty[Vector[String]]
      val otherThread = new Thread(() => {
        MessageRecordingDocumenter(recorder, markupOf, handOff)("from another thread")
        taken = Some(recorder.recordedEvents(false, false).toVector.collect { case markup: MarkupProvided => markup.text })
      })
      otherThread.start()
      joined(otherThread) shouldBe true
      taken shouldBe Some(Vector("from another thread"))

      preparingMayFinish.countDown()
      joined(slowCaller) shouldBe true
      handedOff shouldBe Vector("from another thread")
    }
  }
}
