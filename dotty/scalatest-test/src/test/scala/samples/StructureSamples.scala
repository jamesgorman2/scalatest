package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.Args
import org.scalatest.DoNotDiscover
import org.scalatest.Filter
import org.scalatest.Ignore
import org.scalatest.RandomTestOrder
import org.scalatest.StepwiseNestedSuiteExecution
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.Tag
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.freespec.PathAnyFreeSpec
import org.scalatest.funspec.PathAnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.refspec.RefSpec
import scala.collection.immutable.ListSet
import scala.jdk.CollectionConverters.*

object Slow extends Tag("samples.Slow")

@DoNotDiscover
class FilteredSuite extends AnyFunSuite {

  test("fast") {
    succeed
  }

  test("slow", Slow) {
    succeed
  }

  ignore("later") {
    succeed
  }
}

@DoNotDiscover
class ShuffledSuite extends AnyFunSuite with RandomTestOrder {

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }

  test("c") {
    succeed
  }
}

@DoNotDiscover
class PlainSuite extends Suite {

  override def testNames: Set[String] = ListSet("b", "a", "c")

  override def tags: Map[String, Set[String]] = Map("c" -> Set("samples.Slow"))

  override protected def runTest(testName: String, args: Args): Status = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName))
    args.reporter(TestSucceeded(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName, Vector.empty))
    SucceededStatus
  }
}

@DoNotDiscover
@Ignore
class ShelvedSuite extends AnyFunSuite {

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }
}

@DoNotDiscover
class IgnoringPlainSuite extends Suite {

  override def testNames: Set[String] = ListSet("b", "a", "c")

  override def tags: Map[String, Set[String]] = Map("a" -> Set("org.scalatest.Ignore"))

  // Suite.runTests locates ignored test a's TestIgnored at this method, and aborts the suite if it is missing.
  def a(): Unit = ()

  override protected def runTest(testName: String, args: Args): Status = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName))
    args.reporter(TestSucceeded(args.tracker.nextOrdinal(), suiteName, suiteId, Some(getClass.getName), testName, testName, Vector.empty))
    SucceededStatus
  }
}

@DoNotDiscover
class IgnoredAndExcludedSuite extends AnyFunSuite {

  test("fast") {
    succeed
  }

  ignore("later", Slow) {
    succeed
  }
}

@DoNotDiscover
class Inner1Suite extends AnyFunSuite {

  test("inner one") {
    succeed
  }
}

@DoNotDiscover
class Inner2Suite extends AnyFunSuite {

  test("inner two") {
    succeed
  }
}

@DoNotDiscover
class OuterSuites extends Suites(new Inner1Suite, new Inner2Suite)

@DoNotDiscover
class StepwiseOuterSuites extends Suites(new Inner1Suite, new Inner2Suite) with StepwiseNestedSuiteExecution

@DoNotDiscover
class ThrowingConstructorSpec extends AnyFunSuite {

  throw new IllegalStateException("no config")
}

@DoNotDiscover
class ReadsItsStructureSuite extends AnyFunSuite {

  @volatile private var eventLogWhenRunStarted = Vector.empty[String]

  override def run(testName: Option[String], args: Args): Status = {
    eventLogWhenRunStarted = Files.readAllLines(Paths.get(args.configMap.getRequired[String]("eventLog"))).asScala.toVector
    super.run(testName, args)
  }

  test("reads its structure") {
    val structures = eventLogWhenRunStarted.filter(_.contains(""""item":"structure""""))
    assert(structures.size == 1 && structures.head.contains(""""name":"ReadsItsStructureSuite""""))
  }
}

@DoNotDiscover
class ThrowingScopeRefSpec extends RefSpec {

  object `a scope` {
    // Thrown when the suite registers its tests, which a RefSpec does when first asked for them.
    throw new RuntimeException("boom")
    def `t 1`: Unit = ()
  }
}

@DoNotDiscover
class ThrowingSecondScopePathFunSpec extends PathAnyFunSpec {

  describe("a") {
    it("t") {}
  }

  describe("b") {
    // Thrown in the second instance, which the suite constructs to register b when first asked for the suite's tests.
    throw new RuntimeException("boom")
  }
}

@DoNotDiscover
class ThrowingSecondScopePathFreeSpec extends PathAnyFreeSpec {

  "a" - {
    "t" in {}
  }

  "b" - {
    // Thrown in the second instance, which the suite constructs to register b when first asked for the suite's tests.
    throw new RuntimeException("boom")
  }
}

abstract class CountsNoNestedTests(nested: Suite) extends Suites(nested) {

  // Counting the nested suite's tests would ask it for them before this suite runs.
  override def expectedTestCount(filter: Filter): Int = 0
}

@DoNotDiscover
class NestsThrowingScopeRefSpec extends CountsNoNestedTests(new ThrowingScopeRefSpec)

@DoNotDiscover
class NestsThrowingSecondScopePathFunSpec extends CountsNoNestedTests(new ThrowingSecondScopePathFunSpec)

@DoNotDiscover
class NestsThrowingSecondScopePathFreeSpec extends CountsNoNestedTests(new ThrowingSecondScopePathFreeSpec)
