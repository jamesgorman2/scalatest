package samples

import org.scalatest.Args
import org.scalatest.CompositeStatus
import org.scalatest.DoNotDiscover
import org.scalatest.Outcome
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.WrapWith
import org.scalatest.events.InfoProvided
import org.scalatest.events.NameInfo
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funsuite.AnyFunSuite

/** A suite that runs an instance of the class it wraps in its place, giving that instance's names and tests as its own. */
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

/** A suite each of whose tests runs a `HelperSuite` with the `Args` its test is run with. */
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

/** Runs each of its nested suites itself, sending no event for them. */
@DoNotDiscover
class RunsItsNestedSuites extends Suites(new Inner1Suite, new Inner2Suite) {

  override protected def runNestedSuites(args: Args): Status = new CompositeStatus(nestedSuites.map(_.run(None, args)).toSet)
}

/** Runs test `a` twice, and no other test. */
@DoNotDiscover
class TwiceRunPlainSuite extends PlainSuite {

  override protected def runTests(testName: Option[String], args: Args): Status = {
    runTest("a", args)
    runTest("a", args)
    SucceededStatus
  }
}

/** The `Args` a suite's `run` was given, which a test sends a message to. */
final class ArgsOfRun {

  @volatile private var kept = Option.empty[Args]

  def keep(args: Args): Unit = kept = Some(args)

  /** Sends the reporter of the kept `Args` an `InfoProvided` with message `direct`. */
  def sendDirect(nameInfo: Option[NameInfo]): Unit = kept.foreach(args => args.reporter(InfoProvided(args.tracker.nextOrdinal(), "direct", nameInfo)))
}

/**
 * A suite whose `run` keeps its `Args`, and whose test `t` sends a message straight to a reporter: the one its own
 * `run` was given, or the one `sentTo` was given.
 */
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

  protected def sendDirect(): Unit = sentTo.getOrElse(argsOfRun).sendDirect(nameInfo)
}

object DirectSuite {

  /** What the message's `nameInfo` names. */
  enum Naming {
    case Nothing
    case ItsSuite
    case ItsTest
  }
}

/** A suite whose test `t` sends a message straight to a reporter, then succeeds. */
abstract class SendsInItsTest(sentTo: Option[ArgsOfRun], naming: DirectSuite.Naming) extends DirectSuite(sentTo, naming) {

  test("t") {
    sendDirect()
    succeed
  }
}

@DoNotDiscover
class DirectNamelessSuite extends SendsInItsTest(None, DirectSuite.Naming.Nothing)

@DoNotDiscover
class DirectNamingItsSuite extends SendsInItsTest(None, DirectSuite.Naming.ItsSuite)

@DoNotDiscover
class DirectNamingItsTest extends SendsInItsTest(None, DirectSuite.Naming.ItsTest)

/** A suite nesting one whose test sends its message to the reporter this suite's `run` was given. */
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

/** Runs test `t` twice. The test sends its message, naming itself, only the second time it runs. */
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
      sendDirect()
    }
    succeed
  }
}

/** Sends a message naming test `t` after its tests have run. */
@DoNotDiscover
class DirectNamingItsTestAfterRun extends DirectSuite(None, DirectSuite.Naming.ItsTest) {

  override def run(testName: Option[String], args: Args): Status = {
    val status = super.run(testName, args)
    sendDirect()
    status
  }

  test("t") {
    succeed
  }
}
