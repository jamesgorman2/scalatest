package org.scalatest.tools

import java.io.ByteArrayOutputStream
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.Ordinal
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class StructureWrapperSpec extends AnyWordSpec with Matchers {

  private val registration = new RegisteredSuite(TestEventLogEmitter.appendingTo(new ByteArrayOutputStream), new Suite {}, None)
  private val node = WrapperNode.NestedSuite(registration)

  private def alert(message: String): Event = AlertProvided(new Ordinal(1), message, None)

  private final class HandOffAndPassOnRecording {
    private var recorded = Vector.empty[(String, AnyRef, Event)]

    private def record(step: String, named: AnyRef, event: Event): Unit = synchronized { recorded :+= ((step, named, event)) }

    def inOrder: Vector[(String, AnyRef, Event)] = synchronized(recorded)

    val handOff: (WrapperNode, Event) => Unit = (named, event) => record("handed off", named, event)

    val reporter: Reporter = new Reporter {
      def apply(event: Event): Unit = record("passed on", "the wrapped reporter", event)
    }

    def handOffThrowing(thrown: Throwable): (WrapperNode, Event) => Unit = (_, _) => throw thrown

    def passedOn: Vector[Event] = inOrder.collect { case ("passed on", _, event) => event }
  }

  private def passedOnWhenHandOffThrows(thrown: Throwable, event: Event): Vector[Event] = {
    val recording = new HandOffAndPassOnRecording
    new StructureWrapper(recording.reporter, node, recording.handOffThrowing(thrown))(event)
    recording.passedOn
  }

  "A structure wrapper" should {
    "hand off each event, naming its node, before it passes that event on" in {
      val recording = new HandOffAndPassOnRecording
      val wrapper = new StructureWrapper(recording.reporter, node, recording.handOff)
      val first = alert("first")
      val second = alert("second")
      wrapper(first)
      wrapper(second)
      val steps = recording.inOrder
      steps.map((step, _, _) => step) shouldBe Vector("handed off", "passed on", "handed off", "passed on")
      steps.map((_, _, event) => event).zip(Vector(first, first, second, second)).foreach { (recorded, sent) =>
        recorded should be theSameInstanceAs sent
      }
      steps.collect { case ("handed off", named, _) => named }.foreach(_ should be theSameInstanceAs node)
    }

    "pass an event on where its hand-off throws an exception" in {
      val event = alert("kept")
      val passedOn = passedOnWhenHandOffThrows(new IllegalStateException("no node"), event)
      passedOn.size shouldBe 1
      passedOn.head should be theSameInstanceAs event
    }

    "pass an event on where its hand-off overflows the stack" in {
      passedOnWhenHandOffThrows(new StackOverflowError, alert("kept")).size shouldBe 1
    }

    "pass an event on where its hand-off is interrupted, and leave its thread interrupted" in {
      val (passedOn, wasLeftInterrupted) =
        try {
          val passedOn = passedOnWhenHandOffThrows(new InterruptedException("interrupted while blocked"), alert("kept"))
          (passedOn, Thread.currentThread.isInterrupted)
        } finally {
          Thread.interrupted()
        }
      passedOn.size shouldBe 1
      wasLeftInterrupted shouldBe true
    }

    "throw a VirtualMachineError other than a StackOverflowError that its hand-off throws, and not pass the event on" in {
      val recording = new HandOffAndPassOnRecording
      val outOfMemory = new OutOfMemoryError("no heap")
      val wrapper = new StructureWrapper(recording.reporter, node, recording.handOffThrowing(outOfMemory))
      val thrown = the[OutOfMemoryError] thrownBy wrapper(alert("lost"))
      thrown should be theSameInstanceAs outOfMemory
      recording.passedOn shouldBe Vector.empty
    }
  }
}
