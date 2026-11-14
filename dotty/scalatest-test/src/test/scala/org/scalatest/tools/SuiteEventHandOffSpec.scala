package org.scalatest.tools

import org.scalatest.Args
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.ScalaTestStatefulStatus
import org.scalatest.ScopePlace
import org.scalatest.Status
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.events.DecodedEvent
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.Ordinal
import org.scalatest.events.RecordableEvent
import org.scalatest.events.ScopeOpened
import org.scalatest.events.NameInfo
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestFailed
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.EventLogRecording.passingNoEventOn
import org.scalatest.wordspec.AnyWordSpec

final class SuiteEventHandOffSpec extends AnyWordSpec with Matchers with EventLogRecording {

  private val ordinal = new Ordinal(1)
  private val timeStamp = 1700000000000L
  private val suiteName = "ScopedSuite"
  private val suiteId = "samples.ScopedSuite"

  // Its structure gives the suite node 1, the first scope s node 2, its test t node 3, the second scope s node 4,
  // its test u node 5, and test flat node 6.
  private final class ScopedSuite extends AnyFunSpec {
    describe("s") {
      it("t") {}
    }

    describe("s") {
      it("u") {}
    }

    it("flat") {}
  }

  // Its structure gives the suite node 1, scope s node 2 and its test t node 3. Its run gives its scopes and tests a
  // reporter that passes no event on.
  private final class PassesNoEventOnSpec extends AnyFunSpec {
    override def run(testName: Option[String], args: Args): Status = {
      super.run(testName, args.copy(reporter = passingNoEventOn))
    }

    describe("s") {
      it("t") {}
    }
  }

  private final class OneTestSuite extends Suite {
    override def testNames: Set[String] = Set("only")
  }

  private def suiteStarting: Event = SuiteStarting(ordinal, suiteName, suiteId, Some(suiteId), threadName = "main", timeStamp = timeStamp)

  private def suiteCompleted: Event = SuiteCompleted(ordinal, suiteName, suiteId, Some(suiteId), threadName = "main", timeStamp = timeStamp)

  private def testStarting(testName: String): Event =
    TestStarting(ordinal, suiteName, suiteId, Some(suiteId), testName, testName, threadName = "main", timeStamp = timeStamp)

  private def testSucceeded(testName: String, recorded: Vector[RecordableEvent] = Vector.empty): Event =
    TestSucceeded(ordinal, suiteName, suiteId, Some(suiteId), testName, testName, recorded, threadName = "main", timeStamp = timeStamp)

  private def scopeOpened: Event =
    ScopeOpened(ordinal, "s", NameInfo(suiteName, suiteId, Some(suiteId), None), threadName = "main", timeStamp = timeStamp)

  private def info(message: String): InfoProvided = InfoProvided(ordinal, message, None, threadName = "main", timeStamp = timeStamp)

  private val threadAndTime = """"threadName":"main","timeStamp":1700000000000}"""

  private def suiteLine(execution: Int, eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":1,"execution":$execution,"event":"$eventName",$fields$threadAndTime"""

  private def scopeOrTestLine(node: Int, execution: Int, parentExecution: Int, eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":$node,"execution":$execution,"parentExecution":$parentExecution,"event":"$eventName",$fields$threadAndTime"""

  /** A top-level suite registered with an emitter, with what its events are sent through. */
  private final class RegisteredRun(val eventLog: RecordingEventLog, val emitter: TestEventLogEmitter, val reporter: RecordingReporter, suite: Suite) {
    val wrapperOfSuite: Reporter = emitter.wrapperOfTopLevelSuite(suite, Filter.default, reporter)
    val registration: SuiteRegistration = SuiteRegistration.madeWith(wrapperOfSuite)

    def wrapperOfTest(testName: String): Reporter = registration.reporterOfTest(testName, wrapperOfSuite)

    /** The lines written after the suite's structure. */
    def eventLines: Vector[String] = eventLog.writes.drop(1).map(_.stripSuffix("\n"))
  }

  private def withRegisteredSuite[A](body: RegisteredRun => A): A = {
    val eventLog = new RecordingEventLog
    val run = new RegisteredRun(eventLog, TestEventLogEmitter.appendingTo(eventLog), new RecordingReporter, new ScopedSuite)
    try body(run) finally run.registration.remove()
  }

  "An emitter handed an event that has no origin" should {
    "assign the event to its wrapper's suite, in a first execution" in withRegisteredSuite { run =>
      run.wrapperOfSuite(suiteStarting)
      run.eventLines shouldBe Vector(suiteLine(1, "SuiteStarting"))
    }

    "assign an event that reaches a test's wrapper to that test, in a first execution of the test and of its parent" in withRegisteredSuite { run =>
      run.wrapperOfTest("s t")(testStarting("s t"))
      run.eventLines shouldBe Vector(scopeOrTestLine(3, 1, 1, "TestStarting"))
    }

    "give a node's events its open execution, and open another for the first opening event after that one closes" in withRegisteredSuite { run =>
      val ofTest = run.wrapperOfTest("flat")
      List(testStarting("flat"), info("first"), testSucceeded("flat"), info("after it closed"), testStarting("flat"), testSucceeded("flat"))
        .foreach(ofTest(_))
      run.eventLines shouldBe Vector(
        scopeOrTestLine(6, 1, 1, "TestStarting"),
        scopeOrTestLine(6, 1, 1, "InfoProvided", """"message":"first","""),
        scopeOrTestLine(6, 1, 1, "TestSucceeded"),
        scopeOrTestLine(6, 1, 1, "InfoProvided", """"message":"after it closed","""),
        scopeOrTestLine(6, 2, 1, "TestStarting"),
        scopeOrTestLine(6, 2, 1, "TestSucceeded")
      )
    }

    "give a test's event the execution of its parent that is open" in withRegisteredSuite { run =>
      List(suiteStarting, suiteCompleted, suiteStarting).foreach(run.wrapperOfSuite(_))
      run.wrapperOfTest("flat")(testStarting("flat"))
      run.eventLines.last shouldBe scopeOrTestLine(6, 1, 2, "TestStarting")
    }
  }

  "An emitter handed an event whose origin was recorded" should {
    "assign the event to the test its origin names, where the event reaches no wrapper but its suite's" in withRegisteredSuite { run =>
      val starting = testStarting("s t")
      run.registration.startTestExecution("s t").recordFor(starting)
      run.wrapperOfSuite(starting)
      run.eventLines shouldBe Vector(scopeOrTestLine(3, 1, 1, "TestStarting"))
    }

    "assign the event to the test its origin names, where the event reaches the wrapper of another test first" in withRegisteredSuite { run =>
      val starting = testStarting("s t")
      run.registration.startTestExecution("s t").recordFor(starting)
      run.wrapperOfTest("flat")(starting)
      run.eventLines shouldBe Vector(scopeOrTestLine(3, 1, 1, "TestStarting"))
    }

    "assign a scope's event to the scope at the origin's path, telling sibling scopes of one text apart by their place" in withRegisteredSuite { run =>
      val ofFirst = scopeOpened
      val ofSecond = scopeOpened
      run.registration.originAt(List(ScopePlace("s", 0))).recordFor(ofFirst)
      run.registration.originAt(List(ScopePlace("s", 1))).recordFor(ofSecond)
      run.wrapperOfSuite(ofFirst)
      run.wrapperOfSuite(ofSecond)
      run.eventLines shouldBe Vector(scopeOrTestLine(2, 1, 1, "ScopeOpened"), scopeOrTestLine(4, 1, 1, "ScopeOpened"))
    }

    "assign an event whose origin names no scope to the suite" in withRegisteredSuite { run =>
      val ofSuite = info("from the suite")
      run.registration.originAt(Nil).recordFor(ofSuite)
      run.wrapperOfTest("flat")(ofSuite)
      run.eventLines shouldBe Vector(suiteLine(1, "InfoProvided", """"message":"from the suite","""))
    }

    "number the executions of one test in the order their first events are written" in withRegisteredSuite { run =>
      val first = run.registration.startTestExecution("flat")
      val second = run.registration.startTestExecution("flat")
      val events = List(first -> testStarting("flat"), first -> testSucceeded("flat"), second -> testStarting("flat"), second -> testSucceeded("flat"))
      events.foreach { (origin, event) =>
        origin.recordFor(event)
        run.wrapperOfSuite(event)
      }
      run.eventLines shouldBe Vector(
        scopeOrTestLine(6, 1, 1, "TestStarting"),
        scopeOrTestLine(6, 1, 1, "TestSucceeded"),
        scopeOrTestLine(6, 2, 1, "TestStarting"),
        scopeOrTestLine(6, 2, 1, "TestSucceeded")
      )
    }

    "give a run the execution its suite's SuiteStarting opened, and a later run of the same registration an execution of its own" in
      withRegisteredSuite { run =>
        def sendScopeOpened(): Unit = {
          val opened = scopeOpened
          run.registration.originAt(List(ScopePlace("s", 0))).recordFor(opened)
          run.wrapperOfSuite(opened)
        }
        run.wrapperOfSuite(suiteStarting)
        run.registration.startRun()
        sendScopeOpened()
        run.registration.startRun()
        sendScopeOpened()
        run.eventLines shouldBe Vector(suiteLine(1, "SuiteStarting"), scopeOrTestLine(2, 1, 1, "ScopeOpened"), scopeOrTestLine(2, 2, 2, "ScopeOpened"))
      }

    "give a run that follows a second SuiteStarting the execution that SuiteStarting opened" in withRegisteredSuite { run =>
      def sendInfoOfSuite(): Unit = {
        val ofSuite = info("from the suite")
        run.registration.originAt(Nil).recordFor(ofSuite)
        run.wrapperOfSuite(ofSuite)
      }
      List(1, 2).foreach { _ =>
        run.wrapperOfSuite(suiteStarting)
        run.registration.startRun()
        sendInfoOfSuite()
        run.wrapperOfSuite(suiteCompleted)
      }
      run.eventLines shouldBe Vector(1, 2).flatMap { execution =>
        Vector(
          suiteLine(execution, "SuiteStarting"),
          suiteLine(execution, "InfoProvided", """"message":"from the suite","""),
          suiteLine(execution, "SuiteCompleted")
        )
      }
    }

    "keep a test's execution in the run that was the latest when its first event was sent" in withRegisteredSuite { run =>
      val ofTest = run.registration.startTestExecution("flat")
      val starting = testStarting("flat")
      val succeeded = testSucceeded("flat")
      run.registration.startRun()
      ofTest.recordFor(starting)
      run.registration.startRun()
      ofTest.recordFor(succeeded)
      run.wrapperOfSuite(starting)
      run.wrapperOfSuite(succeeded)
      run.eventLines shouldBe Vector(scopeOrTestLine(6, 1, 1, "TestStarting"), scopeOrTestLine(6, 1, 1, "TestSucceeded"))
    }

    "assign an event whose origin names a test the suite's structure lacks to the suite, in the run's execution" in withRegisteredSuite { run =>
      val starting = testStarting("no such test")
      run.registration.startTestExecution("no such test").recordFor(starting)
      run.wrapperOfSuite(starting)
      run.eventLines shouldBe Vector(suiteLine(1, "TestStarting"))
    }

    "drop the origin once the event is handed off, so that the event sent again has none" in withRegisteredSuite { run =>
      val starting = testStarting("s t")
      run.registration.startTestExecution("s t").recordFor(starting)
      run.wrapperOfSuite(starting)
      run.wrapperOfSuite(starting)
      run.eventLines shouldBe Vector(scopeOrTestLine(3, 1, 1, "TestStarting"), suiteLine(1, "TestStarting"))
    }
  }

  "An emitter handed one event by several structure wrappers" should {
    "write the event once, where it reaches its test's wrapper and then its suite's, and pass it to the suite's reporter once" in
      withRegisteredSuite { run =>
        val starting = testStarting("s t")
        run.wrapperOfTest("s t")(starting)
        run.eventLines shouldBe Vector(scopeOrTestLine(3, 1, 1, "TestStarting"))
        run.reporter.events.size shouldBe 1
        run.reporter.events.head should be theSameInstanceAs starting
      }

    "write the event once, where it reaches a nested suite's wrapper and then its outer suite's" in {
      val eventLog = new RecordingEventLog
      val inner = new OneTestSuite
      val outer = new org.scalatest.Suites(inner)
      val wrapperOfOuter = TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(outer, Filter.default, new RecordingReporter)
      val registrationOfOuter = SuiteRegistration.madeWith(wrapperOfOuter)
      val wrapperOfInner = registrationOfOuter.registerNestedSuite(0, inner, wrapperOfOuter)
      try {
        wrapperOfInner(suiteStarting)
        eventLog.writes.drop(1).map(_.stripSuffix("\n")) shouldBe
          Vector(s"""{"item":"event","node":2,"execution":1,"event":"SuiteStarting",$threadAndTime""")
      } finally {
        SuiteRegistration.madeWith(wrapperOfInner).remove()
        registrationOfOuter.remove()
      }
    }

    "write the event again where it is sent again, once its top-level suite's wrapper has received it" in withRegisteredSuite { run =>
      val starting = testStarting("s t")
      run.wrapperOfTest("s t")(starting)
      run.wrapperOfTest("s t")(starting)
      run.eventLines shouldBe Vector(scopeOrTestLine(3, 1, 1, "TestStarting"), scopeOrTestLine(3, 1, 1, "TestStarting"))
    }
  }

  "An emitter whose suite gives its scopes and tests a reporter that passes no event on" should {
    def afterTheSuiteRan[A](body: (RecordingEventLog, TestEventLogEmitter) => A): A = {
      val eventLog = new RecordingEventLog
      val emitter = TestEventLogEmitter.appendingTo(eventLog)
      new SuiteRunner(
        new PassesNoEventOnSpec,
        Args(new RecordingReporter),
        new ScalaTestStatefulStatus,
        TestEventLogHandOff.none,
        TestEventLogEmitter.reporterOfTopLevelSuiteFor(Some(emitter))
      ).run()
      body(eventLog, emitter)
    }

    "write the suite's SuiteStarting and SuiteCompleted and each event of its test, which its test's wrapper hands off, and no event of its scope" in
      afterTheSuiteRan { (eventLog, _) =>
        val stated = List(
          TestEventLogLine.suiteEvent(1, 1, "SuiteStarting"),
          TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestStarting"),
          TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestSucceeded"),
          TestEventLogLine.suiteEvent(1, 1, "SuiteCompleted")
        )
        StatedLines.mismatchWhenIs(eventLog.writes.drop(1).map(_.stripSuffix("\n")), stated) shouldBe None
      }

    "keep none of the suite's events once the suite's run has completed" in afterTheSuiteRan { (_, emitter) =>
      emitter.keptEventCount shouldBe 0
    }
  }

  "An emitter handed an event by a structure wrapper once the run's registration is removed" should {
    val structureOfScopedSuite =
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, "SuiteEventHandOffSpec$ScopedSuite", "org.scalatest.tools.SuiteEventHandOffSpec$ScopedSuite"),
        TestEventLogLine.scope(2, 1, 0, "s", "s"),
        TestEventLogLine.test(3, 2, 0, "t", "- t"),
        TestEventLogLine.scope(4, 1, 1, "s", "s"),
        TestEventLogLine.test(5, 4, 0, "u", "- u"),
        TestEventLogLine.test(6, 1, 2, "flat", "- flat")
      )

    "neither write nor keep an event sent after the removal, where it reaches a test's wrapper whose reporter passes no event on" in withRegisteredSuite { run =>
      val wrapperOfTest = run.registration.reporterOfTest("s t", passingNoEventOn)
      run.registration.remove()
      val sentLate = info("sent late")
      run.registration.startTestExecution("s t").recordFor(sentLate)
      wrapperOfTest(sentLate)
      StatedLines.mismatchWhenIs(run.eventLog.writes.map(_.stripSuffix("\n")), List(structureOfScopedSuite)) shouldBe None
      run.emitter.keptEventCount shouldBe 0
    }

    "write an event sent after the removal once, assigned to the suite, where it reaches a test's wrapper and then its suite's" in withRegisteredSuite { run =>
      run.registration.remove()
      val sentLate = info("sent late")
      run.registration.startTestExecution("s t").recordFor(sentLate)
      run.wrapperOfTest("s t")(sentLate)
      StatedLines.mismatchWhenIs(
        run.eventLog.writes.map(_.stripSuffix("\n")),
        List(structureOfScopedSuite, TestEventLogLine.suiteEvent(1, 1, "InfoProvided", """"message":"sent late","""))
      ) shouldBe None
    }

    "write an event once, assigned to its test, where its test's wrapper handed it off before the removal and it then reaches its suite's wrapper" in
      withRegisteredSuite { run =>
        val inFlight = testStarting("s t")
        run.registration.startTestExecution("s t").recordFor(inFlight)
        run.registration.reporterOfTest("s t", passingNoEventOn)(inFlight)
        run.registration.remove()
        run.wrapperOfSuite(inFlight)
        StatedLines.mismatchWhenIs(
          run.eventLog.writes.map(_.stripSuffix("\n")),
          List(structureOfScopedSuite, TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestStarting"))
        ) shouldBe None
      }
  }

  "An emitter handed a test's closing event that records messages" should {
    "write each recorded message before the closing event, assigned to the test's node and executions as the closing event is" in
      withRegisteredSuite { run =>
        val ofTest = run.registration.startTestExecution("flat")
        val succeeded = testSucceeded("flat", Vector(info("first"), info("second")))
        ofTest.recordFor(succeeded)
        run.wrapperOfSuite(succeeded)
        run.eventLines shouldBe Vector(
          scopeOrTestLine(6, 1, 1, "InfoProvided", """"message":"first","""),
          scopeOrTestLine(6, 1, 1, "InfoProvided", """"message":"second","""),
          scopeOrTestLine(6, 1, 1, "TestSucceeded")
        )
      }
  }

  "An emitter whose event log has ended" should {
    "write no event a structure wrapper hands it, and the wrapper still passes each on" in withRegisteredSuite { run =>
      run.emitter.endWithoutRunEnded()
      val starting = testStarting("s t")
      run.registration.startTestExecution("s t").recordFor(starting)
      run.wrapperOfTest("s t")(starting)
      run.eventLines shouldBe Vector.empty
      run.reporter.events.size shouldBe 1
    }
  }

  "An emitter handed a suite's event it cannot encode" should {
    "end the event log before that event, and the wrapper still passes the event on" in withRegisteredSuite { run =>
      val cannotBeEncoded = new RuntimeException {
        override def getMessage: String = throw new IllegalStateException("no message")
      }
      val failed =
        TestFailed(
          ordinal,
          "failed",
          suiteName,
          suiteId,
          Some(suiteId),
          "flat",
          "flat",
          Vector.empty,
          Vector.empty,
          Some(cannotBeEncoded),
          threadName = "main",
          timeStamp = timeStamp
        )
      run.wrapperOfTest("flat")(failed)
      run.wrapperOfTest("flat")(testStarting("flat"))
      run.eventLines shouldBe Vector.empty
      run.eventLog.isClosed shouldBe true
      run.reporter.events.size shouldBe 2
    }
  }

  "An emitter handed the events of several suites by several threads at once" should {
    "write each as one whole line, each assigned to its own suite's test, in the executions its thread sent it in" in {
      val threadCount = 4
      val executionsOfEachThread = 25
      val registrations = new java.util.concurrent.ConcurrentLinkedQueue[SuiteRegistration]
      val decoded =
        try {
          wholeLinesWrittenByThreadsAtOnce(threadCount) { (emitter, _) =>
            val wrapperOfSuite = emitter.wrapperOfTopLevelSuite(new OneTestSuite, Filter.default, new RecordingReporter)
            val registration = SuiteRegistration.madeWith(wrapperOfSuite)
            registrations.add(registration)
            val wrapperOfTest = registration.reporterOfTest("only", wrapperOfSuite)
            (1 to executionsOfEachThread).foreach { _ =>
              val ofTest = registration.startTestExecution("only")
              List(testStarting("only"), testSucceeded("only")).foreach { event =>
                ofTest.recordFor(event)
                wrapperOfTest(event)
              }
            }
          }
        } finally {
          registrations.forEach(_.remove())
        }
      val testOfSuite = decoded.collect { case TestEventLogEvent.Structure(nodes) =>
        nodes.collect { case test: org.scalatest.events.StructureNode.Test => test.id }.head
      }
      val executionsByTest =
        decoded.collect { case TestEventLogEvent.ScalaTestEvent(Some(assigned), event) =>
          assigned.node -> (assigned.execution, assigned.parentExecution, event.isInstanceOf[DecodedEvent.TestStarting])
        }.groupMap((node, _) => node)((_, execution) => execution)
      val executionsOfOneTest = (1 to executionsOfEachThread).toVector.flatMap(execution => Vector((execution, Some(1), true), (execution, Some(1), false)))
      testOfSuite.size shouldBe threadCount
      executionsByTest shouldBe testOfSuite.map(test => test -> executionsOfOneTest).toMap
    }
  }
}
