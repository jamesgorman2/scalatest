package org.scalatest.tools

import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.ConfigMap
import org.scalatest.Filter
import org.scalatest.InvocationContext
import org.scalatest.ScopePlace
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace
import org.scalatest.events.Ordinal
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStarting
import org.scalatest.events.RunStopped
import org.scalatest.events.SuiteId
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestName
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

final class TestEventLogEmitterSpec extends AnyWordSpec with Matchers with EventLogRecording {

  import TestEventLogEmitterSpec.*

  private val runStartingLine =
    """{"item":"event","event":"RunStarting","testCount":3,"configMap":{},"threadName":"main","timeStamp":1700000000000}"""

  private def runStarting: Event = RunStarting(ordinal, 3, ConfigMap.empty, threadName = "main", timeStamp = timeStamp)

  private def alert(message: String, threadName: String): Event =
    AlertProvided(ordinal, message, None, threadName = threadName, timeStamp = timeStamp)

  private def eventLine(eventName: String, fields: String): String =
    s"""{"item":"event","event":"$eventName",$fields"threadName":"main","timeStamp":1700000000000}"""

  private def linesAfterHandingOff(events: Event*): Vector[String] = {
    TemporaryEventLog.in { eventLogFile =>
      val emitter = TestEventLogEmitter.started(eventLogFile, identity)
      events.foreach(emitter.writeRunEvent)
      emitter.writeRunEvent(runCompleted)
      TemporaryEventLog.lines(eventLogFile)
    }
  }

  private final class ThrowsFromToString(thrown: Throwable) {
    override def toString: String = throw thrown
  }

  private def cannotBeEncodedFor(thrown: Throwable): Event =
    RunStarting(ordinal, 3, ConfigMap("key" -> new ThrowsFromToString(thrown)), threadName = "main", timeStamp = timeStamp)

  private final class RecursesWithoutEndFromToString {
    override def toString: String = "recursing " + toString
  }

  private final class RejectsWrites extends OutputStream {
    private val closed = new AtomicBoolean

    def isClosed: Boolean = closed.get

    override def write(byte: Int): Unit = throw new IOException("no space left")

    override def close(): Unit = closed.set(true)
  }

  private final class MessageHeldBack extends RuntimeException {
    val isBeingRead: CountDownLatch = new CountDownLatch(1)
    val mayBeRead: CountDownLatch = new CountDownLatch(1)

    override def getMessage: String = {
      isBeingRead.countDown()
      mayBeRead.await(waitLimitSeconds, TimeUnit.SECONDS)
      "held back"
    }
  }

  private def structureOf(
    instance: Suite,
    name: String,
    scopesAndTests: List[ScopeOrTest],
    nestedSuites: List[SuiteStructure] = Nil,
    testsBeforeNestedSuites: Boolean = false
  ): SuiteStructure =
    SuiteStructure(instance, name, SuiteId(s"samples.$name"), s"samples.$name", scopesAndTests, nestedSuites, testsBeforeNestedSuites)

  private def test(name: String): ScopeOrTest = ScopeOrTest.Test(name, TestName(name), s"- $name")

  private final class TestNamesThrow(thrown: Throwable) extends Suite {
    override def testNames: Set[String] = throw thrown
  }

  private final class OneTestSuite extends Suite {
    override def testNames: Set[String] = Set("only")
  }

  private val oneTestSuiteLine = {
    val className = classOf[OneTestSuite].getName
    val suite = NodePath.topLevel(className)
    TestEventLogLine.structure(
      TestEventLogLine.topLevelSuite("TestEventLogEmitterSpec$OneTestSuite", className),
      TestEventLogLine.test(suite.test("only"), suite, 0, "only", "- only")
    )
  }

  "A registration of an emitter's run" should {
    "fail where its node table is read without the emitter's lock" in {
      val emitter = TestEventLogEmitter.appendingTo(new java.io.ByteArrayOutputStream)
      val registration = new RegisteredSuite(emitter, new Suite {}, None)
      (the[AssertionError] thrownBy registration.table).getMessage should include ("The test event log emitter's lock is not held")
      emitter.synchronized(registration.table) shouldBe None
    }
  }

  "An emitter" when {
    "handed a run event" should {
      "append it as one line assigned to the run" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          try {
            emitter.writeRunEvent(runStarting)
            TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
          } finally {
            emitter.writeRunEvent(runCompleted)
          }
        }
      }
    }

    "handed the event that closes the run" should {
      "write that the run ended completed after a RunCompleted" in {
        linesAfterHandingOff() shouldBe Vector(runStartedLine, eventLine("RunCompleted", ""), TestEventLogLine.runEnded("completed"))
      }

      "write that the run ended stopped after a RunStopped" in {
        linesAfterHandingOff(RunStopped(ordinal, threadName = "main", timeStamp = timeStamp)) shouldBe
          Vector(runStartedLine, eventLine("RunStopped", ""), TestEventLogLine.runEnded("stopped"))
      }

      "write that the run ended aborted after a RunAborted" in {
        linesAfterHandingOff(RunAborted(ordinal, "no suites", None, threadName = "main", timeStamp = timeStamp)) shouldBe
          Vector(runStartedLine, eventLine("RunAborted", """"message":"no suites","""), TestEventLogLine.runEnded("aborted"))
      }

      "write nothing handed to it afterwards" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.writeRunEvent(runCompleted)
          emitter.writeRunEvent(alert("late", "main"))
          emitter.writeRunEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe
            Vector(runStartedLine, eventLine("RunCompleted", ""), TestEventLogLine.runEnded("completed"))
        }
      }
    }

    "handed an event it cannot encode" should {
      "not throw, and end the event log before that event" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.writeRunEvent(runStarting)
          emitter.writeRunEvent(cannotBeEncodedFor(new IllegalStateException("no text")))
          emitter.writeRunEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
        }
      }
    }

    "handed an event whose encoding is interrupted" should {
      "not throw, close the event log, and leave its thread interrupted" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val wasLeftInterrupted =
          try {
            emitter.writeRunEvent(cannotBeEncodedFor(new InterruptedException("interrupted while blocked")))
            Thread.currentThread.isInterrupted
          } finally {
            Thread.interrupted()
          }
        wasLeftInterrupted shouldBe true
        eventLog.isClosed shouldBe true
      }

      "leave its thread interrupted where the event log is already closed" in {
        val emitter = TestEventLogEmitter.appendingTo(new RecordingEventLog)
        emitter.endWithoutRunEnded()
        val wasLeftInterrupted =
          try {
            emitter.writeRunEvent(cannotBeEncodedFor(new InterruptedException("interrupted while blocked")))
            Thread.currentThread.isInterrupted
          } finally {
            Thread.interrupted()
          }
        wasLeftInterrupted shouldBe true
      }
    }

    "handed an event whose encoding overflows the stack" should {
      "not throw, and close the event log" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.writeRunEvent(RunStarting(ordinal, 3, ConfigMap("key" -> new RecursesWithoutEndFromToString), threadName = "main", timeStamp = timeStamp))
        eventLog.writes shouldBe Vector.empty
        eventLog.isClosed shouldBe true
      }
    }

    "handed an event whose encoding throws a VirtualMachineError other than a StackOverflowError" should {
      "close the event log, then throw that error" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val outOfMemory = new OutOfMemoryError("no heap")
        val thrown = the[OutOfMemoryError] thrownBy emitter.writeRunEvent(cannotBeEncodedFor(outOfMemory))
        thrown should be theSameInstanceAs outOfMemory
        eventLog.isClosed shouldBe true
      }
    }

    "its event log rejects a write" should {
      "not throw, and close the event log" in {
        val eventLog = new RejectsWrites
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.writeRunEvent(runStarting)
        eventLog.isClosed shouldBe true
      }
    }

    "handed an event whose throwable holds back its message" should {
      "write an event another thread hands it meanwhile" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val heldBack = new MessageHeldBack
        val waiting = new Thread(() => emitter.writeRunEvent(AlertProvided(ordinal, "waiting", None, Some(heldBack), threadName = "main", timeStamp = timeStamp)))
        val meanwhile = new Thread(() => emitter.writeRunEvent(alert("meanwhile", "main")))
        waiting.start()
        try {
          heldBack.isBeingRead.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true
          meanwhile.start()
          joined(meanwhile) shouldBe true
          eventLog.writes shouldBe Vector(eventLine("AlertProvided", """"message":"meanwhile",""") + "\n")
        } finally {
          heldBack.mayBeRead.countDown()
          joined(waiting)
        }
      }
    }

    "told that its run ends without an event that closes it" should {
      "close the event log, and write nothing handed to it afterwards" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.writeRunEvent(runStarting)
        emitter.endWithoutRunEnded()
        emitter.writeRunEvent(runCompleted)
        eventLog.writes shouldBe Vector(runStartingLine + "\n")
        eventLog.isClosed shouldBe true
      }
    }

    "handed events by several threads at once" should {
      "write each as one whole line, in one write that overlaps no other, and keep each thread's events in the order it handed them off" in {
        val threadCount = 4
        val eventsOfEachThread = 25
        val decoded = wholeLinesWrittenByThreadsAtOnce(threadCount) { (emitter, thread) =>
          (1 to eventsOfEachThread).foreach(event => emitter.writeRunEvent(alert(s"alert $event", s"thread $thread")))
        }
        val messagesByThread =
          decoded.collect { case TestEventLogEvent.ScalaTestEvent(None, alert: org.scalatest.events.DecodedEvent.AlertProvided) =>
            alert.threadName -> alert.message
          }.groupMap((threadName, _) => threadName)((_, message) => message)
        messagesByThread shouldBe
          (1 to threadCount).map(thread => s"thread $thread" -> (1 to eventsOfEachThread).map(event => s"alert $event").toVector).toMap
      }
    }

    "handed an event by a thread that has been interrupted" should {
      "write that event and those handed to it afterwards" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          val interrupted = new Thread(() => {
            Thread.currentThread.interrupt()
            emitter.writeRunEvent(alert("while interrupted", "main"))
          })
          interrupted.start()
          interrupted.join()
          emitter.writeRunEvent(runCompleted)
          TemporaryEventLog.decoded(eventLogFile).collect { case TestEventLogEvent.RunEnded(outcome) => outcome } shouldBe
            Vector(RunOutcome.Completed)
          TemporaryEventLog.lines(eventLogFile).count(_.contains("while interrupted")) shouldBe 1
        }
      }
    }

    "handed a top-level suite's structure" should {
      def inner: Suite = new Suite {}
      def outer: Suite = new Suite {}
      def nestedThenScopesAndTests: SuiteStructure =
        structureOf(
          outer,
          "Outer",
          List(
            ScopeOrTest.Scope("s", "s", "s", List(ScopeOrTest.Test("t", TestName("s t"), "- t"))),
            ScopeOrTest.Scope("s", "s", "s", Nil),
            test("flat")
          ),
          List(structureOf(inner, "Inner", List(test("i"))))
        )
      val outerPath = NodePath.topLevel("samples.Outer")
      val outerPlace = NodePlace.Suite(SuiteId("samples.Outer"), 0)
      def nodeId(places: NodePlace*): NodeId = NodeId(places.toVector)

      "write it as one line, the suite first and each node's children after it in declared order, nested suites before scopes and tests" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter.appendingTo(eventLog).addTopLevelSuite(nestedThenScopesAndTests)
        val innerPath = outerPath.suite("samples.Inner")
        val firstS = outerPath.scope("s")
        eventLog.writes shouldBe Vector(
          TestEventLogLine.structure(
            TestEventLogLine.topLevelSuite("Outer", "samples.Outer"),
            TestEventLogLine.nestedSuite(innerPath, 0, "Inner", "samples.Inner"),
            TestEventLogLine.test(innerPath.test("i"), innerPath, 0, "i", "- i"),
            TestEventLogLine.scope(firstS, 1, "s", "s"),
            TestEventLogLine.test(outerPath.test("s t"), firstS, 0, "t", "- t"),
            TestEventLogLine.scope(outerPath.scope("s", 1), 2, "s", "s"),
            TestEventLogLine.test(outerPath.test("flat"), outerPath, 3, "flat", "- flat")
          ) + "\n"
        )
      }

      "write a suite's scopes and tests before its nested suites where the suite runs its tests first" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter
          .appendingTo(eventLog)
          .addTopLevelSuite(structureOf(outer, "Outer", List(test("flat")), List(structureOf(inner, "Inner", Nil)), testsBeforeNestedSuites = true))
        eventLog.writes shouldBe Vector(
          TestEventLogLine.structure(
            TestEventLogLine.topLevelSuite("Outer", "samples.Outer"),
            TestEventLogLine.test(outerPath.test("flat"), outerPath, 0, "flat", "- flat"),
            TestEventLogLine.nestedSuite(outerPath.suite("samples.Inner"), 1, "Inner", "samples.Inner")
          ) + "\n"
        )
      }

      "return the suite's node table, holding the suite's node, suite ID and class name" in {
        val table = TestEventLogEmitter.appendingTo(new RecordingEventLog).addTopLevelSuite(nestedThenScopesAndTests)
        (table.node, table.suiteId, table.className) shouldBe ((nodeId(outerPlace), SuiteId("samples.Outer"), "samples.Outer"))
      }

      "return a node table holding each test's node by its test name" in {
        val emitter = TestEventLogEmitter.appendingTo(new RecordingEventLog)
        val table = emitter.addTopLevelSuite(nestedThenScopesAndTests)
        emitter.synchronized(table.testsByName) shouldBe Map(TestName("s t") -> nodeId(outerPlace, NodePlace.Test(TestName("s t"))), TestName("flat") -> nodeId(outerPlace, NodePlace.Test(TestName("flat"))))
      }

      "return a node table holding each scope's node by its path of registered texts, telling sibling scopes of one text apart by their place" in {
        val structure =
          structureOf(
            outer,
            "Outer",
            List(
              ScopeOrTest.Scope("s", "s", "s", List(ScopeOrTest.Scope("u", "u which", "when u which", Nil))),
              ScopeOrTest.Scope("s", "s", "s", Nil)
            )
          )
        val emitter = TestEventLogEmitter.appendingTo(new RecordingEventLog)
        val table = emitter.addTopLevelSuite(structure)
        emitter.synchronized(table.scopesByPath) shouldBe Map(
          List(ScopePlace("s", 0)) -> nodeId(outerPlace, NodePlace.Scope("s", 0)),
          List(ScopePlace("s", 0), ScopePlace("u which", 0)) -> nodeId(outerPlace, NodePlace.Scope("s", 0), NodePlace.Scope("u which", 0)),
          List(ScopePlace("s", 1)) -> nodeId(outerPlace, NodePlace.Scope("s", 1))
        )
      }

      "return a node table holding the parent of each scope and test" in {
        val emitter = TestEventLogEmitter.appendingTo(new RecordingEventLog)
        val table = emitter.addTopLevelSuite(nestedThenScopesAndTests)
        val outerId = nodeId(outerPlace)
        val firstS = nodeId(outerPlace, NodePlace.Scope("s", 0))
        emitter.synchronized(table.parents) shouldBe Map(
          firstS -> outerId,
          nodeId(outerPlace, NodePlace.Test(TestName("s t"))) -> firstS,
          nodeId(outerPlace, NodePlace.Scope("s", 1)) -> outerId,
          nodeId(outerPlace, NodePlace.Test(TestName("flat"))) -> outerId
        )
      }

      "return a node table holding each nested suite's table at its position" in {
        val emitter = TestEventLogEmitter.appendingTo(new RecordingEventLog)
        val table = emitter.addTopLevelSuite(nestedThenScopesAndTests)
        emitter.synchronized(table.nestedSuites.map(nested => (nested.node, nested.suiteId, nested.className, nested.testsByName, nested.scopesByPath, nested.nestedSuites))) shouldBe
          Vector(
            (
              nodeId(outerPlace, NodePlace.Suite(SuiteId("samples.Inner"), 0)),
              SuiteId("samples.Inner"),
              "samples.Inner",
              Map(TestName("i") -> nodeId(outerPlace, NodePlace.Suite(SuiteId("samples.Inner"), 0), NodePlace.Test(TestName("i")))),
              Map.empty,
              Vector.empty
            )
          )
      }

      "give a later top-level suite of an earlier one's suite ID the next index, so that no two nodes share a path" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.addTopLevelSuite(structureOf(outer, "Outer", List(test("flat"))))
        emitter.addTopLevelSuite(structureOf(inner, "Outer", List(test("flat"))))
        val laterPath = NodePath.topLevel("samples.Outer", 1)
        eventLog.writes shouldBe Vector(
          TestEventLogLine.structure(
            TestEventLogLine.topLevelSuite("Outer", "samples.Outer"),
            TestEventLogLine.test(outerPath.test("flat"), outerPath, 0, "flat", "- flat")
          ) + "\n",
          TestEventLogLine.structure(
            TestEventLogLine.topLevelSuiteWithId(laterPath, "Outer", "samples.Outer", "samples.Outer"),
            TestEventLogLine.test(laterPath.test("flat"), laterPath, 0, "flat", "- flat")
          ) + "\n"
        )
      }

      "write nothing once the event log has ended, and still return the suite's node table" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.endWithoutRunEnded()
        val table = emitter.addTopLevelSuite(structureOf(outer, "Outer", List(test("flat"))))
        eventLog.writes shouldBe Vector.empty
        emitter.synchronized(table.testsByName) shouldBe Map(TestName("flat") -> nodeId(outerPlace, NodePlace.Test(TestName("flat"))))
      }

      "not throw where its event log rejects the write, and close the event log" in {
        val eventLog = new RejectsWrites
        TestEventLogEmitter.appendingTo(eventLog).addTopLevelSuite(structureOf(outer, "Outer", Nil))
        eventLog.isClosed shouldBe true
      }
    }

    "handed the structures of top-level suites by several threads at once" should {
      "write each as one whole line, and give every node of the run its own node path" in {
        val threadCount = 4
        val suitesOfEachThread = 25
        val decoded = wholeLinesWrittenByThreadsAtOnce(threadCount) { (emitter, thread) =>
          (1 to suitesOfEachThread).foreach { suite =>
            emitter.addTopLevelSuite(structureOf(new Suite {}, s"Suite${suite}OfThread$thread", List(test("a"), test("b"))))
          }
        }
        val nodeIds = decoded.collect { case TestEventLogEvent.Structure(nodes) =>
          nodes.map {
            case suite: org.scalatest.events.StructureNode.Suite => suite.id
            case scope: org.scalatest.events.StructureNode.Scope => scope.id
            case test: org.scalatest.events.StructureNode.Test => test.id
          }
        }.flatten
        nodeIds should have size (threadCount * suitesOfEachThread * 3L)
        nodeIds.distinct shouldBe nodeIds
      }
    }

    "asked how a top-level suite starts" should {
      "have written the suite's structure, as the suite and the filter give it, when it returns" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter.appendingTo(eventLog).startOfTopLevelSuite(new OneTestSuite, Filter.default, new RecordingReporter)
        eventLog.writes shouldBe Vector(oneTestSuiteLine + "\n")
      }

      "start it with a wrapper that passes each event unchanged to the suite's reporter" in {
        val reporter = new RecordingReporter
        val suite = new OneTestSuite
        val wrapper = TestEventLogEmitter.appendingTo(new RecordingEventLog).startOfTopLevelSuite(suite, Filter.default, reporter).reporter
        val events = Vector(alert("first", "main"), alert("second", "main"))
        events.foreach(wrapper(_))
        reporter.events.size shouldBe 2
        reporter.events.zip(events).foreach((received, sent) => received should be theSameInstanceAs sent)
      }

      "start it in the context of its registration, and restore the context around it once its run returns" in {
        val anEmitter = TestEventLogEmitter.appendingTo(new RecordingEventLog)
        val suite = new OneTestSuite
        val start = anEmitter.startOfTopLevelSuite(suite, Filter.default, new RecordingReporter)
        val around = new StartedTopLevelSuite(anEmitter, new OneTestSuite)
        var inItsRun = SuiteRegistration.none
        val (contextAround, contextRestored) =
          try {
            around.runningInItsContext {
              val contextGiven = InvocationContext.get
              start.run {
                inItsRun = SuiteRegistration.inContext(suite)
                SucceededStatus
              }
              (contextGiven, InvocationContext.get)
            }
          } finally {
            around.complete()
          }
        (inItsRun ne SuiteRegistration.none, contextAround.isDefined, contextRestored) shouldBe ((true, true, contextAround))
      }

      "end the event log before a structure it cannot read, and start the suite with its reporter itself, in no context" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val reporter = new RecordingReporter
        emitter.writeRunEvent(runStarting)
        val suite = new TestNamesThrow(new IllegalStateException("no names"))
        val start = emitter.startOfTopLevelSuite(suite, Filter.default, reporter)
        emitter.writeRunEvent(runCompleted)
        var inItsRun = Option.empty[SuiteRegistration]
        start.run {
          inItsRun = Some(SuiteRegistration.inContext(suite))
          SucceededStatus
        }
        start.reporter should be theSameInstanceAs reporter
        inItsRun shouldBe Some(SuiteRegistration.none)
        eventLog.writes shouldBe Vector(runStartingLine + "\n")
        eventLog.isClosed shouldBe true
      }

      "not throw where reading the structure is interrupted, and leave its thread interrupted" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val wasLeftInterrupted =
          try {
            emitter.startOfTopLevelSuite(new TestNamesThrow(new InterruptedException("interrupted while blocked")), Filter.default, new RecordingReporter)
            Thread.currentThread.isInterrupted
          } finally {
            Thread.interrupted()
          }
        wasLeftInterrupted shouldBe true
        eventLog.isClosed shouldBe true
      }

      "not throw where reading the structure overflows the stack" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter
          .appendingTo(eventLog)
          .startOfTopLevelSuite(new TestNamesThrow(new StackOverflowError), Filter.default, new RecordingReporter)
        eventLog.isClosed shouldBe true
      }

      "close the event log, then throw a VirtualMachineError other than a StackOverflowError that reading the structure throws" in {
        val eventLog = new RecordingEventLog
        val outOfMemory = new OutOfMemoryError("no heap")
        val thrown = the[OutOfMemoryError] thrownBy
          TestEventLogEmitter.appendingTo(eventLog).startOfTopLevelSuite(new TestNamesThrow(outOfMemory), Filter.default, new RecordingReporter)
        thrown should be theSameInstanceAs outOfMemory
        eventLog.isClosed shouldBe true
      }
    }
  }

  "A run that has no emitter" should {
    "start each top-level suite with the reporter it would report to" in {
      val reporter = new RecordingReporter
      TestEventLogEmitter.startOfTopLevelSuiteFor(None)(new OneTestSuite, Filter.default, reporter).reporter should be theSameInstanceAs reporter
    }

    "build no function to do so" in {
      TestEventLogEmitter.startOfTopLevelSuiteFor(None) should be theSameInstanceAs TestEventLogHandOff.startWithoutEventLog
    }
  }
}

private[tools] object TestEventLogEmitterSpec {

  val ordinal: Ordinal = new Ordinal(1)
  val timeStamp: Long = 1700000000000L
  val identity: ProcessIdentity = ProcessIdentity(4242L, Instant.parse("2026-01-02T03:04:05.678Z"))

  val runStartedLine: String = TestEventLogLine.runStarted(4242L, "2026-01-02T03:04:05.678Z")

  def runCompleted: Event = RunCompleted(ordinal, threadName = "main", timeStamp = timeStamp)
}
