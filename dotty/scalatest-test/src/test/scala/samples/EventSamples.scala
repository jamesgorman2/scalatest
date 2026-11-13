package samples

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.Args
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.DoNotDiscover
import org.scalatest.Reporter
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.events.Event
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.immutable.ListSet
import scala.concurrent.Future

@DoNotDiscover
class StackFunSpec extends AnyFunSpec {

  describe("A Stack") {
    describe("when empty") {
      it("is empty") {
        succeed
      }

      it("pops") {
        succeed
      }
    }
  }
}

@DoNotDiscover
class SetupFailsSpec extends AnyWordSpec with BeforeAndAfterAll {

  override def beforeAll(): Unit = throw new RuntimeException("database unreachable")

  "A repo" should {
    "read" in {
      succeed
    }

    "write" in {
      succeed
    }
  }
}

@DoNotDiscover
class SetupFailsSuites extends Suites(new SetupFailsSpec)

@DoNotDiscover
class ForwardingSuite extends AnyFunSuite {

  override def run(testName: Option[String], args: Args): Status = {
    val forwarding = new Reporter {
      def apply(event: Event): Unit = args.reporter(event)
    }
    super.run(testName, args.copy(reporter = forwarding))
  }

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }
}

@DoNotDiscover
class TwiceSuites private (nestedTwice: Inner1Suite) extends Suites(nestedTwice, nestedTwice) {

  def this() = this(new Inner1Suite)
}

@DoNotDiscover
class RetrySuite extends AnyFunSuite {

  private val timesRun = new AtomicInteger

  override protected def runTest(testName: String, args: Args): Status = {
    val first = super.runTest(testName, args)
    if (first.succeeds()) first else super.runTest(testName, args)
  }

  test("flaky") {
    info("trying")
    assert(timesRun.incrementAndGet() > 1)
  }
}

@DoNotDiscover
class ScopeInfoFunSpec extends AnyFunSpec {

  describe("A Stack") {
    it("is empty") {
      succeed
    }

    info("an info in a scope")

    describe("when full") {
      it("pops") {
        succeed
      }
    }
  }
}

@DoNotDiscover
class SlowSuite extends AnyFunSuite {

  test("sleeps") {
    Thread.sleep(3000)
    succeed
  }
}

@DoNotDiscover
class BothStreamsSuite extends AnyFunSuite {

  test("writes") {
    System.out.println("dividing now")
    System.err.println("warn: careful")
    succeed
  }
}

@DoNotDiscover
class UnregisteredSuite extends Suite {

  override def testNames: Set[String] = ListSet("b", "a")

  override def tags: Map[String, Set[String]] = Map("a" -> Set("org.scalatest.Ignore"))

  // Suite.runTests gives an ignored test's TestIgnored the location of the method of the test's name.
  def a(): Unit = ()

  override protected def runTest(testName: String, args: Args): Status = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName))
    args.reporter(TestSucceeded(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName, Vector.empty))
    SucceededStatus
  }
}

@DoNotDiscover
class AnnouncingSuite extends AnyFunSuite with BeforeAndAfterEach {

  override def beforeEach(): Unit = info("setting up")

  test("runs") {
    succeed
  }

  info("declared after the test")
}

@DoNotDiscover
class StackAsyncFunSpec extends AsyncFunSpec {

  describe("A Stack") {
    it("is empty") {
      Future.successful(succeed)
    }

    ignore("pops") {
      Future.successful(succeed)
    }
  }
}

@DoNotDiscover
class TwinScopesFunSpec extends AnyFunSpec {

  describe("s") {
    it("t") {
      succeed
    }
  }

  describe("s") {
    it("u") {
      succeed
    }
  }
}

@DoNotDiscover
class RunsTwiceSuite extends AnyFunSuite {

  override def run(testName: Option[String], args: Args): Status = {
    super.run(testName, args)
    super.run(testName, args)
  }

  test("t") {
    succeed
  }
}

// Runs each test with the reporter its run was given, in place of the one its test is given.
@DoNotDiscover
class KeepsItsRunsReporterSuite extends AnyFunSuite {

  @volatile private var reporterOfRun = Option.empty[Reporter]

  override def run(testName: Option[String], args: Args): Status = {
    reporterOfRun = Some(args.reporter)
    super.run(testName, args)
  }

  override protected def runTest(testName: String, args: Args): Status =
    super.runTest(testName, reporterOfRun.fold(args)(reporter => args.copy(reporter = reporter)))

  test("a") {
    succeed
  }
}

// An async suite that runs each test with the reporter its run was given, in place of the one its test is given.
@DoNotDiscover
class KeepsItsRunsReporterAsyncSpec extends AsyncFunSpec {

  @volatile private var reporterOfRun = Option.empty[Reporter]

  override def run(testName: Option[String], args: Args): Status = {
    reporterOfRun = Some(args.reporter)
    super.run(testName, args)
  }

  override protected def runTest(testName: String, args: Args): Status =
    super.runTest(testName, reporterOfRun.fold(args)(reporter => args.copy(reporter = reporter)))

  it("t") {
    Future.successful(succeed)
  }

  it("u") {
    Future.successful(succeed)
  }
}
