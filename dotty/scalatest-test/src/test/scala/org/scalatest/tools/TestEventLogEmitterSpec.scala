package org.scalatest.tools

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.ConfigMap
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.NodeId
import org.scalatest.events.Ordinal
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStarting
import org.scalatest.events.RunStopped
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

final class TestEventLogEmitterSpec extends AnyWordSpec with Matchers {

  private val ordinal = new Ordinal(1)
  private val timeStamp = 1700000000000L
  private val identity = Right(ProcessIdentity(4242L, Instant.parse("2026-01-02T03:04:05.678Z")))

  private val runStartedLine = TestEventLogLine.runStarted(4242L, "2026-01-02T03:04:05.678Z")
  private val runStartingLine =
    """{"item":"event","event":"RunStarting","testCount":3,"configMap":{},"threadName":"main","timeStamp":1700000000000}"""

  private def runStarting: Event = RunStarting(ordinal, 3, ConfigMap.empty, threadName = "main", timeStamp = timeStamp)

  private def runCompleted: Event = RunCompleted(ordinal, threadName = "main", timeStamp = timeStamp)

  private def alert(message: String, threadName: String): Event =
    AlertProvided(ordinal, message, None, threadName = threadName, timeStamp = timeStamp)

  private def eventLine(eventName: String, fields: String): String =
    s"""{"item":"event","event":"$eventName",$fields"threadName":"main","timeStamp":1700000000000}"""

  private def linesAfterHandingOff(events: Event*): Vector[String] = {
    TemporaryEventLog.in { eventLogFile =>
      val emitter = TestEventLogEmitter.started(eventLogFile, identity)
      events.foreach(emitter.runEvent)
      emitter.runEvent(runCompleted)
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

  private val waitLimitSeconds = 30L

  private final class MessageHeldBack extends RuntimeException {
    val isBeingRead = new CountDownLatch(1)
    val mayBeRead = new CountDownLatch(1)

    override def getMessage: String = {
      isBeingRead.countDown()
      mayBeRead.await(waitLimitSeconds, TimeUnit.SECONDS)
      "held back"
    }
  }

  // Each write takes long enough that writes made without a common lock overlap.
  private final class RecordingEventLog extends OutputStream {
    private val writesUnderWay = new AtomicInteger
    private val overlapped = new AtomicBoolean
    private val closed = new AtomicBoolean
    private val recorded = new ConcurrentLinkedQueue[String]

    def writes: Vector[String] = recorded.asScala.toVector
    def hadOverlappingWrites: Boolean = overlapped.get
    def isClosed: Boolean = closed.get

    override def write(byte: Int): Unit = write(Array(byte.toByte), 0, 1)

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
      if (writesUnderWay.incrementAndGet() > 1) {
        overlapped.set(true)
      }
      try {
        Thread.sleep(1)
        recorded.add(new String(bytes, offset, length, StandardCharsets.UTF_8))
      } finally {
        writesUnderWay.decrementAndGet()
      }
    }

    override def close(): Unit = closed.set(true)
  }

  private def joined(thread: Thread): Boolean = {
    thread.join(TimeUnit.SECONDS.toMillis(waitLimitSeconds))
    !thread.isAlive
  }

  /**
   * Has each of several threads hand an emitter its events from a common start, and returns the events the event
   * log then holds. Each must have been written as one whole line, in one write that overlaps no other.
   */
  private def wholeLinesWrittenByThreadsAtOnce(threadCount: Int)(handOffOfThread: (TestEventLogEmitter, Int) => Unit): Vector[TestEventLogEvent] = {
    val eventLog = new RecordingEventLog
    val emitter = TestEventLogEmitter.appendingTo(eventLog)
    val start = new CountDownLatch(1)
    val threads = (1 to threadCount).map { thread =>
      new Thread(() => {
        start.await(waitLimitSeconds, TimeUnit.SECONDS)
        handOffOfThread(emitter, thread)
      })
    }
    threads.foreach(_.start())
    start.countDown()
    threads.map(joined) shouldBe Vector.fill(threadCount)(true)

    val writes = eventLog.writes
    eventLog.hadOverlappingWrites shouldBe false
    writes.filterNot(write => write.indexOf('\n') == write.length - 1) shouldBe Vector.empty
    val decoded = writes.map(write => TestEventLogDecoder.decode(write.stripSuffix("\n")))
    decoded.filter(_.isLeft) shouldBe Vector.empty
    decoded.collect { case Right(event) => event }
  }

  private final class RecordingReporter extends Reporter {
    private var received = Vector.empty[Event]
    def events: Vector[Event] = received
    def apply(event: Event): Unit = received :+= event
  }

  private def structureOf(
    instance: Suite,
    name: String,
    scopesAndTests: List[ScopeOrTest],
    nestedSuites: List[SuiteStructure] = Nil,
    testsBeforeNestedSuites: Boolean = false
  ): SuiteStructure =
    SuiteStructure(instance, name, s"samples.$name", s"samples.$name", scopesAndTests, nestedSuites, testsBeforeNestedSuites)

  private def test(name: String): ScopeOrTest = ScopeOrTest.Test(name, name, s"- $name")

  private final class TestNamesThrow(thrown: Throwable) extends Suite {
    override def testNames: Set[String] = throw thrown
  }

  private final class OneTestSuite extends Suite {
    override def testNames: Set[String] = Set("only")
  }

  private val oneTestSuiteLine = {
    val className = classOf[OneTestSuite].getName
    TestEventLogLine.structure(TestEventLogLine.suite(1, "TestEventLogEmitterSpec$OneTestSuite", className), TestEventLogLine.test(2, 1, 0, "only", "- only"))
  }

  "A started emitter" should {
    "have written the run's PID and start time as the event log's one line" in {
      TemporaryEventLog.in { eventLogFile =>
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine)
        } finally {
          emitter.runEvent(runCompleted)
        }
      }
    }

    "leave the event log file as the only file of its folder" in {
      TemporaryEventLog.in { eventLogFile =>
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set("events.log")
        } finally {
          emitter.runEvent(runCompleted)
        }
      }
    }

    "replace a file an earlier run left under the event log's name" in {
      TemporaryEventLog.in { eventLogFile =>
        Files.write(eventLogFile, "an earlier run's log\n".getBytes(StandardCharsets.UTF_8))
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine)
        } finally {
          emitter.runEvent(runCompleted)
        }
      }
    }
  }

  "An emitter that cannot write the file it moves to the event log's name" should {
    "fail to start, and create no event log file" in {
      TemporaryEventLog.in { eventLogFile =>
        Files.createDirectory(eventLogFile.resolveSibling("events.log.tmp"))
        an[IOException] should be thrownBy TestEventLogEmitter.started(eventLogFile, identity)
        TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set("events.log.tmp")
      }
    }
  }

  "An emitter" when {
    "its process has no identity" should {
      "fail to start, saying why, and create no file" in {
        TemporaryEventLog.in { eventLogFile =>
          the[IllegalStateException] thrownBy TestEventLogEmitter.started(eventLogFile, Left("this JVM has no java.lang.ProcessHandle")) should
            have message s"Cannot write the test event log $eventLogFile: this JVM has no java.lang.ProcessHandle"
          TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set.empty
        }
      }
    }

    "its event log's folder does not exist" should {
      "fail to start" in {
        TemporaryEventLog.in { eventLogFile =>
          val inMissingFolder: Path = eventLogFile.resolveSibling("missing").resolve("events.log")
          a[NoSuchFileException] should be thrownBy TestEventLogEmitter.started(inMissingFolder, identity)
        }
      }
    }

    "handed a run event" should {
      "append it as one line assigned to the run" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          try {
            emitter.runEvent(runStarting)
            TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
          } finally {
            emitter.runEvent(runCompleted)
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
          emitter.runEvent(runCompleted)
          emitter.runEvent(alert("late", "main"))
          emitter.runEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe
            Vector(runStartedLine, eventLine("RunCompleted", ""), TestEventLogLine.runEnded("completed"))
        }
      }
    }

    "handed an event it cannot encode" should {
      "not throw, and end the event log before that event" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.runEvent(runStarting)
          emitter.runEvent(cannotBeEncodedFor(new IllegalStateException("no text")))
          emitter.runEvent(runCompleted)
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
            emitter.runEvent(cannotBeEncodedFor(new InterruptedException("interrupted while blocked")))
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
            emitter.runEvent(cannotBeEncodedFor(new InterruptedException("interrupted while blocked")))
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
        emitter.runEvent(RunStarting(ordinal, 3, ConfigMap("key" -> new RecursesWithoutEndFromToString), threadName = "main", timeStamp = timeStamp))
        eventLog.writes shouldBe Vector.empty
        eventLog.isClosed shouldBe true
      }
    }

    "handed an event whose encoding throws a VirtualMachineError other than a StackOverflowError" should {
      "close the event log, then throw that error" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val outOfMemory = new OutOfMemoryError("no heap")
        val thrown = the[OutOfMemoryError] thrownBy emitter.runEvent(cannotBeEncodedFor(outOfMemory))
        thrown should be theSameInstanceAs outOfMemory
        eventLog.isClosed shouldBe true
      }
    }

    "its event log rejects a write" should {
      "not throw, and close the event log" in {
        val eventLog = new RejectsWrites
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.runEvent(runStarting)
        eventLog.isClosed shouldBe true
      }
    }

    "handed an event whose throwable holds back its message" should {
      "write an event another thread hands it meanwhile" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val heldBack = new MessageHeldBack
        val waiting = new Thread(() => emitter.runEvent(AlertProvided(ordinal, "waiting", None, Some(heldBack), threadName = "main", timeStamp = timeStamp)))
        val meanwhile = new Thread(() => emitter.runEvent(alert("meanwhile", "main")))
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
        emitter.runEvent(runStarting)
        emitter.endWithoutRunEnded()
        emitter.runEvent(runCompleted)
        eventLog.writes shouldBe Vector(runStartingLine + "\n")
        eventLog.isClosed shouldBe true
      }
    }

    "handed events by several threads at once" should {
      "write each as one whole line, in one write that overlaps no other, and keep each thread's events in the order it handed them off" in {
        val threadCount = 4
        val eventsOfEachThread = 25
        val decoded = wholeLinesWrittenByThreadsAtOnce(threadCount) { (emitter, thread) =>
          (1 to eventsOfEachThread).foreach(event => emitter.runEvent(alert(s"alert $event", s"thread $thread")))
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
            emitter.runEvent(alert("while interrupted", "main"))
          })
          interrupted.start()
          interrupted.join()
          emitter.runEvent(runCompleted)
          TemporaryEventLog.decoded(eventLogFile).collect { case Right(TestEventLogEvent.RunEnded(outcome)) => outcome } shouldBe
            Vector(RunOutcome.Completed)
          TemporaryEventLog.lines(eventLogFile).count(_.contains("while interrupted")) shouldBe 1
        }
      }
    }

    "handed a top-level suite's structure" should {
      val inner = new Suite {}
      val outer = new Suite {}
      val nestedThenScopesAndTests =
        structureOf(
          outer,
          "Outer",
          List(
            ScopeOrTest.Scope("s", "s", List(ScopeOrTest.Test("t", "s t", "- t"))),
            ScopeOrTest.Scope("s", "s", Nil),
            test("flat")
          ),
          List(structureOf(inner, "Inner", List(test("i"))))
        )

      "write it as one line, the suite first and each node's children after it in declared order, nested suites before scopes and tests" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter.appendingTo(eventLog).topLevelSuite(nestedThenScopesAndTests)
        eventLog.writes shouldBe Vector(
          TestEventLogLine.structure(
            TestEventLogLine.suite(1, "Outer", "samples.Outer"),
            TestEventLogLine.nestedSuite(2, 1, 0, "Inner", "samples.Inner"),
            TestEventLogLine.test(3, 2, 0, "i", "- i"),
            TestEventLogLine.scope(4, 1, 1, "s", "s"),
            TestEventLogLine.test(5, 4, 0, "t", "- t"),
            TestEventLogLine.scope(6, 1, 2, "s", "s"),
            TestEventLogLine.test(7, 1, 3, "flat", "- flat")
          ) + "\n"
        )
      }

      "write a suite's scopes and tests before its nested suites where the suite runs its tests first" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter
          .appendingTo(eventLog)
          .topLevelSuite(structureOf(outer, "Outer", List(test("flat")), List(structureOf(inner, "Inner", Nil)), testsBeforeNestedSuites = true))
        eventLog.writes shouldBe Vector(
          TestEventLogLine.structure(
            TestEventLogLine.suite(1, "Outer", "samples.Outer"),
            TestEventLogLine.test(2, 1, 0, "flat", "- flat"),
            TestEventLogLine.nestedSuite(3, 1, 1, "Inner", "samples.Inner")
          ) + "\n"
        )
      }

      "return the suite's node table, holding the suite's node, suite ID, class name and the instance it was read from" in {
        val table = TestEventLogEmitter.appendingTo(new RecordingEventLog).topLevelSuite(nestedThenScopesAndTests)
        table.instance should be theSameInstanceAs outer
        (table.node, table.suiteId, table.className) shouldBe ((NodeId(1), "samples.Outer", "samples.Outer"))
      }

      "return a node table holding each test's node by its test name" in {
        val table = TestEventLogEmitter.appendingTo(new RecordingEventLog).topLevelSuite(nestedThenScopesAndTests)
        table.tests shouldBe Map("s t" -> NodeId(5), "flat" -> NodeId(7))
      }

      "return a node table holding each scope's node by its path, telling sibling scopes of one name apart by their place" in {
        val structure =
          structureOf(outer, "Outer", List(ScopeOrTest.Scope("s", "s", List(ScopeOrTest.Scope("u", "when u", Nil))), ScopeOrTest.Scope("s", "s", Nil)))
        val table = TestEventLogEmitter.appendingTo(new RecordingEventLog).topLevelSuite(structure)
        table.scopes shouldBe Map(
          List(ScopePlace("s", 0)) -> NodeId(2),
          List(ScopePlace("s", 0), ScopePlace("u", 0)) -> NodeId(3),
          List(ScopePlace("s", 1)) -> NodeId(4)
        )
      }

      "return a node table holding each nested suite's table at its position, with the instance it was read from" in {
        val table = TestEventLogEmitter.appendingTo(new RecordingEventLog).topLevelSuite(nestedThenScopesAndTests)
        table.nestedSuites.map(nested => (nested.node, nested.suiteId, nested.className, nested.tests, nested.scopes, nested.nestedSuites)) shouldBe
          Vector((NodeId(2), "samples.Inner", "samples.Inner", Map("i" -> NodeId(3)), Map.empty, Vector.empty))
        table.nestedSuites.head.instance should be theSameInstanceAs inner
      }

      "mint for a later suite no node ID an earlier suite has" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.topLevelSuite(structureOf(outer, "Outer", List(test("flat"))))
        emitter.topLevelSuite(structureOf(inner, "Inner", List(test("i"))))
        eventLog.writes shouldBe Vector(
          TestEventLogLine.structure(TestEventLogLine.suite(1, "Outer", "samples.Outer"), TestEventLogLine.test(2, 1, 0, "flat", "- flat")) + "\n",
          TestEventLogLine.structure(TestEventLogLine.suite(3, "Inner", "samples.Inner"), TestEventLogLine.test(4, 3, 0, "i", "- i")) + "\n"
        )
      }

      "write nothing once the event log has ended, and still return the suite's node table" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        emitter.endWithoutRunEnded()
        val table = emitter.topLevelSuite(structureOf(outer, "Outer", List(test("flat"))))
        eventLog.writes shouldBe Vector.empty
        table.tests shouldBe Map("flat" -> NodeId(2))
      }

      "not throw where its event log rejects the write, and close the event log" in {
        val eventLog = new RejectsWrites
        TestEventLogEmitter.appendingTo(eventLog).topLevelSuite(structureOf(outer, "Outer", Nil))
        eventLog.isClosed shouldBe true
      }
    }

    "handed the structures of top-level suites by several threads at once" should {
      "write each as one whole line, and give every node of the run its own ID" in {
        val threadCount = 4
        val suitesOfEachThread = 25
        val decoded = wholeLinesWrittenByThreadsAtOnce(threadCount) { (emitter, thread) =>
          (1 to suitesOfEachThread).foreach { suite =>
            emitter.topLevelSuite(structureOf(new Suite {}, s"Suite${suite}OfThread$thread", List(test("a"), test("b"))))
          }
        }
        val nodeIds = decoded.collect { case TestEventLogEvent.Structure(nodes) =>
          nodes.map {
            case suite: org.scalatest.events.StructureNode.Suite => suite.id
            case scope: org.scalatest.events.StructureNode.Scope => scope.id
            case test: org.scalatest.events.StructureNode.Test => test.id
          }
        }.flatten
        nodeIds.map(_.value).sorted shouldBe (1L to threadCount * suitesOfEachThread * 3L).toVector
      }
    }

    "asked for the structure wrapper of a top-level suite" should {
      "have written the suite's structure, as the suite and the filter give it, when it returns the wrapper" in {
        val eventLog = new RecordingEventLog
        TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(new OneTestSuite, Filter.default, new RecordingReporter)
        eventLog.writes shouldBe Vector(oneTestSuiteLine + "\n")
      }

      "return a wrapper that passes each event to the suite's reporter, the same event, and writes none of them" in {
        val eventLog = new RecordingEventLog
        val reporter = new RecordingReporter
        val wrapper = TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(new OneTestSuite, Filter.default, reporter)
        val events = Vector(alert("first", "main"), alert("second", "main"))
        events.foreach(wrapper(_))
        reporter.events.size shouldBe 2
        reporter.events.zip(events).foreach((received, sent) => received should be theSameInstanceAs sent)
        eventLog.writes shouldBe Vector(oneTestSuiteLine + "\n")
      }

      "end the event log before a structure it cannot read, and return the suite's reporter itself" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val reporter = new RecordingReporter
        emitter.runEvent(runStarting)
        val returned = emitter.wrapperOfTopLevelSuite(new TestNamesThrow(new IllegalStateException("no names")), Filter.default, reporter)
        emitter.runEvent(runCompleted)
        returned should be theSameInstanceAs reporter
        eventLog.writes shouldBe Vector(runStartingLine + "\n")
        eventLog.isClosed shouldBe true
      }

      "not throw where reading the structure is interrupted, and leave its thread interrupted" in {
        val eventLog = new RecordingEventLog
        val emitter = TestEventLogEmitter.appendingTo(eventLog)
        val wasLeftInterrupted =
          try {
            emitter.wrapperOfTopLevelSuite(new TestNamesThrow(new InterruptedException("interrupted while blocked")), Filter.default, new RecordingReporter)
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
          .wrapperOfTopLevelSuite(new TestNamesThrow(new StackOverflowError), Filter.default, new RecordingReporter)
        eventLog.isClosed shouldBe true
      }

      "close the event log, then throw a VirtualMachineError other than a StackOverflowError that reading the structure throws" in {
        val eventLog = new RecordingEventLog
        val outOfMemory = new OutOfMemoryError("no heap")
        val thrown = the[OutOfMemoryError] thrownBy
          TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(new TestNamesThrow(outOfMemory), Filter.default, new RecordingReporter)
        thrown should be theSameInstanceAs outOfMemory
        eventLog.isClosed shouldBe true
      }
    }
  }

  "A run that has no emitter" should {
    "give each top-level suite the reporter it would report to" in {
      val reporter = new RecordingReporter
      TestEventLogEmitter.reporterOfTopLevelSuiteFor(None)(new OneTestSuite, Filter.default, reporter) should be theSameInstanceAs reporter
    }

    "build no object to do so" in {
      TestEventLogEmitter.reporterOfTopLevelSuiteFor(None) should be theSameInstanceAs TestEventLogHandOff.noStructureWrapper
    }
  }
}
