package org.scalatest.tools

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.Args
import org.scalatest.DistributedTestSorter
import org.scalatest.Distributor
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.ScalaTestStatefulStatus
import org.scalatest.Status
import org.scalatest.StepwiseNestedSuiteExecution
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.Suites
import org.scalatest.events.Event
import org.scalatest.events.NodeId
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.SuiteCompleted
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.samples.MarkupDocSpec
import org.scalatest.samples.OneInstancePerTestProbe
import org.scalatest.samples.OneInstancePerTestThrowingProbe
import org.scalatest.samples.ParallelTestExecutionProbe
import org.scalatest.samples.ParallelTestExecutionThrowingProbe
import org.scalatest.samples.PerTestInstances
import org.scalatest.samples.RandomTestOrderProbe
import org.scalatest.samples.RandomTestOrderThrowingProbe
import org.scalatest.samples.RandomTestOrderTwoTestsThrowingProbe
import org.scalatest.samples.ScopedParallelFunSpec
import org.scalatest.samples.ScopedQueueingAsyncFunSpec
import org.scalatest.samples.ScopedRandomOrderFunSpec
import org.scalatest.samples.SendsFromRunTestsAsyncSuite
import org.scalatest.samples.SendsFromRunTestsParallelSuite
import org.scalatest.samples.SendsFromRunTestsRandomOrderSuite
import org.scalatest.samples.StackPerTestFunSpec
import org.scalatest.samples.RegistrationProbeSuite
import org.scalatest.tools.EventLogRecording.passingNoEventOn
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.*

final class SuiteRegistrationSitesSpec extends AnyWordSpec with Matchers with TestEventLogRuns with EventLogRecording {

  // Records, as its run starts and as its test runs, its registration and whether it is given a structure wrapper.
  private class Probe extends AnyFunSuite {
    @volatile var registrationInRun = Option.empty[SuiteRegistration]
    @volatile var registrationInTest = Option.empty[SuiteRegistration]
    @volatile var runIsGivenAStructureWrapper = false
    @volatile var testIsGivenAStructureWrapper = false

    override def run(testName: Option[String], args: Args): Status = {
      registrationInRun = Some(SuiteRegistration.of(this))
      runIsGivenAStructureWrapper = args.reporter.isInstanceOf[StructureWrapper]
      super.run(testName, args)
    }

    override protected def runTest(testName: String, args: Args): Status = {
      testIsGivenAStructureWrapper = args.reporter.isInstanceOf[StructureWrapper]
      super.runTest(testName, args)
    }

    test("probes") {
      registrationInTest = Some(SuiteRegistration.of(this))
    }
  }

  private final class ProbeWhoseRunThrows(thrown: Throwable) extends Probe {
    override def run(testName: Option[String], args: Args): Status = {
      super.run(testName, args)
      throw thrown
    }
  }

  // Its one test completes only once the promise does, on another thread than the one that runs the suite.
  private final class AsyncProbe(mayComplete: Promise[Unit]) extends AsyncFunSpec {
    @volatile var testIsGivenAStructureWrapper = false
    @volatile var statusOfRun = Option.empty[Status]

    implicit override def executionContext: ExecutionContext = ExecutionContext.global

    override def run(testName: Option[String], args: Args): Status = {
      val status = super.run(testName, args)
      statusOfRun = Some(status)
      status
    }

    override protected def runTest(testName: String, args: Args): Status = {
      testIsGivenAStructureWrapper = args.reporter.isInstanceOf[StructureWrapper]
      super.runTest(testName, args)
    }

    it("completes later") {
      mayComplete.future.map(_ => succeed)
    }
  }

  private def emitter: TestEventLogEmitter = TestEventLogEmitter.appendingTo(new ByteArrayOutputStream)

  // Runs a top-level suite as Runner does, with the emitter's function for its reporter where there is an emitter.
  private def runAsTopLevelSuite(
    suite: Suite,
    emitter: Option[TestEventLogEmitter],
    reporter: Reporter = passingNoEventOn,
    distributor: Option[Distributor] = None
  ): ScalaTestStatefulStatus = {
    val status = new ScalaTestStatefulStatus
    val args = Args(reporter, distributor = distributor)
    new SuiteRunner(suite, args, status, TestEventLogHandOff.none, TestEventLogEmitter.reporterOfTopLevelSuiteFor(emitter)).run()
    status
  }

  // Runs each suite it is handed at once, on the thread that hands it over.
  private object RunningEachSuiteAtOnce extends Distributor {
    def apply(suite: Suite, args: Args): Status = {
      val status = new ScalaTestStatefulStatus
      new SuiteRunner(suite, args, status).run()
      status
    }
  }

  // Runs the suites it is handed only when asked to.
  private final class RunningEachSuiteWhenAsked extends Distributor {
    private var handedOver = Vector.empty[() => Unit]

    def apply(suite: Suite, args: Args): Status = {
      val status = new ScalaTestStatefulStatus
      handedOver :+= (() => new SuiteRunner(suite, args, status).run())
      status
    }

    def runHandedOver(): Unit = {
      val toRun = handedOver
      handedOver = Vector.empty
      toRun.foreach(run => run())
    }
  }

  // Passes no event on, so that an event that reaches it reaches no wrapper after it.
  private val sortingNoEventOn: DistributedTestSorter = new DistributedTestSorter {
    def distributingTest(testName: String): Unit = ()
    def apply(testName: String, event: Event): Unit = ()
    def completedTest(testName: String): Unit = ()
  }

  // Runs a suite registered as a top-level suite of an emitter writing to an event log of its own, and returns the lines it writes.
  private def linesWrittenRunning(suite: Suite)(run: Reporter => Unit): List[String] = {
    val eventLog = new RecordingEventLog
    val wrapper = TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
    try {
      run(wrapper)
    } finally {
      SuiteRegistration.madeWith(wrapper).remove()
    }
    eventLog.writes.map(_.stripSuffix("\n")).toList
  }

  private def isRegistered(registration: Option[SuiteRegistration]): Boolean = registration.exists(_ ne SuiteRegistration.none)

  private def hasNoRegistration(suite: Suite): Boolean = SuiteRegistration.of(suite) eq SuiteRegistration.none

  "A top-level suite of a run that writes a test event log" should {
    "be registered while it runs, and not once its run has returned a completed Status" in {
      val suite = new Probe
      runAsTopLevelSuite(suite, Some(emitter))
      (isRegistered(suite.registrationInRun), isRegistered(suite.registrationInTest), hasNoRegistration(suite)) shouldBe ((true, true, true))
    }

    "not be registered once its run has thrown an exception" in {
      val suite = new ProbeWhoseRunThrows(new IllegalStateException("thrown by run"))
      runAsTopLevelSuite(suite, Some(emitter))
      (isRegistered(suite.registrationInTest), hasNoRegistration(suite)) shouldBe ((true, true))
    }

    "not be registered once its run has thrown an error its runner does not catch" in {
      val error = new LinkageError("thrown by run")
      val suite = new ProbeWhoseRunThrows(error)
      val thrown = the[LinkageError] thrownBy runAsTopLevelSuite(suite, Some(emitter))
      thrown should be theSameInstanceAs error
      (isRegistered(suite.registrationInTest), hasNoRegistration(suite)) shouldBe ((true, true))
    }

    "stay registered where another suite is run with its structure wrapper as its reporter and completes" in {
      val suite = new Probe
      val other = new Probe
      val anEmitter = emitter
      val wrapper = anEmitter.wrapperOfTopLevelSuite(suite, org.scalatest.Filter.default, passingNoEventOn)
      try {
        new SuiteRunner(other, Args(wrapper), new ScalaTestStatefulStatus).run()
        SuiteRegistration.of(suite) should be theSameInstanceAs SuiteRegistration.madeWith(wrapper)
        hasNoRegistration(suite) shouldBe false
      } finally {
        SuiteRegistration.madeWith(wrapper).remove()
      }
    }

    "stay registered until the Status its run returned completes" in {
      val mayComplete = Promise[Unit]()
      val suite = new AsyncProbe(mayComplete)
      val completed = new CountDownLatch(1)
      val status = runAsTopLevelSuite(suite, Some(emitter))
      val wasRegisteredWhileItsTestRan = !hasNoRegistration(suite)
      status.whenCompleted(_ => completed.countDown())
      mayComplete.success(())
      completed.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true
      (wasRegisteredWhileItsTestRan, hasNoRegistration(suite)) shouldBe ((true, true))
    }

    "be run with its structure wrapper, and run each of its tests with the test's" in {
      val suite = new Probe
      runAsTopLevelSuite(suite, Some(emitter))
      (suite.runIsGivenAStructureWrapper, suite.testIsGivenAStructureWrapper) shouldBe ((true, true))
    }

    "run each test of an async style with the test's structure wrapper" in {
      val mayComplete = Promise[Unit]()
      mayComplete.success(())
      val suite = new AsyncProbe(mayComplete)
      val completed = new CountDownLatch(1)
      runAsTopLevelSuite(suite, Some(emitter)).whenCompleted(_ => completed.countDown())
      completed.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true
      suite.testIsGivenAStructureWrapper shouldBe true
    }
  }

  "A top-level suite of a run that writes no test event log" should {
    "have no registration, and be run, as its tests are, with no structure wrapper" in {
      val suite = new Probe
      runAsTopLevelSuite(suite, None)
      (suite.registrationInRun, suite.registrationInTest).toList.flatten.foreach(_ should be theSameInstanceAs SuiteRegistration.none)
      (suite.registrationInRun.isDefined, suite.registrationInTest.isDefined) shouldBe ((true, true))
      (suite.runIsGivenAStructureWrapper, suite.testIsGivenAStructureWrapper) shouldBe ((false, false))
    }
  }

  private def nestedSuiteSites(nesting: String, nest: Seq[Suite] => Suite, distributor: Option[Distributor] = None): Unit = {
    s"A suite that $nesting runs" should {
      "be registered while it runs, with a structure wrapper for its reporter, and not once its run has returned" in {
        val nested = List(new Probe, new Probe)
        runAsTopLevelSuite(nest(nested), Some(emitter), distributor = distributor)
        nested.map(suite => (isRegistered(suite.registrationInRun), isRegistered(suite.registrationInTest), suite.runIsGivenAStructureWrapper)) shouldBe
          List((true, true, true), (true, true, true))
        nested.map(hasNoRegistration) shouldBe List(true, true)
      }

      "be registered apart from the suite nested beside it" in {
        val nested = List(new Probe, new Probe)
        runAsTopLevelSuite(nest(nested), Some(emitter), distributor = distributor)
        nested.head.registrationInRun.get should not be theSameInstanceAs(nested.last.registrationInRun.get)
      }

      "not be registered once its run has thrown" in {
        val nested = new ProbeWhoseRunThrows(new IllegalStateException("thrown by run"))
        runAsTopLevelSuite(nest(List(nested)), Some(emitter), distributor = distributor)
        (isRegistered(nested.registrationInTest), hasNoRegistration(nested)) shouldBe ((true, true))
      }

      // A distributor's runner sends the nested suite's SuiteCompleted only once that Status completes.
      if (distributor.isEmpty) "stay registered once its run has returned, until the Status that run returned completes" in {
        val mayComplete = Promise[Unit]()
        val nested = new AsyncProbe(mayComplete)
        val wasRegisteredOnceItsRunHadReturned = new AtomicReference(Option.empty[Boolean])
        // The outer suite sends the nested suite's SuiteCompleted once the nested suite's run has returned, while its test has yet to complete.
        val completingTheTestAtSuiteCompleted: Reporter = new Reporter {
          def apply(event: Event): Unit = {
            event match {
              case completed: SuiteCompleted if completed.suiteId == nested.suiteId =>
                wasRegisteredOnceItsRunHadReturned.set(Some(!hasNoRegistration(nested)))
                mayComplete.success(())
              case _ => ()
            }
          }
        }
        val completed = new CountDownLatch(1)
        runAsTopLevelSuite(nest(List(nested)), Some(emitter), completingTheTestAtSuiteCompleted)
        nested.statusOfRun.foreach(_.whenCompleted(_ => completed.countDown()))
        completed.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true
        (wasRegisteredOnceItsRunHadReturned.get, hasNoRegistration(nested)) shouldBe ((Some(true), true))
      }

      "have no registration and no structure wrapper where the run writes no test event log" in {
        val nested = new Probe
        runAsTopLevelSuite(nest(List(nested)), None, distributor = distributor)
        nested.registrationInRun.get should be theSameInstanceAs SuiteRegistration.none
        nested.runIsGivenAStructureWrapper shouldBe false
      }
    }
  }

  nestedSuiteSites("Suite.runNestedSuites", nested => new Suites(nested*))

  nestedSuiteSites("StepwiseNestedSuiteExecution.runNestedSuites", nested => new Suites(nested*) with StepwiseNestedSuiteExecution)

  nestedSuiteSites("Suite.runNestedSuites hands to a distributor, which", nested => new Suites(nested*), Some(RunningEachSuiteAtOnce))

  "A suite whose nested suites Suite.runNestedSuites hands to a distributor" should {
    "send each nested suite's events, and those of its test, to that suite's and that test's own nodes" in {
      val suite = new samples.OuterSuites
      val lines = linesWrittenRunning(suite)(wrapper => suite.run(None, Args(wrapper, distributor = Some(RunningEachSuiteAtOnce))))
      val stated = List(
        TestEventLogLine.structure(
          TestEventLogLine.suite(1, "OuterSuites", "samples.OuterSuites"),
          TestEventLogLine.nestedSuite(2, 1, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(3, 2, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(4, 1, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(5, 4, 0, "inner two", "- inner two")
        ),
        TestEventLogLine.suiteEvent(2, 1, "SuiteStarting"),
        TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestStarting"),
        TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestSucceeded"),
        TestEventLogLine.suiteEvent(2, 1, "SuiteCompleted"),
        TestEventLogLine.suiteEvent(4, 1, "SuiteStarting"),
        TestEventLogLine.scopeOrTestEvent(5, 1, 1, "TestStarting"),
        TestEventLogLine.scopeOrTestEvent(5, 1, 1, "TestSucceeded"),
        TestEventLogLine.suiteEvent(4, 1, "SuiteCompleted")
      )
      StatedLines.mismatchWhenIs(lines, stated) shouldBe None
    }
  }

  private def perTestInstanceSites(site: String, probe: () => Suite, throwingProbe: () => Suite, distributor: Option[Distributor]): Unit = {
    s"A suite instance $site creates to run one test" should {
      "be registered while its test runs, and not once its run has completed" in {
        val suite = probe()
        val (_, ranItsTest) = PerTestInstances.recordedIn(runAsTopLevelSuite(suite, Some(emitter), distributor = distributor))
        ranItsTest.map((instance, registration) => (instance ne suite, isRegistered(Some(registration)), hasNoRegistration(instance))) shouldBe
          Vector((true, true, true))
      }

      "not be registered once its run has thrown" in {
        val (runForOneTest, _) = PerTestInstances.recordedIn(runAsTopLevelSuite(throwingProbe(), Some(emitter), distributor = distributor))
        runForOneTest.map(hasNoRegistration) shouldBe Vector(true)
      }

      "have no registration where the run writes no test event log" in {
        val (_, ranItsTest) = PerTestInstances.recordedIn(runAsTopLevelSuite(probe(), None, distributor = distributor))
        ranItsTest.map((_, registration) => registration eq SuiteRegistration.none) shouldBe Vector(true)
      }
    }
  }

  perTestInstanceSites("OneInstancePerTest.runTest", () => new OneInstancePerTestProbe, () => new OneInstancePerTestThrowingProbe, None)

  perTestInstanceSites(
    "ParallelTestExecution.runTest",
    () => new ParallelTestExecutionProbe,
    () => new ParallelTestExecutionThrowingProbe,
    Some(RunningEachSuiteAtOnce)
  )

  perTestInstanceSites("RandomTestOrder.runTest", () => new RandomTestOrderProbe, () => new RandomTestOrderThrowingProbe, None)

  "The suite instances RandomTestOrder.runTest creates to run its tests" should {
    "not be registered once the run of one of them has thrown, including those whose runs it leaves unrun" in {
      val suite = new RandomTestOrderTwoTestsThrowingProbe
      val (runForOneTest, _) = PerTestInstances.recordedIn(runAsTopLevelSuite(suite, Some(emitter)))
      val instancesOfTests = suite.instancesOfTests.asScala.toVector
      (runForOneTest.size, instancesOfTests.size, instancesOfTests.map(hasNoRegistration)) shouldBe ((1, 2, Vector(true, true)))
    }
  }

  "A suite mixing in OneInstancePerTest" should {
    "send each test's events from the instance it runs the test in to the test's node, in the execution of the run that created that instance" in {
      val suite = new StackPerTestFunSpec
      val lines = linesWrittenRunning(suite)(wrapper => suite.run(None, Args(wrapper)))
      val stated = List(
        TestEventLogLine.structure(
          TestEventLogLine.suite(1, "StackPerTestFunSpec", "org.scalatest.samples.StackPerTestFunSpec"),
          TestEventLogLine.scope(2, 1, 0, "A Stack", "A Stack"),
          TestEventLogLine.test(3, 2, 0, "is empty", "- is empty"),
          TestEventLogLine.test(4, 2, 1, "pops", "- pops")
        ),
        TestEventLogLine.scopeOrTestEvent(2, 1, 1, "ScopeOpened"),
        TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestStarting"),
        TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestSucceeded"),
        TestEventLogLine.scopeOrTestEvent(4, 1, 1, "TestStarting"),
        TestEventLogLine.scopeOrTestEvent(4, 1, 1, "TestSucceeded"),
        TestEventLogLine.scopeOrTestEvent(2, 1, 1, "ScopeClosed")
      )
      StatedLines.mismatchWhenIs(lines, stated) shouldBe None
    }
  }

  "A suite whose scope's events a TestSortingReporter holds back until its test completes" should {
    def scopeAndItsTest(suite: Suite, eventsInOrderHandedOff: List[(Long, String)]): List[String] = {
      TestEventLogLine.structure(
        TestEventLogLine.suite(1, suite.suiteName, suite.getClass.getName),
        TestEventLogLine.scope(2, 1, 0, "s", "s"),
        TestEventLogLine.test(3, 2, 0, "t", "- t")
      ) :: eventsInOrderHandedOff.map((node, eventName) => TestEventLogLine.scopeOrTestEvent(node, 1, 1, eventName))
    }

    val scopeClosedBeforeItsTest = List(2L -> "ScopeOpened", 2L -> "ScopeClosed", 3L -> "TestStarting", 3L -> "TestSucceeded")

    "hand the scope's events off as they are sent, where it mixes in RandomTestOrder" in {
      val suite = new ScopedRandomOrderFunSpec
      val lines = linesWrittenRunning(suite)(wrapper => suite.run(None, Args(wrapper)))
      StatedLines.mismatchWhenIs(lines, scopeAndItsTest(suite, scopeClosedBeforeItsTest)) shouldBe None
    }

    "hand the scope's events off as they are sent, where it mixes in ParallelTestExecution and its test runs once its run has returned" in {
      val suite = new ScopedParallelFunSpec
      val distributor = new RunningEachSuiteWhenAsked
      val lines = linesWrittenRunning(suite) { wrapper =>
        suite.run(None, Args(wrapper, distributor = Some(distributor)))
        distributor.runHandedOver()
      }
      StatedLines.mismatchWhenIs(lines, scopeAndItsTest(suite, scopeClosedBeforeItsTest)) shouldBe None
    }

    "hand the scope's events off as they are sent, where it is of an async style and its test completes once its run has returned" in {
      val suite = new ScopedQueueingAsyncFunSpec
      val lines = linesWrittenRunning(suite) { wrapper =>
        suite.run(None, Args(wrapper))
        suite.queueing.runQueued()
      }
      val scopeClosedWhileItsTestRuns = List(2L -> "ScopeOpened", 3L -> "TestStarting", 2L -> "ScopeClosed", 3L -> "TestSucceeded")
      StatedLines.mismatchWhenIs(lines, scopeAndItsTest(suite, scopeClosedWhileItsTestRuns)) shouldBe None
    }
  }

  "A suite run for one test with a test sorter" should {
    List(
      "mixes in ParallelTestExecution" -> (() => new SendsFromRunTestsParallelSuite),
      "mixes in RandomTestOrder" -> (() => new SendsFromRunTestsRandomOrderSuite),
      "is of an async style" -> (() => new SendsFromRunTestsAsyncSuite)
    ).foreach { (kind, sample) =>
      s"hand off, assigned to that test, a message sent through the reporter its runTests is given, where it $kind" in {
        val suite = sample()
        val lines = linesWrittenRunning(suite)(wrapper => suite.run(Some("t"), Args(wrapper, distributedTestSorter = Some(sortingNoEventOn))))
        val stated = List(
          TestEventLogLine.structure(TestEventLogLine.suite(1, suite.suiteName, suite.getClass.getName), TestEventLogLine.test(2, 1, 0, "t", "- t")),
          TestEventLogLine.scopeOrTestEvent(2, 1, 1, "InfoProvided", """"message":"sent by runTests",""")
        )
        StatedLines.mismatchWhenIs(lines, stated) shouldBe None
      }
    }
  }

  "A suite instance with two registrations" should {
    "be given no structure wrapper ahead of its sorter or for the instances it runs its tests in, so that its events reach the emitter through the wrapper it reports to" in {
      val eventLog = new RecordingEventLog
      val anEmitter = TestEventLogEmitter.appendingTo(eventLog)
      val suite = new ScopedRandomOrderFunSpec
      val first = anEmitter.wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
      val second = anEmitter.wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
      try {
        suite.run(None, Args(first))
      } finally {
        SuiteRegistration.madeWith(first).remove()
        SuiteRegistration.madeWith(second).remove()
      }
      val nodesOfFirstStructure = Set(1L, 2L, 3L).map(NodeId(_))
      val events = eventLog.writes.map(write => TestEventLogDecoder.decode(write.stripSuffix("\n"))).collect {
        case Right(TestEventLogEvent.ScalaTestEvent(execution, event)) => (execution.exists(assigned => nodesOfFirstStructure.contains(assigned.node)), event.getClass.getSimpleName)
      }
      events shouldBe Vector(true -> "ScopeOpened", true -> "TestStarting", true -> "TestSucceeded", true -> "ScopeClosed")
    }
  }

  "A suite whose nested suites StepwiseNestedSuiteExecution.runNestedSuites runs" should {
    "send each nested suite's events, and those of its test, to that suite's and that test's own nodes" in {
      val eventLog = new RecordingEventLog
      val suite = new samples.StepwiseOuterSuites
      val wrapper = TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
      try {
        suite.run(None, Args(wrapper))
      } finally {
        SuiteRegistration.madeWith(wrapper).remove()
      }
      val stated = List(
        TestEventLogLine.structure(
          TestEventLogLine.suite(1, "StepwiseOuterSuites", "samples.StepwiseOuterSuites"),
          TestEventLogLine.nestedSuite(2, 1, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(3, 2, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(4, 1, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(5, 4, 0, "inner two", "- inner two")
        ),
        TestEventLogLine.suiteEvent(2, 1, "SuiteStarting"),
        TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestStarting"),
        TestEventLogLine.scopeOrTestEvent(3, 1, 1, "TestSucceeded"),
        TestEventLogLine.suiteEvent(2, 1, "SuiteCompleted"),
        TestEventLogLine.suiteEvent(4, 1, "SuiteStarting"),
        TestEventLogLine.scopeOrTestEvent(5, 1, 1, "TestStarting"),
        TestEventLogLine.scopeOrTestEvent(5, 1, 1, "TestSucceeded"),
        TestEventLogLine.suiteEvent(4, 1, "SuiteCompleted")
      )
      StatedLines.mismatchWhenIs(eventLog.writes.map(_.stripSuffix("\n")), stated) shouldBe None
    }
  }

  "A path suite whose run is called twice" should {
    "send the events of its second run in a second execution of each of its scopes and tests" in {
      val eventLog = new RecordingEventLog
      val suite = new samples.StackPathFunSpec
      val wrapper = TestEventLogEmitter.appendingTo(eventLog).wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
      try {
        suite.run(None, Args(wrapper))
        suite.run(None, Args(wrapper))
      } finally {
        SuiteRegistration.madeWith(wrapper).remove()
      }
      def eventsOfRun(execution: Int): List[String] = {
        def inRun(node: Long, eventName: String): String = TestEventLogLine.scopeOrTestEvent(node, execution, execution, eventName)
        List(
          inRun(2, "ScopeOpened"),
          inRun(3, "TestStarting"),
          inRun(3, "TestSucceeded"),
          inRun(4, "ScopeOpened"),
          inRun(5, "TestStarting"),
          inRun(5, "TestSucceeded"),
          inRun(4, "ScopeClosed"),
          inRun(2, "ScopeClosed")
        )
      }
      val structure =
        TestEventLogLine.structure(
          TestEventLogLine.suite(1, "StackPathFunSpec", "samples.StackPathFunSpec"),
          TestEventLogLine.scope(2, 1, 0, "A Stack", "A Stack"),
          TestEventLogLine.test(3, 2, 0, "is empty", "- is empty"),
          TestEventLogLine.scope(4, 2, 1, "when full", "when full"),
          TestEventLogLine.test(5, 4, 0, "pops", "- pops")
        )
      StatedLines.mismatchWhenIs(eventLog.writes.map(_.stripSuffix("\n")), structure :: eventsOfRun(1) ::: eventsOfRun(2)) shouldBe None
    }
  }

  "A DocSpec" should {
    "record that its markup is its suite's own as it sends it" in {
      val anEmitter = emitter
      val suite = new MarkupDocSpec
      val wrapper = anEmitter.wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
      try {
        // Sent to a reporter that is no structure wrapper, so the emitter keeps the origin until the registration is removed.
        suite.run(None, Args(passingNoEventOn))
        anEmitter.keptEventCount shouldBe 1
      } finally {
        SuiteRegistration.madeWith(wrapper).remove()
      }
      anEmitter.keptEventCount shouldBe 0
    }
  }

  "A lookup of a suite instance's registration" should {
    "not wait for a thread that holds the lock instances are registered and removed under" in {
      val anEmitter = emitter
      val suite = new Probe
      val wrapper = anEmitter.wrapperOfTopLevelSuite(suite, Filter.default, passingNoEventOn)
      val lookedUp = new AtomicReference(Option.empty[SuiteRegistration])
      val lookup = new Thread(() => lookedUp.set(Some(SuiteRegistration.of(suite))))
      try {
        val returnedWhileTheLockWasHeld = RegisteredSuites.synchronized {
          lookup.start()
          joined(lookup)
        }
        returnedWhileTheLockWasHeld shouldBe true
        lookedUp.get.get should be theSameInstanceAs SuiteRegistration.madeWith(wrapper)
      } finally {
        joined(lookup)
        SuiteRegistration.madeWith(wrapper).remove()
        anEmitter.endWithoutRunEnded()
      }
    }
  }

  "A process's suite instances" should {
    "be looked up while an event log of the process is open, and have no registration once the last has closed" in {
      TemporaryEventLog.in { eventLogFile =>
        val output = outputOfItsOwnJvm(SuiteLookupsInTheirOwnJvm.getClass.getName.stripSuffix("$"), Nil, eventLogFile.getParent)
        output.stdout shouldBe Vector(
          "with one event log open: registered",
          "with the first of two event logs closed: registered",
          "with both closed: not registered",
          "with another opened: registered"
        )
      }
    }
  }

  "A suite Framework runs" should {
    "be registered while it runs with the runner argument, and not once its task has ended" in {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        val ran = RegistrationProbeSuite.instancesRunIn {
          launched(Launch.ByFramework, classOf[RegistrationProbeSuite].getName, runnerArgument(eventLogFile))
        }
        ran.map((_, registrationAsItRan) => registrationAsItRan ne SuiteRegistration.none) shouldBe Vector(true)
        ran.map((suite, _) => hasNoRegistration(suite)) shouldBe Vector(true)
      }
    }

    "have no registration while it runs without the runner argument" in {
      val ran = RegistrationProbeSuite.instancesRunIn(launched(Launch.ByFramework, classOf[RegistrationProbeSuite].getName, Nil))
      ran.map((_, registrationAsItRan) => registrationAsItRan eq SuiteRegistration.none) shouldBe Vector(true)
    }
  }
}
