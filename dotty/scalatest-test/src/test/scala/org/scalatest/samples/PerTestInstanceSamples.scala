package org.scalatest.samples

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.OneInstancePerTest
import org.scalatest.ParallelTestExecution
import org.scalatest.RandomTestOrder
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.SuiteMixin
import org.scalatest.SuiteRegistration
import org.scalatest.events.InfoProvided
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** The instances of a suite run for one test, each with the registration it had as its test ran. */
object PerTestInstances {

  private val runForOneTest = new ConcurrentLinkedQueue[Suite]
  private val ranItsTest = new ConcurrentLinkedQueue[(Suite, SuiteRegistration)]

  def recordRunForOneTest(suite: Suite): Unit = runForOneTest.add(suite)

  def recordRanItsTest(suite: Suite): Unit = ranItsTest.add(suite -> SuiteRegistration.of(suite))

  /**
   * Returns the instances run for one test in the runs `launch` starts, and those that ran their test, each with the
   * registration it had as it ran it. Callers record one at a time.
   */
  def recordedIn(launch: => Unit): (Vector[Suite], Vector[(Suite, SuiteRegistration)]) = synchronized {
    runForOneTest.clear()
    ranItsTest.clear()
    launch
    (runForOneTest.asScala.toVector, ranItsTest.asScala.toVector)
  }
}

// Records each instance run for one test, and throws from that run where throwsWhenRunForOneTest is true.
trait RecordsItsInstancesOfTests extends SuiteMixin { this: Suite =>

  protected def throwsWhenRunForOneTest: Boolean = false

  abstract override def run(testName: Option[String], args: Args): Status = {
    if (testName.isDefined) {
      PerTestInstances.recordRunForOneTest(this)
      if (throwsWhenRunForOneTest) {
        throw new RuntimeException("no fixture")
      }
    }
    super.run(testName, args)
  }
}

@DoNotDiscover
class OneInstancePerTestProbe extends AnyFunSuite with OneInstancePerTest with RecordsItsInstancesOfTests {

  test("probes") {
    PerTestInstances.recordRanItsTest(this)
  }
}

@DoNotDiscover
class OneInstancePerTestThrowingProbe extends OneInstancePerTestProbe {
  override protected def throwsWhenRunForOneTest: Boolean = true
}

@DoNotDiscover
class ParallelTestExecutionProbe extends AnyFunSuite with ParallelTestExecution with RecordsItsInstancesOfTests {

  test("probes") {
    PerTestInstances.recordRanItsTest(this)
  }
}

@DoNotDiscover
class ParallelTestExecutionThrowingProbe extends ParallelTestExecutionProbe {
  override protected def throwsWhenRunForOneTest: Boolean = true
}

@DoNotDiscover
class RandomTestOrderProbe extends AnyFunSuite with RandomTestOrder with RecordsItsInstancesOfTests {

  test("probes") {
    PerTestInstances.recordRanItsTest(this)
  }
}

@DoNotDiscover
class RandomTestOrderThrowingProbe extends RandomTestOrderProbe {
  override protected def throwsWhenRunForOneTest: Boolean = true
}

// Its run for each test throws, so the first such run leaves the instances of the other tests unrun.
@DoNotDiscover
class RandomTestOrderTwoTestsThrowingProbe extends AnyFunSuite with RandomTestOrder with RecordsItsInstancesOfTests {

  val instancesOfTests = new ConcurrentLinkedQueue[Suite]

  override protected def throwsWhenRunForOneTest: Boolean = true

  override def newInstance: Suite with RandomTestOrder = {
    val instance = super.newInstance
    instancesOfTests.add(instance)
    instance
  }

  test("first") {
    succeed
  }

  test("second") {
    succeed
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
