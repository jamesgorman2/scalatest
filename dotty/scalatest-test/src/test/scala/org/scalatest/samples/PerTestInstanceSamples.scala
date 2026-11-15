package org.scalatest.samples

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.Filter
import org.scalatest.OneInstancePerTest
import org.scalatest.ParallelTestExecution
import org.scalatest.RandomTestOrder
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.events.InfoProvided
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.tools.RegisteredSuite
import org.scalatest.tools.RegistrationInContext
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** The instances of a suite that ran one test each, each with the registration it had as it ran it. */
object PerTestInstances {

  private val ranItsTest = new ConcurrentLinkedQueue[(Suite, SuiteRegistration)]

  def recordRanItsTest(suite: Suite): Unit = ranItsTest.add(suite -> RegistrationInContext.ofCallingThread)

  /** Returns the instances that ran a test in the runs `launch` starts, each with the registration it had as it ran it. One call records at a time. */
  def recordedIn(launch: => Unit): Vector[(Suite, SuiteRegistration)] = synchronized {
    ranItsTest.clear()
    launch
    ranItsTest.asScala.toVector
  }
}

@DoNotDiscover
class OneInstancePerTestProbe extends AnyFunSuite with OneInstancePerTest {

  test("probes") {
    PerTestInstances.recordRanItsTest(this)
  }
}

@DoNotDiscover
class ParallelTestExecutionProbe extends AnyFunSuite with ParallelTestExecution {

  test("probes") {
    PerTestInstances.recordRanItsTest(this)
  }
}

@DoNotDiscover
class RandomTestOrderProbe extends AnyFunSuite with RandomTestOrder {

  test("probes") {
    PerTestInstances.recordRanItsTest(this)
  }
}

@DoNotDiscover
class StackPerTestFunSpec extends AnyFunSpec with OneInstancePerTest {

  describe("A Stack") {
    it("is empty") {
      succeed
    }

    it("pops") {
      succeed
    }
  }
}

@DoNotDiscover
class ScopedRandomOrderFunSpec extends AnyFunSpec with RandomTestOrder {

  describe("s") {
    it("t") {
      succeed
    }
  }
}

@DoNotDiscover
class ScopedParallelFunSpec extends AnyFunSpec with ParallelTestExecution {

  describe("s") {
    it("t") {
      succeed
    }
  }
}

/** Runs each task it is given only when asked to, on the thread that asks. */
final class QueueingExecutionContext extends ExecutionContext {

  private val queued = new ConcurrentLinkedQueue[Runnable]

  def execute(task: Runnable): Unit = queued.add(task)

  def reportFailure(cause: Throwable): Unit = ()

  /** Runs every queued task, and every task they queue, until none is left. */
  def runQueued(): Unit = Iterator.continually(queued.poll()).takeWhile(_ != null).foreach(_.run())
}

// Its test completes only once its execution context's queued tasks are run.
@DoNotDiscover
class ScopedQueueingAsyncFunSpec extends AsyncFunSpec {

  val queueing = new QueueingExecutionContext

  implicit override def executionContext: ExecutionContext = queueing

  describe("s") {
    it("t") {
      Future(succeed)
    }
  }
}

// Each one's runTests runs no test, and sends a message naming no suite or test through the reporter it is given.

@DoNotDiscover
class SendsFromRunTestsParallelSuite extends AnyFunSuite with ParallelTestExecution {

  override protected def runTests(testName: Option[String], args: Args): Status = {
    args.reporter(InfoProvided(args.tracker.nextOrdinal(), "sent by runTests", None))
    SucceededStatus
  }

  test("t") {
    succeed
  }
}

@DoNotDiscover
class SendsFromRunTestsRandomOrderSuite extends AnyFunSuite with RandomTestOrder {

  override protected def runTests(testName: Option[String], args: Args): Status = {
    args.reporter(InfoProvided(args.tracker.nextOrdinal(), "sent by runTests", None))
    SucceededStatus
  }

  test("t") {
    succeed
  }
}

@DoNotDiscover
class SendsFromRunTestsAsyncSuite extends AsyncFunSuite {

  override protected def runTests(testName: Option[String], args: Args): Status = {
    args.reporter(InfoProvided(args.tracker.nextOrdinal(), "sent by runTests", None))
    SucceededStatus
  }

  test("t") {
    Future.successful(succeed)
  }
}

/** Whether the node of a suite's run is fixed, where the suite's run is registered. */
object NodeOfRun {
  def isFixed(suite: Suite): Option[Boolean] =
    SuiteRegistration.inContext(suite) match {
      case registered: RegisteredSuite => Some(registered.isNodeFixed)
      case _ => None
    }
}

/** Records, each time its run asks for its expected test count, whether the node of its run is fixed by then. */
@DoNotDiscover
class AsyncSuiteRecordingItsNodeAsTestsAreCounted extends AsyncFunSuite {
  @volatile var nodeFixedAsTestsAreCounted = Vector.empty[Boolean]

  override def expectedTestCount(filter: Filter): Int = {
    nodeFixedAsTestsAreCounted ++= NodeOfRun.isFixed(this)
    super.expectedTestCount(filter)
  }

  test("t") {
    Future.successful(succeed)
  }
}

/** Records, each time its run asks for its expected test count, whether the node of its run is fixed by then. */
@DoNotDiscover
class ParallelSuiteRecordingItsNodeAsTestsAreCounted extends AnyFunSuite with ParallelTestExecution {
  @volatile var nodeFixedAsTestsAreCounted = Vector.empty[Boolean]

  override def expectedTestCount(filter: Filter): Int = {
    nodeFixedAsTestsAreCounted ++= NodeOfRun.isFixed(this)
    super.expectedTestCount(filter)
  }

  test("t") {
    succeed
  }
}
