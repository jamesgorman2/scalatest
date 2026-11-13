package org.scalatest.tools

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.scalatest.Args
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.ScalaTestStatefulStatus
import org.scalatest.Stopper
import org.scalatest.Suite
import org.scalatest.events.Event
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TopLevelSuiteReporterSpec extends AnyWordSpec with Matchers {

  private val waitLimitSeconds = 30L

  private final class RecordingReporter extends Reporter {
    private var received = Vector.empty[Event]
    def eventNames: Vector[String] = synchronized(received.map(_.getClass.getSimpleName))
    def apply(event: Event): Unit = synchronized { received :+= event }
  }

  // Records each suite it is asked to give a reporter, and gives each the one reporter it holds.
  private final class ReporterOfSuite {
    private var asked = Vector.empty[(Suite, Filter, Reporter)]
    val handedOut = new RecordingReporter
    def askedFor: Vector[(Suite, Filter, Reporter)] = synchronized(asked)
    val function: (Suite, Filter, Reporter) => Reporter = (suite, filter, reporter) => {
      synchronized { asked :+= ((suite, filter, reporter)) }
      handedOut
    }
  }

  private def stopper(stopRequested: Boolean): Stopper = {
    val stopper = Stopper.default
    if (stopRequested) {
      stopper.requestStop()
    }
    stopper
  }

  private def withPoolOfOneThread[A](body: ExecutorService => A): A = {
    val pool = Executors.newSingleThreadExecutor()
    try body(pool)
    finally {
      pool.shutdownNow()
      pool.awaitTermination(waitLimitSeconds, TimeUnit.SECONDS)
    }
  }

  private val eventsOfOneTestSuite = Vector("SuiteStarting", "TestStarting", "TestSucceeded", "SuiteCompleted")

  "A SuiteRunner" when {

    "its suite is to start" should {
      "ask for the suite's reporter once, with the suite's filter and the reporter it was given, and report every event of the suite to the reporter it gets" in {
        val suite = new samples.Inner1Suite
        val reporter = new RecordingReporter
        val reporterOfSuite = new ReporterOfSuite
        val args = Args(reporter, stopper(stopRequested = false))
        new SuiteRunner(suite, args, new ScalaTestStatefulStatus, TestEventLogHandOff.none, reporterOfSuite.function).run()
        reporterOfSuite.askedFor shouldBe Vector((suite, args.filter, reporter))
        reporterOfSuite.handedOut.eventNames shouldBe eventsOfOneTestSuite
        reporter.eventNames shouldBe Vector.empty
      }
    }

    "a stop was requested before its suite started" should {
      "not ask for the suite's reporter" in {
        val reporter = new RecordingReporter
        val reporterOfSuite = new ReporterOfSuite
        val args = Args(reporter, stopper(stopRequested = true))
        new SuiteRunner(new samples.Inner1Suite, args, new ScalaTestStatefulStatus, TestEventLogHandOff.none, reporterOfSuite.function).run()
        reporterOfSuite.askedFor shouldBe Vector.empty
        reporter.eventNames shouldBe Vector.empty
      }
    }
  }

  "A ConcurrentDistributor handed a suite and what gives the suite its reporter" when {

    "a stop was requested before the suite started" should {
      "not ask for the suite's reporter" in {
        withPoolOfOneThread { pool =>
          val reporterOfSuite = new ReporterOfSuite
          val args = Args(new RecordingReporter, stopper(stopRequested = true))
          val distributor = new ConcurrentDistributor(args, pool)
          distributor.apply(new samples.Inner1Suite, args, TestEventLogHandOff.none, reporterOfSuite.function)
          distributor.waitUntilDone()
          reporterOfSuite.askedFor shouldBe Vector.empty
        }
      }
    }
  }

  "A DiscoverySuite whose nested suite a ConcurrentDistributor runs" when {

    def discoverySuiteRunBy(pool: ExecutorService, stopRequested: Boolean, reporter: Reporter, reporterOfSuite: ReporterOfSuite): DiscoverySuite = {
      val discoverySuite = new DiscoverySuite("", Set("samples.Inner1Suite"), true, getClass.getClassLoader, reporterOfSuite.function)
      val args = Args(reporter, stopper(stopRequested))
      val distributor = new ConcurrentDistributor(args, pool)
      discoverySuite.run(None, args.copy(distributor = Some(distributor)))
      distributor.waitUntilDone()
      discoverySuite
    }

    "the nested suite is to start" should {
      "ask for the nested suite's reporter once, and have every event of that suite reported to the reporter it gets" in {
        withPoolOfOneThread { pool =>
          val reporter = new RecordingReporter
          val reporterOfSuite = new ReporterOfSuite
          val discoverySuite = discoverySuiteRunBy(pool, stopRequested = false, reporter, reporterOfSuite)
          reporterOfSuite.askedFor.map((suite, _, _) => suite) shouldBe discoverySuite.nestedSuites
          reporterOfSuite.handedOut.eventNames shouldBe eventsOfOneTestSuite
          reporter.eventNames shouldBe Vector.empty
        }
      }
    }

    "a stop was requested before the nested suite started" should {
      "not ask for the nested suite's reporter" in {
        withPoolOfOneThread { pool =>
          val reporterOfSuite = new ReporterOfSuite
          discoverySuiteRunBy(pool, stopRequested = true, new RecordingReporter, reporterOfSuite)
          reporterOfSuite.askedFor shouldBe Vector.empty
        }
      }
    }
  }
}
