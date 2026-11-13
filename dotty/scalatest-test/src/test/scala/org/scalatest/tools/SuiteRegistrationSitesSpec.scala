package org.scalatest.tools

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.Args
import org.scalatest.Reporter
import org.scalatest.ScalaTestStatefulStatus
import org.scalatest.Status
import org.scalatest.StepwiseNestedSuiteExecution
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.Suites
import org.scalatest.events.Event
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.samples.RegistrationProbeSuite
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise

final class SuiteRegistrationSitesSpec extends AnyWordSpec with Matchers with TestEventLogRuns {

  private val waitLimitSeconds = 30L

  private val ignoringEvents: Reporter = new Reporter {
    def apply(event: Event): Unit = ()
  }

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

    implicit override def executionContext: ExecutionContext = ExecutionContext.global

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
  private def runAsTopLevelSuite(suite: Suite, emitter: Option[TestEventLogEmitter]): ScalaTestStatefulStatus = {
    val status = new ScalaTestStatefulStatus
    new SuiteRunner(suite, Args(ignoringEvents), status, TestEventLogHandOff.none, TestEventLogEmitter.reporterOfTopLevelSuiteFor(emitter)).run()
    status
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

    "stay registered where a suite that is run with its structure wrapper as its reporter completes" in {
      val suite = new Probe
      val anEmitter = emitter
      val wrapper = anEmitter.wrapperOfTopLevelSuite(suite, org.scalatest.Filter.default, ignoringEvents)
      try {
        new SuiteRunner(new Probe, Args(wrapper), new ScalaTestStatefulStatus).run()
        SuiteRegistration.of(suite) should be theSameInstanceAs SuiteRegistration.madeWith(wrapper)
        hasNoRegistration(suite) shouldBe false
      } finally {
        SuiteRegistration.madeWith(wrapper).removed()
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

  private def nestedSuiteSites(nesting: String, nest: Seq[Suite] => Suite): Unit = {
    s"A suite that $nesting runs" should {
      "be registered while it runs, with a structure wrapper for its reporter, and not once its run has returned" in {
        val nested = List(new Probe, new Probe)
        runAsTopLevelSuite(nest(nested), Some(emitter))
        nested.map(suite => (isRegistered(suite.registrationInRun), isRegistered(suite.registrationInTest), suite.runIsGivenAStructureWrapper)) shouldBe
          List((true, true, true), (true, true, true))
        nested.map(hasNoRegistration) shouldBe List(true, true)
      }

      "be registered apart from the suite nested beside it" in {
        val nested = List(new Probe, new Probe)
        runAsTopLevelSuite(nest(nested), Some(emitter))
        nested.head.registrationInRun.get should not be theSameInstanceAs(nested.last.registrationInRun.get)
      }

      "not be registered once its run has thrown" in {
        val nested = new ProbeWhoseRunThrows(new IllegalStateException("thrown by run"))
        runAsTopLevelSuite(nest(List(nested)), Some(emitter))
        (isRegistered(nested.registrationInTest), hasNoRegistration(nested)) shouldBe ((true, true))
      }

      "have no registration and no structure wrapper where the run writes no test event log" in {
        val nested = new Probe
        runAsTopLevelSuite(nest(List(nested)), None)
        nested.registrationInRun.get should be theSameInstanceAs SuiteRegistration.none
        nested.runIsGivenAStructureWrapper shouldBe false
      }
    }
  }

  nestedSuiteSites("Suite.runNestedSuites", nested => new Suites(nested*))

  nestedSuiteSites("StepwiseNestedSuiteExecution.runNestedSuites", nested => new Suites(nested*) with StepwiseNestedSuiteExecution)

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
