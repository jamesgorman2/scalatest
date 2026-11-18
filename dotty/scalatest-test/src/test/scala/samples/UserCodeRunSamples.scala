package samples

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.Args
import org.scalatest.BeforeAndAfterAll
import org.scalatest.CompositeStatus
import org.scalatest.DoNotDiscover
import org.scalatest.Outcome
import org.scalatest.Reporter
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.WrapWith
import org.scalatest.events.Event
import org.scalatest.events.InfoProvided
import org.scalatest.events.NameInfo
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.Future

@DoNotDiscover
class DelegatingSuite(wrapped: Class[? <: Suite]) extends Suite {

  private val instance: Suite = wrapped.getDeclaredConstructor().newInstance()

  override def suiteName: String = instance.suiteName

  override def suiteId: String = instance.suiteId

  override def testNames: Set[String] = instance.testNames

  override def run(testName: Option[String], args: Args): Status = instance.run(testName, args)
}

@DoNotDiscover
@WrapWith(classOf[DelegatingSuite])
class StackSpec extends AnyFunSpec {

  describe("A Stack") {
    it("pops") {
      succeed
    }
  }
}

@DoNotDiscover
class HelperSuite extends AnyFunSpec {

  describe("A helper") {
    it("helps") {
      succeed
    }
  }
}

abstract class RunsHelpers extends AnyFunSuite {

  @volatile private var argsOfTest = Option.empty[Args]

  override protected def runTest(testName: String, args: Args): Status = {
    argsOfTest = Some(args)
    super.runTest(testName, args)
  }

  protected def runAHelper(): Unit = argsOfTest.foreach(args => new HelperSuite().run(None, args))
}

@DoNotDiscover
class OuterSuite extends RunsHelpers {

  test("uses a helper") {
    runAHelper()
    succeed
  }
}

@DoNotDiscover
class HelperOnAThreadSuite extends RunsHelpers {

  test("uses a helper") {
    val helping = new Thread(() => runAHelper())
    helping.start()
    helping.join()
    succeed
  }
}

@DoNotDiscover
class TwoHelpersSuite extends RunsHelpers {

  test("first") {
    runAHelper()
    succeed
  }

  test("second") {
    runAHelper()
    succeed
  }
}

@DoNotDiscover
class User1Suite extends RunsHelpers {

  test("uses one") {
    runAHelper()
    succeed
  }
}

@DoNotDiscover
class User2Suite extends RunsHelpers {

  test("uses two") {
    runAHelper()
    succeed
  }
}

@DoNotDiscover
class UserSuites extends Suites(new User1Suite, new User2Suite)

@DoNotDiscover
class RetryRunsAHelper extends RunsHelpers {

  private val timesRun = new AtomicInteger

  override protected def runTest(testName: String, args: Args): Status = {
    val first = super.runTest(testName, args)
    if (first.succeeds()) first else super.runTest(testName, args)
  }

  test("flaky") {
    info("trying")
    runAHelper()
    assert(timesRun.incrementAndGet() > 1)
  }
}

@DoNotDiscover
class AsyncOuterSuite extends AsyncFunSuite {

  @volatile private var argsOfTest = Option.empty[Args]

  override protected def runTest(testName: String, args: Args): Status = {
    argsOfTest = Some(args)
    super.runTest(testName, args)
  }

  test("uses a helper") {
    Future(argsOfTest.foreach(args => new HelperSuite().run(None, args))).map(_ => succeed)
  }
}

@DoNotDiscover
class SetupSuite extends AnyFunSuite with BeforeAndAfterAll {

  @volatile private var argsOfRun = Option.empty[Args]

  private val dropsEveryEvent = new Reporter {
    def apply(event: Event): Unit = ()
  }

  override def run(testName: Option[String], args: Args): Status = {
    argsOfRun = Some(args)
    super.run(testName, args)
  }

  override protected def beforeAll(): Unit = argsOfRun.foreach(args => new HelperSuite().run(None, args.copy(reporter = dropsEveryEvent)))

  test("works") {
    succeed
  }
}

// Sends no event for the nested suite it runs.
@DoNotDiscover
class RunsItsFirstNestedSuiteTwice extends Suites(new Inner1Suite, new Inner2Suite) {

  override protected def runNestedSuites(args: Args): Status = {
    val first = nestedSuites.head
    val runs = List(first.run(None, args), first.run(None, args))
    new CompositeStatus(runs.toSet)
  }
}

// Sends no event for the nested suites it runs.
@DoNotDiscover
class RunsItsNestedSuites extends Suites(new Inner1Suite, new Inner2Suite) {

  override protected def runNestedSuites(args: Args): Status = new CompositeStatus(nestedSuites.map(_.run(None, args)).toSet)
}

@DoNotDiscover
class ModeSuite(mode: String) extends AnyFunSuite {

  test(mode) {
    succeed
  }
}

// Its nested suites share a suite ID. It sends no event for them.
@DoNotDiscover
class PairSuites extends Suites(new ModeSuite("fast"), new ModeSuite("slow")) {

  override protected def runNestedSuites(args: Args): Status = new CompositeStatus(nestedSuites.reverse.map(_.run(None, args)).toSet)
}

@DoNotDiscover
class TwiceRunPlainSuite extends PlainSuite {

  override protected def runTests(testName: Option[String], args: Args): Status = {
    runTest("a", args)
    runTest("a", args)
    SucceededStatus
  }
}

final class ArgsOfRun {

  @volatile private var kept = Option.empty[Args]

  def keep(args: Args): Unit = kept = Some(args)

  def sendToReporterOfRun(nameInfo: Option[NameInfo]): Unit = kept.foreach(args => args.reporter(InfoProvided(args.tracker.nextOrdinal(), "direct", nameInfo)))
}

abstract class DirectSuite(sentTo: Option[ArgsOfRun], naming: DirectSuite.Naming) extends AnyFunSuite {

  protected val argsOfRun = new ArgsOfRun

  override def run(testName: Option[String], args: Args): Status = {
    argsOfRun.keep(args)
    super.run(testName, args)
  }

  protected def nameInfo: Option[NameInfo] = {
    naming match {
      case DirectSuite.Naming.Nothing => None
      case DirectSuite.Naming.ItsSuite => Some(NameInfo(suiteName, suiteId, Some(getClass.getName), None))
      case DirectSuite.Naming.ItsTest => Some(NameInfo(suiteName, suiteId, Some(getClass.getName), Some("t")))
    }
  }

  protected def sendToReporterOfRun(): Unit = sentTo.getOrElse(argsOfRun).sendToReporterOfRun(nameInfo)
}

object DirectSuite {

  enum Naming {
    case Nothing
    case ItsSuite
    case ItsTest
  }
}

abstract class SendsInItsTest(sentTo: Option[ArgsOfRun], naming: DirectSuite.Naming) extends DirectSuite(sentTo, naming) {

  test("t") {
    sendToReporterOfRun()
    succeed
  }
}

@DoNotDiscover
class DirectNamelessSuite extends SendsInItsTest(None, DirectSuite.Naming.Nothing)

@DoNotDiscover
class DirectNamingItsSuite extends SendsInItsTest(None, DirectSuite.Naming.ItsSuite)

@DoNotDiscover
class DirectNamingItsTest extends SendsInItsTest(None, DirectSuite.Naming.ItsTest)

abstract class DirectSuites(argsOfRun: ArgsOfRun, nested: Suite) extends Suites(nested) {

  override def run(testName: Option[String], args: Args): Status = {
    argsOfRun.keep(args)
    super.run(testName, args)
  }
}

@DoNotDiscover
class NestedDirectNamingItsSuite(sentTo: ArgsOfRun) extends SendsInItsTest(Some(sentTo), DirectSuite.Naming.ItsSuite)

@DoNotDiscover
class DirectSuitesNamingTheSuite(argsOfRun: ArgsOfRun) extends DirectSuites(argsOfRun, new NestedDirectNamingItsSuite(argsOfRun)) {
  def this() = this(new ArgsOfRun)
}

@DoNotDiscover
class NestedDirectNamingItsTest(sentTo: ArgsOfRun) extends SendsInItsTest(Some(sentTo), DirectSuite.Naming.ItsTest)

@DoNotDiscover
class DirectSuitesNamingTheTest(argsOfRun: ArgsOfRun) extends DirectSuites(argsOfRun, new NestedDirectNamingItsTest(argsOfRun)) {
  def this() = this(new ArgsOfRun)
}

@DoNotDiscover
class DirectNamingItsSecondRun extends DirectSuite(None, DirectSuite.Naming.ItsTest) {

  @volatile private var runs = 0

  override def withFixture(test: NoArgTest): Outcome = {
    runs += 1
    super.withFixture(test)
  }

  override protected def runTest(testName: String, args: Args): Status = {
    super.runTest(testName, args)
    super.runTest(testName, args)
  }

  test("t") {
    if (runs == 2) {
      sendToReporterOfRun()
    }
    succeed
  }
}

@DoNotDiscover
class DirectNamingItsTestAfterRun extends DirectSuite(None, DirectSuite.Naming.ItsTest) {

  override def run(testName: Option[String], args: Args): Status = {
    val status = super.run(testName, args)
    sendToReporterOfRun()
    status
  }

  test("t") {
    succeed
  }
}
