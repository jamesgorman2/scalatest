package org.scalatest.tools

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.Args
import org.scalatest.DistributedTestSorter
import org.scalatest.Distributor
import org.scalatest.Filter
import org.scalatest.InvocationContext
import org.scalatest.Reporter
import org.scalatest.ScalaTestStatefulStatus
import org.scalatest.Status
import org.scalatest.StepwiseNestedSuiteExecution
import org.scalatest.Succeeded
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.Suites
import org.scalatest.concurrent.SerialExecutionContext
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.Ordinal
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.samples.AsyncSuiteRecordingItsNodeAsTestsAreCounted
import org.scalatest.samples.MarkupDocSpec
import org.scalatest.samples.OneInstancePerTestProbe
import org.scalatest.samples.ParallelSuiteRecordingItsNodeAsTestsAreCounted
import org.scalatest.samples.ParallelTestExecutionProbe
import org.scalatest.samples.PerTestInstances
import org.scalatest.samples.RandomTestOrderProbe
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
import scala.concurrent.Future
import scala.concurrent.Promise

final class SuiteRegistrationSitesSpec extends AnyWordSpec with Matchers with TestEventLogRuns with EventLogRecording {

  private class Probe extends AnyFunSuite {
    @volatile var registrationInRun = Option.empty[SuiteRegistration]
    @volatile var registrationInTest = Option.empty[SuiteRegistration]
    @volatile var testRunsInATest = false
    @volatile var runIsGivenAStructureWrapper = false
    @volatile var testIsGivenAStructureWrapper = false

    override def run(testName: Option[String], args: Args): Status = {
      registrationInRun = Some(SuiteRegistration.inContext(this))
      runIsGivenAStructureWrapper = args.reporter.isInstanceOf[StructureWrapper]
      super.run(testName, args)
    }

    override protected def runTest(testName: String, args: Args): Status = {
      testIsGivenAStructureWrapper = args.reporter.isInstanceOf[StructureWrapper]
      super.runTest(testName, args)
    }

    test("probes") {
      registrationInTest = Some(RegistrationInContext.ofCallingThread)
      testRunsInATest = RegistrationInContext.isInATest
    }
  }

  private final class ProbeWhoseRunThrows(thrown: Throwable) extends Probe {
    override def run(testName: Option[String], args: Args): Status = {
      super.run(testName, args)
      throw thrown
    }
  }

  private final class AsyncProbe(mayComplete: Promise[Unit]) extends AsyncFunSpec {
    @volatile var registrationInRun = Option.empty[SuiteRegistration]
    @volatile var testIsGivenAStructureWrapper = false

    implicit override def executionContext: ExecutionContext = ExecutionContext.global

    override def run(testName: Option[String], args: Args): Status = {
      registrationInRun = Some(SuiteRegistration.inContext(this))
      super.run(testName, args)
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

  private def runAsTopLevelSuite(
    suite: Suite,
    emitter: Option[TestEventLogEmitter],
    reporter: Reporter = passingNoEventOn,
    distributor: Option[Distributor] = None
  ): ScalaTestStatefulStatus = {
    val status = new ScalaTestStatefulStatus
    val args = Args(reporter, distributor = distributor)
    new SuiteRunner(suite, args, status, TestEventLogHandOff.none, TestEventLogEmitter.startOfTopLevelSuiteFor(emitter)).run()
    status
  }

  private object RunningEachSuiteAtOnce extends Distributor {
    def apply(suite: Suite, args: Args): Status = {
      val status = new ScalaTestStatefulStatus
      new SuiteRunner(suite, args, status).run()
      status
    }
  }

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

  // So that an event that reaches it reaches no wrapper after it.
  private val sortingNoEventOn: DistributedTestSorter = new DistributedTestSorter {
    def distributingTest(testName: String): Unit = ()
    def apply(testName: String, event: Event): Unit = ()
    def completedTest(testName: String): Unit = ()
  }

  private def eventLogLinesRunningAsTopLevelSuite(suite: Suite)(runWithItsStructureWrapper: Reporter => Unit): List[String] = {
    val eventLog = new RecordingEventLog
    val started = new StartedTopLevelSuite(TestEventLogEmitter.appendingTo(eventLog), suite)
    try {
      started.runningInItsContext(runWithItsStructureWrapper(started.structureWrapper))
    } finally {
      started.complete()
    }
    eventLog.writes.map(_.stripSuffix("\n")).toList
  }

  private def isRegistered(registration: Option[SuiteRegistration]): Boolean = registration.exists(_ ne SuiteRegistration.none)

  private def hasBeenRemoved(registration: Option[SuiteRegistration]): Option[Boolean] =
    registration.collect { case registered: RegisteredSuite => registered.emitter.synchronized(registered.hasBeenRemoved) }

  private def inTheContextOfAnotherSuite[A](body: => A): (A, Boolean) = {
    val other = new StartedTopLevelSuite(emitter, new Probe)
    try {
      other.runningInItsContext {
        val contextGiven = InvocationContext.get
        val returned = body
        val contextRestored = contextGiven.isDefined && InvocationContext.get == contextGiven
        (returned, contextRestored)
      }
    } finally {
      other.complete()
    }
  }

  "A top-level suite of a run that writes a test event log" should {
    "run in the context of its registration, run its test in the context of the test's execution, and restore the context it started in once its run has returned" in {
      val suite = new Probe
      val (_, restored) = inTheContextOfAnotherSuite(runAsTopLevelSuite(suite, Some(emitter)))
      (isRegistered(suite.registrationInRun), suite.registrationInTest, suite.testRunsInATest, restored) shouldBe
        ((true, suite.registrationInRun, true, true))
    }

    "restore the context it started in, and be removed, once its run has thrown an exception" in {
      val suite = new ProbeWhoseRunThrows(new IllegalStateException("thrown by run"))
      val (_, restored) = inTheContextOfAnotherSuite(runAsTopLevelSuite(suite, Some(emitter)))
      (isRegistered(suite.registrationInTest), hasBeenRemoved(suite.registrationInRun), restored) shouldBe ((true, Some(true), true))
    }

    "restore the context it started in, and be removed, once its run has thrown an error its runner does not catch" in {
      val error = new LinkageError("thrown by run")
      val suite = new ProbeWhoseRunThrows(error)
      val (thrown, restored) = inTheContextOfAnotherSuite(the[LinkageError] thrownBy runAsTopLevelSuite(suite, Some(emitter)))
      thrown should be theSameInstanceAs error
      (isRegistered(suite.registrationInTest), hasBeenRemoved(suite.registrationInRun), restored) shouldBe ((true, Some(true), true))
    }

    "keep its registration until the Status its run returned completes" in {
      val mayComplete = Promise[Unit]()
      val suite = new AsyncProbe(mayComplete)
      val completed = new CountDownLatch(1)
      val status = runAsTopLevelSuite(suite, Some(emitter))
      val removedBeforeItsTestCompleted = hasBeenRemoved(suite.registrationInRun)
      status.whenCompleted(_ => completed.countDown())
      mayComplete.success(())
      completed.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true
      (removedBeforeItsTestCompleted, hasBeenRemoved(suite.registrationInRun)) shouldBe ((Some(false), Some(true)))
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
    "find no registration, and be run, as its tests are, with no structure wrapper" in {
      val suite = new Probe
      runAsTopLevelSuite(suite, None)
      (suite.registrationInRun, suite.registrationInTest) shouldBe ((Some(SuiteRegistration.none), Some(SuiteRegistration.none)))
      (suite.runIsGivenAStructureWrapper, suite.testIsGivenAStructureWrapper) shouldBe ((false, false))
    }

    "run in no context, where the site that starts it runs in one, and restore that context once its run has returned" in {
      val suite = new Probe
      val (_, restored) = inTheContextOfAnotherSuite(runAsTopLevelSuite(suite, None))
      (suite.registrationInRun, suite.registrationInTest, restored) shouldBe ((Some(SuiteRegistration.none), Some(SuiteRegistration.none), true))
    }
  }

  "A suite a test runs on a thread the test starts, in a run that writes a test event log" should {
    "find no registration, in its run or in its test, and run its test with no structure wrapper" in {
      val helper = new Probe
      val outer = new AnyFunSuite {
        test("runs a helper on a thread of its own") {
          val helping = new Thread(() => { helper.run(None, Args(passingNoEventOn)); () })
          helping.start()
          helping.join()
        }
      }
      runAsTopLevelSuite(outer, Some(emitter))
      (helper.registrationInRun, helper.registrationInTest, helper.testRunsInATest) shouldBe ((Some(SuiteRegistration.none), Some(SuiteRegistration.none), false))
      helper.testIsGivenAStructureWrapper shouldBe false
    }
  }

  private def nestedSuiteSites(nesting: String, nest: Seq[Suite] => Suite, distributor: Option[Distributor] = None): Unit = {
    s"A suite that $nesting runs" should {
      "run in the context of a registration of its own, with a structure wrapper for its reporter" in {
        val nested = List(new Probe, new Probe)
        val outer = nest(nested)
        runAsTopLevelSuite(outer, Some(emitter), distributor = distributor)
        nested.map(suite => (isRegistered(suite.registrationInRun), suite.registrationInTest == suite.registrationInRun, suite.runIsGivenAStructureWrapper)) shouldBe
          List((true, true, true), (true, true, true))
      }

      "be registered apart from the suite nested beside it" in {
        val nested = List(new Probe, new Probe)
        runAsTopLevelSuite(nest(nested), Some(emitter), distributor = distributor)
        nested.head.registrationInRun.get should not be theSameInstanceAs(nested.last.registrationInRun.get)
      }

      "find no registration and have no structure wrapper where the run writes no test event log" in {
        val nested = new Probe
        runAsTopLevelSuite(nest(List(nested)), None, distributor = distributor)
        nested.registrationInRun shouldBe Some(SuiteRegistration.none)
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
      val lines = eventLogLinesRunningAsTopLevelSuite(suite)(wrapper => suite.run(None, Args(wrapper, distributor = Some(RunningEachSuiteAtOnce))))
      val top = NodePath.topLevel("samples.OuterSuites")
      val inner1 = top.suite("samples.Inner1Suite")
      val inner2 = top.suite("samples.Inner2Suite")
      val innerOne = inner1.test("inner one")
      val innerTwo = inner2.test("inner two")
      val stated = List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("OuterSuites", "samples.OuterSuites"),
          TestEventLogLine.nestedSuite(inner1, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(innerOne, inner1, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(inner2, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(innerTwo, inner2, 0, "inner two", "- inner two")
        ),
        TestEventLogLine.nodeEvent(inner1, List(1, 1), "SuiteStarting"),
        TestEventLogLine.nodeEvent(innerOne, List(1, 1, 1), "TestStarting"),
        TestEventLogLine.nodeEvent(innerOne, List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.nodeEvent(inner1, List(1, 1), "SuiteCompleted"),
        TestEventLogLine.nodeEvent(inner2, List(1, 1), "SuiteStarting"),
        TestEventLogLine.nodeEvent(innerTwo, List(1, 1, 1), "TestStarting"),
        TestEventLogLine.nodeEvent(innerTwo, List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.nodeEvent(inner2, List(1, 1), "SuiteCompleted")
      )
      StatedLines.mismatchWhenIs(lines, stated) shouldBe None
    }
  }

  private def perTestInstanceSites(site: String, probe: () => Suite, distributor: Option[Distributor]): Unit = {
    s"A suite instance $site creates to run one test" should {
      "run its test in the context of a registration of its own" in {
        val suite = probe()
        val ranItsTest = PerTestInstances.recordedIn(runAsTopLevelSuite(suite, Some(emitter), distributor = distributor))
        ranItsTest.map((instance, registration) => (instance ne suite, isRegistered(Some(registration)))) shouldBe Vector((true, true))
      }

      "find no registration where the run writes no test event log" in {
        val ranItsTest = PerTestInstances.recordedIn(runAsTopLevelSuite(probe(), None, distributor = distributor))
        ranItsTest.map((_, registration) => registration) shouldBe Vector(SuiteRegistration.none)
      }
    }
  }

  perTestInstanceSites("OneInstancePerTest.runTest", () => new OneInstancePerTestProbe, None)

  perTestInstanceSites("ParallelTestExecution.runTest", () => new ParallelTestExecutionProbe, Some(RunningEachSuiteAtOnce))

  perTestInstanceSites("RandomTestOrder.runTest", () => new RandomTestOrderProbe, None)

  "A suite mixing in OneInstancePerTest" should {
    "send each test's events from the instance it runs the test in to the test's node, in the execution of the run that created that instance" in {
      val suite = new StackPerTestFunSpec
      val lines = eventLogLinesRunningAsTopLevelSuite(suite)(wrapper => suite.run(None, Args(wrapper)))
      val top = NodePath.topLevel("org.scalatest.samples.StackPerTestFunSpec")
      val aStack = top.scope("A Stack")
      val isEmpty = top.test("A Stack is empty")
      val pops = top.test("A Stack pops")
      val stated = List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("StackPerTestFunSpec", "org.scalatest.samples.StackPerTestFunSpec"),
          TestEventLogLine.scope(aStack, 0, "A Stack", "A Stack"),
          TestEventLogLine.test(isEmpty, aStack, 0, "is empty", "- is empty"),
          TestEventLogLine.test(pops, aStack, 1, "pops", "- pops")
        ),
        TestEventLogLine.nodeEvent(aStack, List(1, 1), "ScopeOpened"),
        TestEventLogLine.nodeEvent(isEmpty, List(1, 1, 1), "TestStarting"),
        TestEventLogLine.nodeEvent(isEmpty, List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.nodeEvent(pops, List(1, 1, 1), "TestStarting"),
        TestEventLogLine.nodeEvent(pops, List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.nodeEvent(aStack, List(1, 1), "ScopeClosed")
      )
      StatedLines.mismatchWhenIs(lines, stated) shouldBe None
    }
  }

  "A suite whose scope's events a TestSortingReporter holds back until its test completes" should {
    // Each event handed off is named by the text of its scope or test: "s" or "t".
    def scopeAndItsTest(suite: Suite, eventsInOrderHandedOff: List[(String, String)]): List[String] = {
      val top = NodePath.topLevel(suite.getClass.getName)
      val s = top.scope("s")
      val t = top.test("s t")
      val nodes = Map("s" -> (s, List(1, 1)), "t" -> (t, List(1, 1, 1)))
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite(suite.suiteName, suite.getClass.getName),
        TestEventLogLine.scope(s, 0, "s", "s"),
        TestEventLogLine.test(t, s, 0, "t", "- t")
      ) :: eventsInOrderHandedOff.map { (node, eventName) =>
        val (path, executions) = nodes(node)
        TestEventLogLine.nodeEvent(path, executions, eventName)
      }
    }

    val scopeClosedBeforeItsTest = List("s" -> "ScopeOpened", "s" -> "ScopeClosed", "t" -> "TestStarting", "t" -> "TestSucceeded")

    "hand the scope's events off as they are sent, where it mixes in RandomTestOrder" in {
      val suite = new ScopedRandomOrderFunSpec
      val lines = eventLogLinesRunningAsTopLevelSuite(suite)(wrapper => suite.run(None, Args(wrapper)))
      StatedLines.mismatchWhenIs(lines, scopeAndItsTest(suite, scopeClosedBeforeItsTest)) shouldBe None
    }

    "hand the scope's events off as they are sent, where it mixes in ParallelTestExecution and its test runs once its run has returned" in {
      val suite = new ScopedParallelFunSpec
      val distributor = new RunningEachSuiteWhenAsked
      val lines = eventLogLinesRunningAsTopLevelSuite(suite) { wrapper =>
        suite.run(None, Args(wrapper, distributor = Some(distributor)))
        distributor.runHandedOver()
      }
      StatedLines.mismatchWhenIs(lines, scopeAndItsTest(suite, scopeClosedBeforeItsTest)) shouldBe None
    }

    "hand the scope's events off as they are sent, where it is of an async style and its test completes once its run has returned" in {
      val suite = new ScopedQueueingAsyncFunSpec
      val lines = eventLogLinesRunningAsTopLevelSuite(suite) { wrapper =>
        suite.run(None, Args(wrapper))
        suite.queueing.runQueued()
      }
      val scopeClosedWhileItsTestRuns = List("s" -> "ScopeOpened", "t" -> "TestStarting", "s" -> "ScopeClosed", "t" -> "TestSucceeded")
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
        val lines = eventLogLinesRunningAsTopLevelSuite(suite)(wrapper => suite.run(Some("t"), Args(wrapper, distributedTestSorter = Some(sortingNoEventOn))))
        val top = NodePath.topLevel(suite.getClass.getName)
        val stated = List(
          TestEventLogLine.structure(TestEventLogLine.topLevelSuite(suite.suiteName, suite.getClass.getName), TestEventLogLine.test(top.test("t"), top, 0, "t", "- t")),
          TestEventLogLine.nodeEvent(top.test("t"), List(1, 1), "InfoProvided", """"message":"sent by runTests",""")
        )
        StatedLines.mismatchWhenIs(lines, stated) shouldBe None
      }
    }
  }

  "A suite whose nested suites StepwiseNestedSuiteExecution.runNestedSuites runs" should {
    "send each nested suite's events, and those of its test, to that suite's and that test's own nodes" in {
      val suite = new samples.StepwiseOuterSuites
      val lines = eventLogLinesRunningAsTopLevelSuite(suite)(wrapper => suite.run(None, Args(wrapper)))
      val top = NodePath.topLevel("samples.StepwiseOuterSuites")
      val inner1 = top.suite("samples.Inner1Suite")
      val inner2 = top.suite("samples.Inner2Suite")
      val innerOne = inner1.test("inner one")
      val innerTwo = inner2.test("inner two")
      val stated = List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("StepwiseOuterSuites", "samples.StepwiseOuterSuites"),
          TestEventLogLine.nestedSuite(inner1, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(innerOne, inner1, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(inner2, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(innerTwo, inner2, 0, "inner two", "- inner two")
        ),
        TestEventLogLine.nodeEvent(inner1, List(1, 1), "SuiteStarting"),
        TestEventLogLine.nodeEvent(innerOne, List(1, 1, 1), "TestStarting"),
        TestEventLogLine.nodeEvent(innerOne, List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.nodeEvent(inner1, List(1, 1), "SuiteCompleted"),
        TestEventLogLine.nodeEvent(inner2, List(1, 1), "SuiteStarting"),
        TestEventLogLine.nodeEvent(innerTwo, List(1, 1, 1), "TestStarting"),
        TestEventLogLine.nodeEvent(innerTwo, List(1, 1, 1), "TestSucceeded"),
        TestEventLogLine.nodeEvent(inner2, List(1, 1), "SuiteCompleted")
      )
      StatedLines.mismatchWhenIs(lines, stated) shouldBe None
    }
  }

  "A path suite whose run is called twice" should {
    "send the events of its second run in a second execution of each of its scopes and tests" in {
      val suite = new samples.StackPathFunSpec
      val lines = eventLogLinesRunningAsTopLevelSuite(suite) { wrapper =>
        suite.run(None, Args(wrapper))
        suite.run(None, Args(wrapper))
      }
      val top = NodePath.topLevel("samples.StackPathFunSpec")
      val aStack = top.scope("A Stack")
      val isEmpty = top.test("A Stack is empty")
      val whenFull = aStack.scope("when full")
      val pops = top.test("A Stack when full pops")
      def eventsOfRun(execution: Int): List[String] = {
        def inRun(node: NodePath, depth: Int, eventName: String): String = TestEventLogLine.nodeEvent(node, List.fill(depth)(execution), eventName)
        List(
          inRun(aStack, 2, "ScopeOpened"),
          inRun(isEmpty, 3, "TestStarting"),
          inRun(isEmpty, 3, "TestSucceeded"),
          inRun(whenFull, 3, "ScopeOpened"),
          inRun(pops, 4, "TestStarting"),
          inRun(pops, 4, "TestSucceeded"),
          inRun(whenFull, 3, "ScopeClosed"),
          inRun(aStack, 2, "ScopeClosed")
        )
      }
      val structure =
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("StackPathFunSpec", "samples.StackPathFunSpec"),
          TestEventLogLine.scope(aStack, 0, "A Stack", "A Stack"),
          TestEventLogLine.test(isEmpty, aStack, 0, "is empty", "- is empty"),
          TestEventLogLine.scope(whenFull, 1, "when full", "when full"),
          TestEventLogLine.test(pops, whenFull, 0, "pops", "- pops")
        )
      StatedLines.mismatchWhenIs(lines, structure :: eventsOfRun(1) ::: eventsOfRun(2)) shouldBe None
    }
  }

  "A DocSpec" should {
    "hand its markup off as its suite's own when it sends it" in {
      val anEmitter = emitter
      val suite = new MarkupDocSpec
      val started = new StartedTopLevelSuite(anEmitter, suite)
      try {
        // Sent to a reporter that is no structure wrapper, so the emitter keeps the handed-off markup until the registration is removed.
        started.runningInItsContext(suite.run(None, Args(passingNoEventOn)))
        anEmitter.handedOffEventCount shouldBe 1
      } finally {
        started.complete()
      }
      anEmitter.handedOffEventCount shouldBe 0
    }
  }

  "The registration a suite's run finds in its context on one thread" should {
    "be found by a suite handed to a distributor in its context, on a thread of the distributor's pool, which is left in no context" in {
      val suite = new Probe
      val started = new StartedTopLevelSuite(emitter, suite)
      val pool = Executors.newSingleThreadExecutor()
      val contextAfterTheSuite = new AtomicReference(Option.empty[Option[InvocationContext]])
      try {
        val distributor = new ConcurrentDistributor(Args(passingNoEventOn), pool)
        started.runningInItsContext(distributor(new RecordingRegistrationOf(suite), Args(passingNoEventOn)))
        distributor.waitUntilDone()
        pool.submit((() => contextAfterTheSuite.set(Some(InvocationContext.get))): Runnable).get(waitLimitSeconds, TimeUnit.SECONDS)
      } finally {
        pool.shutdown()
        started.complete()
      }
      (suite.registrationInRun, contextAfterTheSuite.get) shouldBe ((Some(started.registration), Some(None)))
    }

    "be found by a task given to a SerialExecutionContext in its context and run by another thread, which is left in no context" in {
      val suite = new Probe
      val started = new StartedTopLevelSuite(emitter, suite)
      val executionContext = new SerialExecutionContext
      val foundInTask = new AtomicReference(Option.empty[SuiteRegistration])
      val contextAfterTheTask = new AtomicReference(Option.empty[Option[InvocationContext]])
      try {
        started.runningInItsContext(executionContext.execute(() => foundInTask.set(Some(SuiteRegistration.inContext(suite)))))
        val runningTheTask = new Thread(() => {
          executionContext.runNow(Future.successful(Succeeded))
          contextAfterTheTask.set(Some(InvocationContext.get))
        })
        runningTheTask.start()
        joined(runningTheTask) shouldBe true
      } finally {
        started.complete()
      }
      (foundInTask.get, contextAfterTheTask.get) shouldBe ((Some(started.registration), Some(None)))
    }
  }

  "A task given to a SerialExecutionContext in no context" should {
    "run in no context, where the thread that runs it runs in one" in {
      val started = new StartedTopLevelSuite(emitter, new Probe)
      val executionContext = new SerialExecutionContext
      val contextOfTask = new AtomicReference(Option.empty[Option[InvocationContext]])
      try {
        executionContext.execute(() => contextOfTask.set(Some(InvocationContext.get)))
        started.runningInItsContext(executionContext.runNow(Future.successful(Succeeded)))
      } finally {
        started.complete()
      }
      contextOfTask.get shouldBe Some(None)
    }
  }

  "A run that writes no test event log" should {
    "run a suite handed to a distributor, and a task given to a SerialExecutionContext, in no context" in {
      val suite = new Probe
      val pool = Executors.newSingleThreadExecutor()
      val executionContext = new SerialExecutionContext
      val contextOfTask = new AtomicReference(Option.empty[Option[InvocationContext]])
      try {
        val distributor = new ConcurrentDistributor(Args(passingNoEventOn), pool)
        distributor(new RecordingRegistrationOf(suite), Args(passingNoEventOn))
        distributor.waitUntilDone()
        executionContext.execute(() => contextOfTask.set(Some(InvocationContext.get)))
        executionContext.runNow(Future.successful(Succeeded))
      } finally {
        pool.shutdown()
      }
      (suite.registrationInRun, contextOfTask.get) shouldBe ((Some(SuiteRegistration.none), Some(None)))
    }
  }

  "The emitter, handing off an event of a suite instance whose run has not read its suite ID" should {
    "read the instance's suite ID once, where reading it throws" in {
      val started = new StartedTopLevelSuite(emitter, new Probe)
      val suite = new CountingSuiteIdReads(Some(new IllegalStateException("no suite ID")))
      try {
        val wrapper = registeredIn(new InRunOfSuite(registrationOf(started), None), suite).reporterOfSuite(passingNoEventOn)
        wrapper(anAlert)
        wrapper(anAlert)
      } finally {
        started.complete()
      }
      suite.reads.get shouldBe 1
    }

    "read no suite ID once the event log has ended" in {
      val anEmitter = emitter
      val started = new StartedTopLevelSuite(anEmitter, new Probe)
      val suite = new CountingSuiteIdReads()
      try {
        val wrapper = registeredIn(new InRunOfSuite(registrationOf(started), None), suite).reporterOfSuite(passingNoEventOn)
        anEmitter.endWithoutRunEnded()
        wrapper(anAlert)
      } finally {
        started.complete()
      }
      suite.reads.get shouldBe 0
    }
  }

  "The emitter, handing off an event of a suite instance at a place no structure holds" should {
    "read the instance's suite name once where its run has read none, and add a node table of that name under the suite it started in" in {
      val anEmitter = emitter
      val started = new StartedTopLevelSuite(anEmitter, new Probe)
      val suite = new CountingSuiteNameReads()
      try {
        val registration = registeredIn(new InRunOfSuite(registrationOf(started), None), suite)
        val wrapper = registration.reporterOfSuite(passingNoEventOn)
        wrapper(anAlert)
        wrapper(anAlert)
        val table = anEmitter.synchronized(registration.table.map(table => (table.name, table.parent)))
        (suite.reads.get, table) shouldBe ((1, Some(("counted", anEmitter.synchronized(registrationOf(started).table.map(_.node))))))
      } finally {
        started.complete()
      }
    }

    "read the instance's suite name once where its run has read none, and give it no node table where reading it throws" in {
      val anEmitter = emitter
      val started = new StartedTopLevelSuite(anEmitter, new Probe)
      val suite = new CountingSuiteNameReads(Some(new IllegalStateException("no suite name")))
      try {
        val registration = registeredIn(new InRunOfSuite(registrationOf(started), None), suite)
        val wrapper = registration.reporterOfSuite(passingNoEventOn)
        wrapper(anAlert)
        wrapper(anAlert)
        (suite.reads.get, anEmitter.synchronized(registration.table)) shouldBe ((1, None))
      } finally {
        started.complete()
      }
    }

    "read no suite name where its run has read one, and name the node table by it" in {
      val anEmitter = emitter
      val started = new StartedTopLevelSuite(anEmitter, new Probe)
      val suite = new CountingSuiteNameReads()
      try {
        val registration = registeredIn(new InRunOfSuite(registrationOf(started), None), suite)
        registration.suiteNameRead("read by its run")
        registration.reporterOfSuite(passingNoEventOn)(anAlert)
        (suite.reads.get, anEmitter.synchronized(registration.table.map(_.name))) shouldBe ((0, Some("read by its run")))
      } finally {
        started.complete()
      }
    }
  }

  "A nested suite instance whose outer instance's suite ID another thread is reading" should {
    "stay unfixed until that read returns, and then take its node from the suite ID its run read" in {
      val inner = new CountingSuiteIdReads()
      val outer = new HoldingSuiteIdReads(inner)
      val anEmitter = emitter
      val started = new StartedTopLevelSuite(anEmitter, new Suites(outer))
      val readsOfInnerAtStart = inner.reads.get
      try {
        val outerRegistration = registeredIn(new InRunOfSuite(registrationOf(started), None), outer)
        val innerRegistration = registeredIn(new InRunOfSuite(outerRegistration, None), inner)
        outer.holdReads()
        val handingOffForOuter = new Thread(() => outerRegistration.reporterOfSuite(passingNoEventOn)(anAlert))
        handingOffForOuter.start()
        outer.isBeingRead.await(waitLimitSeconds, TimeUnit.SECONDS) shouldBe true
        innerRegistration.suiteIdRead(classOf[CountingSuiteIdReads].getName)
        val fixedWhileOuterIsRead = anEmitter.synchronized(innerRegistration.isNodeFixed)
        outer.mayBeRead.countDown()
        joined(handingOffForOuter) shouldBe true
        innerRegistration.reporterOfSuite(passingNoEventOn)(anAlert)
        val nodeOfInner = anEmitter.synchronized(registrationOf(started).table.flatMap(_.childSuites.headOption).flatMap(_.childSuites.headOption).map(_.node))
        (fixedWhileOuterIsRead, anEmitter.synchronized(innerRegistration.table.map(_.node)), inner.reads.get - readsOfInnerAtStart) shouldBe
          ((false, nodeOfInner, 0))
      } finally {
        outer.mayBeRead.countDown()
        started.complete()
      }
    }
  }

  "A suite whose tests a TestSortingReporter sorts" should {
    "have the node of its run fixed by the suite ID it reads for that reporter, as an async suite" in {
      val suite = new AsyncSuiteRecordingItsNodeAsTestsAreCounted
      val started = new StartedTopLevelSuite(emitter, new Probe)
      try {
        started.runningInItsContext(suite.run(None, Args(passingNoEventOn)))
      } finally {
        started.complete()
      }
      suite.nodeFixedAsTestsAreCounted shouldBe Vector(true)
    }

    "have the node of its run fixed by the suite ID it reads for that reporter, as a suite mixing in ParallelTestExecution" in {
      val suite = new ParallelSuiteRecordingItsNodeAsTestsAreCounted
      val started = new StartedTopLevelSuite(emitter, new Probe)
      try {
        started.runningInItsContext(suite.run(None, Args(passingNoEventOn, distributor = Some(RunningEachSuiteAtOnce))))
      } finally {
        started.complete()
      }
      suite.nodeFixedAsTestsAreCounted shouldBe Vector(true)
    }
  }

  private final class CountingSuiteIdReads(thrown: Option[RuntimeException] = None) extends AnyFunSuite {
    val reads = new AtomicInteger

    override def suiteId: String = {
      reads.incrementAndGet()
      thrown.foreach(throw _)
      super.suiteId
    }
  }

  private final class CountingSuiteNameReads(thrown: Option[RuntimeException] = None) extends AnyFunSuite {
    val reads = new AtomicInteger

    override def suiteName: String = {
      reads.incrementAndGet()
      thrown.foreach(throw _)
      "counted"
    }
  }

  private final class HoldingSuiteIdReads(nested: Suite) extends Suite {
    val isBeingRead = new CountDownLatch(1)
    val mayBeRead = new CountDownLatch(1)
    @volatile private var holds = false

    def holdReads(): Unit = holds = true

    override def nestedSuites: IndexedSeq[Suite] = Vector(nested)

    override def suiteId: String = {
      if (holds) {
        isBeingRead.countDown()
        mayBeRead.await(waitLimitSeconds, TimeUnit.SECONDS)
      }
      super.suiteId
    }
  }

  private def registrationOf(started: StartedTopLevelSuite): RegisteredSuite = started.registration.asInstanceOf[RegisteredSuite]

  private def registeredIn(context: ContextOfRegisteredSuite, suite: Suite): RegisteredSuite = context.register(suite).asInstanceOf[RegisteredSuite]

  private def anAlert: Event = AlertProvided(new Ordinal(1), "alert", None, threadName = "main", timeStamp = 0L)

  private final class RecordingRegistrationOf(probe: Probe) extends Suite {
    override def run(testName: Option[String], args: Args): Status = {
      probe.registrationInRun = Some(SuiteRegistration.inContext(probe))
      org.scalatest.SucceededStatus
    }
  }

  "A suite Framework runs" should {
    "run its test in the context of its registration with the runner argument" in {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        val ran = RegistrationProbeSuite.instancesRunIn {
          launched(Launch.ByFramework, classOf[RegistrationProbeSuite].getName, runnerArgument(eventLogFile))
        }
        ran.map((_, registrationAsItRan) => registrationAsItRan ne SuiteRegistration.none) shouldBe Vector(true)
      }
    }

    "find no registration while it runs without the runner argument" in {
      val ran = RegistrationProbeSuite.instancesRunIn(launched(Launch.ByFramework, classOf[RegistrationProbeSuite].getName, Nil))
      ran.map((_, registrationAsItRan) => registrationAsItRan) shouldBe Vector(SuiteRegistration.none)
    }
  }
}
