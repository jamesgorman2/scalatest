package org.scalatest.tools

import java.io.ByteArrayOutputStream
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.NodeId
import org.scalatest.events.Ordinal
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class StructureWrapperSpec extends AnyWordSpec with Matchers {

  private val suite = new Suite {}
  private val table =
    new SuiteNodeTable(suite, NodeId(1), "samples.OneSuite", "samples.OneSuite", Map.empty, Map.empty, Map.empty, Vector.empty)
  private val registration = new RegisteredSuite(TestEventLogEmitter.appendingTo(new ByteArrayOutputStream), table, suite, None, None)
  private val node = WrapperNode.NestedSuite(registration)

  private def alert(message: String): Event = AlertProvided(new Ordinal(1), message, None)

  // Records, in one sequence, each event handed off and each event the wrapped reporter receives.
  private final class Recording {
    private var recorded = Vector.empty[(String, AnyRef, Event)]

    def inOrder: Vector[(String, AnyRef, Event)] = recorded

    val handOff: (WrapperNode, Event) => Unit = (named, event) => recorded :+= (("handed off", named, event))

    val reporter: Reporter = new Reporter {
      def apply(event: Event): Unit = recorded :+= (("passed on", "the wrapped reporter", event))
    }

    def handOffThrowing(thrown: Throwable): (WrapperNode, Event) => Unit = (_, _) => throw thrown

    def passedOn: Vector[Event] = recorded.collect { case ("passed on", _, event) => event }
  }

  private def passedOnWhenHandOffThrows(thrown: Throwable, event: Event): Vector[Event] = {
    val recording = new Recording
    new StructureWrapper(recording.reporter, node, recording.handOffThrowing(thrown))(event)
    recording.passedOn
  }

  "A structure wrapper" should {
    "hand off each event, naming its node, before it passes that event on" in {
      val recording = new Recording
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

    "give the registration of its suite where it is a suite's wrapper" in {
      val ofTopLevelSuite = new StructureWrapper(new Recording().reporter, WrapperNode.TopLevelSuite(registration), new Recording().handOff)
      val ofNestedSuite = new StructureWrapper(new Recording().reporter, WrapperNode.NestedSuite(registration), new Recording().handOff)
      ofTopLevelSuite.registrationOfItsSuite should be theSameInstanceAs registration
      ofNestedSuite.registrationOfItsSuite should be theSameInstanceAs registration
    }

    "give no registration where it is a test's wrapper" in {
      val ofTest = new StructureWrapper(new Recording().reporter, WrapperNode.Test(registration, "a test"), new Recording().handOff)
      ofTest.registrationOfItsSuite should be theSameInstanceAs SuiteRegistration.none
    }

    "give no registration where it is a suite's wrapper ahead of a sorter" in {
      val aheadOfSorter = new StructureWrapper(new Recording().reporter, WrapperNode.SuiteAheadOfSorter(registration), new Recording().handOff)
      aheadOfSorter.registrationOfItsSuite should be theSameInstanceAs SuiteRegistration.none
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
      val recording = new Recording
      val outOfMemory = new OutOfMemoryError("no heap")
      val wrapper = new StructureWrapper(recording.reporter, node, recording.handOffThrowing(outOfMemory))
      val thrown = the[OutOfMemoryError] thrownBy wrapper(alert("lost"))
      thrown should be theSameInstanceAs outOfMemory
      recording.passedOn shouldBe Vector.empty
    }
  }
}
